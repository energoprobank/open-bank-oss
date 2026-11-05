// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.domain

import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.lending.domain.model.RateAdjustment
import com.openbank.lending.domain.model.RateAdjustmentBlockReason
import com.openbank.lending.domain.model.RateAdjustmentMath
import com.openbank.lending.domain.model.RateAdjustmentPolicy
import com.openbank.lending.domain.model.RateAdjustmentSource
import com.openbank.lending.domain.model.RateAdjustmentStatus
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.Amortization
import com.openbank.libs.lending.AmortizationMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The CR-1 money math: clamping, the tail re-amortisation (incl. the reversion on validTo baked into
 * the schedule), APRC over the remaining schedule, and every guardrail.
 */
class RateAdjustmentMathTest {

    private val now: OffsetDateTime = OffsetDateTime.of(2026, 11, 5, 9, 0, 0, 0, ZoneOffset.UTC)
    private val today: LocalDate = now.toLocalDate()
    private val firstDue: LocalDate = LocalDate.of(2026, 1, 15)
    private val rate = BigDecimal("0.0599")

    private fun czk(v: String) = Money.of(BigDecimal(v), "CZK")

    private fun loan(
        status: LoanStatus = LoanStatus.ACTIVE,
        version: Long = 0,
        method: AmortizationMethod = AmortizationMethod.ANNUITY,
    ) = Loan(
        applicationId = LoanApplicationId.random(),
        partyId = java.util.UUID.randomUUID(),
        principal = czk("240000.00"),
        nominalAnnualRate = rate,
        termPeriods = 48,
        method = method,
        firstDueDate = firstDue,
        status = status,
        disbursedAt = now.minusMonths(11),
        createdAt = now.minusMonths(11),
        version = version,
    )

    /** The booked schedule, with every instalment due on/before [today] paid. */
    private fun schedule(loan: Loan): List<LoanInstallment> = Amortization.schedule(
        loan.principal,
        loan.nominalAnnualRate,
        loan.termPeriods,
        loan.firstDueDate,
        12,
        loan.method,
    ).installments.map {
        val due = !it.dueDate.isAfter(today)
        LoanInstallment(
            loanId = loan.id, number = it.number, dueDate = it.dueDate, openingBalance = it.openingBalance,
            principal = it.principal, interest = it.interest, payment = it.payment, closingBalance = it.closingBalance,
            paid = due, interestAccrued = due,
        )
    }

    private fun adjustment(
        loan: Loan,
        delta: Int = -50,
        from: LocalDate = today,
        to: LocalDate = today.plusMonths(12),
        status: RateAdjustmentStatus = RateAdjustmentStatus.ACTIVE,
    ) = RateAdjustment(
        loanId = loan.id, deltaBps = delta, validFrom = from, validTo = to, source = RateAdjustmentSource.REWARDS,
        sourceRef = "ref-${java.util.UUID.randomUUID()}", approvalRef = "appr", status = status,
        createdBy = "service-account-openbank-rewards", createdAt = now,
    )

    // ── clamping ────────────────────────────────────────────────────────────────

    @Test
    fun `effective rate is the contract rate plus the deltas`() {
        assertThat(RateAdjustmentMath.effectiveRate(rate, -50, BigDecimal.ZERO)).isEqualByComparingTo("0.0549")
    }

    @Test
    fun `effective rate is clamped to the product floor`() {
        assertThat(RateAdjustmentMath.effectiveRate(BigDecimal("0.03"), -100, BigDecimal("0.025")))
            .isEqualByComparingTo("0.025")
    }

    @Test
    fun `effective rate never goes below zero even with a negative floor`() {
        assertThat(RateAdjustmentMath.effectiveRate(BigDecimal("0.004"), -100, BigDecimal("-1")))
            .isEqualByComparingTo("0")
    }

    @Test
    fun `the clamp never raises a contract rate that already sits below the floor`() {
        assertThat(RateAdjustmentMath.effectiveRate(BigDecimal("0.01"), -50, BigDecimal("0.02")))
            .isEqualByComparingTo("0.01")
    }

    // ── schedule recalculation ──────────────────────────────────────────────────

    @Test
    fun `repricing at the unchanged rate reproduces the booked tail`() {
        val l = loan()
        val tail = RateAdjustmentPolicy.repriceableTail(schedule(l), today)
        val repriced = RateAdjustmentMath.reprice(tail, l.method, 12) { rate }
        assertThat(repriced.map { it.payment }).isEqualTo(tail.map { it.payment })
        assertThat(repriced.last().closingBalance.isZero()).isTrue()
    }

