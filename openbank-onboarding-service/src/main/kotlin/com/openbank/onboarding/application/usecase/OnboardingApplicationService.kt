// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.application.usecase

import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import com.openbank.onboarding.application.port.out.ApplicationStore
import com.openbank.onboarding.application.port.out.BankIdPort
import com.openbank.onboarding.application.port.out.ContactCodeDeliveryPort
import com.openbank.onboarding.application.port.out.ContactVerificationStore
import com.openbank.onboarding.application.port.out.EnergoProCustomerPort
import com.openbank.onboarding.application.port.out.EnergoProRecord
import com.openbank.onboarding.application.port.out.IdentityVerificationPort
import com.openbank.onboarding.application.port.out.PartyFacts
import com.openbank.onboarding.application.port.out.PartyMasterDataPort
import com.openbank.onboarding.application.port.out.PersonalDetailsWrite
import com.openbank.onboarding.application.port.out.ProviderApplicant
import com.openbank.onboarding.application.port.out.ProviderResult
import com.openbank.onboarding.application.port.out.TaxResidencyWrite
import com.openbank.onboarding.domain.model.ContactVerification
import com.openbank.onboarding.domain.model.IdentityMethod
import com.openbank.onboarding.domain.model.IdentityStatus
import com.openbank.onboarding.domain.model.IdentityVerification
import com.openbank.onboarding.domain.model.OnboardingApplication
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What the bank tells the applicant after sending the codes; demo codes only where the sandbox reveals them. */
data class ContactCodesSent(
    val id: UUID,
    val expiresInSeconds: Long,
    val demoEmailCode: String?,
    val demoPhoneCode: String?,
)

data class ContactCheckResult(val emailVerified: Boolean, val phoneVerified: Boolean)

data class IdentityView(
    val verification: IdentityVerification,
    val checks: List<com.openbank.onboarding.application.port.out.ProviderCheck>,
)

/**
 * Self-service account opening, write side (experience v2 step 8). Everything is keyed by the party
 * id that customer-edge took from the customer's token (or, before sign-in, by nothing personal at all).
 */
