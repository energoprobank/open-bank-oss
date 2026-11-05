# SPDX-License-Identifier: Apache-2.0
# Unit tests for rewards_rest_ext.rego. Run EXPLICITLY by file:
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/rewards/rewards_rest_ext.rego \
#            openbank-infra/gitops/components/rewards/rewards_rest_ext_test.rego

package openbank.rest_test

import rego.v1

import data.openbank.rest

operator := {"service_account": false, "type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

viewer := {"service_account": false, "type": "HUMAN", "id": "u-view", "roles": ["ROLE_VIEWER"]}

edge := {"service_account": true, "client_id": "openbank-edge", "type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

shared := {"service_account": true, "client_id": "openbank-services", "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

rewards_client := {"service_account": true, "client_id": "openbank-rewards", "type": "HUMAN", "id": "service-account-openbank-rewards", "roles": ["ROLE_API"]}

rules_mock := {"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": ["rewards.staff.write"]}}}}

allowed(p, action) if rest.allow.allow with input as {"principal": p, "action": action, "resource": {"type": "rewards", "id": "p-1"}}
	with data.rules as rules_mock

test_edge_may_read_customer if allowed(edge, "rewards.customer.read")

test_edge_may_write_customer if allowed(edge, "rewards.customer.write")

# Base rest.rego operator-read-any admits staff reads of any *.read action (fleet-wide convention).
test_operator_may_not_write_customer_api if not allowed(operator, "rewards.customer.write")

test_rewards_client_may_not_use_customer_api if not allowed(rewards_client, "rewards.customer.write")

test_viewer_may_read_staff if allowed(viewer, "rewards.staff.read")

test_viewer_may_not_write_staff if not allowed(viewer, "rewards.staff.write")

test_operator_may_approve if allowed(operator, "rewards.staff.approve")

test_shared_machine_never_writes_staff if not allowed(shared, "rewards.staff.write")

test_edge_never_reads_staff if not allowed(edge, "rewards.staff.read")
