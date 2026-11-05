// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.RateAdjustmentRepository
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.RateAdjustment
import com.openbank.lending.domain.model.RateAdjustmentBlockReason
import com.openbank.lending.domain.model.RateAdjustmentBlockedException
import com.openbank.lending.domain.model.RateAdjustmentRequest
import com.openbank.lending.domain.model.RateAdjustmentSource
import com.openbank.lending.domain.model.RateAdjustmentSourceRefConflictException
import com.openbank.lending.domain.model.RateAdjustmentStatus
import com.openbank.lending.infrastructure.rewards.RateAdjustmentConfig
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.Amortization
import com.openbank.libs.lending.AmortizationMethod
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class RateAdjustmentServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-11-05T09:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.now(clock)
    private val loans = mockk<LoanRepository>()
    private val installments = mockk<InstallmentRepository>()
    private val adjustments = mockk<RateAdjustmentRepository>()
    private val applications = mockk<LoanApplicationRepository>()
    private val events = mockk<LoanEventEmitter>()
    private val config = RateAdjustmentConfig(BigDecimal.ZERO, listOf("service-account-openbank-rewards"), emptyList())
    private val service = RateAdjustmentService(loans, installments, adjustments, applications, events, config, clock)

    private val loan = Loan(
        applicationId = LoanApplicationId.random(),
        partyId = UUID.randomUUID(),
        principal = Money.of(BigDecimal("240000.00"), "CZK"),
        nominalAnnualRate = BigDecimal("0.0599"),
        termPeriods = 48,
        method = AmortizationMethod.ANNUITY,
        firstDueDate = LocalDate.of(2026, 1, 15),
        disbursedAt = OffsetDateTime.now(clock).minusMonths(11),
        createdAt = OffsetDateTime.now(clock).minusMonths(11),
    )

    private val schedule: List<LoanInstallment> = Amortization.schedule(
        loan.principal,
        loan.nominalAnnualRate,
        loan.termPeriods,
        loan.firstDueDate,
    ).installments.map {
        val due = !it.dueDate.isAfter(today)
        LoanInstallment(
            loanId = loan.id, number = it.number, dueDate = it.dueDate, openingBalance = it.openingBalance,
            principal = it.principal, interest = it.interest, payment = it.payment, closingBalance = it.closingBalance,
            paid = due, interestAccrued = due,
        )
    }

    private val request = RateAdjustmentRequest(
        deltaBps = -50,
        validFrom = today,
        validTo = today.plusMonths(12),
        source = RateAdjustmentSource.REWARDS,
        sourceRef = "coupon-42",
        approvalRef = "sca-7",
    )

    private val emitted = mutableListOf<LendingOutboxMessage>()
    private val repriced = slot<List<LoanInstallment>>()
    private val inserted = slot<RateAdjustment>()

    @BeforeEach
    fun wire() {
        every { loans.withLocked<Any>(any(), any()) } answers {
            loans.findById(firstArg()).flatMap(secondArg<(Loan?) -> Uni<Any>>())
        }
        every { loans.findById(loan.id) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loan.id) } returns Uni.createFrom().item(schedule)
        every { installments.reprice(capture(repriced)) } answers { Uni.createFrom().item(firstArg<List<*>>().size) }
        every { adjustments.findByLoan(loan.id) } returns Uni.createFrom().item(emptyList())
        every { adjustments.findBySourceRef(any()) } returns Uni.createFrom().nullItem()
        every { adjustments.insert(capture(inserted)) } answers { Uni.createFrom().item(firstArg<RateAdjustment>()) }
        every { adjustments.markCancelled(any(), any(), any()) } returns Uni.createFrom().item(1)
        every { events.emit(capture(emitted)) } returns Uni.createFrom().item(Unit)
    }

    @Test
    fun `apply stores the adjustment, reprices the tail and emits loan_rate_adjusted`() {
        val outcome = service.apply(loan.id, request, "service-account-openbank-rewards").await().indefinitely()

        assertThat(outcome.replayed).isFalse()
        assertThat(inserted.captured.createdBy).isEqualTo("service-account-openbank-rewards")
        assertThat(inserted.captured.after!!.nominalRatePct).isEqualByComparingTo("5.49")
        assertThat(repriced.captured.map { it.id }).isEqualTo(schedule.filter { !it.paid }.map { it.id })

        val event = emitted.single()
        assertThat(event.eventType).isEqualTo("loan.rate_adjusted")
        val json = ObjectMapper().readTree(event.payload)
        assertThat(json["aggregateType"].asText()).isEqualTo("LOAN")
        assertThat(json["aggregateId"].asText()).isEqualTo(loan.id.value.toString())
        assertThat(json["loanId"].asText()).isEqualTo(loan.id.value.toString())
        assertThat(json["partyId"].asText()).isEqualTo(loan.partyId.toString())
        assertThat(json["beforeRatePct"].asText()).isEqualTo("5.99")
        assertThat(json["afterRatePct"].asText()).isEqualTo("5.49")
        assertThat(json["effectiveFrom"].asText()).isEqualTo("2026-11-15")
        assertThat(json["after"]["instalment"]["currency"].asText()).isEqualTo("CZK")
        assertThat(json["sourceService"].asText()).isEqualTo("lending")
    }

    @Test
    fun `a replayed sourceRef returns the stored adjustment and writes nothing`() {
        val stored = RateAdjustment(
            loanId = loan.id, deltaBps = -50, validFrom = request.validFrom, validTo = request.validTo,
            source = RateAdjustmentSource.REWARDS, sourceRef = "coupon-42", approvalRef = "sca-7",
            createdBy = "service-account-openbank-rewards", createdAt = OffsetDateTime.now(clock),
        )
        every { adjustments.findBySourceRef("coupon-42") } returns Uni.createFrom().item(stored)

        val outcome = service.apply(loan.id, request, "service-account-openbank-rewards").await().indefinitely()

        assertThat(outcome.replayed).isTrue()
        assertThat(outcome.adjustment.adjustmentId).isEqualTo(stored.adjustmentId)
        verify(exactly = 0) { adjustments.insert(any()) }
        verify(exactly = 0) { installments.reprice(any()) }
        verify(exactly = 0) { events.emit(any()) }
    }

    @Test
    fun `a sourceRef reused with different terms is a conflict`() {
        val stored = RateAdjustment(
            loanId = loan.id, deltaBps = -25, validFrom = request.validFrom, validTo = request.validTo,
            source = RateAdjustmentSource.REWARDS, sourceRef = "coupon-42", approvalRef = "sca-7",
            createdBy = "x", createdAt = OffsetDateTime.now(clock),
        )
        every { adjustments.findBySourceRef("coupon-42") } returns Uni.createFrom().item(stored)

        assertThatThrownBy { service.apply(loan.id, request, "rewards").await().indefinitely() }
            .isInstanceOf(RateAdjustmentSourceRefConflictException::class.java)
    }

    @Test
    fun `a guardrail refusal writes nothing and carries the reason`() {
        every { loans.findById(loan.id) } returns Uni.createFrom().item(loan.copy(version = 1))

        assertThatThrownBy { service.apply(loan.id, request, "rewards").await().indefinitely() }
            .isInstanceOfSatisfying(RateAdjustmentBlockedException::class.java) {
                assertThat(it.reason).isEqualTo(RateAdjustmentBlockReason.FORBEARANCE)
            }
        verify(exactly = 0) { adjustments.insert(any()) }
        verify(exactly = 0) { events.emit(any()) }
    }

    @Test
    fun `a row that moved underneath the reprice fails the command`() {
        every { installments.reprice(any()) } returns Uni.createFrom().item(0)

        assertThatThrownBy { service.apply(loan.id, request, "rewards").await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Schedule changed")
    }

    @Test
    fun `preview reports eligibility without writing`() {
        val preview = service.preview(loan.id, -50, 12).await().indefinitely()
        assertThat(preview.eligible).isTrue()
        assertThat(preview.after!!.instalment.amount).isLessThan(preview.before!!.instalment.amount)
        verify(exactly = 0) { adjustments.insert(any()) }
        verify(exactly = 0) { installments.reprice(any()) }
    }

    @Test
    fun `preview of a blocked loan says why and still shows the current terms`() {
        every { loans.findById(loan.id) } returns
            Uni.createFrom().item(loan.copy(status = com.openbank.lending.domain.model.LoanStatus.DELINQUENT))
        val preview = service.preview(loan.id, -50, 12).await().indefinitely()
        assertThat(preview.eligible).isFalse()
        assertThat(preview.reason).isEqualTo(RateAdjustmentBlockReason.LOAN_NOT_IN_GOOD_STANDING)
        assertThat(preview.before!!.nominalRatePct).isEqualByComparingTo("5.99")
        assertThat(preview.after).isNull()
    }

    @Test
    fun `cancel re-amortises at the contract rate, marks the row and emits the cancellation`() {
        service.apply(loan.id, request, "rewards").await().indefinitely()
        val active = inserted.captured
        val discounted = schedule.filter { it.paid } + repriced.captured
        every { installments.findByLoan(loan.id) } returns Uni.createFrom().item(discounted)
        every { adjustments.findByLoan(loan.id) } returns Uni.createFrom().item(listOf(active))
        emitted.clear()

        val cancelled = service.cancel(loan.id, active.adjustmentId, "rewards").await().indefinitely()

        assertThat(cancelled.status).isEqualTo(RateAdjustmentStatus.CANCELLED)
        verify { adjustments.markCancelled(active.adjustmentId, "rewards", any()) }
        assertThat(repriced.captured.first().payment).isEqualTo(schedule.first { !it.paid }.payment)
        assertThat(emitted.single().eventType).isEqualTo("loan.rate_adjustment_cancelled")
    }

    @Test
    fun `cancelling an already cancelled adjustment is a no-op`() {
        val gone = RateAdjustment(
            loanId = loan.id, deltaBps = -50, validFrom = today, validTo = today.plusMonths(12),
            source = RateAdjustmentSource.REWARDS, sourceRef = "c", approvalRef = "a",
            status = RateAdjustmentStatus.CANCELLED, createdBy = "x", createdAt = OffsetDateTime.now(clock),
        )
        every { adjustments.findByLoan(loan.id) } returns Uni.createFrom().item(listOf(gone))

        service.cancel(loan.id, gone.adjustmentId, "rewards").await().indefinitely()

        verify(exactly = 0) { adjustments.markCancelled(any(), any(), any()) }
        verify(exactly = 0) { events.emit(any()) }
    }

    @Test
    fun `overview shows outstanding, the adjusted rate, next instalment and arrears days`() {
        val active = RateAdjustment(
            loanId = loan.id, deltaBps = -50, validFrom = today, validTo = today.plusMonths(12),
            source = RateAdjustmentSource.REWARDS, sourceRef = "c", approvalRef = "a",
            createdBy = "x", createdAt = OffsetDateTime.now(clock),
        )
        every { loans.findByParty(loan.partyId) } returns Uni.createFrom().item(listOf(loan))
        every { applications.findByParty(loan.partyId) } returns Uni.createFrom().item(
            listOf(
                LoanApplication(
                    id = loan.applicationId, partyId = loan.partyId, requestedAmount = loan.principal,
                    nominalAnnualRate = loan.nominalAnnualRate, termPeriods = 48, firstDueDate = loan.firstDueDate,
                    proposedBy = "x", createdAt = loan.createdAt, productType = "CONSUMER_CREDIT",
                ),
            ),
        )
        every { installments.findByLoans(any()) } returns Uni.createFrom().item(schedule)
        every { adjustments.findByLoan(loan.id) } returns Uni.createFrom().item(listOf(active))

        val row = service.overview(loan.partyId).await().indefinitely().single()

        val next = schedule.first { !it.paid }
        assertThat(row.productType).isEqualTo("CONSUMER_CREDIT")
        assertThat(row.outstanding).isEqualTo(next.openingBalance)
        assertThat(row.nextInstalment!!.dueDate).isEqualTo(next.dueDate)
        assertThat(row.nominalRatePct).isEqualByComparingTo("5.49")
        assertThat(row.contractRatePct).isEqualByComparingTo("5.99")
        assertThat(row.daysPastDue).isZero()
        assertThat(row.rateAdjustment!!.deltaBps).isEqualTo(-50)
    }
}
