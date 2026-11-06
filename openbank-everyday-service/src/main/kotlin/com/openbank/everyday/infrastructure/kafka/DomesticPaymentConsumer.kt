// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.everyday.application.BillsService
import com.openbank.everyday.application.PaymentSeen
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.scheduler.Scheduled
import io.smallrye.common.annotation.Blocking
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Sees every domestic payment the bank creates (`openbank.domestic.payment.events`) and hands the
 * bill payments among them to [BillsService]. Never rethrows: an event that is not a bill payment,
 * or that cannot be read, is acknowledged and counted — a bill payment's only consequence here is
 * a record and a notice to the biller, and the retry job owns a notice that failed.
 */
@ApplicationScoped
class DomesticPaymentConsumer(
    private val bills: BillsService,
    private val mapper: ObjectMapper,
    private val metrics: MeterRegistry,
) {
    private val log = Logger.getLogger(DomesticPaymentConsumer::class.java)

    @Incoming("domestic-payments-in")
    @Blocking
    fun consume(payload: String) {
        val outcome = try {
            handle(mapper.readTree(payload))
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warnf("Domestic payment event not processed: %s", e.message)
            "error"
        }
        metrics.counter("openbank_everyday_payment_events_total", "outcome", outcome).increment()
    }

    private fun handle(event: JsonNode): String = when {
        event.text("eventType") != "DOMESTIC_PAYMENT_CREATED" -> "ignored"
        else -> parse(event)?.let { if (bills.paymentSeen(it)) "bill" else "not_a_bill" } ?: "unreadable"
    }

    private fun parse(e: JsonNode): PaymentSeen? {
        val paymentId = e.text("paymentId")?.let(UUID::fromString) ?: return null
        val party = (e.text("debtorPartyId") ?: e.text("initiatedByPartyId"))?.let(UUID::fromString) ?: return null
        val number = e.text("creditorAccountNumber") ?: return null
        val bank = e.text("creditorBankCode")
        return PaymentSeen(
            paymentId = paymentId,
            partyId = party,
            creditorAccount = if (bank.isNullOrBlank() || number.startsWith("CZ")) number else "$number/$bank",
            reference = e.text("variableSymbol"),
            amount = e.get("amount")?.decimalValue() ?: BigDecimal.ZERO,
            currency = e.text("currency") ?: "CZK",
            at = e.text("occurredAt")?.let(Instant::parse) ?: Instant.now(),
        )
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeUnless {
        it.isNull
    }?.asText()?.takeIf { it.isNotBlank() }
}

/** Tells Energo-Pro about bill payments whose notice did not go through the first time. */
@ApplicationScoped
class BillNoticeRetry(private val bills: BillsService) {
    private val log = Logger.getLogger(BillNoticeRetry::class.java)

    // Plain blocking JDBC + a REST call: the executor thread a non-suspend @Scheduled runs on is right.
    @Scheduled(
        every = "\${openbank.everyday.notice-retry.every:2m}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun retry() {
        val sent = bills.retryNotices()
        if (sent > 0) log.infof("Energo-Pro notices sent on retry: %d", sent)
    }
}
