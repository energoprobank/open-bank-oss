// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

class CardSelfServiceTest {

    @Test
    fun `a PIN is four digits and not one a thief tries first`() {
        assertThat(PinPolicy.problem("4826")).isNull()
        assertThat(PinPolicy.problem("482")).isNotNull()
        assertThat(PinPolicy.problem("48a6")).isNotNull()
        assertThat(PinPolicy.problem("7777")).isNotNull()
        assertThat(PinPolicy.problem("1234")).isNotNull()
        assertThat(PinPolicy.problem("6543")).isNotNull()
        assertThat(PinPolicy.problem("1987")).isNotNull()
        assertThat(PinPolicy.problem("2024")).isNotNull()
        val r = Random(7)
        repeat(200) { assertThat(PinPolicy.problem(PinPolicy.random { r.nextInt(it) })).isNull() }
    }

    @Test
    fun `a travel window covers its countries and days, inclusive`() {
        val w = TravelWindow(UUID.randomUUID(), listOf("HR", "SI"), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 14))
        assertThat(w.covers("hr", LocalDate.of(2026, 7, 1))).isTrue()
        assertThat(w.covers("SI", LocalDate.of(2026, 7, 14))).isTrue()
        assertThat(w.covers("HR", LocalDate.of(2026, 7, 15))).isFalse()
        assertThat(w.covers("IT", LocalDate.of(2026, 7, 5))).isFalse()
    }

    @Test
    fun `with payments abroad off, a travel notice opens exactly its countries and days`() {
        val card = card(abroadEnabled = false)
        val day = LocalDate.of(2026, 7, 5)
        val travel =
            listOf(TravelWindow(UUID.randomUUID(), listOf("HR"), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 14)))
        fun req(country: String) = AuthorizationRequest(10_000, AuthorizationChannel.CHIP_AND_PIN, "5411", country)
        assertThat(CardAuthorizationPolicy.decide(card, req("HR"), emptyList(), travel, day).approved).isTrue()
        assertThat(
            CardAuthorizationPolicy.decide(card, req("IT"), emptyList(), travel, day).reason,
        ).isEqualTo(DeclineReason.ABROAD_DISABLED)
        assertThat(
            CardAuthorizationPolicy.decide(card, req("HR"), emptyList(), travel, LocalDate.of(2026, 8, 1)).reason,
        )
            .isEqualTo(DeclineReason.ABROAD_DISABLED)
        assertThat(CardAuthorizationPolicy.decide(card, req("CZ"), emptyList(), emptyList(), day).approved).isTrue()
    }

    private fun card(abroadEnabled: Boolean) = Card(
        id = UUID.randomUUID(),
        idempotencyKey = "k",
        partyId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        productCode = "P",
        cardType = CardType.DEBIT,
        network = CardNetwork.VISA,
        maskedPan = "411111******1234",
        cardholderName = "Jana",
        embossedName = "JANA",
        expiryDate = LocalDate.of(2030, 1, 31),
        status = CardStatus.ACTIVE,
        dailyLimitMinorUnits = 10_000_000,
        monthlyLimitMinorUnits = 100_000_000,
        currency = "CZK",
        deliveryAddress = null,
        activatedAt = Instant.EPOCH,
        blockedAt = null,
        blockedReason = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        abroadEnabled = abroadEnabled,
    )
}
