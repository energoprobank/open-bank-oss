// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.entity

import com.openbank.lending.domain.model.RateAdjustmentSource
import com.openbank.lending.domain.model.RateAdjustmentStatus
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** One additive loan rate adjustment (V28). Every column named explicitly. */
@Entity
@Table(name = "loan_rate_adjustment")
class RateAdjustmentEntity : PanacheEntityBase() {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    var id: UUID = Ids.newId()

    @Column(name = "loan_id", columnDefinition = "uuid")
    var loanId: UUID = UUID(0, 0)

    @Column(name = "delta_bps")
    var deltaBps: Int = 0

    @Column(name = "valid_from")
    var validFrom: LocalDate = LocalDate.EPOCH

    @Column(name = "valid_to")
    var validTo: LocalDate = LocalDate.EPOCH

    @Column(name = "source", length = 16)
    @Enumerated(EnumType.STRING)
    var source: RateAdjustmentSource = RateAdjustmentSource.REWARDS

    @Column(name = "source_ref", length = 128)
    var sourceRef: String = ""

    @Column(name = "approval_ref", length = 128)
    var approvalRef: String = ""

    @Column(name = "status", length = 16)
    @Enumerated(EnumType.STRING)
    var status: RateAdjustmentStatus = RateAdjustmentStatus.ACTIVE

    @Column(name = "created_by", length = 128)
    var createdBy: String = ""

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN

    @Column(name = "cancelled_by", length = 128)
    var cancelledBy: String? = null

    @Column(name = "cancelled_at")
    var cancelledAt: OffsetDateTime? = null

    @Column(name = "currency", length = 3, columnDefinition = "char(3)")
    var currency: String? = null

    @Column(name = "before_rate_pct", precision = 10, scale = 4)
    var beforeRatePct: BigDecimal? = null

    @Column(name = "before_aprc_pct", precision = 10, scale = 2)
    var beforeAprcPct: BigDecimal? = null

    @Column(name = "before_instalment", precision = 20, scale = 2)
    var beforeInstalment: BigDecimal? = null

    @Column(name = "after_rate_pct", precision = 10, scale = 4)
    var afterRatePct: BigDecimal? = null

    @Column(name = "after_aprc_pct", precision = 10, scale = 2)
    var afterAprcPct: BigDecimal? = null

    @Column(name = "after_instalment", precision = 20, scale = 2)
    var afterInstalment: BigDecimal? = null

    @Column(name = "effective_from")
    var effectiveFrom: LocalDate? = null
}
