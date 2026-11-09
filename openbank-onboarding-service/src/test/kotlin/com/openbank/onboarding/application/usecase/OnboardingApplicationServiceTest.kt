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
import com.openbank.onboarding.application.port.out.LivenessStore
import com.openbank.onboarding.application.port.out.PartyFacts
import com.openbank.onboarding.application.port.out.PartyMasterDataPort
import com.openbank.onboarding.application.port.out.PersonalDetailsWrite
import com.openbank.onboarding.application.port.out.ProviderApplicant
import com.openbank.onboarding.application.port.out.ProviderCheck
import com.openbank.onboarding.application.port.out.ProviderResult
import com.openbank.onboarding.application.port.out.TaxResidencyWrite
import com.openbank.onboarding.domain.model.ContactVerification
import com.openbank.onboarding.domain.model.IdentityMethod
import com.openbank.onboarding.domain.model.IdentityStatus
import com.openbank.onboarding.domain.model.IdentityVerification
import com.openbank.onboarding.domain.model.LivenessPolicy
import com.openbank.onboarding.domain.model.LivenessPurpose
import com.openbank.onboarding.domain.model.LivenessSession
import com.openbank.onboarding.domain.model.LivenessStatus
import com.openbank.onboarding.domain.model.OnboardingApplication
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/** A clock the test moves forward. */
class MovableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

/** The V5 tables in memory, same semantics as JdbcApplicationStore. */
class InMemoryStore :
    ApplicationStore,
    ContactVerificationStore,
    LivenessStore {
    val verifications = mutableMapOf<UUID, ContactVerification>()
    val applications = mutableMapOf<UUID, OnboardingApplication>()
    val identities = mutableMapOf<UUID, IdentityVerification>()
    val policies = mutableMapOf<UUID, LivenessPolicy>()
    val sessions = mutableMapOf<UUID, LivenessSession>()

    override fun insertContactVerification(v: ContactVerification) {
        verifications[v.id] = v
    }
    override fun contactVerification(id: UUID) = verifications[id]
    override fun countContactVerifications(email: String, phone: String, since: Instant) =
        verifications.values.count { (it.email == email || it.phone == phone) && it.createdAt.isAfter(since) }
    override fun updateContactVerification(v: ContactVerification) {
        verifications[v.id] = v
    }
    override fun application(partyId: UUID) = applications[partyId]
    override fun upsertApplication(a: OnboardingApplication) {
        applications[a.partyId] = a
    }
    override fun insertIdentityVerification(v: IdentityVerification) {
        identities[v.id] = v
    }
    override fun identityVerification(id: UUID) = identities[id]

    override fun identityVerificationByState(state: String) = identities.values.firstOrNull { it.oidcState == state }

    override fun identityVerifications(partyId: UUID) =
        identities.values.filter { it.partyId == partyId }.sortedBy { it.createdAt }
    override fun updateIdentityVerification(v: IdentityVerification) {
        identities[v.id] = v
    }
    override fun livenessPolicy(partyId: UUID) = policies[partyId]
    override fun upsertLivenessPolicy(p: LivenessPolicy) {
        policies[p.partyId] = p
    }
    override fun insertLivenessSession(s: LivenessSession) {
        sessions[s.id] = s
    }
    override fun livenessSession(id: UUID) = sessions[id]
    override fun updateLivenessSession(s: LivenessSession) {
        sessions[s.id] = s
    }
    override fun consumeLivenessSession(id: UUID, at: Instant): Boolean {
        val s = sessions[id] ?: return false
        if (s.consumedAt != null) return false
        sessions[id] = s.copy(consumedAt = at)
        return true
    }
}

/** A provider whose sessions verify once [ready] is set. */
class FakeProvider :
    BankIdPort,
    IdentityVerificationPort {
    var ready = false
    var identity = ProviderApplicant("Мария", "Георгиева", "1975-01-01", "7501010010")
    private fun result(ref: String) = ProviderResult(
        ref,
        if (ready) "VERIFIED" else "IN_PROGRESS",
        listOf(ProviderCheck("DOCUMENT", if (ready) "PASSED" else "PENDING")),
        identity.takeIf { ready },
    )
    override fun start(reference: String, applicant: ProviderApplicant) = result("bankid-$reference")
    override fun startDocumentScan(reference: String, applicant: ProviderApplicant, documentType: String?) =
        result("scan-$reference")
    override fun startLiveness(reference: String, applicant: ProviderApplicant) = result("live-$reference")
    override fun get(providerRef: String) = result(providerRef)
}

