# SPDX-License-Identifier: Apache-2.0
# Everyday-service REST extension (experience v2 step 3): bills, billers, the Energo-Pro electricity
# link and bill reminders. Extends openbank.rest; mounted alongside rest.rego.
#
# Every route is called by customer-edge with its own machine principal, after it has taken the party
# from the customer's token (the path party is never the app's). Identity-gated, never a role:
# ROLE_API is held by every service account.
#   everyday.bills.read   — billers, the electricity account, bills, reminders
#   everyday.bills.update — link / unlink the Energo-Pro account, save reminders

package openbank.rest

import rego.v1

allowed_reasons contains "service-everyday-edge-m2m" if {
	input.principal.type == "HUMAN"
	machine_named("service-account-openbank-edge")
	input.action in {
		"everyday.bills.read",
		"everyday.bills.update",
	}
}
