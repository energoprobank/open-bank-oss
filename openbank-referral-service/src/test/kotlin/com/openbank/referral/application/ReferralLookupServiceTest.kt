// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.referral.application

import com.openbank.referral.application.port.out.ReferralInviteRepository
import com.openbank.referral.application.port.out.ReferralRefereeLookup
import com.openbank.referral.domain.InviteStatus
import com.openbank.referral.domain.ReferralInvite
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ReferralLookupServiceTest {
    private val now = Instant.parse("2026-11-05T10:00:00Z")
    private val invites = mockk<ReferralInviteRepository>()
    private val referees = mockk<ReferralRefereeLookup>()
    private val lookup = ReferralLookupService(invites, referees, Clock.fixed(now, ZoneOffset.UTC))
    private val adam = UUID.randomUUID()
    private val token = "AbCdEfGhIjKlMnOpQrStUv"

    private fun invite(status: InviteStatus, expiresAt: Instant = now.plusSeconds(3600)) = ReferralInvite(
        id = UUID.randomUUID(), programId = UUID.randomUUID(),
        token = ReferralService.hash(
            token,
        ),
        referrerPartyId = adam,
        refereePartyId = null, status = status, expiresAt = expiresAt, idempotencyKey = "k", attributedAt = null,
    )

    @Test
    fun `an open invite names its referrer, but used, expired, unknown and malformed tokens do not`(): Unit =
        runBlocking {
            coEvery { invites.findByToken(ReferralService.hash(token)) } returns invite(InviteStatus.ISSUED)
            assertThat(lookup.previewReferrer(token)).isEqualTo(adam)
            coEvery { invites.findByToken(ReferralService.hash(token)) } returns invite(InviteStatus.ATTRIBUTED)
            assertThat(lookup.previewReferrer(token)).isNull()
            coEvery { invites.findByToken(ReferralService.hash(token)) } returns
                invite(InviteStatus.ISSUED, now.minusSeconds(1))
            assertThat(lookup.previewReferrer(token)).isNull()
            coEvery { invites.findByToken(any()) } returns null
            assertThat(lookup.previewReferrer(token)).isNull()
            assertThat(lookup.previewReferrer("short")).isNull()
        }

    @Test
    fun `the referrer of a referee is the attributed invite's`(): Unit = runBlocking {
        val eva = UUID.randomUUID()
        coEvery { referees.attributedInvites(eva) } returns
            listOf(invite(InviteStatus.ATTRIBUTED).copy(refereePartyId = eva))
        assertThat(lookup.referrerOf(eva)).isEqualTo(adam)
        coEvery { referees.attributedInvites(any()) } returns emptyList()
        assertThat(lookup.referrerOf(UUID.randomUUID())).isNull()
    }
}
