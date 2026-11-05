// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.usecase

import com.openbank.transaction.application.port.`in`.InitiateTransactionCommand
import com.openbank.transaction.application.port.`in`.RewardCreditCommand
import com.openbank.transaction.application.port.`in`.RewardCreditResult
import com.openbank.transaction.application.port.`in`.RewardCreditUseCase
import com.openbank.transaction.application.port.`in`.TransactionUseCase
import com.openbank.transaction.application.port.out.AccountLookupPort
import com.openbank.transaction.application.port.out.TransactionRepository
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.domain.reward.RewardCreditPolicy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * FLUX reward cash credit (ADR-0344 D3): the narrowest real path from the rewards engine to a
 * customer's balance. It is the welcome bonus's path (an incoming `CREDIT` through the ordinary
 * payment saga, booked in the ledger, visible in the customer's history) with three differences:
 *
 *  1. **Funding.** The transaction carries a [com.openbank.transaction.domain.reward.RewardReason],
 *     so [PaymentJournalFactory] debits the FLUX provision (2400/2401), not cash clearing.
 *  2. **Target checks.** account-service must confirm the account belongs to `partyId`, is an
 *     ACTIVE CURRENT account and is denominated in the credit's currency. The engine is trusted to
 *     call, not to name the right account.
 *  3. **Ceiling.** [RewardCreditPolicy] caps every call per currency (configurable).
 *
 * Idempotent on `sourceRef` through the transaction's own global key registry (V22) as
 * `reward-credit:<sourceRef>`. A replay with the same content answers the original transaction; a
 * replay that names a different account, amount, currency or reason is a 409 — the saga's own
 * idempotency would otherwise return the first transaction and silently drop the second request.
 * A replay is answered BEFORE the account is re-checked, so a credit already paid stays answerable
 * after the account is later closed.
 */
@ApplicationScoped
class RewardCreditService(
    private val transactions: TransactionUseCase,
    private val transactionRepository: TransactionRepository,
    private val accounts: AccountLookupPort,
    private val policy: RewardCreditPolicy,
    private val clock: Clock,
) : RewardCreditUseCase {

    @Inject
    constructor(
        transactions: TransactionUseCase,
        transactionRepository: TransactionRepository,
        accounts: AccountLookupPort,
        policy: RewardCreditPolicy,
    ) : this(transactions, transactionRepository, accounts, policy, Clock.systemUTC())

    override suspend fun credit(command: RewardCreditCommand): RewardCreditResult {
        val sourceRef = command.sourceRef.trim()
        require(sourceRef.isNotEmpty()) { "sourceRef must not be blank" }
        require(sourceRef.length <= MAX_SOURCE_REF) { "sourceRef must be at most $MAX_SOURCE_REF characters" }
        val key = idempotencyKey(sourceRef)

        transactionRepository.findByIdempotencyKey(key)?.let { existing ->
            return answer(existing, command, key, replayed = true)
        }

        val amount = policy.checkAmount(command.amount, command.currency)
        policy.checkTarget(accounts.findAccount(command.accountId), command.partyId, command.currency)

        val transaction = transactions.initiateTransaction(
            InitiateTransactionCommand(
                idempotencyKey = key,
                type = TransactionType.CREDIT,
                sourceAccountId = null,
                targetAccountId = command.accountId,
                amount = amount,
                currencyCode = command.currency,
                description = command.reason.description,
                valueDate = LocalDate.now(clock.withZone(BANK_ZONE)),
                initiatedBy = REWARDS_INITIATOR,
                // Bank-initiated: no customer expressed a will to move money, so no SCA (ADR-0021).
                initiatedByPartyId = null,
                rewardReason = command.reason,
            ),
        )
        // A concurrent duplicate that won the key race comes back here as the winner's transaction.
        return answer(transaction, command, key, replayed = false)
    }

    private fun answer(
        tx: Transaction,
        command: RewardCreditCommand,
        key: String,
        replayed: Boolean,
    ): RewardCreditResult {
        val sameContent = tx.rewardReason == command.reason &&
            tx.targetAccountId == command.accountId &&
            tx.amount.currency.code == command.currency &&
            tx.amount.amount.compareTo(command.amount) == 0
        if (!sameContent) {
            throw RewardCreditConflictException(
                "sourceRef ${key.removePrefix(KEY_PREFIX)} was already used for a different reward credit (${tx.id})",
            )
        }
        return RewardCreditResult(
            creditId = creditId(key),
            transactionId = tx.id,
            status = tx.status,
            replayed = replayed,
        )
    }

    companion object {
        const val KEY_PREFIX = "reward-credit:"

        /** transactions.idempotency_key is VARCHAR(100); the prefix takes 14 of it. */
        const val MAX_SOURCE_REF = 80

        private val BANK_ZONE: ZoneId = ZoneId.of("Europe/Prague")

        /** Recorded as the initiator of every reward credit: the rewards engine's machine identity. */
        val REWARDS_INITIATOR: UUID = UUID.nameUUIDFromBytes("service-account-openbank-rewards".toByteArray())

        fun idempotencyKey(sourceRef: String) = KEY_PREFIX + sourceRef

        fun creditId(key: String): UUID = UUID.nameUUIDFromBytes(key.toByteArray())
    }
}

/** 409: the sourceRef was already paid with different content. */
class RewardCreditConflictException(message: String) : RuntimeException(message)
