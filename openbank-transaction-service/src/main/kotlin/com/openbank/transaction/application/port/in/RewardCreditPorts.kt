// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// `in` is the hexagonal inbound-port package every service uses; ktlint's package-name rule rejects
// the keyword. Suppressed per file (the delegation-service precedent) rather than grown into the baseline.
@file:Suppress("ktlint:standard:package-name")

package com.openbank.transaction.application.port.`in`

import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.reward.RewardReason
import java.math.BigDecimal
import java.util.UUID

/**
 * Pay a FLUX reward in cash to the customer's own current account (ADR-0344 D3). [sourceRef] is the
 * rewards engine's reference for this payout (a coupon use, a referral release): the same reference
 * never pays twice.
 */
data class RewardCreditCommand(
    val partyId: UUID,
    val accountId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val reason: RewardReason,
    val sourceRef: String,
)

/**
 * [creditId] is stable for a sourceRef (a name-based UUID of it), so a retry answers the same id;
 * [replayed] says the sourceRef had already been paid and nothing new was booked.
 */
data class RewardCreditResult(
    val creditId: UUID,
    val transactionId: UUID,
    val status: TransactionStatus,
    val replayed: Boolean,
)

interface RewardCreditUseCase {
    suspend fun credit(command: RewardCreditCommand): RewardCreditResult
}