    @Test
    fun `a 12-month coupon lowers 12 instalments, then reverts, and never changes principal or term`() {
        val l = loan()
        val booked = schedule(l)
        val tail = RateAdjustmentPolicy.repriceableTail(booked, today)
        val adj = adjustment(l)
        val repriced = RateAdjustmentMath.reprice(tail, l.method, 12) {
            RateAdjustmentMath.rateOn(it, l.nominalAnnualRate, listOf(adj), BigDecimal.ZERO)
        }

        // Same rows, same due dates, same count: the term does not move (G-CR-04).
        assertThat(repriced.map { it.id }).isEqualTo(tail.map { it.id })
        assertThat(repriced.map { it.dueDate }).isEqualTo(tail.map { it.dueDate })
        // Principal repaid over the tail is still exactly the outstanding balance.
        val principalSum = repriced.map { it.principal.amount }.reduce(BigDecimal::add)
        assertThat(principalSum).isEqualByComparingTo(tail.first().openingBalance.amount)
        assertThat(repriced.last().closingBalance.isZero()).isTrue()

        val discounted = repriced.take(12)
        val reverted = repriced.drop(12)
        // Lower instalment inside the window …
        assertThat(discounted.first().payment.amount).isLessThan(tail.first().payment.amount)
        assertThat(discounted.map { it.payment }.distinct()).hasSize(1)
        // … and interest at 5.49 % on the opening balance.
        val expected = tail.first().openingBalance.amount.multiply(BigDecimal("0.0549"))
            .divide(BigDecimal(12), 2, java.math.RoundingMode.HALF_EVEN)
        assertThat(discounted.first().interest.amount).isEqualByComparingTo(expected)
        // After validTo the contract rate applies again: a NEW level payment, re-amortised.
        assertThat(reverted.first().payment.amount).isGreaterThan(discounted.first().payment.amount)
        val revertedInterest = reverted.first().openingBalance.amount.multiply(rate)
            .divide(BigDecimal(12), 2, java.math.RoundingMode.HALF_EVEN)
        assertThat(reverted.first().interest.amount).isEqualByComparingTo(revertedInterest)
        // The customer pays less in total.
        val before = tail.sumOf { it.payment.amount }
        val after = repriced.sumOf { it.payment.amount }
        assertThat(after).isLessThan(before)
    }

    @Test
    fun `equal-principal loans keep their principal split and only lose interest`() {
        val l = loan(method = AmortizationMethod.EQUAL_PRINCIPAL)
        val tail = RateAdjustmentPolicy.repriceableTail(schedule(l), today)
        val adj = adjustment(l)
        val repriced = RateAdjustmentMath.reprice(tail, l.method, 12) {
            RateAdjustmentMath.rateOn(it, l.nominalAnnualRate, listOf(adj), BigDecimal.ZERO)
        }
        assertThat(repriced.first().principal.amount).isEqualByComparingTo(tail.first().principal.amount)
        assertThat(repriced.first().interest.amount).isLessThan(tail.first().interest.amount)
        assertThat(repriced.last().closingBalance.isZero()).isTrue()
    }

    @Test
    fun `APRC over the remaining schedule tracks the nominal rate`() {
        val l = loan()
        val tail = RateAdjustmentPolicy.repriceableTail(schedule(l), today)
        val aprc = RateAdjustmentMath.aprcPct(tail, 12)!!
        // (1 + 0.0599/12)^12 − 1 = 6.16 %
        assertThat(aprc).isEqualByComparingTo("6.16")
    }

    // ── guardrails ──────────────────────────────────────────────────────────────

    private fun decide(
        l: Loan = loan(),
        rows: List<LoanInstallment> = schedule(l),
        existing: List<RateAdjustment> = emptyList(),
        candidate: RateAdjustment = adjustment(l),
        floor: BigDecimal = BigDecimal.ZERO,
    ) = RateAdjustmentPolicy.evaluate(l, rows, existing, candidate, today, floor)

    private fun blockedReason(d: RateAdjustmentPolicy.Decision) = (d as RateAdjustmentPolicy.Decision.Blocked).reason

    @Test
    fun `a loan in good standing is allowed and the plan discloses before and after`() {
        val d = decide() as RateAdjustmentPolicy.Decision.Allowed
        assertThat(d.plan.before.nominalRatePct).isEqualByComparingTo("5.99")
        assertThat(d.plan.after.nominalRatePct).isEqualByComparingTo("5.49")
        assertThat(d.plan.after.instalment.amount).isLessThan(d.plan.before.instalment.amount)
        assertThat(d.plan.after.aprcPct).isLessThan(d.plan.before.aprcPct)
        assertThat(d.plan.effectiveFrom).isEqualTo(LocalDate.of(2026, 11, 15))
    }

    @Test
    fun `arrears of more than 30 days block`() {
        val l = loan()
        val rows = schedule(l).map { if (it.dueDate == LocalDate.of(2026, 9, 15)) it.copy(paid = false) else it }
        assertThat(blockedReason(decide(l, rows))).isEqualTo(RateAdjustmentBlockReason.ARREARS)
    }

