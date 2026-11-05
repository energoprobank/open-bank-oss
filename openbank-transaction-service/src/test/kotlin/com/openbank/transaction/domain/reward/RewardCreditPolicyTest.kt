// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.domain.reward

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class RewardCreditPolicyTest {

    private val policy = RewardCreditPolicy(mapOf("CZK" to BigDecimal("2000.00"), "EUR" to BigDecimal("100.00")))
    private val party = UUID.randomUUID()

    private fun refusal(block: () -> Unit): RewardCreditRefusal {
        var caught: RewardCreditRefusedException? = null
        try {
            block()
        } catch (e: RewardCreditRefusedException) {
            caught = e
        }
        return checkNotNull(caught) { "expected a refusal" }.refusal
    }

    private fun account(
        partyId: UUID = party,
        type: String = "CURRENT",
        currency: String = "CZK",
        status: String = "ACTIVE",
    ) = RewardTargetAccount(partyId, type, currency, status)

    @Test
    fun `an amount up to the cap passes, normalised to two decimals`() {
        assertThat(policy.checkAmount(BigDecimal("2000"), "CZK")).isEqualTo(BigDecimal("2000.00"))
        assertThat(policy.checkAmount(BigDecimal("0.01"), "EUR")).isEqualTo(BigDecimal("0.01"))
        assertThat(policy.checkAmount(BigDecimal("12.5000"), "EUR")).isEqualTo(BigDecimal("12.50"))
    }

    @Test
    fun `amounts outside the policy are refused with their own code`() {
        assertThat(refusal { policy.checkAmount(BigDecimal("2000.01"), "CZK") })
            .isEqualTo(RewardCreditRefusal.AMOUNT_ABOVE_CAP)
        assertThat(refusal { policy.checkAmount(BigDecimal("100.01"), "EUR") })
            .isEqualTo(RewardCreditRefusal.AMOUNT_ABOVE_CAP)
        assertThat(refusal { policy.checkAmount(BigDecimal.ZERO, "CZK") })
            .isEqualTo(RewardCreditRefusal.AMOUNT_NOT_POSITIVE)
        assertThat(refusal { policy.checkAmount(BigDecimal("-5"), "CZK") })
            .isEqualTo(RewardCreditRefusal.AMOUNT_NOT_POSITIVE)
        assertThat(refusal { policy.checkAmount(BigDecimal("1.005"), "CZK") })
            .isEqualTo(RewardCreditRefusal.AMOUNT_PRECISION)
        // A currency with no cap is not a reward currency: refused, never paid uncapped.
        assertThat(refusal { policy.checkAmount(BigDecimal("1.00"), "USD") })
            .isEqualTo(RewardCreditRefusal.CURRENCY_NOT_SUPPORTED)
    }

    @Test
    fun `the party's own active current account in the currency passes`() {
        policy.checkTarget(account(), party, "CZK")
    }

    @Test
    fun `every other target is refused with its own code`() {
        assertThat(refusal { policy.checkTarget(null, party, "CZK") })
            .isEqualTo(RewardCreditRefusal.ACCOUNT_NOT_FOUND)
        assertThat(refusal { policy.checkTarget(account(partyId = UUID.randomUUID()), party, "CZK") })
            .isEqualTo(RewardCreditRefusal.ACCOUNT_NOT_OWNED_BY_PARTY)
        assertThat(refusal { policy.checkTarget(account(type = "SAVINGS"), party, "CZK") })
            .isEqualTo(RewardCreditRefusal.ACCOUNT_NOT_CURRENT)
        assertThat(refusal { policy.checkTarget(account(status = "FROZEN"), party, "CZK") })
            .isEqualTo(RewardCreditRefusal.ACCOUNT_NOT_ACTIVE)
        assertThat(refusal { policy.checkTarget(account(currency = "EUR"), party, "CZK") })
            .isEqualTo(RewardCreditRefusal.CURRENCY_MISMATCH)
    }

    @Test
    fun `ownership is checked before anything else, so another party's account never leaks its type`() {
        assertThatThrownBy {
            policy.checkTarget(account(partyId = UUID.randomUUID(), type = "SAVINGS", currency = "EUR"), party, "CZK")
        }.isInstanceOfSatisfying(RewardCreditRefusedException::class.java) {
            assertThat(it.refusal).isEqualTo(RewardCreditRefusal.ACCOUNT_NOT_OWNED_BY_PARTY)
        }
    }

    @Test
    fun `reason descriptions are the bilingual FLUX reward texts the customer sees`() {
        RewardReason.entries.forEach {
            assertThat(it.description).startsWith("FLUX odměna").contains("/ FLUX reward")
        }
    }
}
