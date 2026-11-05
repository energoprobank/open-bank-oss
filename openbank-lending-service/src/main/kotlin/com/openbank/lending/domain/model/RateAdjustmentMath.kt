// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.domain.model

import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.Amortization
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.lending.Aprc
import com.openbank.libs.lending.Delinquency
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Pure math for additive rate adjustments. No persistence, no framework.
 *
 * ## How a rate change reaches the schedule
 *
 * Each instalment of the repriceable TAIL (unpaid, interest not yet accrued, due after `asOf`) is
 * charged at the rate in force on its due date ([rateOn]). The tail is cut into runs of equal rate,
 * and each run is re-amortised with the shared [Amortization.schedule] from the balance it opens
 * with, over ALL the periods still remaining — the standard "rate change ⇒ recompute the annuity
 * over the remaining term" rule. The term and the outstanding principal never change (G-CR-04);
 * the last instalment still clears the balance to exactly zero.
 *
 * ## Why no job reverts the rate on validTo
 *
 * The adjustment's window is applied per due date, so the run AFTER `validTo` is already priced at
 * the contract rate when the adjustment is applied: the reversion is written into the schedule the
 * customer is shown (G-CR-07) instead of being a later, silent mutation. Cancelling an adjustment
 * (compensation) recomputes the same tail without it.
 *
 * Due dates are kept as they are; only opening/principal/interest/payment/closing move.
 */
object RateAdjustmentMath {

    private val MC = MathContext.DECIMAL128
    private const val BPS_PER_UNIT = 10_000L
    private const val PERCENT = 100L
    private val BPS = BigDecimal.valueOf(BPS_PER_UNIT)
    private val HUNDRED = BigDecimal.valueOf(PERCENT)
    private const val PCT_SCALE = 4
    private const val APRC_PCT_SCALE = 2

    /**
     * The rate an instalment due on [dueDate] is charged at: [contractRate] plus every adjustment
     * covering that date, clamped to `max(floor, 0)` — but never RAISED by the clamp, so a loan
     * whose contract rate already sits below a (mis)configured floor keeps its contract rate.
     */
    fun rateOn(
        dueDate: LocalDate,
        contractRate: BigDecimal,
        adjustments: List<RateAdjustment>,
        floor: BigDecimal,
    ): BigDecimal {
        val deltaBps = adjustments.filter { it.covers(dueDate) }.sumOf { it.deltaBps }
        return effectiveRate(contractRate, deltaBps, floor)
    }

    fun effectiveRate(contractRate: BigDecimal, deltaBps: Int, floor: BigDecimal): BigDecimal {
        val adjusted = contractRate.add(BigDecimal(deltaBps).divide(BPS, MC), MC)
        val lowerBound = floor.max(BigDecimal.ZERO).min(contractRate)
        return adjusted.max(lowerBound).stripTrailingZeros()
    }

    /**
     * Re-amortise [tail] (contiguous, ascending by number) with the per-due-date rate [rateFor].
     * Returns the same rows (same ids, numbers and due dates) with new amounts.
     */
    fun reprice(
        tail: List<LoanInstallment>,
        method: AmortizationMethod,
        periodsPerYear: Int,
        rateFor: (LocalDate) -> BigDecimal,
    ): List<LoanInstallment> {
        if (tail.isEmpty()) return tail
        val rows = tail.sortedBy { it.number }
        val rates = rows.map { rateFor(it.dueDate) }
        val out = ArrayList<LoanInstallment>(rows.size)
        var opening = rows.first().openingBalance
        var start = 0
        while (start < rows.size) {
            var end = start
            while (end + 1 < rows.size && rates[end + 1].compareTo(rates[start]) == 0) end++
            val runLength = end - start + 1
            if (!opening.isPositive()) {
                // Nothing left to amortise (fully prepaid principal): zero rows, nothing invented.
                val zero = Money.zero(opening.currency.code)
                for (i in start..end) {
                    out +=
                        rows[i].copy(
                            openingBalance = zero,
                            principal = zero,
                            interest = zero,
                            payment = zero,
                            closingBalance = zero,
                        )
                }
            } else {
                val schedule = Amortization.schedule(
                    principal = opening,
                    nominalAnnualRate = rates[start],
                    termPeriods = rows.size - start,
                    firstDueDate = rows[start].dueDate,
                    periodsPerYear = periodsPerYear,
                    method = method,
                ).installments
                for (k in 0 until runLength) {
                    val line = schedule[k]
                    out += rows[start + k].copy(
                        openingBalance = line.openingBalance,
                        principal = line.principal,
                        interest = line.interest,
                        payment = line.payment,
                        closingBalance = line.closingBalance,
                    )
                }
                opening = schedule[runLength - 1].closingBalance
            }
            start = end + 1
        }
        return out
    }