    @Test
    fun `arrears of 30 days or fewer do not block`() {
        val l = loan()
        val rows = schedule(l).map { if (it.dueDate == LocalDate.of(2026, 10, 15)) it.copy(paid = false) else it }
        assertThat(decide(l, rows)).isInstanceOf(RateAdjustmentPolicy.Decision.Allowed::class.java)
    }

    @Test
    fun `forbearance blocks - assessed status or a restructured schedule`() {
        assertThat(blockedReason(decide(loan(status = LoanStatus.FORBEARANCE_ASSESSED))))
            .isEqualTo(RateAdjustmentBlockReason.FORBEARANCE)
        assertThat(blockedReason(decide(loan(version = 1)))).isEqualTo(RateAdjustmentBlockReason.FORBEARANCE)
    }

    @Test
    fun `collections and termination states block`() {
        for (s in listOf(
            LoanStatus.DELINQUENT,
            LoanStatus.DEFAULTED,
            LoanStatus.ACCELERATED,
            LoanStatus.TERMINATION_NOTICED,
        )) {
            assertThat(
                blockedReason(decide(loan(status = s))),
            ).isEqualTo(RateAdjustmentBlockReason.LOAN_NOT_IN_GOOD_STANDING)
        }
    }

    @Test
    fun `only one live rewards adjustment per loan`() {
        val l = loan()
        assertThat(blockedReason(decide(l, existing = listOf(adjustment(l)))))
            .isEqualTo(RateAdjustmentBlockReason.ADJUSTMENT_ALREADY_ACTIVE)
    }

    @Test
    fun `a cancelled or expired adjustment does not count against the cap`() {
        val l = loan()
        val cancelled = adjustment(l, status = RateAdjustmentStatus.CANCELLED)
        val expired = adjustment(l, from = today.minusMonths(13), to = today.minusMonths(1))
        assertThat(
            decide(l, existing = listOf(cancelled, expired)),
        ).isInstanceOf(RateAdjustmentPolicy.Decision.Allowed::class.java)
    }

    @Test
    fun `the total reduction is capped at one percentage point`() {
        val l = loan()
        // With REWARDS the only source, a second live adjustment is already refused by the
        // one-per-loan rule, so the total cap is exercised through the candidate itself.
        val big = adjustment(l, delta = -100)
        assertThat(decide(l, candidate = big)).isInstanceOf(RateAdjustmentPolicy.Decision.Allowed::class.java)
        val over = adjustment(l, delta = -101)
        assertThat(blockedReason(decide(l, candidate = over))).isEqualTo(RateAdjustmentBlockReason.TOTAL_CAP_EXCEEDED)
    }

    @Test
    fun `a rate already at the floor has nothing to lower`() {
        assertThat(blockedReason(decide(floor = rate))).isEqualTo(RateAdjustmentBlockReason.RATE_AT_FLOOR)
    }

    @Test
    fun `a partial floor clamps the reduction instead of blocking`() {
        val d = decide(floor = BigDecimal("0.0579")) as RateAdjustmentPolicy.Decision.Allowed
        assertThat(d.plan.after.nominalRatePct).isEqualByComparingTo("5.79")
    }

    @Test
    fun `a backdated start is refused - the new rate is disclosed before it applies`() {
        val l = loan()
        assertThat(blockedReason(decide(l, candidate = adjustment(l, from = today.minusDays(1)))))
            .isEqualTo(RateAdjustmentBlockReason.BACKDATED)
    }

    @Test
    fun `a fully repaid schedule has nothing to reprice`() {
        val l = loan()
        val rows = schedule(l).map { it.copy(paid = true) }
        assertThat(blockedReason(decide(l, rows))).isEqualTo(RateAdjustmentBlockReason.NO_REMAINING_SCHEDULE)
    }

    @Test
    fun `an out-of-order prepaid later instalment makes the tail non-repriceable`() {
        val l = loan()
        val rows = schedule(l).map { if (it.number == 20) it.copy(paid = true) else it }
        assertThat(blockedReason(decide(l, rows))).isEqualTo(RateAdjustmentBlockReason.SCHEDULE_NOT_REPRICEABLE)
    }

    @Test
    fun `cancelling re-amortises the tail back to the contract rate`() {
        val l = loan()
        val adj = adjustment(l)
        val booked = schedule(l)
        val tail = RateAdjustmentPolicy.repriceableTail(booked, today)
        val discounted = RateAdjustmentMath.reprice(tail, l.method, 12) {
            RateAdjustmentMath.rateOn(it, l.nominalAnnualRate, listOf(adj), BigDecimal.ZERO)
        }
        val adjustedSchedule = booked.filter { it.paid } + discounted
        val d = RateAdjustmentPolicy.planCancellation(l, adjustedSchedule, listOf(adj), adj, today, BigDecimal.ZERO)
            as RateAdjustmentPolicy.Decision.Allowed
        assertThat(d.plan.repricedTail.map { it.payment }).isEqualTo(tail.map { it.payment })
        assertThat(d.plan.after.nominalRatePct).isEqualByComparingTo("5.99")
    }
}
