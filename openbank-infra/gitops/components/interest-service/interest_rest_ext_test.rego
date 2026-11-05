# SPDX-License-Identifier: Apache-2.0
# Unit tests for interest_rest_ext.rego (#3679 follow-up — edge/M2M write tightening).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego, and `opa test <dir>`
# would try to load it as data and die with a merge error):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/interest-service/interest_rest_ext.rego \
#            openbank-infra/gitops/components/interest-service/interest_rest_ext_test.rego
#
# Do NOT add another service's *_rest_ext.rego to the same invocation: they all extend the
# same package and would cross-contaminate allowed_reasons (issue #1797 follow-up 2).
#
# Unlike delegation_rest_ext_test.rego (which asserts on allowed_reasons), the core cases here
# assert the FINAL decision: the matrix mock deliberately grants ROLE_OPERATOR the interest
# writes, so for a service-account principal a reason still FIRES — only the prohibition at the
# allow head vetoes it. Asserting count(allowed_reasons) == 0 would test the wrong layer.

package openbank.rest_test

import data.openbank.rest

# The mock GRANTS the interest writes to ROLE_OPERATOR on purpose: the prohibition must beat a
# live matrix grant, or it is decoration. Reads stay granted too (edge may serve customer views).
rules_mock := {
	"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": [
		"interest.list",
		"interest.read",
		"interest.create",
		"interest.trigger",
		"interest.delete",
	]}}},
	"money_path_services": [],
	"money_path_action_prefixes": {},
	"four_eyes": {"verbs": [], "actions": []},
	"feature_flags": {"prohibited_flag_combinations": [], "money_path_flags": []},
	"shared_m2m_write_prohibition": {"reasons": []},
}

operator := {"service_account": false, "type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

edge := {"service_account": true, "client_id": "openbank-edge", "type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

services_m2m := {"service_account": true, "client_id": "openbank-services", "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

# --- the regressions this file exists to prevent ---

test_edge_may_not_create_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "interest.create"}
		with data.rules as rules_mock
}

test_edge_may_not_trigger_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "interest.trigger"}
		with data.rules as rules_mock
}

test_edge_may_not_delete_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "interest.delete"}
		with data.rules as rules_mock
}

test_shared_m2m_may_not_write_despite_matrix_grant if {
	rest.allow == false with input as {"principal": services_m2m, "action": "interest.create"}
		with data.rules as rules_mock
}

test_prohibition_fires_for_any_service_account if {
	rest.prohibited with input as {"principal": edge, "action": "interest.trigger"}
		with data.rules as rules_mock
}

# --- what must keep working ---

test_operator_may_create if {
	decision := rest.allow with input as {"principal": operator, "action": "interest.create"}
		with data.rules as rules_mock
	decision.allow == true
	# matrix-allows also fires (the mock grants it) and min() picks it as the surfaced reason —
	# the extension's reason is asserted by membership, not by the surfaced-reason lottery.
	"operator-interest-write" in rest.allowed_reasons with input as {"principal": operator, "action": "interest.create"}
		with data.rules as rules_mock
}

test_edge_may_read if {
	decision := rest.allow with input as {"principal": edge, "action": "interest.read"}
		with data.rules as rules_mock
	decision.allow == true
}

test_edge_may_list if {
	decision := rest.allow with input as {"principal": edge, "action": "interest.list"}
		with data.rules as rules_mock
	decision.allow == true
}

# --- interest-oversight-read (#3679): the console read regression #3695 shipped ---
#
# The matrix mock above grants ROLE_OPERATOR only, so neither of these principals has any base
# grant whatsoever — the extension rule is the sole source of the allow, which is what makes these
# falsifiable. Before the rule existed both resolved false, which is the live 403 demo@ and
# compliance@ have been getting since 2026-08-07.

viewer := {"service_account": false, "type": "HUMAN", "id": "demo@openbank.local", "roles": ["ROLE_VIEWER"]}

auditor := {"service_account": false, "type": "HUMAN", "id": "aud@openbank.local", "roles": ["ROLE_AUDITOR"]}

test_viewer_may_list if {
	decision := rest.allow with input as {"principal": viewer, "action": "interest.list"}
		with data.rules as rules_mock
	decision.allow == true
	"interest-oversight-read" in rest.allowed_reasons with input as {"principal": viewer, "action": "interest.list"}
		with data.rules as rules_mock
}

test_viewer_may_read if {
	decision := rest.allow with input as {"principal": viewer, "action": "interest.read"}
		with data.rules as rules_mock
	decision.allow == true
}

# ROLE_AUDITOR is admitted by WithholdingRemittanceResource's class @RolesAllowed and by nothing in
# the base policy — so this case is carried entirely by the extension.
test_auditor_may_list_withholding if {
	"interest-oversight-read" in rest.allowed_reasons with input as {"principal": auditor, "action": "interest.list"}
		with data.rules as rules_mock
}

# The point of the closed two-action literal. Replacing it with
# startswith(input.action, "interest.") turns this test red — that is the falsification, and it is
# why the rule is not written as a prefix family.
test_oversight_read_grants_no_write if {
	not "interest-oversight-read" in rest.allowed_reasons with input as {"principal": viewer, "action": "interest.create"}
		with data.rules as rules_mock
	not "interest-oversight-read" in rest.allowed_reasons with input as {"principal": viewer, "action": "interest.trigger"}
		with data.rules as rules_mock
	not "interest-oversight-read" in rest.allowed_reasons with input as {"principal": viewer, "action": "interest.delete"}
		with data.rules as rules_mock
}

