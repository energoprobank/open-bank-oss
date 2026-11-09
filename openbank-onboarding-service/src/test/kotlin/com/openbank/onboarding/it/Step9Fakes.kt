// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.it

import com.openbank.onboarding.application.port.out.BankIdOidcPort
import com.openbank.onboarding.application.port.out.BankIdTokens
import com.openbank.onboarding.application.port.out.ChipRead
import com.openbank.onboarding.application.port.out.DocumentRead
import com.openbank.onboarding.application.port.out.DocumentVerificationPort
import com.openbank.onboarding.application.port.out.IncomingPayment
import com.openbank.onboarding.application.port.out.KycAssessmentPort
import com.openbank.onboarding.application.port.out.KycDecision
import com.openbank.onboarding.application.port.out.OtherBankPort
import com.openbank.onboarding.application.port.out.OutgoingPayment
import com.openbank.onboarding.application.port.out.PartyIdentityPort
import com.openbank.onboarding.application.port.out.PidApplicant
import com.openbank.onboarding.application.port.out.PidDecision
import com.openbank.onboarding.application.port.out.PidPort
import com.openbank.onboarding.application.port.out.PidResolution
import com.openbank.onboarding.application.port.out.RegisterResult
import com.openbank.onboarding.application.port.out.RegistersPort
import com.openbank.onboarding.application.port.out.ScreeningOutcome
import com.openbank.onboarding.application.port.out.ScreeningPort
import com.openbank.onboarding.application.port.out.SelfieResult
import com.openbank.onboarding.domain.model.FinancialProfile
import com.openbank.onboarding.domain.model.Person
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Step 9 test doubles. Each behaves like the real dependency for an ordinary applicant and can be
// told to answer differently (a match, a grey face, a hit), so the ITs drive every decision path.

@Alternative
@Priority(1)
@ApplicationScoped
class FakePid : PidPort {
    var next: PidResolution = PidResolution(PidDecision.NO_MATCH, null, null)
    val registered = ConcurrentHashMap<UUID, PidApplicant>()

    override fun resolve(applicant: PidApplicant): PidResolution = next

    override fun register(partyId: UUID, applicant: PidApplicant) {
        registered[partyId] = applicant
    }
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakePartyIdentity(private val party: FakePartyMasterData) : PartyIdentityPort {
    val verified = ConcurrentHashMap<UUID, String>()
    var profile: FinancialProfile? = FinancialProfile(
        setOf("EVERYDAY_BANKING"),
        setOf("EMPLOYMENT"),
        "EMPLOYED",
        "UP_TO_30K",
        false,
        false,
        listOf("CZ"),
        false,
    )

    override fun nationalId(partyId: UUID): String? =
        party.personalDetails[partyId]?.nationalId ?: Applicants.facts[partyId]?.nationalId

    override fun recordVerifiedIdentity(
        partyId: UUID,
        method: String,
        documentType: String?,
        documentNumber: String?,
        issuingCountry: String?,
        expiryDate: String?,
    ) {
        verified[partyId] = method
    }

    override fun financialProfile(partyId: UUID): FinancialProfile? = profile

    override fun nationality(partyId: UUID): String? = party.personalDetails[partyId]?.nationality ?: "CZ"
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeBankIdOidc : BankIdOidcPort {
    val expected = ConcurrentHashMap<String, Person>()

    /** Who Bank iD says the person is: the applicant, unless a test puts someone else here. */
    var otherPerson: Person? = null

    override fun expectLogin(state: String, applicant: Person) {
        expected[state] = applicant
    }

    override fun authorizeUrl(state: String, nonce: String, codeChallenge: String) =
        "http://bankid.test/auth?state=$state&code_challenge=$codeChallenge"

    override fun exchange(code: String, codeVerifier: String): BankIdTokens = BankIdTokens(code)

    override fun userinfo(tokens: BankIdTokens): Person = otherPerson ?: expected.getValue(tokens.accessToken)
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeDocuments : DocumentVerificationPort {
    var faceMatch = 0.93
    var expiry: String = LocalDate.now().plusYears(5).toString()
    private val read = ConcurrentHashMap<String, Person>()

    override fun readDocument(reference: String, documentType: String, issuingCountry: String, applicant: Person) =
        DocumentRead("doc-$reference", applicant, "204512345", issuingCountry, issuingCountry, expiry, true)
            .also { read[it.id] = applicant }

    override fun readChip(documentId: String) =
        ChipRead(read.getValue(documentId), "204512345", "VALID", "CSCA-CZ 2021")

    override fun selfie(reference: String, documentId: String) = SelfieResult(0.97, faceMatch)
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeRegisters : RegistersPort {
    var insolvent = false

    override fun czInvalidDocument(documentNumber: String) =
        RegisterResult("MVCR_INVALID_DOCUMENTS", "NO_RECORD", "not listed")

    override fun czInsolvency(nationalId: String) = if (insolvent) {
        RegisterResult(
            "ISIR",
            "RECORD",
            "KSPH 38 INS 1/2026 KONKURS",
        )
    } else {
        RegisterResult("ISIR", "NO_RECORD", "none")
    }

    override fun bgPerson(egn: String): Person? = null

    override fun bgDocument(egn: String, documentNumber: String) = RegisterResult("REGIX_MVR", "NO_RECORD", "valid")

    override fun bgInsolvency(egn: String) = RegisterResult("REGIX_INSOLVENCY", "NO_RECORD", "none")
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeOtherBank : OtherBankPort {
    private val payments = ConcurrentHashMap<String, IncomingPayment>()
    var payerOverride: String? = null

    override fun send(payment: OutgoingPayment) {
        payments[payment.variableSymbol] = IncomingPayment(
            "p-${payment.variableSymbol}",
            payerOverride ?: payment.payerName,
            payment.payerAccount,
            payment.payerBankCode,
            "Other Bank",
            payment.amount,
            payment.currency,
            null,
        )
    }

    override fun incoming(creditorAccount: String, variableSymbol: String) = listOfNotNull(payments[variableSymbol])
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeScreening : ScreeningPort {
    var pepStatus = "CLEAR"

    override fun screen(
        partyId: UUID,
        name: String,
        dateOfBirth: String?,
        nationality: String?,
        lists: List<String>,
        key: String,
    ) = ScreeningOutcome("chk-$key", if ("PEP_GLOBAL" in lists) pepStatus else "CLEAR", 0.0, lists.joinToString(","))
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeKyc : KycAssessmentPort {
    val assessments = ConcurrentHashMap<UUID, Map<String, Any?>>()

    /** kyc-service's rule: straight through only for LOW risk with no review reason. */
    override fun assess(partyId: UUID, body: Map<String, Any?>): KycDecision {
        assessments[partyId] = body
        val clean = body["riskLevel"] == "LOW" && (body["reviewReasons"] as List<*>).isEmpty()
        return KycDecision("case-$partyId", if (clean) "APPROVED" else "UNDER_REVIEW", null)
    }

    override fun status(partyId: UUID): KycDecision? = null
}
