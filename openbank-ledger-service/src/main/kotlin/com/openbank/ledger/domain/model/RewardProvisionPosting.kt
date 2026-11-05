// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import java.math.BigDecimal
import java.util.UUID

/**
 * FLUX rewards market (ADR-0344). The market fixes the posting currency: a CZ member's FLUX are worth
 * CZK, a BG member's EUR. A request naming a market and a different currency is refused rather than
 * posted to the other market's accounts.
 */
enum class RewardMarket(val currency: String) {
    CZ("CZK"),
    BG("EUR"),
}

/**
 * The FLUX accounts seeded by V33, by their fixed ids (`a0000000-0000-0000-0000-00000000<code>`).
 * The ledger owns the chart, so its own template names them here; no product service carries the
 * expense account. transaction-service's reward cash credit names the provision by the same id.
 */
object RewardChart {
    /** 2400 / 2401 FLUX Reward Provision (LIABILITY). */
    val FLUX_PROVISION: Map<String, UUID> = mapOf(
        "CZK" to UUID.fromString("a0000000-0000-0000-0000-000000002400"),
        "EUR" to UUID.fromString("a0000000-0000-0000-0000-000000002401"),
    )

    /** 5300 / 5301 FLUX Reward Expense (EXPENSE). */
    val FLUX_REWARD_EXPENSE: Map<String, UUID> = mapOf(
        "CZK" to UUID.fromString("a0000000-0000-0000-0000-000000005300"),
        "EUR" to UUID.fromString("a0000000-0000-0000-0000-000000005301"),
    )

    /** 5310 / 5311 Credit Reward Interest Forgone (EXPENSE). Seeded for lending; no poster yet. */
    val CREDIT_REWARD_INTEREST_FORGONE: Map<String, UUID> = mapOf(
        "CZK" to UUID.fromString("a0000000-0000-0000-0000-000000005310"),
        "EUR" to UUID.fromString("a0000000-0000-0000-0000-000000005311"),
    )
}

/** One leg of the provision template: which account, which side, how much (always positive). */
data class RewardProvisionLeg(val glAccountId: UUID, val side: JournalSide, val amount: BigDecimal)

/**
 * The daily FLUX provision posting (ADR-0344 D2). The rewards engine reports the signed change of
 * the outstanding FLUX liability for one market and day:
 *
 *     delta > 0  (FLUX earned)              DEBIT  FLUX reward expense   CREDIT FLUX provision
 *     delta < 0  (breakage / expiry release) DEBIT  FLUX provision        CREDIT FLUX reward expense
 *
 * Redemption is NOT posted here: a cash credit debits the provision directly in transaction-service
 * (Dr provision / Cr customer deposit), which is what closes the books — the provision only ever
 * holds what is earned and neither redeemed nor released.
 *
 * Pure: no persistence, no clock. The caller turns the legs into a journal and owns idempotency.
 */
object RewardProvisionPosting {
    /** Both markets' currencies have two minor-unit digits. */
    const val MONEY_SCALE = 2

    fun legs(market: RewardMarket, currency: String, deltaAmount: BigDecimal): List<RewardProvisionLeg> {
        requireValid(market.currency == currency) {
            "market ${market.name} posts in ${market.currency}, not $currency"
        }
        requireValid(deltaAmount.signum() != 0) { "deltaAmount must not be zero" }
        requireValid(deltaAmount.stripTrailingZeros().scale() <= MONEY_SCALE) {
            "deltaAmount must have at most $MONEY_SCALE decimal places, got $deltaAmount"
        }
        val amount = deltaAmount.abs().setScale(MONEY_SCALE)
        val expense = RewardChart.FLUX_REWARD_EXPENSE.getValue(currency)
        val provision = RewardChart.FLUX_PROVISION.getValue(currency)
        return if (deltaAmount.signum() > 0) {
            listOf(
                RewardProvisionLeg(expense, JournalSide.DEBIT, amount),
                RewardProvisionLeg(provision, JournalSide.CREDIT, amount),
            )
        } else {
            listOf(
                RewardProvisionLeg(provision, JournalSide.DEBIT, amount),
                RewardProvisionLeg(expense, JournalSide.CREDIT, amount),
            )
        }
    }
}
