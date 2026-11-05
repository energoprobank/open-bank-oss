# SPDX-License-Identifier: Apache-2.0
# Interest-service REST extension (ADR-0034 Phase 5, issue #938; tightened in the #3679
# follow-up). Extends openbank.rest with interest-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (InterestResource, WithholdingRemittanceResource):
#   interest.create  — accrue, capitalize (#accountId), createRateConfig, assemble (withholding)
#   interest.trigger — accrueAll (daily batch accrual)
#   interest.delete  — deactivateRateConfig (#id)
#   interest.list    — listAllAccruals, listRateConfigs, list (withholding)
#   interest.read    — getAccruals/getSummary/getCapitalizations/getRateConfig (#id), get (withholding)
#   interest.adjust       — RateAdjustmentResource.create (#accountId)   — FLUX rewards rate boost
#   interest.adjustCancel — RateAdjustmentResource.cancel (#accountId)   — its compensation
#   interest.adjustList   — RateAdjustmentResource.list (#accountId)
#   (see rewards-rate-adjustment below: the ONE carve-out from the M2M prohibition)
#
# Base rest.rego grants operator-read-any (ROLE_OPERATOR/ROLE_ADMIN) for interest.list/.read, and
# compliance-read-any for interest.read ONLY (that rule matches actions ending in ".read", so it
# never covers the list). It reaches no other role: ROLE_VIEWER and ROLE_AUDITOR — both admitted by
# the resources' own @RolesAllowed — are denied by the base policy. See interest-oversight-read
# below, which closes that gap; the earlier form of this line claimed no extension was needed for
# reads and was wrong.
#
# No legitimate M2M writer exists: audited the fleet for callers of interest-service —
# agent-service's InterestServiceClient is read-only (listAccruals/getAccruals), and accrueAll's
# documented "external scheduler" does not exist (no @Scheduled annotation, no CronJob in gitops
# or in-cluster). interest.create/.trigger/.delete are invoked synchronously by admin-ui
# operators only.
#
# Tightening (#3679 follow-up): the fleet caller audit answered "no M2M writer", but two service
# accounts carry ROLE_OPERATOR in the realm — service-account-openbank-services (shared backend
# client) and service-account-openbank-edge (customer edge) — and BOTH could reach the writes:
# this rule used to be role-only, and the rules.yaml role_action_matrix grants ROLE_OPERATOR the
# interest writes, so matrix-allows admitted them even had this rule excluded service accounts.
# interest is money-path (posts real GL journals, #1478) and customer-edge is customer-reachable,
# so a role-only path is not acceptable here. Two layers, both needed:
#   1. the allow rule below excludes every service account outright (delegation idiom), and
#   2. the prohibition vetoes the write actions for ANY service account at the allow head —
#      unreachable via matrix-allows or any future reason.
# Reads are untouched: the edge legitimately serves customer interest views.

package openbank.rest

import rego.v1

allowed_reasons contains "operator-interest-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	not principal_is_machine
	input.action in {"interest.create", "interest.trigger", "interest.delete"}
}