    /**
     * APRC (CCD 2023/2225 Annex III, via the fleet's single solver [Aprc]) over the REMAINING
     * schedule: the balance the first remaining period opens with is the advance, every remaining
     * instalment a payment, at whole periods from the start of that first period. Lending holds no
     * fee data, so no charges enter the equation. Returned in percent; null when unsolvable.
     */
    fun aprcPct(tail: List<LoanInstallment>, periodsPerYear: Int): BigDecimal? {
        val rows = tail.sortedBy { it.number }.filter { it.payment.isPositive() }
        val advance = tail.minByOrNull { it.number }?.openingBalance ?: return null
        if (rows.isEmpty() || !advance.isPositive()) return null
        val aprc = Aprc.solve(
            advances = listOf(Aprc.CashFlow(BigDecimal.ZERO, advance.amount)),
            payments = rows.mapIndexed { index, row ->
                Aprc.CashFlow(BigDecimal(index + 1).divide(BigDecimal(periodsPerYear), MC), row.payment.amount)
            },
        ) ?: return null
        return aprc.multiply(HUNDRED).setScale(APRC_PCT_SCALE, RoundingMode.HALF_UP)
    }

    fun pct(rate: BigDecimal): BigDecimal =
        rate.multiply(HUNDRED).setScale(PCT_SCALE, RoundingMode.HALF_UP).stripTrailingZeros().let {
            if (it.scale() < 0) it.setScale(0) else it
        }

    /** Days past due of the oldest unpaid instalment as of [asOf]. */
    fun daysPastDue(schedule: List<LoanInstallment>, asOf: LocalDate): Int =
        Delinquency.daysPastDue(schedule.filter { !it.paid }.minByOrNull { it.dueDate }?.dueDate, asOf)
}

/**
 * Guardrails for a reward rate adjustment (FLUX CR-1, G-CR-04/06/07) and the plan it produces.
 * Pure: the use case loads the state, this decides.
 */
object RateAdjustmentPolicy {

    /** −1.0 pp: the most all live adjustments on one loan may lower its rate by. */
    const val MAX_TOTAL_REDUCTION_BPS = 100

    /** G-CR-06: more than this many days past due blocks a credit coupon. */
    const val MAX_DAYS_PAST_DUE = 30

    /** A computed adjustment: the new tail and the disclosed terms. */
    data class Plan(
        val currentTail: List<LoanInstallment>,
        val repricedTail: List<LoanInstallment>,
        val before: LoanTermsView,
        val after: LoanTermsView,
        val effectiveFrom: LocalDate,
    )

    sealed interface Decision {
        data class Allowed(val plan: Plan) : Decision
        data class Blocked(val reason: RateAdjustmentBlockReason, val detail: String) : Decision
    }

    /**
     * Decide whether [candidate] may be applied to [loan] as of [asOf], given its [schedule] and the
     * [existing] adjustments, and if so compute the plan.
     */
    @Suppress("ReturnCount", "LongParameterList", "CyclomaticComplexMethod")
    fun evaluate(
        loan: Loan,
        schedule: List<LoanInstallment>,
        existing: List<RateAdjustment>,
        candidate: RateAdjustment,
        asOf: LocalDate,
        floor: BigDecimal,
    ): Decision {
        if (candidate.validFrom.isBefore(asOf)) {
            return Decision.Blocked(
                RateAdjustmentBlockReason.BACKDATED,
                "validFrom ${candidate.validFrom} is before $asOf",
            )
        }
        when {
            loan.status == LoanStatus.FORBEARANCE_ASSESSED ->
                return Decision.Blocked(RateAdjustmentBlockReason.FORBEARANCE, "loan is under forbearance")
            loan.status != LoanStatus.ACTIVE ->
                return Decision.Blocked(
                    RateAdjustmentBlockReason.LOAN_NOT_IN_GOOD_STANDING,
                    "loan status is ${loan.status}",
                )
            // Loan.version is bumped only by a reschedule (forbearance restructuring, #667/#668).
            loan.version > 0 ->
                return Decision.Blocked(RateAdjustmentBlockReason.FORBEARANCE, "loan schedule was restructured")
        }
        val dpd = RateAdjustmentMath.daysPastDue(schedule, asOf)
        if (dpd > MAX_DAYS_PAST_DUE) {
            return Decision.Blocked(RateAdjustmentBlockReason.ARREARS, "$dpd days past due")
        }
        val live = existing.filter { it.isLiveOn(asOf) && it.adjustmentId != candidate.adjustmentId }
        if (live.any { it.source == candidate.source }) {
            return Decision.Blocked(
                RateAdjustmentBlockReason.ADJUSTMENT_ALREADY_ACTIVE,
                "a ${candidate.source} rate adjustment is already active on this loan",
            )
        }
        if (live.sumOf { it.deltaBps } + candidate.deltaBps < -MAX_TOTAL_REDUCTION_BPS) {
            return Decision.Blocked(
                RateAdjustmentBlockReason.TOTAL_CAP_EXCEEDED,
                "live adjustments plus this one exceed -$MAX_TOTAL_REDUCTION_BPS bps",
            )
        }
        return planChange(loan, schedule, live, live + candidate, asOf, floor, candidate)
    }

