// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.it

import com.openbank.onboarding.application.port.out.BankIdPort
import com.openbank.onboarding.application.port.out.ContactCodeDeliveryPort
import com.openbank.onboarding.application.port.out.EnergoProCustomerPort
import com.openbank.onboarding.application.port.out.EnergoProRecord
import com.openbank.onboarding.application.port.out.IdentityVerificationPort
import com.openbank.onboarding.application.port.out.PartyFacts
import com.openbank.onboarding.application.port.out.PartyMasterDataPort
import com.openbank.onboarding.application.port.out.PersonalDetailsWrite
import com.openbank.onboarding.application.port.out.ProviderApplicant
import com.openbank.onboarding.application.port.out.ProviderCheck
import com.openbank.onboarding.application.port.out.ProviderResult
import com.openbank.onboarding.application.port.out.TaxResidencyWrite
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Test doubles for the third-party providers and party-service, so the write-side ITs exercise the
// REST layer and the real database without the provider simulator. Each provider answers at once
// (VERIFIED), which is what the simulator does for a name without its fail marker.

/** The applicants party-service "holds": a CZ one and a BG one. */
object Applicants {
    val CZ: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c201")
    val BG: UUID = UUID.fromString("00000000-0000-0000-0000-00000000b601")

    // Step 9 applicants, one per decision path (each IT test owns its own applicant).
    val BANK_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000009a1")
    val DOCUMENT: UUID = UUID.fromString("00000000-0000-0000-0000-0000000009a2")
    val GREY: UUID = UUID.fromString("00000000-0000-0000-0000-0000000009a3")
    val OTHER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000009a4")
    val facts = mapOf(
        CZ to PartyFacts(CZ, "Jana Testová", "1990-04-12", "9004120004", "CZ", "PROSPECT"),
        BG to PartyFacts(BG, "Велина Тестова", "1975-01-01", "7501010010", "BG", "PROSPECT"),
        BANK_ID to PartyFacts(BANK_ID, "Tomáš Banka", "1985-03-21", null, "CZ", "PENDING_KYC"),
        DOCUMENT to PartyFacts(DOCUMENT, "Petr Doklad", "1988-06-15", null, "CZ", "PENDING_KYC"),
        GREY to PartyFacts(GREY, "Eva Šedá", "1992-08-07", null, "CZ", "PENDING_KYC"),
        OTHER to PartyFacts(OTHER, "Karel Jiný", "1979-01-01", null, "CZ", "PENDING_KYC"),
    )

    /** The national number each step-9 applicant types. */
    val numbers = mapOf(
        BANK_ID to "8503211233",
        DOCUMENT to "8806154566",
        GREY to "9258077895",
        OTHER to "7901013219",
    )
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakePartyMasterData : PartyMasterDataPort {
    val personalDetails = ConcurrentHashMap<UUID, PersonalDetailsWrite>()
    val taxResidencies = ConcurrentHashMap<UUID, List<TaxResidencyWrite>>()

    override fun get(partyId: UUID): PartyFacts? = Applicants.facts[partyId]

    override fun writePersonalDetails(partyId: UUID, details: PersonalDetailsWrite) {
        personalDetails[partyId] = details
    }

    override fun writeTaxSelfCertification(partyId: UUID, usPerson: Boolean, residencies: List<TaxResidencyWrite>) {
        taxResidencies[partyId] = residencies
    }
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeContactCodeDelivery : ContactCodeDeliveryPort {
    val lastCode = ConcurrentHashMap<String, String>()

    override fun sendEmail(to: String, text: String) {
        lastCode[to] = text.takeLast(CODE_LENGTH)
    }

    override fun sendSms(to: String, text: String) {
        lastCode[to] = text.takeLast(CODE_LENGTH)
    }

    private companion object {
        const val CODE_LENGTH = 6
    }
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeEnergoPro : EnergoProCustomerPort {
    override fun find(customerNumber: String): EnergoProRecord? = if (customerNumber == "EP-4100999") {
        EnergoProRecord(
            customerNumber, "CZ", "Jana", "Testová", "Na Bělidle 1/2", "Praha 5", "150 00",
            "jana.testova@example.com", "+420777000111",
        )
    } else {
        null
    }
}

private fun verified(reference: String, applicant: ProviderApplicant, vararg checks: String) =
    ProviderResult("prov-$reference", "VERIFIED", checks.map { ProviderCheck(it, "PASSED") }, applicant)

@Alternative
@Priority(1)
@ApplicationScoped
class FakeBankId : BankIdPort {
    private val started = ConcurrentHashMap<String, ProviderResult>()

    override fun start(reference: String, applicant: ProviderApplicant): ProviderResult =
        verified(reference, applicant, "BANK_ID").also { started[it.providerRef] = it }

    override fun get(providerRef: String): ProviderResult = started.getValue(providerRef)
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeIdentityVerification : IdentityVerificationPort {
    private val started = ConcurrentHashMap<String, ProviderResult>()

    override fun startDocumentScan(
        reference: String,
        applicant: ProviderApplicant,
        documentType: String?,
    ): ProviderResult = verified(reference, applicant, "DOCUMENT", "CHIP", "LIVENESS", "FACE_MATCH").also {
        started[it.providerRef] = it
    }

    override fun startLiveness(reference: String, applicant: ProviderApplicant): ProviderResult =
        ProviderResult("prov-$reference", "VERIFIED", listOf(ProviderCheck("LIVENESS", "PASSED")), null)
            .also { started[it.providerRef] = it }

    override fun get(providerRef: String): ProviderResult = started.getValue(providerRef)
}
