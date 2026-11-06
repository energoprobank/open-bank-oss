// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.application

import com.openbank.everyday.domain.Txn
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

// ---------------------------------------------------------------- recall

/**
 * A request to get an outgoing transfer back: wrong account, wrong amount, a duplicate, or fraud.
 * The bank sends it to the recipient's bank (camt.056); that bank answers within its deadline. A card
 * payment is not recalled — it is disputed (dispute-service).
 */
@ApplicationScoped
class RecallService(private val history: PaymentHistory, private val store: CaseStore, private val clock: Clock) {
    fun recall(partyId: UUID, transactionId: UUID, reason: String, note: String?): RecallCase {
        require(reason in REASONS) { "reason must be one of ${REASONS.joinToString()}" }
        require((note?.length ?: 0) <= MAX_NOTE) { "note is too long" }
        val today = history.today()
        val payment = history.payments(partyId, today.minusDays(MAX_AGE_DAYS)).firstOrNull { it.id == transactionId }
            ?: throw ResourceNotFoundException("No such payment of yours", "PAYMENT_NOT_FOUND")
        refusal(payment, transactionId)?.let { (message, code) -> throw ResourceConflictException(message, code) }
        val case = RecallCase(
            UUID.randomUUID(),
            partyId,
            transactionId,
            "RC" + transactionId.toString().replace("-", "").take(REF_LENGTH).uppercase(),
            reason,
            note?.trim()?.takeIf {
                it.isNotEmpty()
            },
            "SENT_TO_BANK",
            workingDaysAfter(today, ANSWER_WORKING_DAYS),
        )
        store.insertRecall(case, clock.instant())
        return case
    }

    /** Why this payment cannot be recalled (message, code), or null when it can. */
    private fun refusal(payment: Txn, transactionId: UUID): Pair<String, String>? = when {
        !payment.outgoing || payment.ownCounterparty ->
            "Only a transfer you sent to someone else can be recalled" to
                "NOT_RECALLABLE"
        payment.rail == "CARD" -> "A card payment is disputed, not recalled" to "USE_DISPUTE"
        store.recallFor(transactionId) != null -> "This payment is already being recalled" to "RECALL_EXISTS"
        else -> null
    }

    private fun workingDaysAfter(day: LocalDate, n: Int): LocalDate {
        var d = day
        var left = n
        while (left > 0) {
            d = d.plusDays(1)
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) left--
        }
        return d
    }

    private companion object {
        val REASONS = setOf("WRONG_ACCOUNT", "WRONG_AMOUNT", "DUPLICATE", "FRAUD")
        const val MAX_NOTE = 500
        const val MAX_AGE_DAYS = 395L
        const val REF_LENGTH = 10
        const val ANSWER_WORKING_DAYS = 15
    }
}

// ---------------------------------------------------------------- top-up

/**
 * Money added from a card at another bank. The card is entered on the acquirer's secure page (3-D
 * Secure included), never in the app; once the acquirer authorises, the bank books the credit once.
 */
@ApplicationScoped
class TopUpService(
    private val history: PaymentHistory,
    private val store: CaseStore,
    private val acquirer: AcquirerPort,
    private val transactions: TransactionsPort,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(TopUpService::class.java)

    fun create(partyId: UUID, accountId: UUID, amount: BigDecimal, currency: String): TopUp {
        val account = history.own(partyId, accountId)
        require(currency == account.currency) { "currency must be the account's (${account.currency})" }
        val (min, max) = LIMITS.getValue(currency)
        require(amount >= min && amount <= max) { "amount must be between $min and $max $currency" }
        val id = UUID.randomUUID()
        val session = acquirer.start(amount.setScale(2), currency, id.toString())
        val topUp = TopUp(
            id, partyId, account.id, amount.setScale(2), currency, BigDecimal.ZERO.setScale(2), "CREATED", session.ref,
            session.checkoutUrl, null,
        )
        store.insertTopUp(topUp, clock.instant())
        return topUp
    }

    /** The top-up's state; asks the acquirer while it is open and books the credit once it authorised. */
    fun status(partyId: UUID, id: UUID): TopUp {
        var t = store.topUp(partyId, id) ?: throw ResourceNotFoundException("No such top-up", "TOP_UP_NOT_FOUND")
        if (t.status == "CREATED") {
            when (acquirer.status(t.acquirerRef)) {
                "AUTHORISED" -> t = t.copy(status = "AUTHORISED")
                "DECLINED", "EXPIRED" -> t = t.copy(status = "FAILED")
            }
            if (t.status != "CREATED") store.updateTopUp(t, clock.instant())
        }
        if (t.status == "AUTHORISED") {
            val tx = transactions.credit(t.accountId, t.amount, t.currency, "Top up by card", "top-up:${t.id}", partyId)
            t = t.copy(status = "CREDITED", creditedTransactionId = tx)
            store.updateTopUp(t, clock.instant())
            log.infof("Top-up %s credited %s %s to account %s", t.id, t.amount, t.currency, t.accountId)
        }
        return t
    }

    private companion object {
        val LIMITS = mapOf(
            "CZK" to (BigDecimal("100") to BigDecimal("50000")),
            "EUR" to (BigDecimal("5") to BigDecimal("2000")),
        )
    }
}

// ---------------------------------------------------------------- payment protection

/**
 * Payment protection: unusual payments wait for a confirmation, street mode caps payments while the
 * customer is out, and a trusted person can be told about a held payment. The settings live here;
 * customer-edge enforces street mode on each payment.
 */
@ApplicationScoped
class ProtectionService(private val store: CaseStore, private val clock: Clock) {
    fun protection(partyId: UUID): Protection =
        store.protection(partyId) ?: Protection(false, false, DEFAULT_LIMIT, null, null)

    fun save(partyId: UUID, p: Protection): Protection {
        require(p.streetModeLimit.signum() > 0 && p.streetModeLimit <= MAX_LIMIT) {
            "street_mode_limit must be between 1 and $MAX_LIMIT"
        }
        require((p.trustedApproverName?.length ?: 0) <= MAX_NAME) { "trusted_approver_name is too long" }
        require(p.trustedApproverPhone == null || PHONE.matches(p.trustedApproverPhone)) {
            "trusted_approver_phone must be +420 or +359 and 9 digits"
        }
        store.saveProtection(partyId, p.copy(streetModeLimit = p.streetModeLimit.setScale(2)), clock.instant())
        return protection(partyId)
    }

    private companion object {
        val DEFAULT_LIMIT = BigDecimal("2000.00")
        val MAX_LIMIT = BigDecimal("1000000")
        const val MAX_NAME = 120
        val PHONE = Regex("^\\+(420|359)\\d{9}$")
    }
}