    /**
     * The plan for moving the tail from the [from] set of adjustments to the [to] set. Used for both
     * apply ([evaluate]) and cancel ([planCancellation]).
     */
    @Suppress("ReturnCount", "LongParameterList")
    private fun planChange(
        loan: Loan,
        schedule: List<LoanInstallment>,
        from: List<RateAdjustment>,
        to: List<RateAdjustment>,
        asOf: LocalDate,
        floor: BigDecimal,
        changed: RateAdjustment,
    ): Decision {
        val tail = repriceableTail(schedule, asOf)
        if (tail.isEmpty()) {
            return Decision.Blocked(RateAdjustmentBlockReason.NO_REMAINING_SCHEDULE, "no instalment left to reprice")
        }
        val firstTail = tail.first().number
        if (schedule.any { it.number > firstTail && (it.paid || it.interestAccrued) }) {
            return Decision.Blocked(
                RateAdjustmentBlockReason.SCHEDULE_NOT_REPRICEABLE,
                "a later instalment is already paid or accrued",
            )
        }
        val affected = tail.filter { changed.copy(status = RateAdjustmentStatus.ACTIVE).covers(it.dueDate) }
        if (affected.isEmpty()) {
            return Decision.Blocked(
                RateAdjustmentBlockReason.NO_REMAINING_SCHEDULE,
                "no remaining instalment falls due inside ${changed.validFrom}..${changed.validTo}",
            )
        }
        val rateBefore = { d: LocalDate -> RateAdjustmentMath.rateOn(d, loan.nominalAnnualRate, from, floor) }
        val rateAfter = { d: LocalDate -> RateAdjustmentMath.rateOn(d, loan.nominalAnnualRate, to, floor) }
        if (affected.all { rateBefore(it.dueDate).compareTo(rateAfter(it.dueDate)) == 0 }) {
            return Decision.Blocked(RateAdjustmentBlockReason.RATE_AT_FLOOR, "the rate is already at its floor")
        }
        val repriced = RateAdjustmentMath.reprice(tail, loan.method, loan.periodsPerYear, rateAfter)
        val effectiveFrom = affected.first().dueDate
        val beforeRow = tail.first { it.dueDate == effectiveFrom }
        val afterRow = repriced.first { it.dueDate == effectiveFrom }
        return Decision.Allowed(
            Plan(
                currentTail = tail,
                repricedTail = repriced,
                before = LoanTermsView(
                    nominalRatePct = RateAdjustmentMath.pct(rateBefore(effectiveFrom)),
                    aprcPct = RateAdjustmentMath.aprcPct(tail, loan.periodsPerYear),
                    instalment = beforeRow.payment,
                ),
                after = LoanTermsView(
                    nominalRatePct = RateAdjustmentMath.pct(rateAfter(effectiveFrom)),
                    aprcPct = RateAdjustmentMath.aprcPct(repriced, loan.periodsPerYear),
                    instalment = afterRow.payment,
                ),
                effectiveFrom = effectiveFrom,
            ),
        )
    }

    /**
     * Compensation: the tail re-amortised WITHOUT [cancelled]. Guardrails do not apply — taking a
     * reward back must always be possible while there is a tail to reprice; when there is none
     * (every covered instalment already paid), the result is Blocked and the caller only flips the
     * status.
     */
    fun planCancellation(
        loan: Loan,
        schedule: List<LoanInstallment>,
        existing: List<RateAdjustment>,
        cancelled: RateAdjustment,
        asOf: LocalDate,
        floor: BigDecimal,
    ): Decision {
        val live = existing.filter { it.status == RateAdjustmentStatus.ACTIVE }
        val remaining = live.filter { it.adjustmentId != cancelled.adjustmentId }
        return planChange(loan, schedule, live, remaining, asOf, floor, cancelled)
    }

    /** Unpaid, not yet accrued instalments due after [asOf], ascending — the only rows a rate change may touch. */
    fun repriceableTail(schedule: List<LoanInstallment>, asOf: LocalDate): List<LoanInstallment> = schedule
        .filter { !it.paid && !it.interestAccrued && it.dueDate.isAfter(asOf) }
        .sortedBy { it.number }
}
