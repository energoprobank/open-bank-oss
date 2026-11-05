// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.rest.dto

import com.openbank.interest.application.port.`in`.RateAdjustmentResult
import com.openbank.interest.domain.model.RateAdjustment
import com.openbank.interest.domain.model.RateAdjustmentStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Body of `POST /api/v1/interest/accounts/{accountId}/rate-adjustments`. Every field is nullable so
 * an omitted one is a 400 from the resource's guard, not an NPE/500 from Kotlin's null intrinsics.
 */
data class CreateRateAdjustmentRequest(
    val deltaBps: Int? = null,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val source: String? = null,
    val sourceRef: String? = null,
    val approvalRef: String? = null,
)

data class RateAdjustmentResponse(
    val adjustmentId: UUID,
    val accountId: UUID,
    val deltaBps: Int,
    val validFrom: LocalDate,
    val validTo: LocalDate,
    val status: RateAdjustmentStatus,
    val source: String,
    val sourceRef: String,
    val approvalRef: String,
    val createdBy: String,
    val createdAt: OffsetDateTime,
    val cancelledAt: OffsetDateTime?,
    /** The account's resulting annual rate in percent (4.5 = 4.5 % p.a.); null when no base rate resolves. */
    val effectiveRatePct: BigDecimal?,
)

private const val PCT_SCALE = 4

fun RateAdjustment.toResponse(effectiveAnnualRate: BigDecimal? = null) = RateAdjustmentResponse(
    adjustmentId = adjustmentId,
    accountId = accountId,
    deltaBps = deltaBps,
    validFrom = validFrom,
    validTo = validTo,
    status = status,
    source = source,
    sourceRef = sourceRef,
    approvalRef = approvalRef,
    createdBy = createdBy,
    createdAt = createdAt,
    cancelledAt = cancelledAt,
    effectiveRatePct = effectiveAnnualRate?.let(::toPercent),
)

fun RateAdjustmentResult.toResponse() = adjustment.toResponse(effectiveAnnualRate)

/** 0.045 -> 4.5; never exponent notation (stripTrailingZeros alone turns 10 into 1E+1). */
internal fun toPercent(annualRate: BigDecimal): BigDecimal {
    val pct = annualRate.movePointRight(2).setScale(PCT_SCALE, RoundingMode.HALF_UP).stripTrailingZeros()
    return if (pct.scale() < 0) pct.setScale(0) else pct
}
