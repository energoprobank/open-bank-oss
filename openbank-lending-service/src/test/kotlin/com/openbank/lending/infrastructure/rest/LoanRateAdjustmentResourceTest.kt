// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.`in`.RateAdjustmentOutcome
import com.openbank.lending.application.port.`in`.RateAdjustmentUseCase
import com.openbank.lending.domain.model.LoanTermsView
import com.openbank.lending.domain.model.RateAdjustment
import com.openbank.lending.domain.model.RateAdjustmentBlockReason
import com.openbank.lending.domain.model.RateAdjustmentBlockedException
import com.openbank.lending.domain.model.RateAdjustmentPreview
import com.openbank.lending.domain.model.RateAdjustmentSource
import com.openbank.lending.domain.model.RateAdjustmentSourceRefConflictException
import com.openbank.lending.infrastructure.rewards.RateAdjustmentConfig
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.Principal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Transport seam of the rate-adjustment API: who may call (verified service-account names, not
 * roles), and how each refusal maps to the rewards contract's `{code, message}`.
 */
class LoanRateAdjustmentResourceTest {

    private val rewards = "service-account-openbank-rewards"
    private val edge = "service-account-openbank-edge"
    private val loanId = UUID.randomUUID()
    private val useCase = mockk<RateAdjustmentUseCase>()
    private val config = RateAdjustmentConfig(BigDecimal.ZERO, listOf(rewards), listOf(rewards, edge))

    private fun resource(who: SecurityIdentity) = LoanRateAdjustmentResource(useCase, config, who)

    private fun sa(name: String): SecurityIdentity = QuarkusSecurityIdentity.builder()
        .setPrincipal(
            TestJwt(
                mapOf(
                    "azp" to name.removePrefix("service-account-"),
                    "preferred_username" to name,
                    "sub" to "s",
                ),
            ),
        )
        .build()

    private fun person(name: String): SecurityIdentity =
        QuarkusSecurityIdentity.builder().setPrincipal(Principal { name }).build()

    private class TestJwt(private val claims: Map<String, Any?>) : JsonWebToken {
        override fun getName(): String = claims["preferred_username"] as? String ?: "anonymous"
        override fun getClaimNames(): Set<String> = claims.filterValues { it != null }.keys

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> getClaim(claimName: String): T = claims[claimName] as T
    }

    private val body = RateAdjustmentBody(
        deltaBps = -50,
        validFrom = LocalDate.of(2026, 11, 5),
        validTo = LocalDate.of(2027, 11, 5),
        source = "REWARDS",
        sourceRef = "coupon-1",
        approvalRef = "sca-1",
    )

    private val terms = LoanTermsView(BigDecimal("5.99"), BigDecimal("6.16"), Money.of(BigDecimal("5636.17"), "CZK"))

    private val stored = RateAdjustment(
        loanId = LoanId(loanId), deltaBps = -50, validFrom = body.validFrom!!, validTo = body.validTo!!,
        source = RateAdjustmentSource.REWARDS, sourceRef = "coupon-1", approvalRef = "sca-1",
        createdBy = rewards, createdAt = OffsetDateTime.now(), before = terms,
        after = terms.copy(nominalRatePct = BigDecimal("5.49")), effectiveFrom = LocalDate.of(2026, 11, 15),
    )

    @Suppress("UNCHECKED_CAST")
    private fun Response.error() = entity as Map<String, String?>

    @Test
    fun `the rewards service-account creates an adjustment - 201 with before, after and effectiveFrom`() {
        every { useCase.apply(LoanId(loanId), any(), rewards) } returns
            Uni.createFrom().item(RateAdjustmentOutcome(stored, replayed = false))

        val response = resource(sa(rewards)).create(loanId, body).await().indefinitely()

        assertThat(response.status).isEqualTo(201)
        val dto = response.entity as RateAdjustmentDto
        assertThat(dto.adjustmentId).isEqualTo(stored.adjustmentId)
        assertThat(dto.after!!.nominalRatePct).isEqualByComparingTo("5.49")
        assertThat(dto.before!!.instalment).isEqualTo(MoneyDto("5636.17", "CZK"))
        assertThat(dto.effectiveFrom).isEqualTo(LocalDate.of(2026, 11, 15))
    }

    @Test
    fun `customer-edge may preview but never create or cancel`() {
        every { useCase.preview(LoanId(loanId), -50, 12) } returns Uni.createFrom().item(
            RateAdjustmentPreview(terms, terms, true, null, LocalDate.of(2026, 11, 15)),
        )
        assertThat(resource(sa(edge)).preview(loanId, -50, 12).await().indefinitely().status).isEqualTo(200)
        assertThat(resource(sa(edge)).create(loanId, body).await().indefinitely().status).isEqualTo(403)
        assertThat(resource(sa(edge)).cancel(loanId, UUID.randomUUID()).await().indefinitely().status).isEqualTo(403)
        verify(exactly = 0) { useCase.apply(any(), any(), any()) }
    }

    @Test
    fun `a person named like the service-account is not the service-account`() {
        val response = resource(person(rewards)).create(loanId, body).await().indefinitely()
        assertThat(response.status).isEqualTo(403)
        assertThat(response.error()["code"]).isEqualTo("FORBIDDEN")
    }

    @Test
    fun `a guardrail refusal is 409 CREDIT_COUPON_BLOCKED with the reason`() {
        every { useCase.apply(any(), any(), any()) } returns Uni.createFrom().failure(
            RateAdjustmentBlockedException(RateAdjustmentBlockReason.ARREARS, "45 days past due"),
        )
        val response = resource(sa(rewards)).create(loanId, body).await().indefinitely()
        assertThat(response.status).isEqualTo(409)
        assertThat(response.error()).containsEntry("code", "CREDIT_COUPON_BLOCKED").containsEntry("reason", "ARREARS")
    }

    @Test
    fun `a sourceRef reused with other terms is 409 SOURCE_REF_CONFLICT`() {
        every { useCase.apply(any(), any(), any()) } returns
            Uni.createFrom().failure(RateAdjustmentSourceRefConflictException("used"))
        val response = resource(sa(rewards)).create(loanId, body).await().indefinitely()
        assertThat(response.status).isEqualTo(409)
        assertThat(response.error()["code"]).isEqualTo("SOURCE_REF_CONFLICT")
    }

    @Test
    fun `a malformed body is 400, not 500`() {
        val response = resource(sa(rewards)).create(loanId, body.copy(deltaBps = 25)).await().indefinitely()
        assertThat(response.status).isEqualTo(400)
        assertThat(resource(sa(rewards)).create(loanId, body.copy(sourceRef = null)).await().indefinitely().status)
            .isEqualTo(400)
        assertThat(resource(sa(rewards)).create(loanId, null).await().indefinitely().status).isEqualTo(400)
        assertThat(resource(sa(rewards)).preview(loanId, null, 12).await().indefinitely().status).isEqualTo(400)
    }
}
