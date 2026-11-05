// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.domain

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Percent base for capacities, pre-charge and benefit percentages. */
const val PERCENT = 100

enum class BenefitType(val target: TargetKind) {
    BILL_CASHBACK(TargetKind.BILL_PAYMENT),
    DEPOSIT_RATE_BOOST(TargetKind.SAVINGS_ACCOUNT),
    LOAN_RATE_REDUCTION(TargetKind.LOAN),
    INSTALMENT_RELIEF(TargetKind.LOAN),
    COSMETIC(TargetKind.NONE),
    FEE_WAIVER(TargetKind.NONE),
    CASH_CREDIT(TargetKind.NONE),
    ;

    /** Credit-reward instruments (CR-1, CR-3): own budget line, never in promotions or nudges (G-CR-02). */
    val credit: Boolean get() = this == LOAN_RATE_REDUCTION || this == INSTALMENT_RELIEF
}

enum class TargetKind { BILL_PAYMENT, SAVINGS_ACCOUNT, LOAN, NONE }

enum class CouponCategory { BILLS, SAVINGS, LOANS, STYLE }

enum class Rarity { STANDARD, GOLD, SIGNATURE }

/** Benefit parameters as stored with the definition; every field optional, per benefit type. */
data class BenefitParams(
    val percent: Int? = null,
    val cap: BigDecimal? = null,
    val deltaBps: Int? = null,
    val durationDays: Int? = null,
    val balanceCap: BigDecimal? = null,
    val theme: String? = null,
)

data class CouponDef(
    val couponDefId: String,
    val market: Market,
    val benefitType: BenefitType,
    val category: CouponCategory,
    val params: BenefitParams,
    val rarity: Rarity,
    val capacity: Int,
    val validityDays: Int?,
    val levelGate: Int,
    val inCatalogue: Boolean,
    val budgetLineId: String,
    val active: Boolean,
    val sortOrder: Int,
)

enum class CouponState { ISSUED, CHARGING, READY, USING, USED, EXPIRED, WITHDRAWN }

data class Coupon(
    val couponId: UUID,
    val partyId: UUID,
    val couponDefId: String,
    val market: Market,
    val state: CouponState,
    val capacity: Int,
    val chargedOwn: Int,
    val chargedBonus: Int,
    val preCharge: Int,
    val issuedAt: Instant,
    val expiresAt: Instant?,
    val usedAt: Instant?,
    val sourceRef: String,
    val targetKind: TargetKind?,
    val targetId: String?,
    val outcome: String?,
) {
    val charged: Int get() = chargedOwn + chargedBonus + preCharge
    val remainingCapacity: Int get() = capacity - charged
    val percent: Int get() = charged * PERCENT / capacity
    val holding: Boolean get() = state in HOLDING_STATES

    companion object {
        /** States in which a coupon still holds FLUX (counted in the liability). */
        val HOLDING_STATES = setOf(CouponState.ISSUED, CouponState.CHARGING, CouponState.READY, CouponState.USING)

        fun issue(def: CouponDef, partyId: UUID, preChargePct: Int, sourceRef: String, at: Instant): Coupon {
            val pre = def.capacity * preChargePct / PERCENT
            return Coupon(
                couponId = UUID.randomUUID(), partyId = partyId, couponDefId = def.couponDefId, market = def.market,
                state = if (pre >= def.capacity) CouponState.READY else CouponState.ISSUED,
                capacity = def.capacity, chargedOwn = 0, chargedBonus = 0, preCharge = pre.coerceAtMost(def.capacity),
                issuedAt = at,
                expiresAt = def.validityDays?.let {
                    at.plus(Duration.ofDays(it.toLong()))
                },
                usedAt = null,
                sourceRef = sourceRef, targetKind = null, targetId = null, outcome = null,
            )
        }
    }
}

/** The result of one charge command: how much of the customer's own FLUX moves, and the promotion bonus. */
data class ChargePlan(val own: Int, val bonus: Int)

object Charging {
    /**
     * Fill [coupon] from a [balance] with up to [requested] own FLUX, under an optional promotion
     * [multiplier] whose remaining per-party bonus is [bonusCapLeft]. The customer never pays for
     * capacity the coupon does not have: own FLUX is limited to what fills the coupon at the
     * multiplier (spec AC-RW-03, AC-RW-04).
     */
    @Suppress("ThrowsCount") // one rule table; splitting hides it
    fun plan(coupon: Coupon, requested: Int, balance: Int, multiplier: BigDecimal?, bonusCapLeft: Int): ChargePlan {
        if (coupon.state !in setOf(CouponState.CHARGING, CouponState.ISSUED)) {
            throw RewardsRuleException("COUPON_NOT_CHARGEABLE", "coupon is ${coupon.state}")
        }
        if (requested <= 0) throw RewardsRuleException("INVALID_AMOUNT", "charge a positive amount of FLUX")
        val room = coupon.remainingCapacity
        val available = minOf(requested, balance)
        if (available <= 0) throw RewardsRuleException("INSUFFICIENT_FLUX", "the balance is empty")
        val m = multiplier?.takeIf { it > BigDecimal.ONE }
        if (m == null || bonusCapLeft <= 0) return ChargePlan(own = minOf(available, room), bonus = 0)
        // Own FLUX needed to fill the room at the multiplier, rounded up.
        val ownToFill = BigDecimal(room).divide(m, 0, java.math.RoundingMode.CEILING).toInt()
        val own = minOf(available, ownToFill)
        val bonusAtMultiplier = m.subtract(BigDecimal.ONE).multiply(BigDecimal(own)).toInt()
        val bonus = minOf(bonusAtMultiplier, room - own, bonusCapLeft).coerceAtLeast(0)
        return ChargePlan(own = own, bonus = bonus)
    }

    fun apply(coupon: Coupon, plan: ChargePlan): Coupon {
        val updated = coupon.copy(
            chargedOwn = coupon.chargedOwn + plan.own,
            chargedBonus =
            coupon.chargedBonus + plan.bonus,
        )
        return if (updated.remainingCapacity <= 0) updated.copy(state = CouponState.READY) else updated
    }
}

/** What a coupon did once used; shown on the coupon and in the ledger history. */
data class FulfilmentOutcome(
    val summary: String,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val rateDeltaBps: Int? = null,
    val until: Instant? = null,
    val reference: String? = null,
)
