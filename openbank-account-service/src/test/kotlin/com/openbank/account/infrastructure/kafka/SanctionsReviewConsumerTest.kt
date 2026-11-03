// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.kafka

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.account.application.port.out.DirectoryParty
import com.openbank.account.application.port.out.PartyDirectoryPort
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SanctionsReviewConsumerTest {
    private val partyEvents = mockk<PartyEventConsumer>(relaxed = true)
    private val directory = mockk<PartyDirectoryPort>()
    private val consumer = SanctionsReviewConsumer(partyEvents, directory, jacksonObjectMapper())
    private val party = UUID.randomUUID()

    private fun payload(status: String, key: String = "account-open:$party:onboarding-account-$party") =
        """{"status":"$status","idempotencyKey":"$key","name":"Michal Král"}"""

    private fun record(type: String?, value: String) = ConsumerRecord("openbank.sanctions.screening.event", 0, 0L, "k", value)
        .also { r -> type?.let { r.headers().add(SanctionsReviewConsumer.EVENT_TYPE_HEADER, it.toByteArray()) } }

    @Test
    fun `a cleared onboarding hit resumes the onboarding, activating when the party is active`(): Unit = runBlocking {
        coEvery { directory.find(party) } returns DirectoryParty(party, "INDIVIDUAL", "ACTIVE", "Michal Král")

        consumer.consume(record(SanctionsReviewConsumer.REVIEWED, payload("CLEAR")))

        coVerify(exactly = 1) { partyEvents.resumeAfterClearance(party, "Michal Král", true) }
    }

    @Test
    fun `screenings, confirmed hits and other checks are acked untouched`(): Unit = runBlocking {
        consumer.consume(record("SanctionChecked", payload("CLEAR")))
        consumer.consume(record(null, payload("CLEAR")))
        consumer.consume(record(SanctionsReviewConsumer.REVIEWED, payload("HIT")))
        consumer.consume(record(SanctionsReviewConsumer.REVIEWED, payload("CLEAR", key = "manual-123")))
        consumer.consume(record(SanctionsReviewConsumer.REVIEWED, payload("CLEAR", key = "account-open:$party:customer-key")))
        consumer.consume(record(SanctionsReviewConsumer.REVIEWED, "not json"))

        coVerify(exactly = 0) { partyEvents.resumeAfterClearance(any(), any(), any()) }
        coVerify(exactly = 0) { directory.find(any()) }
    }

    @Test
    fun `parse accepts WHITELISTED and reads the party from the key`() {
        assertThat(consumer.parse(payload("WHITELISTED"))).isEqualTo(SanctionsReviewConsumer.Clearance(party, "Michal Král"))
    }
}
