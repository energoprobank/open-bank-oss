// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.application.port.out

import com.openbank.onboarding.domain.model.ContactVerification
import com.openbank.onboarding.domain.model.IdentityVerification
import com.openbank.onboarding.domain.model.LivenessPolicy
import com.openbank.onboarding.domain.model.LivenessSession
import com.openbank.onboarding.domain.model.OnboardingApplication
import java.time.Instant
import java.util.UUID

// Third-party providers (experience v2 step 8). Locally all four are faces of
// openbank-provider-simulator; each has its own base URL, so replacing one with the real
// provider is an adapter/URL change here and nothing changes for the app.

/** A customer record in Energo-Pro's own customer system (an independent company). */
data class EnergoProRecord(
    val customerNumber: String,
    val market: String,
    val firstName: String,
    val lastName: String,
    val street: String,
    val city: String,
    val postalCode: String,
    val email: String,
    val phone: String,
)

interface EnergoProCustomerPort {
    /** Null when Energo-Pro has no such customer. */
    fun find(customerNumber: String): EnergoProRecord?
}

/** Delivers a one-time code by e-mail or SMS. */
interface ContactCodeDeliveryPort {
    fun sendEmail(to: String, text: String)

    fun sendSms(to: String, text: String)
}

data class ProviderApplicant(
    val givenName: String,
    val familyName: String,
    val dateOfBirth: String?,
    val nationalId: String?,
)

data class ProviderCheck(val type: String, val status: String)

/** A provider session as the provider reports it; [identity] only once verified (never for liveness). */
data class ProviderResult(
    val providerRef: String,
    val status: String,
    val checks: List<ProviderCheck>,
    val identity: ProviderApplicant?,
)

/** Czech Bank iD: identification through the applicant's own bank. */
interface BankIdPort {
    fun start(reference: String, applicant: ProviderApplicant): ProviderResult

    fun get(providerRef: String): ProviderResult
}

/** Identity-verification vendor: document scan (photo, chip, liveness, face match) or liveness alone. */
interface IdentityVerificationPort {
    fun startDocumentScan(reference: String, applicant: ProviderApplicant, documentType: String?): ProviderResult

    fun startLiveness(reference: String, applicant: ProviderApplicant): ProviderResult

    fun get(providerRef: String): ProviderResult
}

/** The person as party-service holds them. */
data class PartyFacts(
    val partyId: UUID,
    val legalName: String,
    val dateOfBirth: String?,
    val nationalId: String?,
    val market: String,
    val status: String,
)

data class PersonalDetailsWrite(
    val dateOfBirth: String,
    val nationalId: String,
    val phone: String,
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String,
)

data class TaxResidencyWrite(val country: String, val tin: String?)

/** party-service: the person's master data. Errors keep party-service's status (400 / 404 / 409). */
interface PartyMasterDataPort {
    fun get(partyId: UUID): PartyFacts?

    fun writePersonalDetails(partyId: UUID, details: PersonalDetailsWrite)

    fun writeTaxSelfCertification(partyId: UUID, usPerson: Boolean, residencies: List<TaxResidencyWrite>)
}

/** E-mail + phone verifications (V5 `contact_verifications`). */
interface ContactVerificationStore {
    fun insertContactVerification(v: ContactVerification)

    fun contactVerification(id: UUID): ContactVerification?

    /** Verifications created for [email] or [phone] since [since] (rate limit). */
    fun countContactVerifications(email: String, phone: String, since: Instant): Int

    fun updateContactVerification(v: ContactVerification)
}

/** The application's own state and its identity checks (V5 `onboarding_applications`, `identity_verifications`). */
interface ApplicationStore {
    fun application(partyId: UUID): OnboardingApplication?

    fun upsertApplication(a: OnboardingApplication)

    fun insertIdentityVerification(v: IdentityVerification)

    fun identityVerification(id: UUID): IdentityVerification?

    fun updateIdentityVerification(v: IdentityVerification)
}

/** Face-check policy and sessions (V5 `liveness_policies`, `liveness_sessions`). */
interface LivenessStore {
    fun livenessPolicy(partyId: UUID): LivenessPolicy?

    fun upsertLivenessPolicy(p: LivenessPolicy)

    fun insertLivenessSession(s: LivenessSession)

    fun livenessSession(id: UUID): LivenessSession?

    fun updateLivenessSession(s: LivenessSession)

    /** Marks the session consumed if it is still unconsumed; false when another use won the race. */
    fun consumeLivenessSession(id: UUID, at: Instant): Boolean
}
