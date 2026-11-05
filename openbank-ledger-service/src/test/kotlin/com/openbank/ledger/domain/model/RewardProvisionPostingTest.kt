// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class RewardProvisionPostingTest {

    private fun id(code: String) = UUID.fromString("a0000000-0000-0000-0000-00000000$code")

    @Test
    fun `a positive delta debits FLUX reward expense and credits the FLUX provision`() {
        val legs = RewardProvisionPosting.legs(RewardMarket.CZ, "CZK", BigDecimal("1250.5"))

        assertThat(legs).containsExactly(
            RewardProvisionLeg(id("5300"), JournalSide.DEBIT, BigDecimal("1250.50")),
            RewardProvisionLeg(id("2400"), JournalSide.CREDIT, BigDecimal("1250.50")),
        )
    }

    @Test
    fun `a negative delta releases the provision against the expense`() {
        val legs = RewardProvisionPosting.legs(RewardMarket.CZ, "CZK", BigDecimal("-40.00"))

        assertThat(legs).containsExactly(
            RewardProvisionLeg(id("2400"), JournalSide.DEBIT, BigDecimal("40.00")),
            RewardProvisionLeg(id("5300"), JournalSide.CREDIT, BigDecimal("40.00")),
        )
    }

    @Test
    fun `the BG market posts to the EUR accounts`() {
        val legs = RewardProvisionPosting.legs(RewardMarket.BG, "EUR", BigDecimal("3.10"))

        assertThat(legs.map { it.glAccountId }).containsExactly(id("5301"), id("2401"))
    }

    @Test
    fun `the template always balances`() {
        listOf("0.01", "-0.01", "99999.99", "-12345.67").forEach { delta ->
            val legs = RewardProvisionPosting.legs(RewardMarket.CZ, "CZK", BigDecimal(delta))
            val debits = legs.filter { it.side == JournalSide.DEBIT }.sumOf { it.amount }
            val credits = legs.filter { it.side == JournalSide.CREDIT }.sumOf { it.amount }
            assertThat(debits).isEqualByComparingTo(credits)
            assertThat(legs).allSatisfy { assertThat(it.amount.signum()).isPositive() }
        }
    }

    @Test
    fun `a currency that is not the market's is refused`() {
        assertThatThrownBy { RewardProvisionPosting.legs(RewardMarket.CZ, "EUR", BigDecimal.TEN) }
            .isInstanceOf(LedgerValidationException::class.java)
            .hasMessageContaining("CZK")
        assertThatThrownBy { RewardProvisionPosting.legs(RewardMarket.BG, "CZK", BigDecimal.TEN) }
            .isInstanceOf(LedgerValidationException::class.java)
    }

    @Test
    fun `a zero delta and sub-minor-unit amounts are refused`() {
        assertThatThrownBy { RewardProvisionPosting.legs(RewardMarket.CZ, "CZK", BigDecimal("0.00")) }
            .isInstanceOf(LedgerValidationException::class.java)
        assertThatThrownBy { RewardProvisionPosting.legs(RewardMarket.CZ, "CZK", BigDecimal("0.005")) }
            .isInstanceOf(LedgerValidationException::class.java)
        // Trailing zeros past the minor unit are not extra precision.
        assertThat(RewardProvisionPosting.legs(RewardMarket.CZ, "CZK", BigDecimal("1.2500")).first().amount)
            .isEqualTo(BigDecimal("1.25"))
    }

    @Test
    fun `the chart ids are the fixed ids V33 seeds, and 5200 is never one of them`() {
        assertThat(RewardChart.FLUX_PROVISION).containsEntry("CZK", id("2400")).containsEntry("EUR", id("2401"))
        assertThat(RewardChart.FLUX_REWARD_EXPENSE).containsEntry("CZK", id("5300")).containsEntry("EUR", id("5301"))
        assertThat(RewardChart.CREDIT_REWARD_INTEREST_FORGONE)
            .containsEntry("CZK", id("5310"))
            .containsEntry("EUR", id("5311"))
        val all = RewardChart.FLUX_PROVISION.values + RewardChart.FLUX_REWARD_EXPENSE.values +
            RewardChart.CREDIT_REWARD_INTEREST_FORGONE.values
        // ADR-0310 proposed 5200, which is MM Interest Expense (V29).
        assertThat(all).doesNotContain(id("5200"), id("5201"))
    }
}
