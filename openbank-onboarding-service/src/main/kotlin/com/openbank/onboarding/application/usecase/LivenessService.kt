// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.application.usecase

import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import com.openbank.onboarding.application.port.out.IdentityVerificationPort
import com.openbank.onboarding.application.port.out.LivenessStore
import com.openbank.onboarding.application.port.out.PartyMasterDataPort
import com.openbank.onboarding.domain.model.LivenessPolicy
import com.openbank.onboarding.domain.model.LivenessPurpose
import com.openbank.onboarding.domain.model.LivenessSession
import com.openbank.onboarding.domain.model.LivenessStatus
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

/**
 * Face check for large payments (experience v2 step 8). The bank's identity-verification gateway lives
 * in onboarding-service (it owns the provider port), so the step-up liveness check for payments uses it
 * too. customer-edge enforces the policy on payments: above the customer's limit a payment must carry a
 * PASSED, unused PAYMENT session, which [consume] spends exactly once.
 */
@ApplicationScoped
class LivenessService(
    private val store: LivenessStore,
    private val idv: IdentityVerificationPort,
    private val party: PartyMasterDataPort,
    private val clock: Clock,
) {
    /** The policy, or the default (off, with the market's default limit) when the customer never set one. */
    fun policy(partyId: UUID): LivenessPolicy = store.livenessPolicy(partyId) ?: run {
        val market = party.get(partyId)?.market ?: "CZ"
        LivenessPolicy(
            partyId,
            enabled = false,
            threshold = defaultThreshold(market),
            currency = currencyOf(market),
            lastCheckAt = null,
        )
    }

    /** Changing the policy is itself confirmed by a face check (a POLICY_CHANGE session, used once). */
    fun updatePolicy(partyId: UUID, enabled: Boolean, threshold: BigDecimal, sessionId: UUID): LivenessPolicy {
        require(threshold.signum() >= 0 && threshold <= MAX_THRESHOLD) {
            "threshold must be between 0 and $MAX_THRESHOLD"
        }
        require(!enabled || threshold.signum() > 0) { "threshold must be above zero when the face check is on" }
        val now = clock.instant()
        val session = ownSession(partyId, sessionId)
        if (!session.usable(now, LivenessPurpose.POLICY_CHANGE) || !store.consumeLivenessSession(session.id, now)) {
            throw ResourceConflictException("Confirm the change with a new face check", "LIVENESS_REQUIRED")
        }
        val updated = policy(partyId).copy(enabled = enabled, threshold = threshold.setScale(2), lastCheckAt = now)
        store.upsertLivenessPolicy(updated)
        return updated
    }

    fun start(partyId: UUID, purpose: LivenessPurpose, paymentReference: String?): LivenessSession {
        require((paymentReference?.length ?: 0) <= MAX_REFERENCE) { "payment_reference is too long" }
        val facts = party.get(partyId) ?: throw ResourceNotFoundException("No such customer", "PARTY_NOT_FOUND")
        val id = UUID.randomUUID()
        val started = idv.startLiveness(id.toString(), OnboardingApplicationService.applicantOf(facts))
        val session = LivenessSession(
            id,
            partyId,
            purpose,
            started.providerRef,
            statusOf(started.status),
            paymentReference,
            null,
            clock.instant(),
        )
        store.insertLivenessSession(session)
        return session
    }

    fun session(partyId: UUID, id: UUID): LivenessSession {
        val session = ownSession(partyId, id)
        if (session.status != LivenessStatus.IN_PROGRESS) return session
        val status = statusOf(idv.get(session.providerRef).status)
        if (status == session.status) return session
        val updated = session.copy(status = status)
        store.updateLivenessSession(updated)
        if (status == LivenessStatus.PASSED) {
            store.livenessPolicy(partyId)?.let { store.upsertLivenessPolicy(it.copy(lastCheckAt = clock.instant())) }
        }
        return updated
    }

    /** Spends a PASSED PAYMENT session for one payment. 409 LIVENESS_REQUIRED when it is not usable. */
    fun consume(partyId: UUID, id: UUID): LivenessSession {
        val now = clock.instant()
        val session = session(partyId, id)
        if (!session.usable(now, LivenessPurpose.PAYMENT) || !store.consumeLivenessSession(session.id, now)) {
            throw ResourceConflictException("A face check is needed for this payment", "LIVENESS_REQUIRED")
        }
        return session.copy(consumedAt = now)
    }

    private fun ownSession(partyId: UUID, id: UUID): LivenessSession =
        store.livenessSession(id)?.takeIf { it.partyId == partyId }
            ?: throw ResourceNotFoundException("No such face check", "SESSION_NOT_FOUND")

    private fun statusOf(providerStatus: String) = when (providerStatus) {
        "VERIFIED" -> LivenessStatus.PASSED
        "FAILED" -> LivenessStatus.FAILED
        else -> LivenessStatus.IN_PROGRESS
    }

    companion object {
        private val MAX_THRESHOLD = BigDecimal("10000000")
        private const val MAX_REFERENCE = 128

        fun currencyOf(market: String) = if (market == "BG") "EUR" else "CZK"

        fun defaultThreshold(market: String): BigDecimal = if (market ==
            "BG"
        ) {
            BigDecimal("2000.00")
        } else {
            BigDecimal("50000.00")
        }
    }
}
