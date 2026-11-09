// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application

import com.openbank.kyc.application.port.out.AdverseMediaScreeningPort
import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.domain.model.AssessedCheck
import com.openbank.kyc.domain.model.CheckStatus
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycAssessment
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycCheck
import com.openbank.kyc.domain.model.KycEvent
import com.openbank.kyc.domain.model.RiskLevel
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Experience v2 step 9: kyc-service decides on an onboarding assessment — straight through or a person. */
class KycAssessmentTest {

    private val repo = mockk<KycCaseRepository>()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC)
    private val partyId = UUID.randomUUID()
    private lateinit var service: KycService

    @BeforeEach
    fun setUp() {
        val adverseMedia = mockk<AdverseMediaScreeningPort>()
        every { adverseMedia.sourceId } returns null
        service = KycService().also {
            it.repo = repo
            it.metrics = mockk<DomainMetrics>(relaxed = true)
            it.clock = clock
            it.adverseMediaSource = adverseMedia
            it.straightThrough = true
        }
        coEvery { repo.update(any<KycCase>(), any()) } answers { firstArg() }
    }

    @Test
    fun `a clean LOW-risk applicant is approved by the policy, with evidence and a review in 5 years`(): Unit =
        runBlocking {
            val case = openCase()
            val saved = service.applyAssessment(
                partyId,
                assessment(RiskLevel.LOW),
                "service-account-openbank-onboarding",
            )

            assertThat(saved.status).isEqualTo(KycCaseStatus.APPROVED)
            assertThat(saved.reviewedBy).isEqualTo("straight-through:onboarding-policy-v1")
            assertThat(saved.checks).allMatch { it.status == CheckStatus.PASSED }
            assertThat(saved.evidence).containsEntry("method", "BANK_ID")
            assertThat(saved.assessedBy).isEqualTo("service-account-openbank-onboarding")
            assertThat(saved.nextReviewAt).isEqualTo(Instant.parse("2031-11-09T10:00:00Z"))
            assertThat(saved.id).isEqualTo(case.id)
            coVerify { repo.update(any(), match<KycEvent> { it.eventType == "KYC_CASE_APPROVED" }) }
        }

    @Test
    fun `MEDIUM risk goes to a person even when every check passed`(): Unit = runBlocking {
        openCase()
        val saved = service.applyAssessment(partyId, assessment(RiskLevel.MEDIUM), "onboarding")
        assertThat(saved.status).isEqualTo(KycCaseStatus.UNDER_REVIEW)
        assertThat(saved.riskLevel).isEqualTo(RiskLevel.MEDIUM)
        coVerify { repo.update(any(), match<KycEvent> { it.eventType == "KYC_CASE_STATUS_CHANGED" }) }
    }

    @Test
    fun `a review reason or a check for review goes to a person, and a FAILED check never rejects`(): Unit =
        runBlocking {
            openCase()
            val withReason = assessment(RiskLevel.LOW).copy(reviewReasons = listOf("FACE_MATCH_GREY"))
            assertThat(
                service.applyAssessment(partyId, withReason, "onboarding").status,
            ).isEqualTo(KycCaseStatus.UNDER_REVIEW)

            openCase()
            val failed = assessment(RiskLevel.LOW).let {
                it.copy(
                    checks = it.checks + (CheckType.SANCTIONS_SCREENING to failedHit),
                )
            }
            assertThat(
                service.applyAssessment(partyId, failed, "onboarding").status,
            ).isEqualTo(KycCaseStatus.UNDER_REVIEW)
        }

    @Test
    fun `with straight-through off every assessment waits for a person`(): Unit = runBlocking {
        service.straightThrough = false
        openCase()
        assertThat(service.applyAssessment(partyId, assessment(RiskLevel.LOW), "onboarding").status)
            .isEqualTo(KycCaseStatus.UNDER_REVIEW)
    }

    @Test
    fun `a decided case is never re-evaluated`(): Unit = runBlocking {
        val approved = case().copy(status = KycCaseStatus.APPROVED, reviewedBy = "sandbox-auto-approval")
        coEvery { repo.findByPartyId(partyId) } returns approved
        assertThat(service.applyAssessment(partyId, assessment(RiskLevel.HIGH), "onboarding")).isEqualTo(approved)
        coVerify(exactly = 0) { repo.update(any(), any()) }
    }

    @Test
    fun `the principal that submitted the assessment cannot decide it`(): Unit = runBlocking {
        val review = case().copy(status = KycCaseStatus.UNDER_REVIEW, assessedBy = "alice")
        coEvery { repo.findById(review.id) } returns review
        assertThatThrownBy { runBlocking { service.approveCase(review.id, "alice", "looks fine to me") } }
            .isInstanceOf(ResourceConflictException::class.java)
        assertThat(service.approveCase(review.id, "bob", "documents verified by hand").status)
            .isEqualTo(KycCaseStatus.APPROVED)
    }

    @Test
    fun `a PENDING check or an unknown due-diligence level is refused`(): Unit = runBlocking {
        openCase()
        val pending = assessment(RiskLevel.LOW).let {
            it.copy(checks = it.checks + (CheckType.ADDRESS to AssessedCheck(CheckStatus.PENDING, null)))
        }
        assertThatThrownBy { runBlocking { service.applyAssessment(partyId, pending, "onboarding") } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            runBlocking { service.applyAssessment(partyId, assessment(RiskLevel.LOW).copy(dueDiligence = "XDD"), "o") }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private val failedHit = AssessedCheck(CheckStatus.FAILED, "HIT")

    private fun openCase(): KycCase {
        val c = case()
        coEvery { repo.findByPartyId(partyId) } returns c
        coEvery { repo.findActiveByPartyId(partyId) } returns c
        return c
    }

    private fun case(): KycCase {
        val id = UUID.randomUUID()
        val now = Instant.now(clock)
        return KycCase(
            id, partyId, KycCaseStatus.OPEN, RiskLevel.MEDIUM, null,
            listOf(CheckType.IDENTITY, CheckType.ADDRESS, CheckType.PEP_SCREENING, CheckType.SANCTIONS_SCREENING).map {
                KycCheck(UUID.randomUUID(), id, it, CheckStatus.PENDING, null, null, null, now)
            },
            null, null, null, now.plusSeconds(3600), now, now,
        )
    }

    private fun assessment(risk: RiskLevel) = KycAssessment(
        policyVersion = "onboarding-policy-v1",
        riskLevel = risk,
        dueDiligence = if (risk >= RiskLevel.HIGH) "EDD" else "CDD",
        checks = listOf(CheckType.IDENTITY, CheckType.ADDRESS, CheckType.PEP_SCREENING, CheckType.SANCTIONS_SCREENING)
            .associateWith { AssessedCheck(CheckStatus.PASSED, "ok") },
        reviewReasons = emptyList(),
        evidence = mapOf("method" to "BANK_ID"),
    )
}
