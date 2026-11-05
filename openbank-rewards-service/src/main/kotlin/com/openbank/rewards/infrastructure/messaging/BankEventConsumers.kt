// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.infrastructure.messaging

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.rewards.application.AccountRef
import com.openbank.rewards.application.BankEventHandler
import com.openbank.rewards.application.CardRef
import com.openbank.rewards.application.RewardsStore
import com.openbank.rewards.application.SettledTransaction
import com.openbank.rewards.infrastructure.persistence.RewardsJson
import io.smallrye.common.annotation.Blocking
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

/**
 * The ingest module: one consumer per banking topic. Producers are inconsistent about where the
 * event type lives (payload `eventType` vs the `ce-type` header only), so every handler reads the
 * header first, then the payload. Unparseable or irrelevant records are logged and acked; a
 * processing failure throws, which sends the record to the channel's DLQ after SmallRye's retry.
 * All handlers are @Blocking: the engine uses JDBC on worker threads.
 */
@ApplicationScoped
@Suppress("TooManyFunctions")
class BankEventConsumers(private val handler: BankEventHandler, private val store: RewardsStore) {
    private val log = Logger.getLogger(BankEventConsumers::class.java)

    @Incoming("accounts-in")
    @Blocking
    fun accounts(msg: Message<String>) = handle(msg) { type, n ->
        val accountId = n.uuid("aggregateId") ?: n.uuid("accountId") ?: return@handle
        when (type) {
            "AccountCreated" -> {
                val party = n.uuid("partyId") ?: return@handle
                val accountType = n.text("accountType") ?: return@handle
                if (accountType.startsWith("GL_") || accountType == "NOSTRO") return@handle
                handler.accountOpened(
                    AccountRef(
                        accountId,
                        party,
                        accountType,
                        n.text("currency") ?: "CZK",
                        n.text("status"),
                        n.instant("occurredAt"),
                    ),
                )
            }
            "AccountStatusChanged" -> handler.accountStatusChanged(
                accountId,
                n.uuid("partyId"),
                n.text("newStatus") ?: return@handle,
                n.instant("occurredAt") ?: Instant.now(),
            )
            "SavingsGoalSet" -> handler.savingsGoalSet(
                accountId,
                n.uuid("partyId"),
                n.instant("occurredAt") ?: Instant.now(),
            )
        }
    }

    @Incoming("transactions-in")
    @Blocking
    fun transactions(msg: Message<String>) = handle(msg) { type, n ->
        val id = n.uuid("aggregateId") ?: n.uuid("transactionId") ?: return@handle
        when (type) {
            "TransactionInitiated" -> store.tx { it.putPendingTransaction(id, msg.payload, Instant.now()) }
            "TransactionCompleted" -> {
                val initiated =
                    store.tx { it.takePendingTransaction(id) }?.let(RewardsJson.mapper::readTree) ?: return@handle
                handler.transactionSettled(
                    SettledTransaction(
                        transactionId = id,
                        type = initiated.text("type") ?: "UNKNOWN",
                        sourceAccountId = initiated.uuid("sourceAccountId"),
                        targetAccountId = initiated.uuid("targetAccountId"),
                        amount = initiated.decimal("amount") ?: return@handle,
                        currency = initiated.text("currencyCode") ?: "CZK",
                        rail = initiated.text("rail") ?: "UNKNOWN",
                        instructionType = initiated.text("instructionType") ?: "UNKNOWN",
                        reversalOf = initiated.uuid("reversalOf"),
                        occurredAt = n.instant("occurredAt") ?: Instant.now(),
                    ),
                )
            }
            "TransactionFailed", "TransactionReversed" -> store.tx { it.takePendingTransaction(id) }
        }
    }

    @Incoming("cards-in")
    @Blocking
    fun cards(msg: Message<String>) = handle(msg) { type, n ->
        val cardId = n.uuid("cardId") ?: n.uuid("aggregateId") ?: return@handle
        when (type) {
            "card.issued.v1" -> {
                val party = n.uuid("partyId") ?: return@handle
                handler.cardIssued(
                    CardRef(cardId, party, n.uuid("accountId"), n.text("status")),
                    n.instant("occurredAt") ?: Instant.now(),
                )
            }
            "card.status_changed.v1" -> handler.cardStatusChanged(
                cardId,
                n.uuid("partyId"),
                n.text("newStatus") ?: return@handle,
                n.instant("occurredAt") ?: Instant.now(),
            )
        }
    }

    /** card.authorised carries party and merchant; card.cleared carries the settled amount (joined on the authorisation). */
    @Suppress("CyclomaticComplexMethod") // one rule table; splitting hides it
    @Incoming("card-processing-in")
    @Blocking
    fun cardProcessing(msg: Message<String>) = handle(msg) { type, n ->
        val authId = n.text("authorizationId") ?: return@handle
        when (type) {
            "card.authorised.v1" -> {
                // The cleared event may have arrived first (different keys, different partitions).
                val parked = store.tx { tx ->
                    tx.putPendingTransaction(authKey(authId), msg.payload, Instant.now())
                    tx.takePendingTransaction(clearedKey(authId))
                }
                parked?.let { cleared(authId, RewardsJson.mapper.readTree(it)) }
            }
            "card.cleared.v1" -> {
                if (n.has("fullyCleared") && !n["fullyCleared"].asBoolean()) return@handle
                val known = n.uuid("partyId") != null || store.tx { it.peekPendingTransaction(authKey(authId)) } != null
                if (known) {
                    cleared(authId, n)
                } else {
                    store.tx {
                        it.putPendingTransaction(clearedKey(authId), msg.payload, Instant.now())
                    }
                }
            }
        }
    }

