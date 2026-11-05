// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.`in`.JournalLineRequest
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.application.port.`in`.PostRewardProvisionCommand
import com.openbank.ledger.application.port.`in`.RewardProvisionResult
import com.openbank.ledger.application.port.`in`.RewardProvisionStatus
import com.openbank.ledger.application.port.`in`.RewardProvisionUseCase
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.RewardProvisionLeg
import com.openbank.ledger.domain.model.RewardProvisionPosting
import com.openbank.ledger.domain.model.checkConflict
import com.openbank.ledger.domain.model.requireValid
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * The FLUX provision posting (ADR-0344 D2): a fixed two-leg template the rewards engine drives
 * daily, so the engine never names a GL account and cannot post anything but this pair.
 *
 * Posted through [LedgerUseCase.postJournalWithOutcome], so it inherits every control an ordinary
 * journal has — double entry, the accounting-day / period / attested-year locks, the account
 * enabled/leaf/currency checks, the transactional outbox. Idempotency is the ledger's own key
 * (`flux-provision:<sourceRef>`); on top of it this service refuses a replay whose CONTENT differs
 * (409): the ledger's replay returns the original entry whatever the second body said, and for a
 * daily liability a silently ignored correction is a wrong balance nobody notices.
 */
@ApplicationScoped
class RewardProvisionService(private val ledger: LedgerUseCase) : RewardProvisionUseCase {

    override suspend fun post(command: PostRewardProvisionCommand): RewardProvisionResult {
        val sourceRef = command.sourceRef.trim()
        requireValid(sourceRef.isNotEmpty()) { "sourceRef must not be blank" }
        requireValid(sourceRef.length <= MAX_SOURCE_REF) { "sourceRef must be at most $MAX_SOURCE_REF characters" }

        val legs = RewardProvisionPosting.legs(command.market, command.currency, command.deltaAmount)
        val key = idempotencyKey(sourceRef)
        val outcome = ledger.postJournalWithOutcome(
            PostJournalCommand(
                idempotencyKey = key,
                transactionId = UUID.nameUUIDFromBytes(key.toByteArray()),
                entryDate = command.postingDate,
                valueDate = command.postingDate,
                description = "FLUX provision ${command.market.name} ${command.postingDate} ($sourceRef)",
                lines = legs.map { it.toRequest(command.currency) },
                postedBy = REWARDS_POSTER,
            ),
        )
        if (outcome.replayed) {
            checkConflict(sameContent(outcome.entry, command, legs)) {
                "sourceRef $sourceRef was already posted with different content (journal ${outcome.entry.id})"
            }
        }
        return RewardProvisionResult(
            status = if (outcome.replayed) RewardProvisionStatus.REPLAYED else RewardProvisionStatus.POSTED,
            journalId = outcome.entry.id,
            entryNumber = outcome.entry.entryNumber,
            postingDate = outcome.entry.entryDate,
            market = command.market,
            currency = command.currency,
            deltaAmount = command.deltaAmount,
            sourceRef = sourceRef,
        )
    }

    private fun sameContent(entry: JournalEntry, command: PostRewardProvisionCommand, legs: List<RewardProvisionLeg>) =
        entry.entryDate == command.postingDate &&
            entry.lines.map { Triple(it.glAccountId, it.side, it.amount.amount.setScale(SCALE)) }.toSet() ==
            legs.map { Triple(it.glAccountId, it.side, it.amount.setScale(SCALE)) }.toSet()

    private fun RewardProvisionLeg.toRequest(currency: String) = JournalLineRequest(
        glAccountId = glAccountId,
        side = side,
        amount = amount,
        currencyCode = currency,
        fxRate = null,
        baseAmount = amount,
        baseCurrencyCode = currency,
    )

    companion object {
        const val KEY_PREFIX = "flux-provision:"

        /** ledger_idempotency.idempotency_key is VARCHAR(255); the prefix takes the rest. */
        const val MAX_SOURCE_REF = 200

        private const val SCALE = RewardProvisionPosting.MONEY_SCALE

        /** Recorded as created_by: the rewards engine, the only principal OPA admits to this action. */
        val REWARDS_POSTER: UUID = UUID.nameUUIDFromBytes("service-account-openbank-rewards".toByteArray())

        fun idempotencyKey(sourceRef: String) = KEY_PREFIX + sourceRef
    }
}
