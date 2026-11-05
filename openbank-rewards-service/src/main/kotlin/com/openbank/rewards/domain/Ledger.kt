// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.domain

import java.time.Instant
import java.util.UUID

enum class EntryType { EARN, CHARGE, RETURN, BURN, EXPIRE, REVERSE, MIGRATION }

/** Ledger sources, agreed with the app (history filter). Wire value = [wire]. */
enum class EntrySource(val wire: String) {
    MISSION("mission"),
    REFERRAL("referral"),
    PROMO_CODE("promoCode"),
    MIGRATED_LOYALTY("migratedLoyalty"),
    PROMOTION_BONUS("promotionBonus"),
    EXPIRY_RETURN("expiryReturn"),
    REDEMPTION("redemption"),
    MANUAL_GRANT("manualGrant"),
    ;

    companion object {
        fun ofWire(value: String): EntrySource = entries.first { it.wire == value }
    }
}

/**
 * One append-only ledger row. [flux] is the signed change of the customer's neutral balance,
 * [couponFlux] the signed change of FLUX held in coupons. Their sum over all rows is the liability
 * in FLUX. (party, type, source, sourceRef) is unique: every ledger effect is exactly-once.
 */
data class LedgerEntry(
    val entryId: UUID,
    val partyId: UUID,
    val type: EntryType,
    val source: EntrySource,
    val flux: Int,
    val couponFlux: Int,
    val couponId: UUID?,
    val sourceRef: String,
    val ruleVersion: String,
    val budgetLineId: String?,
    val market: Market,
    val occurredAt: Instant,
)

/** An earned amount still (partly) in the balance. Charging consumes lots oldest first. */
data class Lot(val lotId: UUID, val partyId: UUID, val amount: Int, val remaining: Int, val earnedAt: Instant)

data class LotDebit(val lotId: UUID, val amount: Int)

object FifoLots {
    /** Debits [amount] from [lots] oldest first. Throws if the lots do not cover it. */
    fun allocate(lots: List<Lot>, amount: Int): List<LotDebit> {
        require(amount > 0) { "amount must be positive" }
        var left = amount
        val debits = mutableListOf<LotDebit>()
        for (lot in lots.sortedWith(compareBy({ it.earnedAt }, { it.lotId }))) {
            if (left == 0) break
            val take = minOf(left, lot.remaining)
            if (take > 0) {
                debits += LotDebit(lot.lotId, take)
                left -= take
            }
        }
        if (left > 0) throw RewardsRuleException("INSUFFICIENT_FLUX", "balance does not cover $amount FLUX")
        return debits
    }
}
