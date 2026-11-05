// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.`in`.LoanOverviewUseCase
import com.openbank.lending.application.port.`in`.RateAdjustmentOutcome
import com.openbank.lending.application.port.`in`.RateAdjustmentUseCase
import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.RateAdjustmentRepository
import com.openbank.lending.domain.model.ActiveRateAdjustment
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanOverview
import com.openbank.lending.domain.model.LoanTermsView
import com.openbank.lending.domain.model.NextInstalment
import com.openbank.lending.domain.model.RateAdjustment
import com.openbank.lending.domain.model.RateAdjustmentBlockedException
import com.openbank.lending.domain.model.RateAdjustmentMath
import com.openbank.lending.domain.model.RateAdjustmentNotFoundException
import com.openbank.lending.domain.model.RateAdjustmentPolicy
import com.openbank.lending.domain.model.RateAdjustmentPreview
import com.openbank.lending.domain.model.RateAdjustmentRequest
import com.openbank.lending.domain.model.RateAdjustmentSource
import com.openbank.lending.domain.model.RateAdjustmentSourceRefConflictException
import com.openbank.lending.domain.model.RateAdjustmentStatus
import com.openbank.lending.infrastructure.rewards.RateAdjustmentConfig
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * FLUX rewards CR-1: additive loan rate adjustments, plus the party loan overview the app's loan
 * screens read (both need the effective, adjustment-aware rate).
 *
 * ## Why not reuse `reschedule`
 *
 * Lending already has a mid-term schedule rewrite, [LendingService.reschedule], and it is the wrong
 * tool here: it is FORBEARANCE — it may change the term and first due date, forgive principal,
 * capitalise accrued interest onto the GL, deletes and renumbers the unpaid tail, and bumps
 * `Loan.version`, which this very feature reads as "was forborne" (G-CR-06). A reward must change
 * none of that (G-CR-04). What it shares with reschedule is the amortisation primitive, and that is
 * reused: [RateAdjustmentMath.reprice] re-amortises through the same `Amortization.schedule`.
 *
 * ## Atomicity
 *
 * Everything for one command runs inside [LoanRepository.withLocked]: the adjustment row, the
 * repriced instalments and the outbox event commit together. The loan lock also serialises two
 * concurrent commands for one loan, so the "one live rewards adjustment" cap cannot be raced; the
 * UNIQUE(source_ref) constraint backs the idempotency check across loans.
 *
 * ## Customer notice (G-CR-07)
 *
 * The new rate and instalment are returned in the 201 body before anything falls due, and the
 * repriced schedule is readable at `GET /api/v1/lending/loans/{id}/schedule`. Lending has no
 * customer-notification path of its own (no notification-service consumer reads
 * `openbank.lending.events`), so `loan.rate_adjusted` carries the before/after terms for whichever
 * channel delivers the notice.
 */