@ApplicationScoped
@Suppress("LongParameterList", "TooManyFunctions")
class OnboardingApplicationService(
    private val store: ApplicationStore,
    private val contacts: ContactVerificationStore,
    private val energoPro: EnergoProCustomerPort,
    private val delivery: ContactCodeDeliveryPort,
    private val bankId: BankIdPort,
    private val idv: IdentityVerificationPort,
    private val party: PartyMasterDataPort,
    private val clock: Clock,
    /** HMAC key for one-time codes. Absent → a random key per process (codes do not survive a restart). */
    @ConfigProperty(name = "openbank.onboarding.otp.hmac-key")
    private val hmacKey: Optional<String>,
    /** SANDBOX ONLY: echo the codes to the caller so a demo can fill them in. Never on in production. */
    @ConfigProperty(name = "openbank.onboarding.otp.reveal-codes", defaultValue = "false")
    private val revealCodes: Boolean,
) {
    private val log = Logger.getLogger(OnboardingApplicationService::class.java)
    private val random = SecureRandom()
    private val key: ByteArray = hmacKey.filter { it.isNotBlank() }.map { it.toByteArray() }
        .orElseGet { ByteArray(KEY_BYTES).also { random.nextBytes(it) } }

    // ---------------------------------------------------------------- e-mail and phone

    fun sendContactCodes(email: String, phone: String): ContactCodesSent {
        val cleanEmail = email.trim().lowercase()
        val cleanPhone = phone.filter { it.isDigit() || it == '+' }
        require(EMAIL.matches(cleanEmail) && cleanEmail.length <= MAX_EMAIL) { "email is not a valid e-mail address" }
        require(PHONE.matches(cleanPhone)) { "phone must be +420 or +359 and 9 digits" }
        val now = clock.instant()
        if (contacts.countContactVerifications(cleanEmail, cleanPhone, now.minus(ContactVerification.RATE_WINDOW)) >=
            ContactVerification.MAX_PER_CONTACT
        ) {
            throw ResourceConflictException(
                "Too many codes requested for this contact; try again later",
                "TOO_MANY_CODES",
            )
        }
        val id = UUID.randomUUID()
        val emailCode = newCode()
        val phoneCode = newCode()
        contacts.insertContactVerification(
            ContactVerification(
                id = id, email = cleanEmail, phone = cleanPhone,
                emailCodeHash = codeHash(id, "email", emailCode), phoneCodeHash = codeHash(id, "phone", phoneCode),
                attempts = 0, emailVerifiedAt = null, phoneVerifiedAt = null, prefillAttempts = 0,
                expiresAt = now.plus(ContactVerification.TTL), createdAt = now,
            ),
        )
        delivery.sendEmail(cleanEmail, "Energo-Pro Bank: $emailCode")
        delivery.sendSms(cleanPhone, "Energo-Pro Bank: $phoneCode")
        return ContactCodesSent(
            id,
            ContactVerification.TTL.seconds,
            demoEmailCode = emailCode.takeIf { revealCodes },
            demoPhoneCode = phoneCode.takeIf { revealCodes },
        )
    }

    /** Checks either or both codes; a wrong code counts as an attempt, five attempts lock the verification. */
    fun confirmContactCodes(id: UUID, emailCode: String?, phoneCode: String?): ContactCheckResult {
        val v = verification(id)
        val now = clock.instant()
        refusal(v, now)?.let { throw it }
        val emailOk =
            v.emailVerified || (emailCode != null && same(v.emailCodeHash, codeHash(id, "email", emailCode.trim())))
        val phoneOk =
            v.phoneVerified || (phoneCode != null && same(v.phoneCodeHash, codeHash(id, "phone", phoneCode.trim())))
        val wrong = (!emailOk && !emailCode.isNullOrBlank()) || (!phoneOk && !phoneCode.isNullOrBlank())
        contacts.updateContactVerification(
            v.copy(
                attempts = v.attempts + if (wrong) 1 else 0,
                emailVerifiedAt = v.emailVerifiedAt ?: now.takeIf { emailOk },
                phoneVerifiedAt = v.phoneVerifiedAt ?: now.takeIf { phoneOk },
            ),
        )
        return ContactCheckResult(emailOk, phoneOk)
    }

    // ---------------------------------------------------------------- Energo-Pro prefill

    /**
     * The applicant's name and supply address from Energo-Pro — only when the e-mail or phone they have
     * just CONFIRMED is the one on that Energo-Pro record. An unknown number and a contact mismatch
     * answer the same 404, so the endpoint cannot be used to test which numbers exist.
     */
    fun energoProPrefill(customerNumber: String, verificationId: UUID): EnergoProRecord {
        require(customerNumber.isNotBlank() && customerNumber.length <= MAX_CUSTOMER_NUMBER) {
            "customer_number is required"
        }
        val v = verification(verificationId)
        if (!v.usableForPrefill(clock.instant())) {
            throw ResourceConflictException("Confirm your e-mail or phone first", "CONTACT_NOT_CONFIRMED")
        }
        contacts.updateContactVerification(v.copy(prefillAttempts = v.prefillAttempts + 1))
        return energoPro.find(customerNumber)?.takeIf { matchesConfirmedContact(it, v) }
            ?: throw ResourceNotFoundException("No Energo-Pro record matches these contact details", "NO_MATCH")
    }

    private fun matchesConfirmedContact(record: EnergoProRecord, v: ContactVerification): Boolean =
        (v.emailVerified && record.email.trim().equals(v.email, ignoreCase = true)) ||
            (v.phoneVerified && record.phone.filter { it.isDigit() || it == '+' } == v.phone)

    private fun verification(id: UUID): ContactVerification = contacts.contactVerification(id)
        ?: throw ResourceNotFoundException("No such verification", "VERIFICATION_NOT_FOUND")

    /** Why these codes can no longer be checked, or null when they can. */
    private fun refusal(v: ContactVerification, now: Instant): ResourceConflictException? = when {
        v.expired(now) -> ResourceConflictException("The codes have expired; send new ones", "CODES_EXPIRED")
        v.locked -> ResourceConflictException("Too many wrong codes; send new ones", "TOO_MANY_ATTEMPTS")
        else -> null
    }

    // ---------------------------------------------------------------- the application (signed in)

    fun application(partyId: UUID): OnboardingApplication =
        store.application(partyId) ?: OnboardingApplication(partyId, null, null, null, null, null, clock.instant())

    fun savePersonalDetails(partyId: UUID, details: PersonalDetailsWrite) {
        party.writePersonalDetails(partyId, details)
        store.upsertApplication(
            application(partyId).copy(personalDetailsAt = clock.instant(), updatedAt = clock.instant()),
        )
    }

    fun saveTaxResidency(partyId: UUID, usPerson: Boolean, residencies: List<TaxResidencyWrite>) {
        party.writeTaxSelfCertification(partyId, usPerson, residencies)
        store.upsertApplication(
            application(partyId).copy(taxResidencyAt = clock.instant(), updatedAt = clock.instant()),
        )
    }

    fun choosePackage(partyId: UUID, packageCode: String) {
        val code = packageCode.trim().uppercase()
        require(code in PACKAGES) { "package_code must be one of ${PACKAGES.joinToString()}" }
        store.upsertApplication(application(partyId).copy(packageCode = code, updatedAt = clock.instant()))
    }

    // ---------------------------------------------------------------- identity

    /**
     * Starts Bank iD (Czech market only) or a document scan for the applicant. The provider compares
     * what it reads with the name, date of birth and national number the party already holds.
     */
    fun startIdentityVerification(partyId: UUID, method: IdentityMethod, documentType: String?): IdentityView {
        val facts = partyFacts(partyId)
        if (method == IdentityMethod.BANK_ID && facts.market != "CZ") {
            throw ResourceConflictException("Bank iD is available in the Czech Republic only", "METHOD_NOT_AVAILABLE")
        }
        require(documentType == null || documentType in DOCUMENT_TYPES) {
            "document_type must be one of ${DOCUMENT_TYPES.joinToString()}"
        }
        val applicant = applicantOf(facts)
        val id = UUID.randomUUID()
        val started = when (method) {
            IdentityMethod.BANK_ID -> bankId.start(id.toString(), applicant)
            IdentityMethod.DOCUMENT_SCAN -> idv.startDocumentScan(id.toString(), applicant, documentType)
        }
        val now = clock.instant()
        val v =
            IdentityVerification(
                id, partyId, method, documentType, started.providerRef, IdentityStatus.IN_PROGRESS, null, now, now,
            )
        store.insertIdentityVerification(v)
        return settle(v, started, facts)
    }

    fun identityVerification(partyId: UUID, id: UUID): IdentityView {
        val v = store.identityVerification(id)?.takeIf { it.partyId == partyId }
            ?: throw ResourceNotFoundException("No such identity verification", "VERIFICATION_NOT_FOUND")
        if (v.status != IdentityStatus.IN_PROGRESS) return IdentityView(v, emptyList())
        val result = when (v.method) {
            IdentityMethod.BANK_ID -> bankId.get(v.providerRef)
            IdentityMethod.DOCUMENT_SCAN -> idv.get(v.providerRef)
        }
        return settle(v, result, partyFacts(partyId))
    }

    /**
     * Records the provider's answer. A VERIFIED result counts only if the identity the provider confirmed
     * is the applicant's own (date of birth and national number match the party) — otherwise FAILED.
     */
    private fun settle(v: IdentityVerification, result: ProviderResult, facts: PartyFacts): IdentityView {
        val now = clock.instant()
        val updated = when (result.status) {
            "VERIFIED" -> {
                val who = result.identity
                val sameDob =
                    who?.dateOfBirth == null || facts.dateOfBirth == null || who.dateOfBirth == facts.dateOfBirth
                val sameId = who?.nationalId == null || facts.nationalId == null || who.nationalId == facts.nationalId
                if (sameDob && sameId) {
                    v.copy(status = IdentityStatus.VERIFIED, updatedAt = now)
                } else {
                    v.copy(status = IdentityStatus.FAILED, failureReason = "IDENTITY_MISMATCH", updatedAt = now)
                }
            }
            "FAILED" -> v.copy(status = IdentityStatus.FAILED, failureReason = "PROVIDER_FAILED", updatedAt = now)
            else -> v
        }
        if (updated != v) {
            store.updateIdentityVerification(updated)
            if (updated.status == IdentityStatus.VERIFIED) {
                store.upsertApplication(
                    application(v.partyId).copy(identityVerifiedAt = now, identityMethod = v.method, updatedAt = now),
                )
                log.infof("Identity verified for party %s by %s", v.partyId, v.method)
            }
        }
        return IdentityView(updated, result.checks)
    }

    private fun partyFacts(partyId: UUID): PartyFacts =
        party.get(partyId) ?: throw ResourceNotFoundException("No such applicant", "APPLICATION_NOT_FOUND")

    // ---------------------------------------------------------------- helpers

    private fun newCode(): String = (1..ContactVerification.CODE_DIGITS).joinToString("") {
        random.nextInt(DECIMAL).toString()
    }

    private fun codeHash(id: UUID, channel: String, code: String): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal("$id|$channel|$code".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        private const val HMAC = "HmacSHA256"
        private const val KEY_BYTES = 32
        private const val DECIMAL = 10
        private const val MAX_EMAIL = 254
        private const val MAX_CUSTOMER_NUMBER = 32
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private val PHONE = Regex("^\\+(420|359)\\d{9}$")
        val PACKAGES = setOf("STANDARD", "PLUS", "PREMIUM")
        val DOCUMENT_TYPES = setOf("ID_CARD", "PASSPORT", "RESIDENCE_PERMIT")

        /** First word = given name, the rest = family name: enough for a provider's name comparison. */
        fun applicantOf(facts: PartyFacts): ProviderApplicant {
            val parts = facts.legalName.trim().split(Regex("\\s+"), limit = 2)
            return ProviderApplicant(
                parts.first(),
                parts.getOrElse(1) {
                    parts.first()
                },
                facts.dateOfBirth,
                facts.nationalId,
            )
        }
    }
}