class FakeParty(var market: String = "BG") : PartyMasterDataPort {
    val written = mutableListOf<PersonalDetailsWrite>()
    var tax: List<TaxResidencyWrite>? = null
    override fun get(partyId: UUID) =
        PartyFacts(partyId, "Мария Георгиева", "1975-01-01", "7501010010", market, "PENDING_KYC")
    override fun writePersonalDetails(partyId: UUID, details: PersonalDetailsWrite) {
        written += details
    }
    override fun writeTaxSelfCertification(partyId: UUID, usPerson: Boolean, residencies: List<TaxResidencyWrite>) {
        tax =
            residencies
    }
}

class OnboardingApplicationServiceTest {
    private val clock = MovableClock(Instant.parse("2026-11-05T10:00:00Z"))
    private val store = InMemoryStore()
    private val sent = mutableListOf<Pair<String, String>>()
    private val provider = FakeProvider()
    private val party = FakeParty()
    private val record = EnergoProRecord(
        "EP-2000001", "BG", "Мария", "Георгиева", "бул. Витоша 15", "София", "1000",
        "maria@example.bg", "+359 87 123 4567",
    )
    private val energoPro = object : EnergoProCustomerPort {
        override fun find(customerNumber: String) = record.takeIf { customerNumber == it.customerNumber }
    }
    private val delivery = object : ContactCodeDeliveryPort {
        override fun sendEmail(to: String, text: String) {
            sent += to to text
        }
        override fun sendSms(to: String, text: String) {
            sent += to to text
        }
    }

    private fun service(reveal: Boolean = true) = OnboardingApplicationService(
        store, store, energoPro, delivery, provider, provider, party, clock,
        Optional.of(
            "test-key",
        ),
        reveal,
    )

    @Test
    fun `codes go to both channels, are stored only as HMACs and are revealed only in the sandbox`() {
        val codes = service().sendContactCodes("Maria@Example.bg", "+359 87 123 4567")
        assertThat(sent.map { it.first }).containsExactly("maria@example.bg", "+359871234567")
        val stored = store.verifications.getValue(codes.id)
        assertThat(stored.emailCodeHash).doesNotContain(codes.demoEmailCode!!).hasSize(64)
        assertThat(service(reveal = false).sendContactCodes("b@example.bg", "+359871234568").demoEmailCode).isNull()
    }

    @Test
    fun `codes are checked per channel, wrong codes lock after five attempts`() {
        val codes = service().sendContactCodes("maria@example.bg", "+359871234567")
        assertThat(service().confirmContactCodes(codes.id, codes.demoEmailCode, "000000"))
            .isEqualTo(ContactCheckResult(emailVerified = true, phoneVerified = false))
        repeat(4) { service().confirmContactCodes(codes.id, null, "111111") }
        assertThatThrownBy { service().confirmContactCodes(codes.id, null, codes.demoPhoneCode) }
            .isInstanceOf(ResourceConflictException::class.java).hasMessageContaining("Too many")
    }

    @Test
    fun `codes expire and the same contact cannot ask for codes without limit`() {
        val codes = service().sendContactCodes("maria@example.bg", "+359871234567")
        clock.now = clock.now.plus(Duration.ofMinutes(11))
        assertThatThrownBy { service().confirmContactCodes(codes.id, codes.demoEmailCode, codes.demoPhoneCode) }
            .isInstanceOf(ResourceConflictException::class.java)
        repeat(4) { service().sendContactCodes("maria@example.bg", "+359871234567") }
        assertThatThrownBy { service().sendContactCodes("maria@example.bg", "+359870000000") }
            .isInstanceOf(ResourceConflictException::class.java).hasMessageContaining("Too many")
    }

