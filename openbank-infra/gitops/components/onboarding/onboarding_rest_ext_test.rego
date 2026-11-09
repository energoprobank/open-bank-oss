# SPDX-License-Identifier: Apache-2.0
# Unit tests for onboarding_rest_ext.rego. Run with the base policy:
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/onboarding/onboarding_rest_ext.rego \
#            openbank-infra/gitops/components/onboarding/onboarding_rest_ext_test.rego

package openbank.rest

import rego.v1

edge_sa := {"service_account": true, "client_id": "openbank-edge", "type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

shared_sa := {"service_account": true, "client_id": "openbank-services", "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API"]}

test_edge_may_drive_account_opening_and_the_face_check if {
	"service-onboarding-edge-m2m" in allowed_reasons with input as {"principal": edge_sa, "action": "onboarding.contact-verification.create"}
	"service-onboarding-edge-m2m" in allowed_reasons with input as {"principal": edge_sa, "action": "onboarding.application.update"}
	"service-onboarding-edge-m2m" in allowed_reasons with input as {"principal": edge_sa, "action": "liveness.session.consume"}
}

test_no_other_machine_may if {
	not "service-onboarding-edge-m2m" in allowed_reasons with input as {"principal": shared_sa, "action": "onboarding.application.update"}
	not "service-onboarding-edge-m2m" in allowed_reasons with input as {"principal": shared_sa, "action": "liveness.session.consume"}
}

test_the_edge_gets_nothing_else_here if {
	not "service-onboarding-edge-m2m" in allowed_reasons with input as {"principal": edge_sa, "action": "onboarding.records.read"}
}

test_edge_relays_the_bank_id_callback if {
	"service-onboarding-edge-m2m" in allowed_reasons with input as {"principal": edge_sa, "action": "onboarding.bank-id.callback"}
}