    private fun cleared(authId: String, n: JsonNode) {
        val auth = store.tx { it.takePendingTransaction(authKey(authId)) }?.let(RewardsJson.mapper::readTree)
        val minor = n.decimal("cumulativeClearedMinorUnits") ?: n.decimal("clearedAmountMinorUnits") ?: return
        handler.cardPayment(
            authorizationId = authId,
            cardId = n.uuid("cardId") ?: auth?.uuid("cardId"),
            partyId = n.uuid("partyId") ?: auth?.uuid("partyId"),
            amount = minor.movePointLeft(2),
            currency = n.text("currencyCode") ?: auth?.text("currencyCode") ?: "CZK",
            merchant = n.text("merchantName") ?: auth?.text("merchantName") ?: auth?.text("mcc") ?: n.text("mcc"),
            at = n.instant("occurredAt") ?: Instant.now(),
        )
    }

    @Incoming("domestic-payments-in")
    @Blocking
    fun domesticPayments(msg: Message<String>) = handle(msg) { type, n ->
        if (type != "DOMESTIC_PAYMENT_CREATED") return@handle
        val paymentId = n.uuid("paymentId") ?: n.uuid("aggregateId") ?: return@handle
        val number = n.text("creditorAccountNumber") ?: return@handle
        val bank = n.text("creditorBankCode")
        handler.domesticPayment(
            paymentId = paymentId,
            partyId = n.uuid("debtorPartyId") ?: n.uuid("partyId") ?: n.uuid("initiatedByPartyId"),
            debtorAccountId = n.uuid("debtorAccountId"),
            creditorAccount = if (bank != null) "$number/$bank" else number,
            amount = n.decimal("amount") ?: return@handle,
            currency = n.text("currency") ?: "CZK",
            at = n.instant("occurredAt") ?: Instant.now(),
        )
    }

    @Incoming("referral-in")
    @Blocking
    fun referrals(msg: Message<String>) = handle(msg) { type, n ->
        if (type != "Qualified") return@handle
        handler.referralQualified(
            inviteId = n.uuid("inviteId") ?: return@handle,
            programId = n.uuid("programId"),
            referrer = n.uuid("referrerPartyId") ?: return@handle,
            referee = n.uuid("refereePartyId") ?: return@handle,
            at = n.instant("occurredAt") ?: Instant.now(),
        )
    }

    @Incoming("lending-in")
    @Blocking
    fun lending(msg: Message<String>) = handle(msg) { type, n ->
        // Only repayment behaviour is read. Credit facts (application, approval, drawdown...) are
        // ignored here AND deny-listed in the domain (G-CR-01).
        if (type != "loan.instalment_paid" &&
            type != "loan.instalment_paid.v1" &&
            type != "LoanInstalmentPaid"
        ) {
            return@handle
        }
        handler.instalmentPaid(
            loanId = n.uuid("loanId") ?: n.uuid("aggregateId") ?: return@handle,
            instalmentNumber = n["instalmentNumber"]?.asInt() ?: 0,
            partyId = n.uuid("partyId") ?: return@handle,
            onTime = n["onTime"]?.asBoolean() ?: false,
            amount = n.decimal("amount"),
            currency = n.text("currency"),
            at = n.instant("paidAt") ?: n.instant("occurredAt") ?: Instant.now(),
        )
    }

    @Incoming("incentive-in")
    @Blocking
    fun incentive(msg: Message<String>) = handle(msg) { type, n ->
        if (!type.contains("committed_for_party") &&
            !type.contains("party-commit") &&
            !type.contains("PartyCommit")
        ) {
            return@handle
        }
        handler.incentiveCommitted(
            partyId = n.uuid("partyId") ?: return@handle,
            offerId = n.uuid("offerId") ?: return@handle,
            reservationId = n.text("reservationId") ?: "",
        )
    }

    /** Acks after the handler ran; a handler failure nacks, which routes the record to the channel's DLQ. */
    private fun handle(msg: Message<String>, block: (String, JsonNode) -> Unit): CompletionStage<Void> {
        val meta = msg.getMetadata(IncomingKafkaRecordMetadata::class.java).orElse(null)
        val node = try {
            RewardsJson.mapper.readTree(msg.payload ?: return msg.ack())
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            log.warnf("unparseable record on %s skipped: %s", meta?.topic, e.message)
            return msg.ack()
        }
        val type =
            meta?.headers?.lastHeader("ce-type")?.value()?.let { String(it) } ?: node.text("eventType")
                ?: node.text("type")
                ?: return msg.ack()
        return try {
            block(type, node)
            msg.ack()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) { // any failure nacks to the DLQ
            log.errorf(e, "rewards ingest of %s failed", type)
            msg.nack(e)
        }
    }

    private fun authKey(authId: String): UUID = UUID.nameUUIDFromBytes("card-auth:$authId".toByteArray())

    private fun clearedKey(authId: String): UUID = UUID.nameUUIDFromBytes("card-cleared:$authId".toByteArray())

    private fun JsonNode.text(field: String): String? = this[field]?.takeUnless {
        it.isNull
    }?.asText()?.takeIf { it.isNotBlank() }
    private fun JsonNode.uuid(field: String): UUID? =
        text(field)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    private fun JsonNode.decimal(field: String): BigDecimal? =
        this[field]?.takeUnless { it.isNull }?.let { runCatching { BigDecimal(it.asText()) }.getOrNull() }
    private fun JsonNode.instant(field: String): Instant? =
        text(field)?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