    @Test
    fun `bad contact input is a 400`() {
        assertThatThrownBy {
            service().sendContactCodes("not-an-email", "+359871234567")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            service().sendContactCodes("a@example.bg", "+44 7700 900000")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the Energo-Pro record is released only for a confirmed contact that is on it`() {
        val s = service()
        val codes = s.sendContactCodes("maria@example.bg", "+359871234567")
        // Not confirmed yet.
        assertThatThrownBy {
            s.energoProPrefill("EP-2000001", codes.id)
        }.isInstanceOf(ResourceConflictException::class.java)
        s.confirmContactCodes(codes.id, codes.demoEmailCode, codes.demoPhoneCode)
        assertThat(s.energoProPrefill("EP-2000001", codes.id).street).isEqualTo("бул. Витоша 15")
    }

    @Test
    fun `an unknown number and someone else's record answer the same, and attempts are capped`() {
        val s = service()
        val codes = s.sendContactCodes("intruder@example.bg", "+359870000001")
        s.confirmContactCodes(codes.id, codes.demoEmailCode, codes.demoPhoneCode)
        val unknown = runCatching { s.energoProPrefill("EP-9999999", codes.id) }.exceptionOrNull()
        val other = runCatching { s.energoProPrefill("EP-2000001", codes.id) }.exceptionOrNull()
        assertThat(unknown).isInstanceOf(ResourceNotFoundException::class.java)
        assertThat(other).isInstanceOf(ResourceNotFoundException::class.java).hasMessage(unknown!!.message)
        // Three tries per confirmed contact, then the verification is spent for prefill.
        assertThat(runCatching { s.energoProPrefill("EP-2000002", codes.id) }.exceptionOrNull())
            .isInstanceOf(ResourceNotFoundException::class.java)
        assertThatThrownBy {
            s.energoProPrefill("EP-2000001", codes.id)
        }.isInstanceOf(ResourceConflictException::class.java)
    }

    @Test
    fun `personal details and tax residency are written through to the party and recorded`() {
        val id = UUID.randomUUID()
        service().savePersonalDetails(
            id,
            PersonalDetailsWrite("1975-01-01", "7501010010", "+359871234567", "бул. Витоша 15", "София", "1000", "BG"),
        )
        service().saveTaxResidency(id, false, listOf(TaxResidencyWrite("BG", null)))
        service().choosePackage(id, "plus")
        assertThat(party.written.single().nationalId).isEqualTo("7501010010")
        assertThat(party.tax).containsExactly(TaxResidencyWrite("BG", null))
        val app = store.applications.getValue(id)
        assertThat(app.personalDetailsAt).isNotNull()
        assertThat(app.taxResidencyAt).isNotNull()
        assertThat(app.packageCode).isEqualTo("PLUS")
        assertThatThrownBy { service().choosePackage(id, "GOLD") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Bank iD is Czech only, a document scan verifies once the provider confirms the same person`() {
        val id = UUID.randomUUID()
        assertThatThrownBy { service().startIdentityVerification(id, IdentityMethod.BANK_ID, null) }
            .isInstanceOf(ResourceConflictException::class.java)
        val started = service().startIdentityVerification(id, IdentityMethod.DOCUMENT_SCAN, "ID_CARD")
        assertThat(started.verification.status).isEqualTo(IdentityStatus.IN_PROGRESS)
        provider.ready = true
        val done = service().identityVerification(id, started.verification.id)
        assertThat(done.verification.status).isEqualTo(IdentityStatus.VERIFIED)
        assertThat(store.applications.getValue(id).identityMethod).isEqualTo(IdentityMethod.DOCUMENT_SCAN)
        // Another applicant cannot read it.
        assertThatThrownBy { service().identityVerification(UUID.randomUUID(), started.verification.id) }
            .isInstanceOf(ResourceNotFoundException::class.java)
    }

    @Test
    fun `a provider confirming someone else is a failed verification`() {
        val id = UUID.randomUUID()
        val started = service().startIdentityVerification(id, IdentityMethod.DOCUMENT_SCAN, null)
        provider.ready = true
        provider.identity = provider.identity.copy(nationalId = "0541010000")
        val done = service().identityVerification(id, started.verification.id)
        assertThat(done.verification.status).isEqualTo(IdentityStatus.FAILED)
        assertThat(done.verification.failureReason).isEqualTo("IDENTITY_MISMATCH")
    }

    @Test
    fun `a face-check policy change needs a passed face check, and a payment session is spent once`() {
        val id = UUID.randomUUID()
        val liveness = LivenessService(store, provider, party, clock)
        assertThat(liveness.policy(id).currency).isEqualTo("EUR")
        assertThat(liveness.policy(id).enabled).isFalse()
        val change = liveness.start(id, LivenessPurpose.POLICY_CHANGE, null)
        assertThatThrownBy { liveness.updatePolicy(id, true, BigDecimal("1500"), change.id) }
            .isInstanceOf(ResourceConflictException::class.java)
        provider.ready = true
        assertThat(liveness.session(id, change.id).status).isEqualTo(LivenessStatus.PASSED)
        assertThat(
            liveness.updatePolicy(id, true, BigDecimal("1500"), change.id).threshold,
        ).isEqualByComparingTo("1500")
        // The policy-change session cannot pay, and a payment session pays only once.
        assertThatThrownBy { liveness.consume(id, change.id) }.isInstanceOf(ResourceConflictException::class.java)
        val payment = liveness.start(id, LivenessPurpose.PAYMENT, "pay-1")
        liveness.consume(id, payment.id)
        assertThatThrownBy { liveness.consume(id, payment.id) }.isInstanceOf(ResourceConflictException::class.java)
        // And a passed session is good for ten minutes only.
        val late = liveness.start(id, LivenessPurpose.PAYMENT, "pay-2")
        clock.now = clock.now.plus(Duration.ofMinutes(11))
        assertThatThrownBy { liveness.consume(id, late.id) }.isInstanceOf(ResourceConflictException::class.java)
    }
}