# Read grant for the oversight roles the two resources actually admit (#3679 follow-up).
#
# The header above claims base rest.rego "already grants operator-read-any and compliance-read-any
# for interest.list/.read — no extension needed for those". Measured against the DEPLOYED bundle,
# that is not true for the roles a real console session carries:
#
#   interest.list  ROLE_VIEWER   -> DENY      interest.read  ROLE_VIEWER   -> DENY
#   interest.list  ROLE_AUDITOR  -> DENY      interest.read  ROLE_AUDITOR  -> DENY
#   interest.list  ROLE_COMPLIANCE -> DENY  (compliance-read-any matches only actions ending
#                                            in ".read", and the endpoint an analyst opens is
#                                            the LIST)
#
# operator-read-any needs ROLE_OPERATOR/ROLE_ADMIN, and role_action_matrix carries no ROLE_VIEWER
# or ROLE_AUDITOR entry at all. Meanwhile InterestResource is class-annotated
# @RolesAllowed(ROLE_VIEWER, ROLE_OPERATOR, ROLE_ADMIN, ROLE_API) and WithholdingRemittanceResource
# adds ROLE_AUDITOR, so both admit these roles at the JAX-RS layer; admin-ui's /interest page is not
# role-gated and its sidebar entry is gated on `payments:view`, which includes ROLES.VIEWER. The
# deployed realm seeds demo@openbank.local with ROLE_VIEWER alone and compliance@/compliance2@ with
# ROLE_COMPLIANCE+ROLE_VIEWER — so since #3695 flipped AUTHZ_ENFORCE on 2026-08-07, all three have
# been 403ing on a read they are entitled to. This closes that regression rather than introducing a
# new grant; it is the same shape sdd/aml/balances/fx/ledger already carry.
#
# READ-ONLY BY CONSTRUCTION, which is what makes a role-only grant acceptable here where the write
# rule above had to exclude service accounts: the action set is a closed literal of the two read
# actions, not a startswith(input.action, "interest.") family, so no widening of interest.* can leak
# through it, and neither role appears on any interest write path. It grants the shared backend
# client nothing new — service-account-openbank-services holds ROLE_API in the deployed realm (and
# ROLE_OPERATOR in the docker/CI realms, where base operator-read-any already covers both actions).
#
# ROLE_COMPLIANCE is deliberately NOT listed: neither resource admits it at the JAX-RS layer, so a
# compliance-only principal 403s before OPA is consulted and a grant here would be dead. The real
# compliance users reach these reads through the ROLE_VIEWER they also hold.
allowed_reasons contains "interest-oversight-read" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_VIEWER", "ROLE_AUDITOR"}
	role in input.principal.roles
	input.action in {"interest.read", "interest.list"}
}

# No M2M caller of interest writes exists (fleet audit above), so this loses no legitimate
# caller and fails closed for any FUTURE service account too. Keyed on the action set, not a
# principal allowlist — a new backend client must never silently gain interest writes.
prohibited if {
	machine_grant_ok
	input.action in {"interest.create", "interest.trigger", "interest.delete"}
}

# --- Additive rate adjustments: the FLUX rewards "Deposit Rate Boost" (rewards-api-contract) ---
#
# The ONE deliberate exception to "no M2M writer" above, and it is kept narrow on every axis:
#
#   * a DIFFERENT action family. The adjustment endpoints are gated by interest.adjust /
#     interest.adjustCancel / interest.adjustList, not interest.create/.delete/.list. So the
#     prohibition above (and operator-interest-write) keep their exact meaning for every existing
#     write: the rewards client gains NO accrue, capitalize, rate-config or remittance power.
#     The suffixes are chosen so no generic base reason can match: operator-read-any and
#     party-self-service key on ".list"/".read", compliance-read-any on ".read", four-eyes verbs on
#     their own suffixes, and rules.yaml role_action_matrix carries none of these actions — so a
#     ROLE_OPERATOR service account (edge, services) cannot reach them through the base policy.
#   * ONE principal. machine_named binds the fixed id service-account-openbank-rewards to the
#     verified client_id (openbank-rewards), the rewards engine's own Keycloak client. The shared
#     backend client and the customer edge are NOT admitted.
#   * the same two layers as the writes above: (1) this allow reason names the one principal, and
#     (2) the prohibition below vetoes the adjustment actions at the allow head for EVERY other
#     service account, so a future reason (or a matrix line) cannot widen it to another machine.
#
# Why an M2M writer is acceptable HERE when it is not for interest.create: an adjustment is a
# bounded delta (|deltaBps| <= 300, <= 400 days, enforced in the domain and by a DB CHECK) on top
# of a rate an operator configured, carries a sourceRef + approvalRef, and is cancellable; it posts
# no journal itself. The accrual that applies it still runs in-process under the existing controls.
allowed_reasons contains "rewards-rate-adjustment" if {
	input.principal.type == "HUMAN"
	machine_named("service-account-openbank-rewards")
	input.action in interest_rate_adjustment_actions
}

# Human operators/admins may LIST adjustments (support desk: "why is this account's rate higher?").
# Staff-only: not principal_is_machine, so the ROLE_OPERATOR service accounts get nothing here.
allowed_reasons contains "operator-rate-adjustment-list" if {
	input.principal.type == "HUMAN"
	not principal_is_machine
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "interest.adjustList"
}

prohibited if {
	machine_grant_ok
	not machine_named("service-account-openbank-rewards")
	input.action in interest_rate_adjustment_actions
}

interest_rate_adjustment_actions := {"interest.adjust", "interest.adjustCancel", "interest.adjustList"}
