# SPDX-License-Identifier: Apache-2.0
# openbank-rewards-service REST extension (FLUX rewards, experience v2 step 2).
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions (CustomerRewardsResource, StaffRewardsResource):
#   rewards.customer.read / rewards.customer.write — the customer API under
#       /api/v1/rewards/parties/{partyId}. ONLY customer-edge calls it: the edge authenticates the
#       customer, derives the authoritative partyId from the customer token and injects it, so the
#       one admitted principal is the edge's own client. No human, no other machine.
#   rewards.staff.read     — console reads (VIEWER, OPERATOR, ADMIN; humans only)
#   rewards.staff.write    — change-request submission, liability run (OPERATOR, ADMIN; humans only)
#   rewards.staff.approve  — maker-checker decision (OPERATOR, ADMIN; humans only). The service
#       itself refuses an approval by the request's maker (four eyes), so this rule only gates role.
#
# Machines never reach staff actions: every staff rule carries `not principal_is_machine`, and the
# prohibition below vetoes rewards.staff.* for any machine at the allow head, so a future
# role_action_matrix line cannot re-admit the shared service accounts (CLAUDE.md, OPA pitfalls).

package openbank.rest

import rego.v1

allowed_reasons contains "edge-rewards-customer" if {
	machine_named("service-account-openbank-edge")
	input.action in {"rewards.customer.read", "rewards.customer.write"}
}

allowed_reasons contains "rewards-staff-read" if {
	input.principal.type == "HUMAN"
	not principal_is_machine
	some role in {"ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "rewards.staff.read"
}

allowed_reasons contains "rewards-staff-write" if {
	input.principal.type == "HUMAN"
	not principal_is_machine
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in {"rewards.staff.write", "rewards.staff.approve"}
}

prohibited if {
	principal_is_machine
	startswith(input.action, "rewards.staff.")
}
