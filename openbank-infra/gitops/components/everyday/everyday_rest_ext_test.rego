# SPDX-License-Identifier: Apache-2.0
# Unit tests for everyday_rest_ext.rego. Run with the base policy:
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/everyday/everyday_rest_ext.rego \
#            openbank-infra/gitops/components/everyday/everyday_rest_ext_test.rego

package openbank.rest

import rego.v1

edge_sa := {"service_account": true, "client_id": "openbank-edge", "type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

shared_sa := {"service_account": true, "client_id": "openbank-services", "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API"]}

test_edge_may_read_and_link_bills if {
	"service-everyday-edge-m2m" in allowed_reasons with input as {"principal": edge_sa, "action": "everyday.bills.read"}
	"service-everyday-edge-m2m" in allowed_reasons with input as {"principal": edge_sa, "action": "everyday.bills.update"}
	"service-everyday-edge-m2m" in allowed_reasons with input as {"principal": edge_sa, "action": "everyday.money.update"}
	"service-everyday-edge-m2m" in allowed_reasons with input as {"principal": edge_sa, "action": "everyday.exports.download"}
}

test_no_other_machine_may if {
	not "service-everyday-edge-m2m" in allowed_reasons with input as {"principal": shared_sa, "action": "everyday.bills.update"}
	not "service-everyday-edge-m2m" in allowed_reasons with input as {"principal": shared_sa, "action": "everyday.money.update"}
}

test_the_edge_gets_nothing_else_here if {
	not "service-everyday-edge-m2m" in allowed_reasons with input as {"principal": edge_sa, "action": "everyday.staff.read"}
}
