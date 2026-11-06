// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.application

import com.openbank.everyday.domain.AccountRef
import com.openbank.everyday.domain.Txn
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** account-service: the customer's own accounts. */
interface AccountsPort {
    fun accounts(partyId: UUID): List<AccountRef>
}

/** transaction-service: an account's booked payments since a date, and booking a credit. */
interface TransactionsPort {
    /** Newest first, back to [since] (inclusive). [own] are the customer's account ids (own transfers). */
    fun payments(account: AccountRef, since: LocalDate, own: Set<UUID>): List<Txn>

    /** Books a credit to [accountId]; the same [idempotencyKey] books it once. Returns the transaction id. */
    fun credit(
        accountId: UUID,
        amount: BigDecimal,
        currency: String,
        description: String,
        idempotencyKey: String,
        party: UUID,
    ): UUID
}

/** The card acquirer that runs the top-up checkout (locally: the provider simulator). */
interface AcquirerPort {
    fun start(amount: BigDecimal, currency: String, reference: String): AcquirerSession

    fun status(acquirerRef: String): String
}

data class AcquirerSession(val ref: String, val checkoutUrl: String, val status: String)

// ---------------------------------------------------------------- stores

data class Mandate(
    val id: UUID,
    val partyId: UUID,
    val scheme: String,
    val creditorName: String,
    val creditorReference: String,
    val debtorAccountId: UUID,
    val limit: BigDecimal?,
    val currency: String,
    val frequency: String,
    val status: String,
    val createdAt: Instant,
)

data class SubscriptionChoice(val cancelRequested: Boolean, val reminderEnabled: Boolean)

data class PaymentExport(
    val id: UUID,
    val partyId: UUID,
    val accountId: UUID,
    val format: String,
    val direction: String,
    val from: LocalDate,
    val to: LocalDate,
    val status: String,
    val fileName: String?,
    val contentType: String?,
    val content: ByteArray?,
    val token: String?,
    val expiresAt: Instant,
)

data class RecallCase(
    val id: UUID,
    val partyId: UUID,
    val transactionId: UUID,
    val reference: String,
    val reason: String,
    val note: String?,
    val status: String,
    val answerBy: LocalDate,
)

data class TopUp(
    val id: UUID,
    val partyId: UUID,
    val accountId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val fee: BigDecimal,
    val status: String,
    val acquirerRef: String,
    val checkoutUrl: String,
    val creditedTransactionId: UUID?,
)

data class Protection(
    val holdUnusual: Boolean,
    val streetMode: Boolean,
    val streetModeLimit: BigDecimal,
    val trustedApproverName: String?,
    val trustedApproverPhone: String?,
)

interface MoneyStore {
    fun mandates(partyId: UUID): List<Mandate>

    fun insertMandate(m: Mandate)

    fun cancelMandate(partyId: UUID, id: UUID, at: Instant): Mandate?

    fun subscriptionChoices(partyId: UUID): Map<String, SubscriptionChoice>

    fun saveSubscriptionChoice(partyId: UUID, subscriptionId: String, choice: SubscriptionChoice, at: Instant)

    fun insertExport(e: PaymentExport, at: Instant)

    fun export(partyId: UUID, id: UUID): PaymentExport?

    fun exportByToken(token: String): PaymentExport?
}

interface CaseStore {
    fun recallFor(transactionId: UUID): RecallCase?

    fun insertRecall(c: RecallCase, at: Instant)

    fun topUp(partyId: UUID, id: UUID): TopUp?

    fun insertTopUp(t: TopUp, at: Instant)

    fun updateTopUp(t: TopUp, at: Instant)

    fun protection(partyId: UUID): Protection?

    fun saveProtection(partyId: UUID, p: Protection, at: Instant)
}
