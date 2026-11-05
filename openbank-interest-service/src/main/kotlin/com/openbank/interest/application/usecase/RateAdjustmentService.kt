// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.usecase

import com.openbank.interest.application.port.`in`.ManageRateAdjustmentUseCase
import com.openbank.interest.application.port.`in`.RateAdjustmentResult
import com.openbank.interest.application.port.out.InterestRateConfigRepository
import com.openbank.interest.application.port.out.LatestAccrualLookup
import com.openbank.interest.application.port.out.RateAdjustmentRepository
import com.openbank.interest.domain.model.RateAdjustment
import com.openbank.interest.domain.model.RateAdjustmentNotFoundException
import com.openbank.interest.domain.model.RateAdjustmentPolicy
import com.openbank.interest.domain.model.RateAdjustmentSourceRefConflictException
import com.openbank.interest.domain.model.RateAdjustmentStatus
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Additive rate adjustments (FLUX rewards "Deposit Rate Boost"). The accrual itself applies them in
 * [InterestService]; this use case only records, lists and cancels them, and reports the account's
 * resulting rate so the caller can show the customer the outcome.
 */
@ApplicationScoped
class RateAdjustmentService(
    private val adjustmentRepo: RateAdjustmentRepository,
    private val configRepo: InterestRateConfigRepository,
    private val latestAccrual: LatestAccrualLookup,
    private val clock: Clock,
) : ManageRateAdjustmentUseCase {

    override fun create(adjustment: RateAdjustment): Uni<RateAdjustmentResult> {
        try {
            RateAdjustmentPolicy.validate(adjustment)
        } catch (e: IllegalArgumentException) {
            return Uni.createFrom().failure(e)
        }
        // Check-first on sourceRef (the caller's idempotency key); the unique index is the race
        // backstop, and a lost race resolves to the winner through the same replay comparison.
        return adjustmentRepo.findBySourceRef(adjustment.sourceRef)
            .flatMap { existing ->
                if (existing != null) {
                    replay(existing, adjustment)
                } else {
                    adjustmentRepo.save(adjustment).map { it to true }.onFailure().recoverWithUni { e ->
                        if (!e.isUniqueViolationOf(SOURCE_REF_INDEX)) {
                            Uni.createFrom().failure(e)
                        } else {
                            adjustmentRepo.findBySourceRef(adjustment.sourceRef).flatMap { winner ->
                                if (winner != null) replay(winner, adjustment) else Uni.createFrom().failure(e)
                            }
                        }
                    }
                }
            }
            .flatMap { (saved, created) -> withEffectiveRate(saved, created) }
    }

    override fun list(accountId: UUID): Uni<List<RateAdjustment>> = adjustmentRepo.findByAccountId(accountId)

    override fun cancel(accountId: UUID, adjustmentId: UUID, cancelledBy: String): Uni<RateAdjustmentResult> =
        adjustmentRepo.findById(adjustmentId).flatMap { existing ->
            when {
                existing == null || existing.accountId != accountId ->
                    Uni.createFrom().failure(RateAdjustmentNotFoundException(accountId, adjustmentId))
                existing.status == RateAdjustmentStatus.CANCELLED -> withEffectiveRate(existing, false)
                else -> adjustmentRepo.update(
                    existing.copy(
                        status = RateAdjustmentStatus.CANCELLED,
                        cancelledBy = cancelledBy,
                        cancelledAt = OffsetDateTime.now(clock),
                    ),
                ).flatMap { withEffectiveRate(it, true) }
            }
        }

    private fun replay(existing: RateAdjustment, requested: RateAdjustment): Uni<Pair<RateAdjustment, Boolean>> =
        if (existing.sameTermsAs(requested)) {
            Uni.createFrom().item(existing to false)
        } else {
            Uni.createFrom().failure(RateAdjustmentSourceRefConflictException(requested.sourceRef))
        }

    private fun withEffectiveRate(adjustment: RateAdjustment, changed: Boolean): Uni<RateAdjustmentResult> {
        val today = LocalDate.now(clock)
        // Today when the window is running (or the adjustment is cancelled — "what is the rate now"),
        // otherwise the first day of the window, so a future-dated boost shows the rate it will give.
        val asOf = if (adjustment.status == RateAdjustmentStatus.CANCELLED ||
            (!today.isBefore(adjustment.validFrom) && today.isBefore(adjustment.validTo))
        ) {
            today
        } else {
            adjustment.validFrom
        }
        return effectiveRate(adjustment.accountId, asOf)
            .map { RateAdjustmentResult(adjustment, changed, it) }
    }

    /**
     * The account's base rate (override, else its product default) plus its ACTIVE adjustments on
     * [date]. The product is learned from the latest accrual; an account that has never accrued and
     * has no override resolves to null rather than a guessed product.
     */
    private fun effectiveRate(accountId: UUID, date: LocalDate): Uni<BigDecimal?> = latestAccrual.latestFor(accountId)
        .flatMap { latest ->
            configRepo.findEffectiveRate(accountId, latest?.productId ?: NO_PRODUCT, date, latest?.currency)
        }
        .flatMap { config ->
            if (config == null) {
                Uni.createFrom().nullItem()
            } else {
                adjustmentRepo.findActiveCovering(accountId, date).map { adjustments ->
                    RateAdjustmentPolicy.effectiveAnnualRate(config.annualRate, adjustments, date)
                }
            }
        }

    private companion object {
        /** Matches no product-default row, so only an account override can resolve. */
        const val NO_PRODUCT = ""
        const val SOURCE_REF_INDEX = "ux_rate_adjustment_source_ref"
        const val SQLSTATE_UNIQUE_VIOLATION = "23505"

        /** Same chain walk as `InterestService.isUniqueViolationOf` (constraint name required). */
        fun Throwable.isUniqueViolationOf(constraintName: String): Boolean = generateSequence(this) { it.cause }
            .filterIsInstance<SQLException>()
            .any {
                it.message?.contains(constraintName) == true &&
                    (it.sqlState == SQLSTATE_UNIQUE_VIOLATION || it.message.orEmpty().contains("(23505)"))
            }
    }
}
