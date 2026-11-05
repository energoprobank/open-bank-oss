// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.persistence.entity

import com.openbank.interest.domain.model.RateAdjustmentStatus
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** One additive rate adjustment (V18). */
@Entity
@Table(name = "interest_rate_adjustments")
class RateAdjustmentEntity : PanacheEntityBase() {
    @Id
    @Column(name = "adjustment_id", columnDefinition = "uuid")
    var adjustmentId: UUID = Ids.newId()

    @Column(name = "account_id", columnDefinition = "uuid")
    var accountId: UUID = Ids.newId()

    @Column(name = "delta_bps")
    var deltaBps: Int = 0

    @Column(name = "valid_from")
    var validFrom: LocalDate = LocalDate.EPOCH

    @Column(name = "valid_to")
    var validTo: LocalDate = LocalDate.EPOCH

    @Column(name = "source", length = 32)
    var source: String = ""

    @Column(name = "source_ref", length = 128)
    var sourceRef: String = ""

    @Column(name = "approval_ref", length = 128)
    var approvalRef: String = ""

    @Column(name = "status", length = 16)
    @Enumerated(EnumType.STRING)
    var status: RateAdjustmentStatus = RateAdjustmentStatus.ACTIVE

    @Column(name = "created_by")
    var createdBy: String = ""

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN

    @Column(name = "cancelled_by")
    var cancelledBy: String? = null

    @Column(name = "cancelled_at")
    var cancelledAt: OffsetDateTime? = null
}
