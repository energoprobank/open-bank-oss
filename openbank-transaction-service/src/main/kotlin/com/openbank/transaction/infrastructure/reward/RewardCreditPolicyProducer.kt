// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.reward

import com.openbank.transaction.domain.reward.RewardCreditPolicy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal

/**
 * Per-call reward credit caps from configuration (ADR-0344): `openbank.rewards.credit.cap.czk` /
 * `.eur`. The two markets' currencies only — a currency with no cap is not paid at all. A cap that
 * is not positive is a misconfiguration and fails startup instead of silently refusing every credit.
 */
@ApplicationScoped
class RewardCreditPolicyProducer(
    @ConfigProperty(name = "openbank.rewards.credit.cap.czk", defaultValue = "2000.00")
    private val capCzk: BigDecimal,
    @ConfigProperty(name = "openbank.rewards.credit.cap.eur", defaultValue = "100.00")
    private val capEur: BigDecimal,
) {
    // @Singleton, not a normal scope: the policy is an immutable value (a final data class) and
    // needs no client proxy.
    @Produces
    @Singleton
    fun rewardCreditPolicy(): RewardCreditPolicy {
        require(capCzk.signum() > 0 && capEur.signum() > 0) { "reward credit caps must be positive" }
        return RewardCreditPolicy(mapOf("CZK" to capCzk, "EUR" to capEur))
    }
}
