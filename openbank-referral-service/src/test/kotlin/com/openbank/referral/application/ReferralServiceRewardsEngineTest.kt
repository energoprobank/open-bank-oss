// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.referral.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.referral.application.port.out.ReferralAuditRepository
import com.openbank.referral.application.port.out.ReferralRewardRepository
import com.openbank.referral.domain.InviteStatus
import com.openbank.referral.domain.ProgramStatus
import com.openbank.referral.domain.ReferralInvite
import com.openbank.referral.domain.ReferralProgram
import com.openbank.referral.domain.ReferralReward
import com.openbank.referral.domain.RewardStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * FLUX rewards: `openbank.referral.rewards-engine.enabled` decides whether qualification still writes
 * the deprecated `RewardRequested` event. A unit test (not a @QuarkusTest profile) on purpose: one
 * more Quarkus restart pushes this module's 512 MB test JVM over its heap.
 */
class ReferralServiceRewardsEngineTest {

    private val now = Instant.parse("2026-11-05T10:00:00Z")
    private val rewards = mockk<ReferralRewardRepository>()
    private val audit = mockk<ReferralAuditRepository>(relaxed = true)
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    private fun service(engineOwnsRewards: Boolean) = ReferralService(
        programs = mockk(),
        invites = mockk(),
        rewards = rewards,
        audit = audit,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        objectMapper = mapper,
        linkBaseUrl = Optional.empty(),
        rewardsEngineOwnsRewards = engineOwnsRewards,
    )

    private val program = ReferralProgram(
        id = UUID.randomUUID(), name = "p", version = 1, rewardAmount = BigDecimal.TEN, currency = "CZK",
        qualifyingEvent = "account.opened", attributionWindowEndsAt = now.plusSeconds(86_400),
        status = ProgramStatus.PUBLISHED, maker = "m", checker = "c", createdAt = now, publishedAt = now,
    )

    private val invite = ReferralInvite(
        id = UUID.randomUUID(), programId = program.id, token = "hash", referrerPartyId = UUID.randomUUID(),
        refereePartyId = UUID.randomUUID(), status = InviteStatus.ATTRIBUTED, expiresAt = now.plusSeconds(60),
        idempotencyKey = "k", attributedAt = now,
    )

    private fun qualify(engineOwnsRewards: Boolean): Pair<ReferralReward, List<OutboxMessage>> = runBlocking {
        val reward = slot<ReferralReward>()
        val outbox = slot<List<OutboxMessage>>()
        coEvery { rewards.findByInviteAndEvent(invite.id, "ev-1") } returns null
        coEvery { rewards.create(capture(reward), capture(outbox)) } answers { firstArg() }
        service(engineOwnsRewards).qualifyAttributed(invite, program, "ev-1", "actor")
        reward.captured to outbox.captured
    }

    @Test
    fun `by default qualification requests the reward and writes both events`() {
        val (reward, outbox) = qualify(engineOwnsRewards = false)

        assertThat(reward.status).isEqualTo(RewardStatus.REWARD_REQUESTED)
        assertThat(outbox.map { it.eventType }).containsExactly("Qualified", "RewardRequested")
    }

    @Test
    fun `when the rewards engine owns rewards only Qualified is written and the reward stays QUALIFIED`() {
        val (reward, outbox) = qualify(engineOwnsRewards = true)

        assertThat(reward.status).isEqualTo(RewardStatus.QUALIFIED)
        assertThat(reward.requestedAt).isNull()
        assertThat(outbox.map { it.eventType }).containsExactly("Qualified")
        coVerify { audit.append("QUALIFIED", any(), "actor", any(), now) }
    }
}