# A viewer still gets nothing on a write, at the FINAL decision layer and not merely the reason set.
test_viewer_still_denied_every_write if {
	rest.allow == false with input as {"principal": viewer, "action": "interest.create"}
		with data.rules as rules_mock
	rest.allow == false with input as {"principal": viewer, "action": "interest.trigger"}
		with data.rules as rules_mock
	rest.allow == false with input as {"principal": viewer, "action": "interest.delete"}
		with data.rules as rules_mock
}

# Must-DENY control for this block: the new rule must not leak onto a foreign namespace.
test_oversight_read_is_scoped_to_interest if {
	rest.allow == false with input as {"principal": viewer, "action": "zzz.frobnicate"}
		with data.rules as rules_mock
	rest.allow == false with input as {"principal": auditor, "action": "ledger.read"}
		with data.rules as rules_mock
}

# Known-positive that the base policy and the extension are both loaded: a negative suite would
# pass vacuously without them. operator-interest-write is the EXTENSION's reason, so its presence
# proves the ext; matrix-allows proving the base+data wiring comes from test_edge_may_read above
# (the edge holds no interest grant outside the matrix mock).
test_extension_is_loaded if {
	"operator-interest-write" in rest.allowed_reasons with input as {"principal": operator, "action": "interest.trigger"}
		with data.rules as rules_mock
}

# --- rewards-rate-adjustment (FLUX Deposit Rate Boost) ---
#
# The matrix mock above does NOT carry the adjustment actions (nor does the real rules.yaml), so
# every allow below comes from the extension alone — falsifiable by deleting the rule.

adj_rewards := {"service_account": true, "client_id": "openbank-rewards", "type": "HUMAN", "id": "service-account-openbank-rewards", "roles": ["ROLE_API"]}

# Phase-1 PEP (no service_account marker): the legacy name-based machine_named body.
adj_rewards_legacy := {"type": "HUMAN", "id": "service-account-openbank-rewards", "roles": ["ROLE_API"]}

# Same NAME, wrong verified client: a renamed machine must not borrow the rewards grant.
adj_rewards_impostor := {"service_account": true, "client_id": "openbank-services", "type": "HUMAN", "id": "service-account-openbank-rewards", "roles": ["ROLE_API", "ROLE_OPERATOR"]}

adj_admin := {"service_account": false, "type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

test_rewards_may_create_cancel_list_adjustments if {
	every action in {"interest.adjust", "interest.adjustCancel", "interest.adjustList"} {
		decision := rest.allow with input as {"principal": adj_rewards, "action": action}
			with data.rules as rules_mock
		decision.allow == true
		decision.reason == "rewards-rate-adjustment"
	}
}

test_rewards_legacy_pep_may_adjust if {
	decision := rest.allow with input as {"principal": adj_rewards_legacy, "action": "interest.adjust"}
		with data.rules as rules_mock
	decision.allow == true
}

test_rewards_impostor_denied if {
	rest.allow == false with input as {"principal": adj_rewards_impostor, "action": "interest.adjust"}
		with data.rules as rules_mock
	rest.allow == false with input as {"principal": adj_rewards_impostor, "action": "interest.adjustList"}
		with data.rules as rules_mock
}

# The carve-out is for the adjustment actions ONLY: the rewards client still cannot accrue,
# capitalize, create rate configs or trigger the batch, even holding ROLE_OPERATOR.
test_rewards_gains_no_existing_interest_write if {
	adj_rewards_op := object.union(adj_rewards, {"roles": ["ROLE_API", "ROLE_OPERATOR"]})
	every action in {"interest.create", "interest.trigger", "interest.delete"} {
		rest.allow == false with input as {"principal": adj_rewards_op, "action": action}
			with data.rules as rules_mock
	}
}

test_other_service_accounts_denied_adjustments if {
	every p in [edge, services_m2m] {
		every action in {"interest.adjust", "interest.adjustCancel", "interest.adjustList"} {
			rest.allow == false with input as {"principal": p, "action": action}
				with data.rules as rules_mock
		}
	}
}

# Second layer: even a reason that WOULD admit another machine is vetoed at the allow head.
test_adjustment_prohibition_beats_a_matrix_grant if {
	widened := {"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": ["interest.adjust", "interest.adjustList"]}}}}
	mock := object.union(rules_mock, widened)
	rest.prohibited with input as {"principal": edge, "action": "interest.adjust"}
		with data.rules as mock
	rest.allow == false with input as {"principal": edge, "action": "interest.adjust"}
		with data.rules as mock
	not rest.prohibited with input as {"principal": adj_rewards, "action": "interest.adjust"}
		with data.rules as mock
}

test_operators_may_list_adjustments_only if {
	every p in [operator, adj_admin] {
		decision := rest.allow with input as {"principal": p, "action": "interest.adjustList"}
			with data.rules as rules_mock
		decision.allow == true
		rest.allow == false with input as {"principal": p, "action": "interest.adjust"}
			with data.rules as rules_mock
		rest.allow == false with input as {"principal": p, "action": "interest.adjustCancel"}
			with data.rules as rules_mock
	}
}

test_viewer_may_not_list_adjustments if {
	rest.allow == false with input as {"principal": viewer, "action": "interest.adjustList"}
		with data.rules as rules_mock
}