@ApplicationScoped
@Suppress("LongParameterList", "TooManyFunctions")
class RateAdjustmentService @Inject constructor(
    private val loans: LoanRepository,
    private val installments: InstallmentRepository,
    private val adjustments: RateAdjustmentRepository,
    private val applications: LoanApplicationRepository,
    private val events: LoanEventEmitter,
    private val config: RateAdjustmentConfig,
    private val clock: Clock,
) : RateAdjustmentUseCase,
    LoanOverviewUseCase {

    private val json = ObjectMapper()

    override fun preview(loanId: LoanId, deltaBps: Int, months: Int): Uni<RateAdjustmentPreview> {
        require(months in 1..MAX_MONTHS) { "months must be between 1 and $MAX_MONTHS" }
        require(deltaBps < 0 && deltaBps >= -RateAdjustmentPolicy.MAX_TOTAL_REDUCTION_BPS) {
            "deltaBps must be between -${RateAdjustmentPolicy.MAX_TOTAL_REDUCTION_BPS} and -1"
        }
        val today = LocalDate.now(clock)
        return loans.findById(loanId).flatMap { loan ->
            if (loan == null) {
                Uni.createFrom().failure(loanNotFound(loanId))
            } else {
                installments.findByLoan(loanId).flatMap { schedule ->
                    adjustments.findByLoan(loanId).map { existing ->
                        val candidate =
                            candidate(loan, deltaBps, today, today.plusMonths(months.toLong()), "preview", "")
                        previewOf(loan, schedule, existing, candidate, today)
                    }
                }
            }
        }
    }

    private fun previewOf(
        loan: Loan,
        schedule: List<LoanInstallment>,
        existing: List<RateAdjustment>,
        candidate: RateAdjustment,
        today: LocalDate,
    ): RateAdjustmentPreview =
        when (val d = RateAdjustmentPolicy.evaluate(loan, schedule, existing, candidate, today, floor())) {
            is RateAdjustmentPolicy.Decision.Allowed ->
                RateAdjustmentPreview(d.plan.before, d.plan.after, true, null, d.plan.effectiveFrom)
            is RateAdjustmentPolicy.Decision.Blocked ->
                RateAdjustmentPreview(currentTerms(loan, schedule, existing, today), null, false, d.reason, null)
        }

    override fun apply(loanId: LoanId, request: RateAdjustmentRequest, actor: String): Uni<RateAdjustmentOutcome> {
        require(actor.isNotBlank()) { "caller identity is required" }
        return loans.withLocked(loanId) { loan ->
            if (loan == null) {
                Uni.createFrom().failure(loanNotFound(loanId))
            } else {
                adjustments.findBySourceRef(request.sourceRef).flatMap { prior ->
                    when {
                        prior == null -> applyNew(loan, request, actor)
                        prior.matches(
                            loanId,
                            request,
                        ) -> Uni.createFrom().item(RateAdjustmentOutcome(prior, replayed = true))
                        else -> Uni.createFrom().failure(
                            RateAdjustmentSourceRefConflictException(
                                "sourceRef ${request.sourceRef} was already used for a different rate adjustment",
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun applyNew(loan: Loan, request: RateAdjustmentRequest, actor: String): Uni<RateAdjustmentOutcome> {
        val today = LocalDate.now(clock)
        return installments.findByLoan(loan.id).flatMap { schedule ->
            adjustments.findByLoan(loan.id).flatMap { existing ->
                val candidate = candidate(
                    loan,
                    request.deltaBps,
                    request.validFrom,
                    request.validTo,
                    request.sourceRef,
                    request.approvalRef,
                )
                    .copy(source = request.source, createdBy = actor)
                when (val d = RateAdjustmentPolicy.evaluate(loan, schedule, existing, candidate, today, floor())) {
                    is RateAdjustmentPolicy.Decision.Blocked ->
                        Uni.createFrom().failure(RateAdjustmentBlockedException(d.reason, d.detail))
                    is RateAdjustmentPolicy.Decision.Allowed -> {
                        val toSave = candidate.copy(
                            before = d.plan.before,
                            after = d.plan.after,
                            effectiveFrom = d.plan.effectiveFrom,
                        )
                        adjustments.insert(toSave)
                            .call { _ -> repriceExactly(d.plan.repricedTail) }
                            .call { saved ->
                                events.emit(
                                    adjustedEvent(
                                        EVENT_ADJUSTED,
                                        loan,
                                        saved,
                                        d.plan.before,
                                        d.plan.after,
                                        d.plan.effectiveFrom,
                                    ),
                                )
                            }
                            .map { RateAdjustmentOutcome(it, replayed = false) }
                    }
                }
            }
        }
    }

    override fun cancel(loanId: LoanId, adjustmentId: UUID, actor: String): Uni<RateAdjustment> {
        require(actor.isNotBlank()) { "caller identity is required" }
        return loans.withLocked(loanId) { loan ->
            if (loan == null) {
                Uni.createFrom().failure(loanNotFound(loanId))
            } else {
                adjustments.findByLoan(loanId).flatMap { existing ->
                    val target = existing.firstOrNull { it.adjustmentId == adjustmentId }
                    when {
                        target == null -> Uni.createFrom().failure(
                            RateAdjustmentNotFoundException("Rate adjustment $adjustmentId not found on loan $loanId"),
                        )
                        target.status == RateAdjustmentStatus.CANCELLED -> Uni.createFrom().item(target)
                        else -> cancelActive(loan, existing, target, actor)
                    }
                }
            }
        }
    }

    private fun cancelActive(
        loan: Loan,
        existing: List<RateAdjustment>,
        target: RateAdjustment,
        actor: String,
    ): Uni<RateAdjustment> {
        val now = OffsetDateTime.now(clock)
        val today = now.toLocalDate()
        return installments.findByLoan(loan.id).flatMap { schedule ->
            val plan = (
                RateAdjustmentPolicy.planCancellation(loan, schedule, existing, target, today, floor())
                    as? RateAdjustmentPolicy.Decision.Allowed
                )?.plan
            // No repriceable tail left (everything covered is paid/accrued, or the loan is closed):
            // the cancellation only stops the adjustment pricing anything further.
            val reprice = plan?.let { repriceExactly(it.repricedTail) } ?: Uni.createFrom().item(Unit)
            reprice
                .flatMap { adjustments.markCancelled(target.adjustmentId, actor, now) }
                .map { target.copy(status = RateAdjustmentStatus.CANCELLED, cancelledBy = actor, cancelledAt = now) }
                .call { cancelled ->
                    val terms =
                        plan
                            ?: return@call events.emit(
                                adjustedEvent(EVENT_CANCELLED, loan, cancelled, null, null, null),
                            )
                    events.emit(
                        adjustedEvent(EVENT_CANCELLED, loan, cancelled, terms.before, terms.after, terms.effectiveFrom),
                    )
                }
        }
    }

    /** Reprice the tail, failing (and so rolling back the whole command) if any row moved underneath. */
    private fun repriceExactly(rows: List<LoanInstallment>): Uni<Unit> = installments.reprice(rows).flatMap { changed ->
        if (changed == rows.size) {
            Uni.createFrom().item(Unit)
        } else {
            Uni.createFrom().failure(
                IllegalStateException("Schedule changed while repricing: $changed of ${rows.size} instalments updated"),
            )
        }
    }

    // --- Overview ----------------------------------------------------------------------------------

    override fun overview(partyId: UUID): Uni<List<LoanOverview>> {
        val today = LocalDate.now(clock)
        return loans.findByParty(partyId).flatMap { book ->
            if (book.isEmpty()) {
                Uni.createFrom().item(emptyList())
            } else {
                applications.findByParty(partyId).flatMap { apps ->
                    val products = apps.associateBy { it.id }
                    installments.findByLoans(book.map { it.id.value }).flatMap { rows ->
                        val byLoan = rows.groupBy { it.loanId }
                        val folded: Uni<List<LoanOverview>> = Uni.createFrom().item(emptyList())
                        book.fold(folded) { acc, loan ->
                            acc.flatMap { done ->
                                adjustments.findByLoan(loan.id).map { adj ->
                                    val app = products[loan.applicationId]
                                    done +
                                        overviewOf(
                                            loan,
                                            byLoan[loan.id].orEmpty(),
                                            adj,
                                            today,
                                            app?.productType,
                                            app?.productKind?.name,
                                        )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Suppress("LongParameterList")
    private fun overviewOf(
        loan: Loan,
        schedule: List<LoanInstallment>,
        adj: List<RateAdjustment>,
        today: LocalDate,
        productType: String?,
        productKind: String?,
    ): LoanOverview {
        val next = schedule.filter { !it.paid }.minByOrNull { it.number }
        val rate = next?.let { RateAdjustmentMath.rateOn(it.dueDate, loan.nominalAnnualRate, adj, floor()) }
            ?: loan.nominalAnnualRate
        val live = adj.firstOrNull { it.source == RateAdjustmentSource.REWARDS && it.isLiveOn(today) }
        return LoanOverview(
            loanId = loan.id.value,
            contractNumber = loan.contractNumber,
            productType = productType,
            productKind = productKind,
            status = loan.status,
            principal = loan.principal,
            outstanding = next?.openingBalance ?: Money.zero(loan.principal.currency.code),
            nominalRatePct = RateAdjustmentMath.pct(rate),
            contractRatePct = RateAdjustmentMath.pct(loan.nominalAnnualRate),
            nextInstalment = next?.let { NextInstalment(it.number, it.dueDate, it.payment) },
            daysPastDue = RateAdjustmentMath.daysPastDue(schedule, today),
            rateAdjustment = live?.let { ActiveRateAdjustment(it.adjustmentId, it.deltaBps, it.validFrom, it.validTo) },
        )
    }

    /** The terms a customer has right now: the next repriceable instalment, else the next unpaid one. */
    private fun currentTerms(
        loan: Loan,
        schedule: List<LoanInstallment>,
        existing: List<RateAdjustment>,
        today: LocalDate,
    ): LoanTermsView? {
        val tail = RateAdjustmentPolicy.repriceableTail(schedule, today)
        val next = tail.firstOrNull() ?: schedule.filter { !it.paid }.minByOrNull { it.number } ?: return null
        return LoanTermsView(
            nominalRatePct = RateAdjustmentMath.pct(
                RateAdjustmentMath.rateOn(next.dueDate, loan.nominalAnnualRate, existing, floor()),
            ),
            aprcPct = RateAdjustmentMath.aprcPct(tail, loan.periodsPerYear),
            instalment = next.payment,
        )
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun floor() = config.productFloorRate

    @Suppress("LongParameterList")
    private fun candidate(
        loan: Loan,
        deltaBps: Int,
        validFrom: LocalDate,
        validTo: LocalDate,
        sourceRef: String,
        approvalRef: String,
    ) = RateAdjustment(
        loanId = loan.id,
        deltaBps = deltaBps,
        validFrom = validFrom,
        validTo = validTo,
        source = RateAdjustmentSource.REWARDS,
        sourceRef = sourceRef,
        approvalRef = approvalRef,
        createdBy = "",
        createdAt = OffsetDateTime.now(clock),
    )

    private fun RateAdjustment.matches(loanId: LoanId, request: RateAdjustmentRequest): Boolean =
        this.loanId == loanId &&
            deltaBps == request.deltaBps &&
            validFrom == request.validFrom &&
            validTo == request.validTo &&
            source == request.source &&
            approvalRef == request.approvalRef

    private fun loanNotFound(loanId: LoanId) = RateAdjustmentNotFoundException("Loan not found: $loanId")

    /**
     * `loan.rate_adjusted` / `loan.rate_adjustment_cancelled`. Serialised from a map rather than
     * hand-concatenated, so a caller-supplied sourceRef/approvalRef can never break the JSON.
     */
    @Suppress("LongParameterList")
    private fun adjustedEvent(
        type: String,
        loan: Loan,
        adjustment: RateAdjustment,
        before: LoanTermsView?,
        after: LoanTermsView?,
        effectiveFrom: LocalDate?,
    ): LendingOutboxMessage {
        val payload = linkedMapOf<String, Any?>(
            "aggregateType" to "LOAN",
            "aggregateId" to loan.id.value.toString(),
            "loanId" to loan.id.value.toString(),
            "partyId" to loan.partyId.toString(),
            "adjustmentId" to adjustment.adjustmentId.toString(),
            "deltaBps" to adjustment.deltaBps,
            "validFrom" to adjustment.validFrom.toString(),
            "validTo" to adjustment.validTo.toString(),
            "source" to adjustment.source.name,
            "sourceRef" to adjustment.sourceRef,
            "approvalRef" to adjustment.approvalRef,
            "status" to adjustment.status.name,
            "beforeRatePct" to before?.nominalRatePct?.toPlainString(),
            "afterRatePct" to after?.nominalRatePct?.toPlainString(),
            "before" to before?.let(::termsJson),
            "after" to after?.let(::termsJson),
            "effectiveFrom" to effectiveFrom?.toString(),
            "occurredAt" to clock.instant().toString(),
            // See LendingService's loan.disbursed comment: the literal "lending" is this producer's settled value.
            "sourceService" to "lending",
        )
        return LendingOutboxMessage(
            aggregateId = loan.id.value,
            eventType = type,
            payload = json.writeValueAsString(payload),
        )
    }

    private fun termsJson(t: LoanTermsView) = linkedMapOf(
        "nominalRatePct" to t.nominalRatePct.toPlainString(),
        "aprcPct" to t.aprcPct?.toPlainString(),
        "instalment" to mapOf(
            "amount" to t.instalment.amount.toPlainString(),
            "currency" to t.instalment.currency.code,
        ),
    )

    companion object {
        const val EVENT_ADJUSTED = "loan.rate_adjusted"
        const val EVENT_CANCELLED = "loan.rate_adjustment_cancelled"
        const val MAX_MONTHS = 120
    }
}
