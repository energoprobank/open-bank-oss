# SPDX-License-Identifier: Apache-2.0
# Onboarding-service REST extension (experience v2 step 8): the account-opening write side and the
# face check for large payments. Extends openbank.rest; mounted alongside rest.rego.
#
# Every one of these routes is called by customer-edge with its own machine principal, after it has
# taken the party from the customer's token (the path party is never the app's). Identity-gated,
# never a role: ROLE_API is held by every service account.
#   onboarding.contact-verification.create / .confirm — e-mail + SMS one-time codes (before sign-in)
#   onboarding.energo-pro.prefill                      — Energo-Pro record against a confirmed contact
#   onboarding.application.read / .update              — the application, identity checks
#   onboarding.bank-id.callback                        — step 9: the browser's return from Bank iD (state-bound)
#   liveness.policy.read / .update, liveness.session.create / .read / .consume — face check

package openbank.rest

import rego.v1

allowed_reasons contains "service-onboarding-edge-m2m" if {
	input.principal.type == "HUMAN"
	machine_named("service-account-openbank-edge")
	input.action in {
		"onboarding.contact-verification.create",
		"onboarding.contact-verification.confirm",
		"onboarding.energo-pro.prefill",
		"onboarding.application.read",
		"onboarding.application.update",
		"onboarding.bank-id.callback",
		"liveness.policy.read",
		"liveness.policy.update",
		"liveness.session.create",
		"liveness.session.read",
		"liveness.session.consume",
	}
}
