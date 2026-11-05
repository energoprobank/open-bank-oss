// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class RateAdjustmentPolicyTest {

    private val accountId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val from = LocalDate.of(2026, 11, 1)
    private val to = LocalDate.of(2027, 2, 1)

    private fun adjustment(
        bps: Int,
        validFrom: LocalDate = from,
        validTo: LocalDate = to,
        status: RateAdjustmentStatus = RateAdjustmentStatus.ACTIVE,
        source: String = "REWARDS",
        sourceRef: String = "coupon-1",
        approvalRef: String = "approval-1",
    ) = RateAdjustment(
        accountId = accountId,
        deltaBps = bps,
        validFrom = validFrom,
        validTo = validTo,
        source = source,
        sourceRef = sourceRef,
        approvalRef = approvalRef,
        status = status,
        createdBy = "service-account-openbank-rewards",
        createdAt = OffsetDateTime.parse("2026-11-01T08:00:00Z"),
    )

    // --- effective-rate math ---

    @Test
    fun `no adjustment returns the base rate unchanged`() {
        val base = BigDecimal("0.040000000000000000")
        val result = RateAdjustmentPolicy.effectiveAnnualRate(base, emptyList(), from)
        assertThat(result).isSameAs(base)
    }

    @Test
    fun `active adjustments are summed onto the base rate`() {
        // 4 % + 50 bps + 25 bps = 4.75 %
        val result = RateAdjustmentPolicy.effectiveAnnualRate(
            BigDecimal("0.04"),
            listOf(adjustment(50), adjustment(25, sourceRef = "coupon-2")),
            from.plusDays(10),
        )
        assertThat(result).isEqualByComparingTo("0.0475")
    }

    @Test
    fun `negative adjustments subtract`() {
        val result = RateAdjustmentPolicy.effectiveAnnualRate(BigDecimal("0.04"), listOf(adjustment(-150)), from)
        assertThat(result).isEqualByComparingTo("0.025")
    }

    @Test
    fun `window is half-open - validFrom inclusive, validTo exclusive`() {
        val adj = listOf(adjustment(100))
        val base = BigDecimal("0.02")
        assertThat(RateAdjustmentPolicy.effectiveAnnualRate(base, adj, from.minusDays(1))).isEqualByComparingTo("0.02")
        assertThat(RateAdjustmentPolicy.effectiveAnnualRate(base, adj, from)).isEqualByComparingTo("0.03")
        assertThat(RateAdjustmentPolicy.effectiveAnnualRate(base, adj, to.minusDays(1))).isEqualByComparingTo("0.03")
        assertThat(RateAdjustmentPolicy.effectiveAnnualRate(base, adj, to)).isEqualByComparingTo("0.02")
    }

    @Test
    fun `cancelled adjustments do not apply`() {
        val result = RateAdjustmentPolicy.effectiveAnnualRate(
            BigDecimal("0.02"),
            listOf(adjustment(100, status = RateAdjustmentStatus.CANCELLED)),
            from,
        )
        assertThat(result).isEqualByComparingTo("0.02")
    }

    @Test
    fun `result is clamped at zero`() {
        val result = RateAdjustmentPolicy.effectiveAnnualRate(BigDecimal("0.01"), listOf(adjustment(-300)), from)
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `result is clamped at the cap when one exists`() {
        val result = RateAdjustmentPolicy.effectiveAnnualRate(
            BigDecimal("0.04"),
            listOf(adjustment(300)),
            from,
            cap = BigDecimal("0.05"),
        )
        assertThat(result).isEqualByComparingTo("0.05")
    }

    @Test
    fun `below the cap the cap is inert`() {
        val result = RateAdjustmentPolicy.effectiveAnnualRate(
            BigDecimal("0.04"),
            listOf(adjustment(50)),
            from,
            cap = BigDecimal("0.05"),
        )
        assertThat(result).isEqualByComparingTo("0.045")
    }

    @Test
    fun `full catalog precision of the base rate is kept`() {
        val result = RateAdjustmentPolicy.effectiveAnnualRate(
            BigDecimal("0.012345678901234567"),
            listOf(adjustment(1)),
            from,
        )
        assertThat(result).isEqualByComparingTo("0.012445678901234567")
    }

    // --- guard ---

    @Test
    fun `a well-formed adjustment passes the guard`() {
        assertThatCode { RateAdjustmentPolicy.validate(adjustment(300)) }.doesNotThrowAnyException()
        assertThatCode { RateAdjustmentPolicy.validate(adjustment(-300)) }.doesNotThrowAnyException()
        assertThatCode {
            RateAdjustmentPolicy.validate(adjustment(10, validTo = from.plusDays(400)))
        }.doesNotThrowAnyException()
    }

    @Test
    fun `delta beyond 300 bps is rejected`() {
        assertThatThrownBy { RateAdjustmentPolicy.validate(adjustment(301)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("300")
        assertThatThrownBy { RateAdjustmentPolicy.validate(adjustment(-301)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `zero delta is rejected`() {
        assertThatThrownBy { RateAdjustmentPolicy.validate(adjustment(0)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `validTo must be after validFrom`() {
        assertThatThrownBy { RateAdjustmentPolicy.validate(adjustment(10, validTo = from)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("validTo")
    }

    @Test
    fun `window longer than 400 days is rejected`() {
        assertThatThrownBy { RateAdjustmentPolicy.validate(adjustment(10, validTo = from.plusDays(401))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("400")
    }

    @Test
    fun `source must be an upper-case code and refs must be present`() {
        assertThatThrownBy { RateAdjustmentPolicy.validate(adjustment(10, source = "rewards")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RateAdjustmentPolicy.validate(adjustment(10, sourceRef = " ")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RateAdjustmentPolicy.validate(adjustment(10, approvalRef = "")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `sameTermsAs ignores identity and audit fields`() {
        val a = adjustment(50)
        val b = a.copy(adjustmentId = UUID.randomUUID(), createdBy = "other", createdAt = a.createdAt.plusHours(1))
        assertThat(a.sameTermsAs(b)).isTrue()
        assertThat(a.sameTermsAs(a.copy(deltaBps = 51))).isFalse()
        assertThat(a.sameTermsAs(a.copy(validTo = to.plusDays(1)))).isFalse()
    }
}
