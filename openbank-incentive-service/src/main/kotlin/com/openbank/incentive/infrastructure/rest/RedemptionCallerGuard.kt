// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.incentive.infrastructure.rest

import com.openbank.libs.authz.ServiceAccountIdentity
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException

/**
 * The ONE machine principal that redeems promo codes in a single step: the FLUX rewards engine,
 * authenticating as its own Keycloak client `openbank-rewards` (ROLE_API). Pinned again by the
 * OPA rule `service-rewards-incentive-redeem`; `RedemptionCallerGuardTest` keeps the two in step.
 */
internal val REDEMPTION_CALLERS = setOf("service-account-openbank-rewards")

/**
 * ROLE_API is held by every service account, so this check — verified client id + principal name,
 * never the username claim alone — is what keeps the redemption route to the rewards engine even
 * where the OPA sidecar runs advisory. No staff path: operators use reserve + commit.
 */
internal fun requireRewardsRedeemer(identity: SecurityIdentity) {
    if (!ServiceAccountIdentity.isOneOf(identity, REDEMPTION_CALLERS)) {
        throw ForbiddenException("caller is not the rewards engine")
    }
}
