// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.account.application.port.out.PartyDirectoryPort
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Resumes an onboarding a sanctions/PEP screening hit stopped, once compliance clears the hit.
 *
 * Opening the onboarding accounts screens the customer (AccountService, ADR-0032 §C, key
 * `account-open:<partyId>:onboarding-…`). A HIT blocks it: PARTY_CREATED is dead-lettered and the
 * customer is left with no account. When an operator later dispositions that check CLEAR or
 * WHITELISTED, sanctions-service publishes `SanctionReviewed` on openbank.sanctions.screening.event;
 * this consumer then opens the accounts (the reviewed check answers the same key, and a clearance
 * carries over to the savings account's screening) and activates them if the party is ACTIVE.
 *
 * Only reviews of onboarding account-opening checks act; every other event is acked untouched.
 */
@ApplicationScoped
class SanctionsReviewConsumer(
    private val partyEvents: PartyEventConsumer,
    private val partyDirectory: PartyDirectoryPort,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(SanctionsReviewConsumer::class.java)

    @Incoming("sanctions-reviews-in")
    suspend fun consume(record: ConsumerRecord<String, String>) {
        val type = record.headers().lastHeader(EVENT_TYPE_HEADER)?.value()?.toString(Charsets.UTF_8)
        if (type != REVIEWED) return
        val cleared = parse(record.value()) ?: return
        val party = partyDirectory.find(cleared.partyId)
        if (party == null) {
            log.warnf("sanctions clearance for unknown party %s — nothing to resume", cleared.partyId)
            return
        }
        partyEvents.resumeAfterClearance(cleared.partyId, party.legalName.ifBlank { cleared.name }, party.status == "ACTIVE")
        log.infof("onboarding resumed for party %s after sanctions clearance", cleared.partyId)
    }

    internal data class Clearance(val partyId: UUID, val name: String)

    /** A CLEAR/WHITELISTED review of an onboarding account-opening check, or null. */
    internal fun parse(payload: String?): Clearance? {
        val node = payload?.let { runCatching { objectMapper.readTree(it) }.getOrNull() } ?: return null
        if (node.path("status").asText() !in CLEARED) return null
        val match = ONBOARDING_KEY.find(node.path("idempotencyKey").asText("")) ?: return null
        val partyId = runCatching { UUID.fromString(match.groupValues[1]) }.getOrNull() ?: return null
        return Clearance(partyId, node.path("name").asText("").trim())
    }

    companion object {
        const val EVENT_TYPE_HEADER = "ce-type"
        const val REVIEWED = "SanctionReviewed"
        private val CLEARED = setOf("CLEAR", "WHITELISTED")
        private val ONBOARDING_KEY = Regex("^account-open:([0-9a-fA-F-]{36}):onboarding-")
    }
}
