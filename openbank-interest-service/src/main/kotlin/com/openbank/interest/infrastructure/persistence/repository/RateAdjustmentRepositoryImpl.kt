// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.persistence.repository

import com.openbank.interest.application.port.out.LatestAccrualLookup
import com.openbank.interest.application.port.out.RateAdjustmentRepository
import com.openbank.interest.domain.model.InterestAccrual
import com.openbank.interest.domain.model.RateAdjustment
import com.openbank.interest.domain.model.RateAdjustmentStatus
import com.openbank.interest.infrastructure.persistence.entity.InterestAccrualEntity
import com.openbank.interest.infrastructure.persistence.entity.RateAdjustmentEntity
import com.openbank.interest.infrastructure.persistence.mapper.InterestMapper
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class RateAdjustmentRepositoryImpl @Inject constructor(private val sf: Mutiny.SessionFactory) :
    RateAdjustmentRepository {

    @WithTransaction override fun save(adjustment: RateAdjustment): Uni<RateAdjustment> {
        val e = adjustment.toEntity()
        return sf.withTransaction { s -> s.persist(e).map { e.toDomain() } }
    }

    @WithTransaction override fun update(adjustment: RateAdjustment): Uni<RateAdjustment> = sf.withTransaction { s ->
        s.find(RateAdjustmentEntity::class.java, adjustment.adjustmentId).flatMap { e ->
            e!!.status = adjustment.status
            e.cancelledBy = adjustment.cancelledBy
            e.cancelledAt = adjustment.cancelledAt
            s.persist(e).map { e.toDomain() }
        }
    }

    @WithSession override fun findById(adjustmentId: UUID): Uni<RateAdjustment?> =
        sf.withSession { s -> s.find(RateAdjustmentEntity::class.java, adjustmentId) }.map { it?.toDomain() }

    @WithSession override fun findBySourceRef(sourceRef: String): Uni<RateAdjustment?> = sf.withSession { s ->
        s.createQuery("FROM RateAdjustmentEntity WHERE sourceRef = :r", RateAdjustmentEntity::class.java)
            .setParameter("r", sourceRef).setMaxResults(1).singleResultOrNull
    }.map { it?.toDomain() }

    @WithSession override fun findByAccountId(accountId: UUID): Uni<List<RateAdjustment>> = sf.withSession { s ->
        s.createQuery(
            "FROM RateAdjustmentEntity WHERE accountId = :a ORDER BY createdAt DESC",
            RateAdjustmentEntity::class.java,
        ).setParameter("a", accountId).resultList
    }.map { it.map(RateAdjustmentEntity::toDomain) }

    @WithSession
    override fun findActiveCovering(accountId: UUID, date: LocalDate): Uni<List<RateAdjustment>> = sf.withSession { s ->
        // Half-open window: valid_from inclusive, valid_to exclusive.
        s.createQuery(
            "FROM RateAdjustmentEntity WHERE accountId = :a AND status = :st " +
                "AND validFrom <= :d AND validTo > :d ORDER BY createdAt",
            RateAdjustmentEntity::class.java,
        ).setParameter("a", accountId).setParameter("st", RateAdjustmentStatus.ACTIVE).setParameter("d", date)
            .resultList
    }.map { it.map(RateAdjustmentEntity::toDomain) }
}

@ApplicationScoped
class LatestAccrualLookupImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: InterestMapper,
) : LatestAccrualLookup {
    @WithSession override fun latestFor(accountId: UUID): Uni<InterestAccrual?> = sf.withSession { s ->
        s.createQuery(
            "FROM InterestAccrualEntity WHERE accountId = :a ORDER BY accrualDate DESC, createdAt DESC",
            InterestAccrualEntity::class.java,
        ).setParameter("a", accountId).setMaxResults(1).singleResultOrNull
    }.map { it?.let(mapper::toDomain) }
}

private fun RateAdjustment.toEntity() = RateAdjustmentEntity().also {
    it.adjustmentId = adjustmentId
    it.accountId = accountId
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
}

private fun RateAdjustmentEntity.toDomain() = RateAdjustment(
    adjustmentId = adjustmentId,
    accountId = accountId,
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
)
