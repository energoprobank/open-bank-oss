// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.domain.model

import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class RateAdjustmentStatus { ACTIVE, CANCELLED }

/**
 * An ADDITIVE, time-boxed change to the rate an account accrues at — e.g. the FLUX rewards
 * "Deposit Rate Boost" coupon (+50 bps for 90 days).
 *
 * Deliberately a separate concept from the per-account override in [InterestRateConfig]: an
 * override REPLACES the rate (one per account, no provenance), an adjustment is a signed delta in
 * basis points layered ON TOP of whatever rate applies (override or product default). Any number
 * of adjustments may be active at once; they sum.
 *
 * The window is half-open: [validFrom] inclusive, [validTo] exclusive — `[validFrom, validTo)`.
 *
 * [sourceRef] is the caller's idempotency key (unique across the table): a replayed create with the
 * same terms returns this row, a create that reuses the key with different terms is a 409.
 */
data class RateAdjustment(
    val adjustmentId: UUID = UUID.randomUUID(),
    val accountId: UUID,
    val deltaBps: Int,
    val validFrom: LocalDate,
    val validTo: LocalDate,
    val source: String,
    val sourceRef: String,
    val approvalRef: String,
    val status: RateAdjustmentStatus = RateAdjustmentStatus.ACTIVE,
    val createdBy: String,
    val createdAt: OffsetDateTime,
    val cancelledBy: String? = null,
    val cancelledAt: OffsetDateTime? = null,
) {
    /** True when this adjustment is ACTIVE and its `[validFrom, validTo)` window contains [date]. */
    fun appliesOn(date: LocalDate): Boolean =
        status == RateAdjustmentStatus.ACTIVE && !date.isBefore(validFrom) && date.isBefore(validTo)

    /** Same business terms as [other] — the idempotency comparison for a replayed create. */
    fun sameTermsAs(other: RateAdjustment): Boolean = accountId == other.accountId &&
        deltaBps == other.deltaBps &&
        validFrom == other.validFrom &&
        validTo == other.validTo &&
        source == other.source &&
        sourceRef == other.sourceRef &&
        approvalRef == other.approvalRef
}

/** The rules for a rate adjustment: the request guard and the effective-rate arithmetic. Pure. */
object RateAdjustmentPolicy {
    /** |deltaBps| ceiling: a single adjustment may move the rate by at most 3 percentage points. */
    const val MAX_ABS_DELTA_BPS = 300

    /** Longest window a single adjustment may span (validTo - validFrom, in days). */
    const val MAX_WINDOW_DAYS = 400L

    private const val MAX_SOURCE_LENGTH = 32
    private const val MAX_REF_LENGTH = 128

    /** Basis points in 1.0 (a rate of 100 %): 0.0001 per bp. */
    private const val BPS_PER_UNIT = 10_000L
    private val SOURCE_PATTERN = Regex("[A-Z][A-Z0-9_]*")

    /** Throws [IllegalArgumentException] (→ 400) when [adjustment] violates a guard. */
    fun validate(adjustment: RateAdjustment) {
        with(adjustment) {
            require(deltaBps != 0) { "deltaBps must not be 0" }
            require(kotlin.math.abs(deltaBps) <= MAX_ABS_DELTA_BPS) {
                "|deltaBps| must be <= $MAX_ABS_DELTA_BPS"
            }
            require(validTo.isAfter(validFrom)) { "validTo must be after validFrom" }
            require(ChronoUnit.DAYS.between(validFrom, validTo) <= MAX_WINDOW_DAYS) {
                "validFrom..validTo must span at most $MAX_WINDOW_DAYS days"
            }
            require(source.length <= MAX_SOURCE_LENGTH && SOURCE_PATTERN.matches(source)) {
                "source must be an upper-case code like REWARDS (max $MAX_SOURCE_LENGTH chars)"
            }
            require(sourceRef.isNotBlank() && sourceRef.length <= MAX_REF_LENGTH) {
                "sourceRef is required (max $MAX_REF_LENGTH chars)"
            }
            require(approvalRef.isNotBlank() && approvalRef.length <= MAX_REF_LENGTH) {
                "approvalRef is required (max $MAX_REF_LENGTH chars)"
            }
        }
    }

    /**
     * The annual rate (decimal fraction, 0.04 = 4 %) an account accrues at on [date]:
     * `baseRate + Σ deltaBps / 10 000` over the ACTIVE adjustments whose window covers [date],
     * clamped to `[0, cap]` (no upper clamp when [cap] is null).
     *
     * When no adjustment applies [baseRate] is returned UNCHANGED — not even clamped — so an account
     * without adjustments accrues exactly as it did before adjustments existed.
     */
    fun effectiveAnnualRate(
        baseRate: BigDecimal,
        adjustments: Collection<RateAdjustment>,
        date: LocalDate,
        cap: BigDecimal? = null,
    ): BigDecimal {
        val applicable = adjustments.filter { it.appliesOn(date) }
        if (applicable.isEmpty()) return baseRate
        val deltaBps = applicable.sumOf { it.deltaBps.toLong() }
        val raw = baseRate + BigDecimal.valueOf(deltaBps).divide(BigDecimal.valueOf(BPS_PER_UNIT))
        val floored = raw.max(BigDecimal.ZERO)
        return if (cap != null) floored.min(cap) else floored
    }
}

/** No adjustment [adjustmentId] exists on account [accountId] → 404. */
class RateAdjustmentNotFoundException(accountId: UUID, adjustmentId: UUID) :
    ResourceNotFoundException(
        "No rate adjustment $adjustmentId on account $accountId",
        "RATE_ADJUSTMENT_NOT_FOUND",
    )

/** [sourceRef] was already used for an adjustment with different terms → 409. */
class RateAdjustmentSourceRefConflictException(sourceRef: String) :
    ResourceConflictException(
        "sourceRef $sourceRef already identifies a rate adjustment with different terms",
        "SOURCE_REF_CONFLICT",
    )
