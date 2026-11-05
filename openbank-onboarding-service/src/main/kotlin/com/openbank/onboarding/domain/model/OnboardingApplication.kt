// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.domain.model

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

// Experience v2 step 8: the write side of self-service account opening. The read model
// (OnboardingRecord, ADR-0068) is unchanged; these are the facts the applicant gives the bank and the
// third-party checks the bank runs on them. Facts that belong to the person (date of birth, national
// number, address, tax residency) are written through to party-service; what is kept here is the
// application's own state.

/** An applicant's e-mail + phone check: two one-time codes, stored only as HMACs. */
data class ContactVerification(
    val id: UUID,
    val email: String,
    val phone: String,
    val emailCodeHash: String,
    val phoneCodeHash: String,
    val attempts: Int,
    val emailVerifiedAt: Instant?,
    val phoneVerifiedAt: Instant?,
    val prefillAttempts: Int,
    val expiresAt: Instant,
    val createdAt: Instant,
) {
    val emailVerified: Boolean get() = emailVerifiedAt != null
    val phoneVerified: Boolean get() = phoneVerifiedAt != null
    val locked: Boolean get() = attempts >= MAX_ATTEMPTS

    fun expired(now: Instant) = now.isAfter(expiresAt)

    /** A confirmed contact may be used (e.g. for the Energo-Pro prefill) for a while after the codes expire. */
    fun usableForPrefill(now: Instant) = (emailVerified || phoneVerified) &&
        now.isBefore(createdAt.plus(PREFILL_WINDOW)) &&
        prefillAttempts < MAX_PREFILL_ATTEMPTS

    companion object {
        const val CODE_DIGITS = 6
        const val MAX_ATTEMPTS = 5
        const val MAX_PREFILL_ATTEMPTS = 3

        /** At most this many verifications per e-mail or phone in [RATE_WINDOW]. */
        const val MAX_PER_CONTACT = 5
        val TTL: Duration = Duration.ofMinutes(10)
        val PREFILL_WINDOW: Duration = Duration.ofMinutes(30)
        val RATE_WINDOW: Duration = Duration.ofHours(1)
    }
}

enum class IdentityMethod { BANK_ID, DOCUMENT_SCAN }

enum class IdentityStatus { IN_PROGRESS, VERIFIED, FAILED }

/** One identity check against a provider (Bank iD or document scan), tied to the application. */
data class IdentityVerification(
    val id: UUID,
    val partyId: UUID,
    val method: IdentityMethod,
    val documentType: String?,
    val providerRef: String,
    val status: IdentityStatus,
    val failureReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** The application's own state: what the applicant chose that is not master data. */
data class OnboardingApplication(
    val partyId: UUID,
    val packageCode: String?,
    val personalDetailsAt: Instant?,
    val taxResidencyAt: Instant?,
    val identityVerifiedAt: Instant?,
    val identityMethod: IdentityMethod?,
    val updatedAt: Instant,
)

enum class LivenessPurpose { POLICY_CHANGE, PAYMENT }

enum class LivenessStatus { IN_PROGRESS, PASSED, FAILED }

/** The customer's face-check policy: payments above [threshold] need a passed liveness session first. */
data class LivenessPolicy(
    val partyId: UUID,
    val enabled: Boolean,
    val threshold: BigDecimal,
    val currency: String,
    val lastCheckAt: Instant?,
)

/** One liveness check (face check). A PASSED session is good for one use within [VALIDITY]. */
data class LivenessSession(
    val id: UUID,
    val partyId: UUID,
    val purpose: LivenessPurpose,
    val providerRef: String,
    val status: LivenessStatus,
    val paymentReference: String?,
    val consumedAt: Instant?,
    val createdAt: Instant,
) {
    fun usable(now: Instant, purpose: LivenessPurpose) = status == LivenessStatus.PASSED &&
        consumedAt == null &&
        this.purpose == purpose &&
        now.isBefore(createdAt.plus(VALIDITY))

    companion object {
        val VALIDITY: Duration = Duration.ofMinutes(10)
    }
}
