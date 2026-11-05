// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.domain.model

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Who asked for a [RateAdjustment]. Closed on purpose: a new source is a new review. */
enum class RateAdjustmentSource { REWARDS, }

/**
 * [ACTIVE] adjustments price every instalment whose due date falls in `(validFrom, validTo]`;
 * [CANCELLED] ones (compensation) price nothing from the moment they are cancelled. An adjustment
 * whose window has passed stays ACTIVE in the table — the window, not the status, is what stops it
 * pricing, so no job has to flip it.
 */
enum class RateAdjustmentStatus { ACTIVE, CANCELLED }

/**
 * An ADDITIVE, time-boxed change to a loan's nominal rate (FLUX CR-1 loan rate coupon).
 *
 * The contract rate on [Loan.nominalAnnualRate] is never rewritten. The rate an instalment is
 * charged at is the contract rate plus the [deltaBps] of every ACTIVE adjustment whose window
 * covers that instalment's due date, clamped to the product floor and never below 0 %
 * ([RateAdjustmentMath.effectiveRate]). Amount, term and limit never change (G-CR-04): only the
 * interest/principal split and the instalment amount of the not-yet-due tail do.
 *
 * [sourceRef] is unique across the table and is the idempotency handle: a retried command with the
 * same reference returns the adjustment it already created.
 */
data class RateAdjustment(
    val adjustmentId: UUID = Ids.newId(),
    val loanId: LoanId,
    val deltaBps: Int,
    val validFrom: LocalDate,
    val validTo: LocalDate,
    val source: RateAdjustmentSource,
    val sourceRef: String,
    val approvalRef: String,
    val status: RateAdjustmentStatus = RateAdjustmentStatus.ACTIVE,
    val createdBy: String,
    val createdAt: OffsetDateTime,
    val cancelledBy: String? = null,
    val cancelledAt: OffsetDateTime? = null,
    /** The terms disclosed when the adjustment was applied — replayed verbatim on an idempotent retry. */
    val before: LoanTermsView? = null,
    val after: LoanTermsView? = null,
    val effectiveFrom: LocalDate? = null,
) {
    /** True when this adjustment prices the instalment due on [dueDate]. */
    fun covers(dueDate: LocalDate): Boolean =
        status == RateAdjustmentStatus.ACTIVE && dueDate.isAfter(validFrom) && !dueDate.isAfter(validTo)

    /** Still able to price a future instalment as of [asOf]. */
    fun isLiveOn(asOf: LocalDate): Boolean = status == RateAdjustmentStatus.ACTIVE && validTo.isAfter(asOf)
}

/** The customer-facing terms of a loan at one moment: what the rewards preview shows before/after. */
data class LoanTermsView(
    /** Nominal rate of the next instalment, in PERCENT (5.99 = 5.99 % p.a.). */
    val nominalRatePct: BigDecimal,
    /** APRC over the remaining schedule, in percent; null when it cannot be computed (never 0). */
    val aprcPct: BigDecimal?,
    val instalment: Money,
)

/** Inbound command. The acting principal comes from the verified token, never from this body. */
data class RateAdjustmentRequest(
    val deltaBps: Int,
    val validFrom: LocalDate,
    val validTo: LocalDate,
    val source: RateAdjustmentSource,
    val sourceRef: String,
    val approvalRef: String,
) {
    init {
        require(deltaBps < 0) { "deltaBps must be negative (a reward lowers the rate): $deltaBps" }
        require(deltaBps >= -RateAdjustmentPolicy.MAX_TOTAL_REDUCTION_BPS) {
            "deltaBps must not exceed -${RateAdjustmentPolicy.MAX_TOTAL_REDUCTION_BPS}: $deltaBps"
        }
        require(validTo.isAfter(validFrom)) { "validTo must be after validFrom" }
        require(sourceRef.isNotBlank() && sourceRef.length <= MAX_REF_LENGTH) {
            "sourceRef is required and at most $MAX_REF_LENGTH characters"
        }
        require(approvalRef.isNotBlank() && approvalRef.length <= MAX_REF_LENGTH) {
            "approvalRef is required and at most $MAX_REF_LENGTH characters"
        }
    }

    companion object {
        const val MAX_REF_LENGTH = 128
    }
}

/** Why a credit coupon cannot be applied (409 `CREDIT_COUPON_BLOCKED`, G-CR-06 and the caps). */
enum class RateAdjustmentBlockReason {
    /** Not ACTIVE: delinquent, defaulted, in termination/collections, settling or closed. */
    LOAN_NOT_IN_GOOD_STANDING,

    /** More than 30 days past due on the oldest unpaid instalment. */
    ARREARS,

    /** The loan is or was under forbearance (assessed, or its schedule was restructured). */
    FORBEARANCE,

    /** One rewards adjustment is already live on this loan. */
    ADJUSTMENT_ALREADY_ACTIVE,

    /** The live adjustments plus this one would exceed −1.0 pp. */
    TOTAL_CAP_EXCEEDED,

    /** The rate is already at the product floor (or 0 %): there is nothing to lower. */
    RATE_AT_FLOOR,

    /** No instalment falls due inside the window that is not already accrued or paid. */
    NO_REMAINING_SCHEDULE,

    /** A later instalment is already paid (prepaid out of order): the tail cannot be re-amortised. */
    SCHEDULE_NOT_REPRICEABLE,

    /** validFrom lies in the past: a rate change is disclosed before it takes effect (G-CR-07). */
    BACKDATED,
}

/** Outcome of a preview: the before/after terms when eligible, the reason when not. */
data class RateAdjustmentPreview(
    val before: LoanTermsView?,
    val after: LoanTermsView?,
    val eligible: Boolean,
    val reason: RateAdjustmentBlockReason?,
    val effectiveFrom: LocalDate?,
)

/** A guardrail refusal raised by the use case and mapped to 409 `CREDIT_COUPON_BLOCKED`. */
class RateAdjustmentBlockedException(val reason: RateAdjustmentBlockReason, message: String) :
    IllegalStateException(message)

/** The same sourceRef was already used for a DIFFERENT command (another loan or other terms). */
class RateAdjustmentSourceRefConflictException(message: String) : IllegalStateException(message)

/** The adjustment does not exist on the addressed loan. */
class RateAdjustmentNotFoundException(message: String) : NoSuchElementException(message)

/** One row of a party's loan overview (GET /api/v1/lending/loans/overview). */
data class LoanOverview(
    val loanId: UUID,
    val contractNumber: String?,
    /** The application's product type (e.g. CONSUMER_CREDIT); null for a loan booked without one. */
    val productType: String?,
    val productKind: String?,
    val status: LoanStatus,
    val principal: Money,
    /** Principal still owed: the opening balance of the first unpaid instalment. */
    val outstanding: Money,
    /** Rate of the next instalment in percent, rate adjustments included. */
    val nominalRatePct: BigDecimal,
    /** The contract rate in percent, before any adjustment. */
    val contractRatePct: BigDecimal,
    val nextInstalment: NextInstalment?,
    val daysPastDue: Int,
    /** The live reward adjustment, if any. */
    val rateAdjustment: ActiveRateAdjustment?,
)

data class NextInstalment(val number: Int, val dueDate: LocalDate, val amount: Money)

data class ActiveRateAdjustment(
    val adjustmentId: UUID,
    val deltaBps: Int,
    val validFrom: LocalDate,
    val validTo: LocalDate,
)
