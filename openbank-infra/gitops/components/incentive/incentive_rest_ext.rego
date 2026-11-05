# SPDX-License-Identifier: Apache-2.0
# incentive-service REST extension (ADR-0034; FLUX rewards programme).
# Extends openbank.rest with incentive allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# ---------------------------------------------------------------------------------------
# CALLER AUDIT. Every incentive @Authorize action and its real caller:
#
#   incentive.redemption.redeem — POST /api/v1/incentives/redemptions (reserve + commit a promo
#                                 code for a party in one step). Caller: the FLUX rewards engine,
#                                 openbank-rewards-service, as its OWN Keycloak client
#                                 `openbank-rewards` (service-account-openbank-rewards, ROLE_API).
#                                 No staff path (operators use reserve + commit) and no other
#                                 machine: the route's in-code guard (requireRewardsRedeemer) pins
#                                 the same principal.
#
# Every other incentive route is @RolesAllowed-only (no @Authorize) and is not decided here.
# ---------------------------------------------------------------------------------------
#
# Narrow rule here, NOT `rules.yaml: authz.role_action_matrix`: a matrix line is a grant to a
# MACHINE no policy can veto (#3765/#3734). The action is deliberately not a read/list verb, so
# rest.rego's generic read rules can never admit anyone else; this identity-gated rule is the
# whole grant.
#
# Do NOT gate on input.principal.type == "SERVICE": AuthorizeInterceptor never emits it
# (rules.yaml: authz_policy, issue #266).

package openbank.rest

import rego.v1

allowed_reasons contains "service-rewards-incentive-redeem" if {
	input.principal.type == "HUMAN"
	machine_named("service-account-openbank-rewards")
	input.action == "incentive.redemption.redeem"
}
