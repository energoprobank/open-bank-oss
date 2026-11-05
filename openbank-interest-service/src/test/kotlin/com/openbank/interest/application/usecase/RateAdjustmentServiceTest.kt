// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.usecase

import com.openbank.interest.application.port.out.InterestRateConfigRepository
import com.openbank.interest.application.port.out.LatestAccrualLookup
import com.openbank.interest.application.port.out.RateAdjustmentRepository
import com.openbank.interest.domain.model.InterestRateConfig
import com.openbank.interest.domain.model.RateAdjustment
import com.openbank.interest.domain.model.RateAdjustmentNotFoundException
import com.openbank.interest.domain.model.RateAdjustmentSourceRefConflictException
import com.openbank.interest.domain.model.RateAdjustmentStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class RateAdjustmentServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 11, 5)
    private val accountId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val adjustmentRepo = mockk<RateAdjustmentRepository>()
    private val configRepo = mockk<InterestRateConfigRepository>()
    private val latestAccrual = mockk<LatestAccrualLookup>()
    private val service = RateAdjustmentService(adjustmentRepo, configRepo, latestAccrual, clock)

    private val override = InterestRateConfig(
        productId = "CURRENT",
        accountId = accountId,
        currency = "CZK",
        annualRate = BigDecimal("0.02"),
        effectiveFrom = LocalDate.of(2026, 1, 1),
        createdAt = OffsetDateTime.now(clock),
        updatedAt = OffsetDateTime.now(clock),
    )

    private fun request(bps: Int = 50, sourceRef: String = "coupon-1") = RateAdjustment(
        accountId = accountId,
        deltaBps = bps,
        validFrom = today,
        validTo = today.plusDays(90),
        source = "REWARDS",
        sourceRef = sourceRef,
        approvalRef = "approval-1",
        createdBy = "service-account-openbank-rewards",
        createdAt = OffsetDateTime.now(clock),
    )

    @BeforeEach
    fun baseRate() {
        // No accrual history: only the account override can resolve (product "" matches no default).
        every { latestAccrual.latestFor(accountId) } returns Uni.createFrom().nullItem()
        every { configRepo.findEffectiveRate(accountId, "", any(), null) } returns Uni.createFrom().item(override)
    }

    @Test
    fun `create persists and reports the boosted rate`() {
        val saved = slot<RateAdjustment>()
        every { adjustmentRepo.findBySourceRef("coupon-1") } returns Uni.createFrom().nullItem()
        every { adjustmentRepo.save(capture(saved)) } answers { Uni.createFrom().item(firstArg<RateAdjustment>()) }
        every { adjustmentRepo.findActiveCovering(accountId, today) } answers {
            Uni.createFrom().item(listOf(saved.captured))
        }

        val result = service.create(request()).await().indefinitely()

        assertThat(result.changed).isTrue()
        assertThat(result.adjustment.status).isEqualTo(RateAdjustmentStatus.ACTIVE)
        assertThat(result.effectiveAnnualRate).isEqualByComparingTo("0.025") // 2 % + 50 bps
    }

    @Test
    fun `replay with identical terms returns the existing adjustment without writing`() {
        val existing = request().copy(
            adjustmentId = UUID.randomUUID(),
            createdAt = OffsetDateTime.now(clock).minusHours(1),
        )
        every { adjustmentRepo.findBySourceRef("coupon-1") } returns Uni.createFrom().item(existing)
        every { adjustmentRepo.findActiveCovering(accountId, today) } returns Uni.createFrom().item(listOf(existing))

        val result = service.create(request()).await().indefinitely()

        assertThat(result.changed).isFalse()
        assertThat(result.adjustment.adjustmentId).isEqualTo(existing.adjustmentId)
        verify(exactly = 0) { adjustmentRepo.save(any()) }
    }

    @Test
    fun `reusing a sourceRef with different terms is a conflict`() {
        every { adjustmentRepo.findBySourceRef("coupon-1") } returns Uni.createFrom().item(request(bps = 25))

        assertThatThrownBy { service.create(request(bps = 50)).await().indefinitely() }
            .isInstanceOf(RateAdjustmentSourceRefConflictException::class.java)
        verify(exactly = 0) { adjustmentRepo.save(any()) }
    }

    @Test
    fun `a lost insert race resolves to the winner`() {
        val winner = request().copy(adjustmentId = UUID.randomUUID())
        every { adjustmentRepo.findBySourceRef("coupon-1") } returnsMany listOf(
            Uni.createFrom().nullItem(),
            Uni.createFrom().item(winner),
        )
        every { adjustmentRepo.save(any()) } returns Uni.createFrom().failure(
            SQLException("duplicate key value violates unique constraint \"ux_rate_adjustment_source_ref\"", "23505"),
        )
        every { adjustmentRepo.findActiveCovering(accountId, today) } returns Uni.createFrom().item(listOf(winner))

        val result = service.create(request()).await().indefinitely()

        assertThat(result.changed).isFalse()
        assertThat(result.adjustment.adjustmentId).isEqualTo(winner.adjustmentId)
    }

    @Test
    fun `a guard violation fails before any read`() {
        assertThatThrownBy { service.create(request(bps = 301)).await().indefinitely() }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { adjustmentRepo.findBySourceRef(any()) }
    }

    @Test
    fun `effective rate is null when no base rate resolves`() {
        every { configRepo.findEffectiveRate(accountId, "", any(), null) } returns Uni.createFrom().nullItem()
        every { adjustmentRepo.findBySourceRef("coupon-1") } returns Uni.createFrom().nullItem()
        every { adjustmentRepo.save(any()) } answers { Uni.createFrom().item(firstArg<RateAdjustment>()) }

        val result = service.create(request()).await().indefinitely()

        assertThat(result.changed).isTrue()
        assertThat(result.effectiveAnnualRate).isNull()
    }

    @Test
    fun `cancel flips the status and the rate falls back`() {
        val existing = request()
        val updated = slot<RateAdjustment>()
        every { adjustmentRepo.findById(existing.adjustmentId) } returns Uni.createFrom().item(existing)
        every { adjustmentRepo.update(capture(updated)) } answers { Uni.createFrom().item(firstArg<RateAdjustment>()) }
        every { adjustmentRepo.findActiveCovering(accountId, today) } returns Uni.createFrom().item(emptyList())

        val result = service.cancel(accountId, existing.adjustmentId, "service-account-openbank-rewards")
            .await().indefinitely()

        assertThat(updated.captured.status).isEqualTo(RateAdjustmentStatus.CANCELLED)
        assertThat(updated.captured.cancelledBy).isEqualTo("service-account-openbank-rewards")
        assertThat(updated.captured.cancelledAt).isNotNull()
        assertThat(result.effectiveAnnualRate).isEqualByComparingTo("0.02")
    }

    @Test
    fun `cancelling an already cancelled adjustment is a no-op`() {
        val cancelled = request().copy(
            status = RateAdjustmentStatus.CANCELLED,
            cancelledBy = "x",
            cancelledAt = OffsetDateTime.now(clock),
        )
        every { adjustmentRepo.findById(cancelled.adjustmentId) } returns Uni.createFrom().item(cancelled)
        every { adjustmentRepo.findActiveCovering(accountId, today) } returns Uni.createFrom().item(emptyList())

        val result = service.cancel(accountId, cancelled.adjustmentId, "y").await().indefinitely()

        assertThat(result.changed).isFalse()
        assertThat(result.adjustment.cancelledBy).isEqualTo("x")
        verify(exactly = 0) { adjustmentRepo.update(any()) }
    }

    @Test
    fun `cancel of an adjustment on another account is not found`() {
        val foreign = request().copy(accountId = UUID.randomUUID())
        every { adjustmentRepo.findById(foreign.adjustmentId) } returns Uni.createFrom().item(foreign)

        assertThatThrownBy { service.cancel(accountId, foreign.adjustmentId, "y").await().indefinitely() }
            .isInstanceOf(RateAdjustmentNotFoundException::class.java)
    }
}
