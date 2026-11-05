// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.repository

import com.openbank.lending.application.port.out.RateAdjustmentRepository
import com.openbank.lending.domain.model.LoanTermsView
import com.openbank.lending.domain.model.RateAdjustment
import com.openbank.lending.domain.model.RateAdjustmentStatus
import com.openbank.lending.infrastructure.persistence.entity.RateAdjustmentEntity
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Same `Mutiny.SessionFactory` idiom as `LoanRepositoryImpl`, so every call made inside
 * `LoanRepository.withLocked` joins the loan-locking transaction: the adjustment row, the repriced
 * instalments and the outbox event commit together or not at all.
 */
@ApplicationScoped
class RateAdjustmentRepositoryImpl @Inject constructor(private val sf: Mutiny.SessionFactory) :
    RateAdjustmentRepository {

    @WithSession
    override fun findByLoan(loanId: LoanId): Uni<List<RateAdjustment>> = sf.withSession { s ->
        s.createQuery(
            "FROM RateAdjustmentEntity WHERE loanId = :l ORDER BY createdAt ASC, id ASC",
            RateAdjustmentEntity::class.java,
        ).setParameter("l", loanId.value).resultList
    }.map { rows -> rows.map { it.toDomain() } }

    @WithSession
    override fun findBySourceRef(sourceRef: String): Uni<RateAdjustment?> = sf.withSession { s ->
        s.createQuery("FROM RateAdjustmentEntity WHERE sourceRef = :r", RateAdjustmentEntity::class.java)
            .setParameter("r", sourceRef)
            .singleResultOrNull
    }.map { it?.toDomain() }

    @WithSession
    override fun findById(adjustmentId: UUID): Uni<RateAdjustment?> =
        sf.withSession { s -> s.find(RateAdjustmentEntity::class.java, adjustmentId) }.map { it?.toDomain() }

    @WithTransaction
    override fun insert(adjustment: RateAdjustment): Uni<RateAdjustment> {
        val e = adjustment.toEntity()
        return sf.withTransaction { s -> s.persist(e).map { e.toDomain() } }
    }

    @WithTransaction
    override fun markCancelled(adjustmentId: UUID, cancelledBy: String, at: OffsetDateTime): Uni<Int> =
        sf.withTransaction { s, _ ->
            s.createMutationQuery(
                "UPDATE RateAdjustmentEntity SET status = :cancelled, cancelledBy = :by, cancelledAt = :at " +
                    "WHERE id = :id AND status = :active",
            )
                .setParameter("cancelled", RateAdjustmentStatus.CANCELLED)
                .setParameter("active", RateAdjustmentStatus.ACTIVE)
                .setParameter("by", cancelledBy)
                .setParameter("at", at)
                .setParameter("id", adjustmentId)
                .executeUpdate()
        }

    private fun RateAdjustment.toEntity() = RateAdjustmentEntity().also {
        it.id = adjustmentId
        it.loanId = loanId.value
        it.deltaBps = deltaBps
        it.validFrom = validFrom
        it.validTo = validTo
        it.source = source
        it.sourceRef = sourceRef
        it.approvalRef = approvalRef
        it.status = status
        it.createdBy = createdBy
        it.createdAt = createdAt
        it.cancelledBy = cancelledBy
        it.cancelledAt = cancelledAt
        it.currency = (before ?: after)?.instalment?.currency?.code
        it.beforeRatePct = before?.nominalRatePct
        it.beforeAprcPct = before?.aprcPct
        it.beforeInstalment = before?.instalment?.amount
        it.afterRatePct = after?.nominalRatePct
        it.afterAprcPct = after?.aprcPct
        it.afterInstalment = after?.instalment?.amount
        it.effectiveFrom = effectiveFrom
    }

    private fun RateAdjustmentEntity.toDomain() = RateAdjustment(
        adjustmentId = id,
        loanId = LoanId(loanId),
        deltaBps = deltaBps,
        validFrom = validFrom,
        validTo = validTo,
        source = source,
        sourceRef = sourceRef,
        approvalRef = approvalRef,
        status = status,
        createdBy = createdBy,
        createdAt = createdAt,
        cancelledBy = cancelledBy,
        cancelledAt = cancelledAt,
        before = terms(beforeRatePct, beforeAprcPct, beforeInstalment),
        after = terms(afterRatePct, afterAprcPct, afterInstalment),
        effectiveFrom = effectiveFrom,
    )

    private fun RateAdjustmentEntity.terms(
        rate: java.math.BigDecimal?,
        aprc: java.math.BigDecimal?,
        instalment: java.math.BigDecimal?,
    ): LoanTermsView? {
        val code = currency ?: return null
        if (rate == null || instalment == null) return null
        return LoanTermsView(
            rate.stripTrailingZeros().let {
                if (it.scale() <
                    0
                ) {
                    it.setScale(0)
                } else {
                    it
                }
            },
            aprc,
            Money.of(instalment, code),
        )
    }
}
