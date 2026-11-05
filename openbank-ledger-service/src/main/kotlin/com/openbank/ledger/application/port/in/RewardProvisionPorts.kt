// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// `in` is the hexagonal inbound-port package every service uses; ktlint's package-name rule rejects
// the keyword. Suppressed per file (the delegation-service precedent) rather than grown into the baseline.
@file:Suppress("ktlint:standard:package-name")

package com.openbank.ledger.application.port.`in`

import com.openbank.ledger.domain.model.RewardMarket
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Post the day's change of the FLUX provision for one market (ADR-0344 D2). [deltaAmount] is signed:
 * positive grows the provision (expense), negative releases it (breakage / expiry). [sourceRef] is
 * the caller's idempotency reference: the same reference never posts twice.
 */
data class PostRewardProvisionCommand(
    val postingDate: LocalDate,
    val market: RewardMarket,
    val currency: String,
    val deltaAmount: BigDecimal,
    val sourceRef: String,
)

enum class RewardProvisionStatus {
    /** This call posted the journal. */
    POSTED,

    /** The sourceRef had already been posted with the same content; nothing was posted. */
    REPLAYED,
}

data class RewardProvisionResult(
    val status: RewardProvisionStatus,
    val journalId: UUID,
    val entryNumber: Long?,
    val postingDate: LocalDate,
    val market: RewardMarket,
    val currency: String,
    val deltaAmount: BigDecimal,
    val sourceRef: String,
)

interface RewardProvisionUseCase {
    suspend fun post(command: PostRewardProvisionCommand): RewardProvisionResult
}
