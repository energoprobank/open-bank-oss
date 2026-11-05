// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.lending.domain.model.RateAdjustment
import com.openbank.libs.domain.identifiers.LoanId
import io.smallrye.mutiny.Uni
import java.util.UUID

/** Additive loan rate adjustments (V28 `loan_rate_adjustment`). */
interface RateAdjustmentRepository {
    fun findByLoan(loanId: LoanId): Uni<List<RateAdjustment>>
    fun findBySourceRef(sourceRef: String): Uni<RateAdjustment?>
    fun findById(adjustmentId: UUID): Uni<RateAdjustment?>

    /** INSERT; the UNIQUE(source_ref) constraint is the last line of the idempotency guard. */
    fun insert(adjustment: RateAdjustment): Uni<RateAdjustment>

    /** ACTIVE → CANCELLED, conditional on the row still being ACTIVE; returns rows changed. */
    fun markCancelled(adjustmentId: UUID, cancelledBy: String, at: java.time.OffsetDateTime): Uni<Int>
}
