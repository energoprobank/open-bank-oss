// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.application.port.out

import com.openbank.onboarding.domain.model.FinancialProfile
import com.openbank.onboarding.domain.model.Person
import java.math.BigDecimal
import java.util.UUID

// Experience v2 step 9: the services and providers an account opening now depends on. Every port
// FAILS CLOSED — an unreachable dependency is an IdentityCheckUnavailable, never a pass.

/** A dependency the bank cannot decide without did not answer: the applicant retries, nothing passes. */
class IdentityCheckUnavailable(what: String, cause: Throwable? = null) :
    RuntimeException("$what is not available right now; please try again", cause)

// ---------------------------------------------------------------- pid-service (golden record, dedup)

enum class PidDecision { NO_MATCH, MATCH_EXISTING, NEEDS_MANUAL_VERIFICATION }

data class PidResolution(val decision: PidDecision, val partyId: UUID?, val caseId: UUID?)

data class PidApplicant(
    val givenName: String,
    val familyName: String,
    val birthdate: String,
    val nationalId: String,
    val nationality: String?,
    val market: String,
)

interface PidPort {
    fun resolve(applicant: PidApplicant): PidResolution

    fun register(partyId: UUID, applicant: PidApplicant)
}

// ---------------------------------------------------------------- Bank iD (OIDC)

data class BankIdTokens(val accessToken: String)

interface BankIdOidcPort {
    /** Simulator-only side channel: who is at the keyboard for [state] (a real Bank iD needs none). */
    fun expectLogin(state: String, applicant: Person)

    fun authorizeUrl(state: String, nonce: String, codeChallenge: String): String

    fun exchange(code: String, codeVerifier: String): BankIdTokens

    /** The person Bank iD vouches for: name, birthdate, birth number, sex. */
    fun userinfo(tokens: BankIdTokens): Person
}

// ---------------------------------------------------------------- identity documents (OCR, chip, selfie)

data class DocumentRead(
    val id: String,
    val ocr: Person,
    val documentNumber: String,
    val issuingCountry: String,
    val nationality: String?,
    val expiryDate: String?,
    val mrzValid: Boolean,
)

data class ChipRead(
    val dg1: Person,
    val documentNumber: String?,
    val passiveAuthentication: String,
    val signer: String?,
)

data class SelfieResult(val liveness: Double, val faceMatch: Double)

interface DocumentVerificationPort {
    fun readDocument(reference: String, documentType: String, issuingCountry: String, applicant: Person): DocumentRead

    fun readChip(documentId: String): ChipRead

    fun selfie(reference: String, documentId: String): SelfieResult
}

// ---------------------------------------------------------------- public registers

data class RegisterResult(val name: String, val result: String, val detail: String) {
    val record: Boolean get() = result == "RECORD" || result == "LISTED" || result == "MISMATCH"
}

interface RegistersPort {
    /** CZ: Ministry of the Interior register of invalid documents. */
    fun czInvalidDocument(documentNumber: String): RegisterResult

    /** CZ: insolvency register (ISIR) by birth number. */
    fun czInsolvency(nationalId: String): RegisterResult

    /** BG: RegiX — GRAO person register by EGN. */
    fun bgPerson(egn: String): Person?

    /** BG: RegiX — Ministry of the Interior identity-document validity. */
    fun bgDocument(egn: String, documentNumber: String): RegisterResult

    /** BG: RegiX — insolvency. */
    fun bgInsolvency(egn: String): RegisterResult
}

// ---------------------------------------------------------------- the first payment (another bank)

data class IncomingPayment(
    val id: String,
    val payerName: String,
    val payerAccount: String,
    val payerBankCode: String,
    val payerBankName: String?,
    val amount: BigDecimal,
    val currency: String,
    val receivedAt: String?,
)

/** A first payment as the applicant's other bank sends it (sandbox). */
data class OutgoingPayment(
    val payerName: String,
    val payerAccount: String,
    val payerBankCode: String,
    val amount: BigDecimal,
    val currency: String,
    val creditorAccount: String,
    val creditorBankCode: String,
    val variableSymbol: String,
    val nationalId: String?,
)

interface OtherBankPort {
    /** Sandbox: the applicant's own other bank sends the first payment. */
    fun send(payment: OutgoingPayment)

    fun incoming(creditorAccount: String, variableSymbol: String): List<IncomingPayment>
}

// ---------------------------------------------------------------- screening, KYC, party extras

data class ScreeningOutcome(val checkId: String?, val status: String, val topScore: Double, val lists: String)

interface ScreeningPort {
    fun screen(
        partyId: UUID,
        name: String,
        dateOfBirth: String?,
        nationality: String?,
        lists: List<String>,
        key: String,
    ): ScreeningOutcome
}

data class KycDecision(val caseId: String?, val status: String, val reviewedBy: String?)

interface KycAssessmentPort {
    fun assess(partyId: UUID, body: Map<String, Any?>): KycDecision

    /** The party's latest KYC case status, or null when there is none. */
    fun status(partyId: UUID): KycDecision?
}

/** party-service extras of step 9: the clear national number, the verified identity, the AML profile. */
interface PartyIdentityPort {
    fun nationalId(partyId: UUID): String?

    fun recordVerifiedIdentity(
        partyId: UUID,
        method: String,
        documentType: String?,
        documentNumber: String?,
        issuingCountry: String?,
        expiryDate: String?,
    )

    /** Null when the applicant has not declared one yet. */
    fun financialProfile(partyId: UUID): FinancialProfile?

    fun nationality(partyId: UUID): String?
}
