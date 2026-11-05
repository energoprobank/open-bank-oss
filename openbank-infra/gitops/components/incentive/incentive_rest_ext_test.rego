# SPDX-License-Identifier: Apache-2.0
# Held to a known-POSITIVE and a known-NEGATIVE: every allow case has a matching deny.

package openbank.rest_test

import data.openbank.rest
import rego.v1

rewards := "service-account-openbank-rewards"

test_rewards_engine_may_redeem if {
	rest.allow with input as {
		"principal": {"service_account": true, "client_id": "openbank-rewards", "type": "HUMAN", "id": rewards, "roles": ["ROLE_API"]},
		"action": "incentive.redemption.redeem",
	}
}

# Phase-1 PEP shape (no service_account marker) keeps the name-based grant.
test_rewards_engine_may_redeem_without_marker if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": rewards, "roles": ["ROLE_API"]},
		"action": "incentive.redemption.redeem",
	}
}

test_rewards_name_on_another_client_may_not_redeem if {
	not rest.allow with input as {
		"principal": {"service_account": true, "client_id": "openbank-edge", "type": "HUMAN", "id": rewards, "roles": ["ROLE_API"]},
		"action": "incentive.redemption.redeem",
	}
}

test_customer_edge_may_not_redeem if {
	not rest.allow with input as {
		"principal": {"service_account": true, "client_id": "openbank-edge", "type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_API"]},
		"action": "incentive.redemption.redeem",
	}
}

test_shared_services_account_may_not_redeem if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API", "ROLE_OPERATOR"]},
		"action": "incentive.redemption.redeem",
	}
}

test_staff_operator_may_not_redeem if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "operator@openbank.test", "roles": ["ROLE_OPERATOR", "ROLE_ADMIN"]},
		"action": "incentive.redemption.redeem",
	}
}

# The rewards engine's grant is this one action, nothing broader.
test_rewards_engine_may_not_take_another_incentive_action if {
	not rest.allow with input as {
		"principal": {"service_account": true, "client_id": "openbank-rewards", "type": "HUMAN", "id": rewards, "roles": ["ROLE_API"]},
		"action": "incentive.offer.publish",
	}
}
