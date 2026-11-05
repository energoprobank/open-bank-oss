// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.rest

import com.openbank.interest.application.port.`in`.ManageRateAdjustmentUseCase
import com.openbank.interest.application.port.`in`.RateAdjustmentResult
import com.openbank.interest.domain.model.RateAdjustment
import com.openbank.interest.infrastructure.rest.dto.CreateRateAdjustmentRequest
import com.openbank.interest.infrastructure.rest.dto.RateAdjustmentResponse
import com.openbank.libs.authz.Authorize
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.Principal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The HTTP mapping of the rate-adjustment endpoints without a Quarkus boot: 201 vs 200 on an
 * idempotent replay, the principal recorded as createdBy, the null-field guards (400 via the
 * libs-runtime IllegalArgumentException mapper), and the authorization annotations. The full HTTP
 * round trip against Postgres is RateAdjustmentResourceIT.
 */
class RateAdjustmentResourceTest {

    private val clock = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC)
    private val accountId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val useCase = mockk<ManageRateAdjustmentUseCase>()
    private val identity = mockk<SecurityIdentity> {
        every { principal } returns Principal { "service-account-openbank-rewards" }
    }
    private val resource = RateAdjustmentResource(useCase, identity, clock)

    private val body = CreateRateAdjustmentRequest(
        deltaBps = 50,
        validFrom = LocalDate.of(2026, 11, 5),
        validTo = LocalDate.of(2027, 2, 3),
        source = "REWARDS",
        sourceRef = "coupon-1",
        approvalRef = "approval-1",
    )

    private fun stubCreate(changed: Boolean): io.mockk.CapturingSlot<RateAdjustment> {
        val captured = slot<RateAdjustment>()
        every { useCase.create(capture(captured)) } answers {
            Uni.createFrom().item(RateAdjustmentResult(firstArg(), changed, BigDecimal("0.045")))
        }
        return captured
    }

    @Test
    fun `a new adjustment answers 201 with the effective rate in percent`() {
        val captured = stubCreate(changed = true)

        val response = resource.create(accountId, body).await().indefinitely()

        assertThat(response.status).isEqualTo(201)
        val entity = response.entity as RateAdjustmentResponse
        assertThat(entity.effectiveRatePct).isEqualByComparingTo("4.5")
        assertThat(entity.effectiveRatePct!!.toPlainString()).isEqualTo("4.5")
        assertThat(captured.captured.accountId).isEqualTo(accountId)
        assertThat(captured.captured.createdBy).isEqualTo("service-account-openbank-rewards")
    }

    @Test
    fun `an idempotent replay answers 200`() {
        stubCreate(changed = false)
        assertThat(resource.create(accountId, body).await().indefinitely().status).isEqualTo(200)
    }

    @Test
    fun `a missing body or field is an IllegalArgumentException, i_e_ 400`() {
        assertThatThrownBy { resource.create(accountId, null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { resource.create(accountId, body.copy(sourceRef = null)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("sourceRef")
        assertThatThrownBy { resource.create(accountId, body.copy(deltaBps = null)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("deltaBps")
    }

    @Test
    fun `every endpoint is role-gated and carries its own authorize action`() {
        val expected = mapOf(
            "create" to "interest.adjust",
            "list" to "interest.adjustList",
            "cancel" to "interest.adjustCancel",
        )
        expected.forEach { (method, action) ->
            val m = RateAdjustmentResource::class.java.declaredMethods.single { it.name == method }
            assertThat(m.getAnnotation(PermitAll::class.java)).describedAs(method).isNull()
            assertThat(m.getAnnotation(RolesAllowed::class.java)).describedAs(method).isNotNull()
            assertThat(m.getAnnotation(Authorize::class.java).action).describedAs(method).isEqualTo(action)
        }
        // Create/cancel are machine-only at the JAX-RS layer; operators may only list.
        listOf("create", "cancel").forEach { method ->
            val roles = RateAdjustmentResource::class.java.declaredMethods.single { it.name == method }
                .getAnnotation(RolesAllowed::class.java).value
            assertThat(roles).containsExactly("ROLE_API")
        }
    }
}
