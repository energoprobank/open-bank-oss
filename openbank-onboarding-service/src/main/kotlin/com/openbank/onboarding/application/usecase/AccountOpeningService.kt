// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.account.Market
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import com.openbank.onboarding.application.port.out.ApplicationStore
import com.openbank.onboarding.application.port.out.BankIdOidcPort
import com.openbank.onboarding.application.port.out.DocumentVerificationPort
import com.openbank.onboarding.application.port.out.KycAssessmentPort
import com.openbank.onboarding.application.port.out.OtherBankPort
import com.openbank.onboarding.application.port.out.OutgoingPayment
import com.openbank.onboarding.application.port.out.PartyFacts
import com.openbank.onboarding.application.port.out.PartyIdentityPort
import com.openbank.onboarding.application.port.out.PartyMasterDataPort
import com.openbank.onboarding.application.port.out.PersonalDetailsWrite
import com.openbank.onboarding.application.port.out.PidApplicant
import com.openbank.onboarding.application.port.out.PidDecision
import com.openbank.onboarding.application.port.out.PidPort
import com.openbank.onboarding.application.port.out.RegisterResult
import com.openbank.onboarding.application.port.out.RegistersPort
import com.openbank.onboarding.application.port.out.ScreeningOutcome
import com.openbank.onboarding.application.port.out.ScreeningPort
import com.openbank.onboarding.domain.model.ApplicationStatus
import com.openbank.onboarding.domain.model.CheckOutcome
import com.openbank.onboarding.domain.model.FaceThresholds
import com.openbank.onboarding.domain.model.FinancialProfile
import com.openbank.onboarding.domain.model.IdentityCheck
import com.openbank.onboarding.domain.model.IdentityComparison
import com.openbank.onboarding.domain.model.IdentityMethod
import com.openbank.onboarding.domain.model.IdentityStatus
import com.openbank.onboarding.domain.model.IdentityVerification
import com.openbank.onboarding.domain.model.NameMatch
import com.openbank.onboarding.domain.model.ONBOARDING_POLICY
import com.openbank.onboarding.domain.model.OnboardingApplication
import com.openbank.onboarding.domain.model.Person
import com.openbank.onboarding.domain.model.RiskModel
import com.openbank.onboarding.domain.model.RiskScore
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

/** One identity verification with the checks the bank ran (the app shows them, the KYC case keeps them). */
data class IdentityProgress(
    val verification: IdentityVerification,
    val checks: List<IdentityCheck>,
    val redirectUrl: String? = null,
    val document: Map<String, Any?>? = null,
)

/** Where the first payment must go, and whether it has arrived. */
data class FirstPaymentInstructions(
    val accountNumber: String,
    val bankCode: String,
    val iban: String,
    val amount: BigDecimal,
    val currency: String,
    val variableSymbol: String,
    val received: Map<String, Any?>?,
)

data class AccountOpeningView(
    val application: OnboardingApplication,
    val identity: IdentityProgress?,
    val identityAttemptsLeft: Int,
    val firstPayment: FirstPaymentInstructions?,
    val reviewReasons: List<String>,
)

/**
 * Experience v2 step 9: the account opening, orchestrated. The order is the bank's, not the app's:
 * party PENDING → identity (pid dedup, then Bank iD or document + chip + selfie, then registers) →
 * screening (name + date of birth + nationality) → risk → KYC (kyc-service decides: straight through
 * for a clean LOW-risk applicant, a person otherwise) → the accounts activate on the KYC decision.
 *
 * Nothing here passes by default. Every comparison names what it compared; every provider that
 * cannot answer stops the step (IdentityCheckUnavailable → 503), never waves it through.
 */
@ApplicationScoped
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
class AccountOpeningService(
    private val store: ApplicationStore,
    private val party: PartyMasterDataPort,
    private val identity: PartyIdentityPort,
    private val pid: PidPort,
    private val bankId: BankIdOidcPort,
    private val documents: DocumentVerificationPort,
    private val registers: RegistersPort,
    private val otherBank: OtherBankPort,
    private val screening: ScreeningPort,
    private val kyc: KycAssessmentPort,
    private val clock: Clock,
    private val json: ObjectMapper,
    @ConfigProperty(name = "openbank.onboarding.first-payment.cz.account", defaultValue = "2000145399")
    private val czAccount: String,
    @ConfigProperty(name = "openbank.onboarding.first-payment.cz.bank-code", defaultValue = "8899")
    private val czBankCode: String,
    @ConfigProperty(name = "openbank.onboarding.first-payment.cz.iban", defaultValue = "CZ6588990000002000145399")
    private val czIban: String,
    @ConfigProperty(name = "openbank.onboarding.first-payment.bg.account", defaultValue = "BG80EPRO96611020345678")
    private val bgIban: String,
    /** SANDBOX ONLY: the app may ask "the applicant's other bank" to send the first payment. */
    @ConfigProperty(name = "openbank.onboarding.first-payment.simulate-enabled", defaultValue = "false")
    private val simulateEnabled: Boolean,
) {
    private val log = Logger.getLogger(AccountOpeningService::class.java)
    private val random = SecureRandom()

    // ================================================================ personal details + identity resolution

    /**
     * The applicant's details. pid-service is asked first, and FAILS CLOSED: a person the bank already
     * has (another party, the same national number and name) is told so; an unclear match goes to a
     * four-eyes identity case. Only then is the party written (party-service refuses a minor, a taken
     * number and a number that does not encode the date of birth) and the person registered in pid.
     */
    @Suppress("ThrowsCount") // each refusal is a distinct answer for the applicant (invalid, minor, customer, review)
    fun savePersonalDetails(partyId: UUID, details: PersonalDetailsWrite) {
        val facts = facts(partyId)
        val market = Market.of(facts.market) ?: Market.DEFAULT
        val today = LocalDate.now(clock.withZone(PRAGUE))
        val nationalId = market.nationalId(details.nationalId, today)
            ?: throw IllegalArgumentException("birth_number is not a valid ${market.nationalIdName}")
        require(nationalId.birthdate.toString() == details.dateOfBirth) { "birth_number does not encode date_of_birth" }
        if (nationalId.ageOn(today) < IdentityComparison.ADULT) {
            throw ResourceConflictException("An account can be opened from the age of 18", "UNDERAGE")
        }
        val (given, family) = split(facts.legalName)
        val applicant =
            PidApplicant(given, family, details.dateOfBirth, nationalId.canonical, details.nationality, market.name)
        val resolution = pid.resolve(applicant)
        var app = application(partyId)
        when (resolution.decision) {
            PidDecision.MATCH_EXISTING -> if (resolution.partyId != partyId) {
                log.infof("Sign-up for party %s matches existing customer %s", partyId, resolution.partyId)
                throw ResourceConflictException(
                    "You are already a customer of the bank — please sign in with your existing login",
                    "ALREADY_CUSTOMER",
                )
            }
            PidDecision.NEEDS_MANUAL_VERIFICATION -> app = app.copy(
                pidDecision = resolution.decision.name,
                pidCaseId = resolution.caseId,
            )
            PidDecision.NO_MATCH -> app = app.copy(pidDecision = resolution.decision.name)
        }
        try {
            party.writePersonalDetails(partyId, details.copy(nationalId = nationalId.canonical))
        } catch (e: ResourceConflictException) {
            if (e.code == "ALREADY_CUSTOMER" && resolution.decision == PidDecision.NEEDS_MANUAL_VERIFICATION) {
                // The number belongs to a record pid could not match to this applicant: a person decides.
                store.upsertApplication(
                    app.copy(status = ApplicationStatus.IDENTITY_REVIEW, updatedAt = clock.instant()),
                )
                throw ResourceConflictException(
                    "We need to check your identity by hand; we will let you know",
                    "IDENTITY_REVIEW",
                )
            }
            throw e
        }
        pid.register(partyId, applicant)
        store.upsertApplication(app.copy(personalDetailsAt = clock.instant(), updatedAt = clock.instant()))
    }

    // ================================================================ identity: Bank iD (OIDC)

    /** Starts Bank iD (Czech market): an authorization-code + PKCE round trip through the applicant's bank. */
    fun startBankId(partyId: UUID): IdentityProgress {
        val facts = facts(partyId)
        if (facts.market !=
            "CZ"
        ) {
            throw ResourceConflictException(
                "Bank iD is available in the Czech Republic only",
                "METHOD_NOT_AVAILABLE",
            )
        }
        requireAttemptsLeft(partyId)
        val state = token(STATE_BYTES)
        val nonce = token(STATE_BYTES)
        val verifier = token(VERIFIER_BYTES)
        bankId.expectLogin(state, applicant(partyId, facts))
        val now = clock.instant()
        val v = IdentityVerification(
            UUID.randomUUID(), partyId, IdentityMethod.BANK_ID, null, "bankid:$state", IdentityStatus.IN_PROGRESS, null,
            now, now, oidcState = state, oidcVerifier = verifier, oidcNonce = nonce,
        )
        store.insertIdentityVerification(v)
        return IdentityProgress(v, emptyList(), redirectUrl = bankId.authorizeUrl(state, nonce, challenge(verifier)))
    }

    /**
     * The browser came back from Bank iD. The code is exchanged server-side with the PKCE verifier, and
     * the claims Bank iD vouches for are compared with the applicant — name, birthdate, birth number,
     * sex. A different person is a FAILED attempt (the applicant may retry or switch method).
     */
    fun bankIdCallback(code: String?, state: String?, error: String?): IdentityProgress {
        val v = state?.let { store.identityVerificationByState(it) }
            ?: throw ResourceNotFoundException("Unknown or expired Bank iD session", "VERIFICATION_NOT_FOUND")
        if (v.status != IdentityStatus.IN_PROGRESS) return progress(v)
        if (code.isNullOrBlank()) {
            return finish(
                v,
                listOf(IdentityCheck("BANK_ID", CheckOutcome.FAILED, "Bank iD: ${error ?: "cancelled"}")),
                "BANK_ID_CANCELLED",
            )
        }
        val claims = bankId.userinfo(bankId.exchange(code, requireNotNull(v.oidcVerifier)))
        val facts = facts(v.partyId)
        val applicant = applicant(v.partyId, facts)
        val checks = IdentityComparison.compare("Bank iD", applicant, claims) +
            IdentityComparison.ageCheck(claims.dateOfBirth, today())
        val evidence = mapOf(
            "bankId" to mapOf(
                "claims" to
                    mapOf(
                        "givenName" to claims.givenName,
                        "familyName" to claims.familyName,
                        "birthdate" to claims.dateOfBirth,
                        "gender" to claims.sex,
                    ),
                "matches" to checks.filter { it.type in setOf("NAME", "BIRTHDATE", "NATIONAL_ID", "SEX") }
                    .associate { matchKey(it.type) to (it.status == CheckOutcome.PASSED) },
            ),
        )
        return finish(
            v.copy(oidcState = null),
            listOf(IdentityCheck("BANK_ID", CheckOutcome.PASSED, "Bank iD authentication")) + checks,
            null,
            evidence,
        )
    }

    // ================================================================ identity: document + chip + selfie

    fun startDocument(partyId: UUID, documentType: String?, issuingCountry: String?): IdentityProgress {
        val facts = facts(partyId)
        val type = (documentType ?: "ID_CARD").uppercase()
        require(type in DOCUMENT_TYPES) { "document_type must be one of ${DOCUMENT_TYPES.joinToString()}" }
        requireAttemptsLeft(partyId)
        val now = clock.instant()
        val v = IdentityVerification(
            UUID.randomUUID(), partyId, IdentityMethod.DOCUMENT_SCAN, type, "document:pending",
            IdentityStatus.IN_PROGRESS, null, now, now,
            evidenceJson = json.writeValueAsString(
                mapOf(
                    "issuingCountry" to (issuingCountry ?: facts.market).uppercase(),
                ),
            ),
        )
        store.insertIdentityVerification(v)
        return progress(v)
    }

    /** The photographed document: OCR + MRZ, compared with the applicant, then expiry and the registers. */
    fun readDocument(partyId: UUID, id: UUID): IdentityProgress {
        val v = own(partyId, id, IdentityMethod.DOCUMENT_SCAN)
        if (v.status != IdentityStatus.IN_PROGRESS || v.documentId != null) return progress(v)
        val facts = facts(partyId)
        val applicant = applicant(partyId, facts)
        val issuing = (evidence(v)["issuingCountry"] as? String) ?: facts.market
        val doc = documents.readDocument(v.id.toString(), requireNotNull(v.documentType), issuing, applicant)
        val today = today()
        val expiry = doc.expiryDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val register = if (facts.market == "BG") {
            registers.bgDocument(requireNotNull(applicant.nationalId), doc.documentNumber)
        } else {
            registers.czInvalidDocument(doc.documentNumber)
        }
        val checks =
            listOf(IdentityCheck("DOCUMENT_OCR", CheckOutcome.PASSED, "document read (${doc.issuingCountry})")) +
                IdentityComparison.compare("Document", applicant, doc.ocr) +
                listOf(
                    IdentityCheck(
                        "MRZ",
                        outcome(doc.mrzValid),
                        if (doc.mrzValid) MRZ_OK else MRZ_BAD,
                    ),
                    IdentityCheck(
                        "DOCUMENT_EXPIRY",
                        outcome(expiry != null && !expiry.isBefore(today)),
                        expiry?.let { "valid until $it" } ?: "no expiry date read",
                    ),
                    IdentityCheck(
                        "DOCUMENT_REGISTER",
                        outcome(!register.record),
                        "${register.name}: ${register.detail}",
                    ),
                    IdentityComparison.ageCheck(doc.ocr.dateOfBirth, today),
                )
        val failure = documentFailure(checks)
        val document = mapOf(
            "type" to v.documentType,
            "issuingCountry" to doc.issuingCountry,
            "numberLast4" to doc.documentNumber.takeLast(LAST4),
            "number" to doc.documentNumber,
            "expiry" to doc.expiryDate,
            "registerListed" to register.record,
            "nationality" to doc.nationality,
        )
        val updated = v.copy(documentId = doc.id, providerRef = "document:${doc.id}")
        val ev =
            evidence(v) +
                mapOf("document" to document, "ocr" to personMap(doc.ocr), "registers" to listOf(registerMap(register)))
        return if (failure != null) finish(updated, checks, failure, ev) else saveProgress(updated, checks, ev)
    }

    /** The most specific reason a document check failed, or null when every check passed. */
    private fun documentFailure(checks: List<IdentityCheck>): String? {
        fun failed(type: String) = checks.any { it.type == type && it.status == CheckOutcome.FAILED }
        return when {
            failed("DOCUMENT_EXPIRY") -> "DOCUMENT_EXPIRED"
            failed("DOCUMENT_REGISTER") -> "DOCUMENT_INVALID"
            failed("AGE") -> "UNDERAGE"
            checks.any { it.status == CheckOutcome.FAILED } -> "DOCUMENT_MISMATCH"
            else -> null
        }
    }

    /** The NFC chip: passive authentication of the signed data, and the chip agreeing with the printed page. */
    fun readChip(partyId: UUID, id: UUID): IdentityProgress {
        val v = own(partyId, id, IdentityMethod.DOCUMENT_SCAN)
        if (v.status != IdentityStatus.IN_PROGRESS) return progress(v)
        val documentId = v.documentId ?: throw ResourceConflictException("Scan the document first", "DOCUMENT_REQUIRED")
        if (evidence(v).containsKey("chip")) return progress(v)
        val chip = documents.readChip(documentId)
        val ocr = evidence(v)["ocr"].asPerson()
        val printedNumber = (evidence(v)["document"] as? Map<*, *>)?.get("number") as? String
        val paValid = chip.passiveAuthentication == "VALID"
        val agrees =
            NameMatch.same(
                NameMatch.tokens(ocr.givenName, ocr.familyName),
                NameMatch.tokens(chip.dg1.givenName, chip.dg1.familyName),
            ) &&
                chip.dg1.dateOfBirth == ocr.dateOfBirth &&
                (chip.documentNumber == null || chip.documentNumber == printedNumber)
        val checks = previousChecks(v) + listOf(
            IdentityCheck(
                "CHIP_PA",
                outcome(paValid),
                "passive authentication ${chip.passiveAuthentication}${chip.signer?.let {
                    " ($it)"
                } ?: ""}",
            ),
            IdentityCheck(
                "CHIP_DATA",
                outcome(agrees),
                if (agrees) "chip data agrees with the printed page" else "chip data differs from the printed page",
            ),
        )
        val ev =
            evidence(v) +
                mapOf("chip" to mapOf("passiveAuthentication" to chip.passiveAuthentication, "signer" to chip.signer))
        return if (!paValid || !agrees) finish(v, checks, "CHIP_INVALID", ev) else saveProgress(v, checks, ev)
    }

    /** The selfie: liveness, and a face match against the CHIP photo (the one the state signed). */
    fun takeSelfie(partyId: UUID, id: UUID): IdentityProgress {
        val v = own(partyId, id, IdentityMethod.DOCUMENT_SCAN)
        if (v.status != IdentityStatus.IN_PROGRESS) return progress(v)
        val documentId = v.documentId ?: throw ResourceConflictException("Scan the document first", "DOCUMENT_REQUIRED")
        if (!evidence(
                v,
            ).containsKey("chip")
        ) {
            throw ResourceConflictException("Read the document's chip first", "CHIP_REQUIRED")
        }
        val result = documents.selfie(v.id.toString(), documentId)
        val liveness = FaceThresholds.liveness(result.liveness)
        val face = FaceThresholds.faceMatch(result.faceMatch)
        val checks = previousChecks(v) + listOf(liveness, face)
        val ev = evidence(v) + mapOf("face" to mapOf("liveness" to result.liveness, "match" to result.faceMatch))
        val failure = when {
            liveness.status == CheckOutcome.FAILED -> "LIVENESS_FAILED"
            face.status == CheckOutcome.FAILED -> "FACE_MISMATCH"
            else -> null
        }
        return finish(v.copy(needsReview = face.status == CheckOutcome.REVIEW), checks, failure, ev)
    }

    fun identityProgress(partyId: UUID, id: UUID): IdentityProgress {
        val v = store.identityVerification(id)?.takeIf { it.partyId == partyId }
            ?: throw ResourceNotFoundException("No such identity verification", "VERIFICATION_NOT_FOUND")
        return progress(v)
    }

    // ================================================================ the first payment (document path)

    fun firstPayment(partyId: UUID): FirstPaymentInstructions {
        val facts = facts(partyId)
        var app = application(partyId)
        val vs = app.firstPaymentReference ?: variableSymbol(partyId).also {
            app = app.copy(firstPaymentReference = it, updatedAt = clock.instant())
            store.upsertApplication(app)
        }
        val received = app.firstPaymentJson?.let { readMap(it) } ?: checkIncoming(partyId, facts, vs)
        return instructions(facts.market, vs, received)
    }

    /** SANDBOX: the applicant's account at another bank sends the first payment (their own name). */
    fun simulateFirstPayment(partyId: UUID): FirstPaymentInstructions {
        if (!simulateEnabled) throw ResourceNotFoundException("Not available", "NOT_FOUND")
        val facts = facts(partyId)
        val instructions = firstPayment(partyId)
        if (instructions.received == null) {
            val bg = facts.market == "BG"
            otherBank.send(
                OutgoingPayment(
                    payerName = facts.legalName,
                    payerAccount = if (bg) "BG18RZBB91550123456789" else "19-2000145399",
                    payerBankCode = if (bg) "RZBB" else "0800",
                    amount = instructions.amount, currency = instructions.currency,
                    creditorAccount = if (bg) bgIban else czAccount, creditorBankCode = if (bg) "EPRO" else czBankCode,
                    variableSymbol = instructions.variableSymbol, nationalId = identity.nationalId(partyId),
                ),
            )
        }
        return firstPayment(partyId)
    }

    private fun checkIncoming(partyId: UUID, facts: PartyFacts, vs: String): Map<String, Any?>? {
        val bg = facts.market == "BG"
        val payment = otherBank.incoming(if (bg) bgIban else czAccount, vs).firstOrNull() ?: return null
        val nameMatch = NameMatch.same(NameMatch.tokens(facts.legalName), NameMatch.tokens(payment.payerName))
        val ownBank = payment.payerBankCode in setOf(czBankCode, "EPRO")
        val received = mapOf(
            "payerName" to payment.payerName,
            "payerAccountMasked" to mask(payment.payerAccount),
            "payerBank" to (payment.payerBankName ?: payment.payerBankCode),
            "amount" to payment.amount.toPlainString(),
            "currency" to payment.currency,
            "nameMatch" to nameMatch,
            "fromAnotherBank" to !ownBank,
            "receivedAt" to (payment.receivedAt ?: clock.instant().toString()),
        )
        store.upsertApplication(
            application(
                partyId,
            ).copy(firstPaymentJson = json.writeValueAsString(received), updatedAt = clock.instant()),
        )
        return received
    }

    // ================================================================ submit: screening, risk, KYC

    /**
     * Everything the applicant gives is in: screen (sanctions and PEP separately, with date of birth and
     * nationality, monitored for list changes), check insolvency, score the risk and hand kyc-service the
     * assessment with its evidence. kyc-service decides — straight through or a person.
     */
    fun submit(partyId: UUID): AccountOpeningView {
        val app = application(partyId)
        if (app.status in DECIDED_OR_WAITING) return view(partyId)
        val ready = readiness(partyId, app)
        val facts = facts(partyId)
        val nationalId = requireNotNull(identity.nationalId(partyId)) { "the applicant has no national number" }
        val nationality = identity.nationality(partyId)
        val key = "onboarding:$partyId"
        val sanctions =
            screening.screen(
                partyId,
                facts.legalName,
                facts.dateOfBirth,
                nationality,
                SANCTIONS_LISTS,
                "$key:sanctions",
            )
        val pep =
            screening.screen(partyId, facts.legalName, facts.dateOfBirth, nationality, listOf("PEP_GLOBAL"), "$key:pep")
        val bg = facts.market == "BG"
        val insolvency = if (bg) registers.bgInsolvency(nationalId) else registers.czInsolvency(nationalId)
        val grao = if (bg) registers.bgPerson(nationalId) else null
        val graoChecks = grao?.let { IdentityComparison.compare("RegiX GRAO", applicant(partyId, facts), it) }.orEmpty()
        val risk = RiskModel.score(ready.profile, nationality, pep.status in HITS, ready.documentPath)
        val outcome = Outcome(ready, sanctions, pep, insolvency, graoChecks, risk, app.pidDecision)
        val reasons = reviewReasons(outcome)
        val decision = kyc.assess(partyId, assessmentBody(outcome, reasons, app))
        store.upsertApplication(
            app.copy(
                status = when (decision.status) {
                    "APPROVED" -> ApplicationStatus.APPROVED
                    "REJECTED" -> ApplicationStatus.REJECTED
                    else -> ApplicationStatus.IN_REVIEW
                },
                assessmentJson = json.writeValueAsString(
                    mapOf("reviewReasons" to reasons, "risk" to risk.rating, "kyc" to decision.status),
                ),
                kycCaseId = decision.caseId,
                submittedAt = clock.instant(),
                updatedAt = clock.instant(),
            ),
        )
        log.infof("Account opening for %s: risk %s, KYC %s, reasons %s", partyId, risk.rating, decision.status, reasons)
        return view(partyId)
    }

    /** What the applicant has given, or the 409 naming what is still missing. */
    private data class Readiness(
        val profile: FinancialProfile,
        val verified: IdentityVerification?,
        val failures: Int,
        val documentPath: Boolean,
        val firstPayment: Map<String, Any?>?,
    )

    private data class Outcome(
        val ready: Readiness,
        val sanctions: ScreeningOutcome,
        val pep: ScreeningOutcome,
        val insolvency: RegisterResult,
        val graoChecks: List<IdentityCheck>,
        val risk: RiskScore,
        val pidDecision: String?,
    )

    @Suppress("ThrowsCount") // one 409 per missing piece, each with its own code for the app
    private fun readiness(partyId: UUID, app: OnboardingApplication): Readiness {
        if (app.personalDetailsAt == null) {
            throw ResourceConflictException("Give your personal details first", "DETAILS_REQUIRED")
        }
        val profile = identity.financialProfile(partyId)
            ?: throw ResourceConflictException("Tell us about your money first", "FINANCIAL_PROFILE_REQUIRED")
        val verifications = store.identityVerifications(partyId)
        val verified = verifications.lastOrNull { it.status == IdentityStatus.VERIFIED }
        val failures = verifications.count { it.status == IdentityStatus.FAILED }
        if (verified == null && failures < MAX_ATTEMPTS) {
            throw ResourceConflictException("Verify your identity first", "IDENTITY_REQUIRED")
        }
        val documentPath = verified?.method != IdentityMethod.BANK_ID
        val first = if (documentPath && verified != null) firstPayment(partyId).received else null
        if (documentPath && verified != null && first == null) {
            val waiting = app.copy(status = ApplicationStatus.AWAITING_FIRST_PAYMENT, updatedAt = clock.instant())
            store.upsertApplication(waiting)
            throw ResourceConflictException("We have not received your first payment yet", "AWAITING_FIRST_PAYMENT")
        }
        return Readiness(profile, verified, failures, documentPath, first)
    }

    /** Why a person must decide; empty means the policy allows a straight-through approval. */
    private fun reviewReasons(o: Outcome): List<String> {
        val first = o.ready.firstPayment
        return listOfNotNull(
            "IDENTITY_NOT_VERIFIED".takeIf { o.ready.verified == null },
            "FACE_MATCH_GREY".takeIf { o.ready.verified?.needsReview == true },
            "IDENTITY_CASE_OPEN".takeIf { o.pidDecision == PidDecision.NEEDS_MANUAL_VERIFICATION.name },
            "SANCTIONS_${o.sanctions.status}".takeIf { o.sanctions.status in HITS },
            "PEP_${o.pep.status}".takeIf { o.pep.status in HITS },
            "PEP_DECLARED".takeIf { o.ready.profile.pep },
            "INSOLVENCY_RECORD".takeIf { o.insolvency.record },
            "REGISTER_MISMATCH".takeIf { o.graoChecks.any { it.status != CheckOutcome.PASSED } },
            "FIRST_PAYMENT_NAME_MISMATCH".takeIf { first != null && first["nameMatch"] != true },
            "FIRST_PAYMENT_NOT_ANOTHER_BANK".takeIf { first != null && first["fromAnotherBank"] != true },
            "RISK_${o.risk.rating}".takeIf { o.risk.rating != "LOW" },
        )
    }

    private fun assessmentBody(o: Outcome, reasons: List<String>, app: OnboardingApplication): Map<String, Any?> {
        val verified = o.ready.verified
        val identityOk = verified != null &&
            !verified.needsReview &&
            o.graoChecks.all { it.status == CheckOutcome.PASSED }
        val label = verified?.method?.label()
        return mapOf(
            "policyVersion" to ONBOARDING_POLICY,
            "riskLevel" to o.risk.rating,
            "dueDiligence" to o.risk.dueDiligence,
            "checks" to mapOf(
                "IDENTITY" to check(
                    identityOk,
                    label?.let { "$it verified" } ?: "not verified (${o.ready.failures} failed attempts)",
                ),
                "ADDRESS" to check(
                    verified != null,
                    "address confirmed with the verified identity (${label ?: "none"})",
                ),
                "SANCTIONS_SCREENING" to screened(o.sanctions),
                "PEP_SCREENING" to if (o.ready.profile.pep) manualReview("self-declared PEP") else screened(o.pep),
            ),
            "reviewReasons" to reasons,
            "evidence" to evidenceOf(o, app),
        )
    }

    private fun evidenceOf(o: Outcome, app: OnboardingApplication): Map<String, Any?> {
        val verified = o.ready.verified
        val identityEvidence = verified?.let { evidence(it) }.orEmpty()
        val grao = if (o.graoChecks.isEmpty()) {
            emptyList()
        } else {
            val match = o.graoChecks.all { it.status == CheckOutcome.PASSED }
            listOf(
                mapOf(
                    "name" to "REGIX_GRAO",
                    "result" to if (match) "MATCH" else "MISMATCH",
                    "detail" to "person register",
                ),
            )
        }
        val profile = o.ready.profile
        return mapOf(
            "method" to verified?.method?.let { if (it == IdentityMethod.BANK_ID) "BANK_ID" else "DOCUMENT" },
            "identityChecks" to checksJson(verified?.let { previousChecks(it) }.orEmpty() + o.graoChecks),
            "document" to (identityEvidence["document"] as? Map<*, *>)?.filterKeys { it != "number" },
            "chip" to identityEvidence["chip"],
            "face" to identityEvidence["face"],
            "bankId" to identityEvidence["bankId"],
            "registers" to
                (identityEvidence["registers"] as? List<*>).orEmpty() + registerMap(o.insolvency) + grao,
            "screening" to listOf(screeningMap(o.sanctions), screeningMap(o.pep)),
            "risk" to mapOf(
                "score" to o.risk.score,
                "rating" to o.risk.rating,
                "dueDiligence" to o.risk.dueDiligence,
                "factors" to o.risk.factors.map {
                    mapOf("code" to it.code, "points" to it.points, "label" to it.label)
                },
            ),
            "firstPayment" to o.ready.firstPayment,
            "pid" to mapOf("decision" to app.pidDecision, "caseId" to app.pidCaseId?.toString()),
            "profile" to mapOf(
                "purposes" to profile.purposes,
                "incomeSources" to profile.incomeSources,
                "occupation" to profile.occupation,
                "expectedMonthlyTurnover" to profile.expectedMonthlyTurnover,
            ),
        )
    }

    private fun manualReview(result: String) = mapOf("status" to "MANUAL_REVIEW", "result" to result)

    /** The application as the app shows it; a decision a person took since is read back from kyc-service. */
    fun view(partyId: UUID): AccountOpeningView {
        var app = application(partyId)
        if (app.status == ApplicationStatus.IN_REVIEW) {
            val now = kyc.status(partyId)?.status
            val decided = when (now) {
                "APPROVED" -> ApplicationStatus.APPROVED
                "REJECTED" -> ApplicationStatus.REJECTED
                else -> null
            }
            if (decided != null) {
                app = app.copy(status = decided, updatedAt = clock.instant())
                store.upsertApplication(app)
            }
        }
        val verifications = store.identityVerifications(partyId)
        val latest = verifications.lastOrNull()
        val failures = verifications.count { it.status == IdentityStatus.FAILED }
        val reasons = app.assessmentJson?.let {
            (readMap(it)["reviewReasons"] as? List<*>)?.map { r -> r.toString() }
        }.orEmpty()
        val docPath =
            verifications.lastOrNull { it.status == IdentityStatus.VERIFIED }?.method == IdentityMethod.DOCUMENT_SCAN
        return AccountOpeningView(
            app,
            latest?.let { progress(it) },
            (MAX_ATTEMPTS - failures).coerceAtLeast(0),
            if (docPath || app.firstPaymentReference != null) firstPayment(partyId) else null,
            reasons,
        )
    }

    // ================================================================ helpers

    private fun application(partyId: UUID): OnboardingApplication =
        store.application(partyId) ?: OnboardingApplication(partyId, null, null, null, null, null, clock.instant())

    private fun facts(partyId: UUID): PartyFacts =
        party.get(partyId) ?: throw ResourceNotFoundException("No such applicant", "APPLICATION_NOT_FOUND")

    private fun applicant(partyId: UUID, facts: PartyFacts): Person {
        val nationalId = identity.nationalId(partyId)
            ?: throw ResourceConflictException("Give your personal details first", "DETAILS_REQUIRED")
        val market = Market.of(facts.market) ?: Market.DEFAULT
        val (given, family) = split(facts.legalName)
        return Person(given, family, facts.dateOfBirth, nationalId, market.nationalId(nationalId, today())?.sex)
    }

    private fun own(partyId: UUID, id: UUID, method: IdentityMethod): IdentityVerification =
        store.identityVerification(id)?.takeIf { it.partyId == partyId && it.method == method }
            ?: throw ResourceNotFoundException("No such identity verification", "VERIFICATION_NOT_FOUND")

    private fun requireAttemptsLeft(partyId: UUID) {
        val all = store.identityVerifications(partyId)
        if (all.any { it.status == IdentityStatus.VERIFIED }) {
            throw ResourceConflictException("Your identity is already verified", "IDENTITY_VERIFIED")
        }
        if (all.count { it.status == IdentityStatus.FAILED } >= MAX_ATTEMPTS) {
            throw ResourceConflictException(
                "Too many attempts — a colleague will check your identity",
                "TOO_MANY_ATTEMPTS",
            )
        }
    }

    /** Saves the checks so far and keeps the verification IN_PROGRESS. */
    private fun saveProgress(
        v: IdentityVerification,
        checks: List<IdentityCheck>,
        evidence: Map<String, Any?>,
    ): IdentityProgress {
        val updated = v.copy(
            evidenceJson = json.writeValueAsString(evidence + ("checks" to checksJson(checks))),
            updatedAt = clock.instant(),
        )
        store.updateIdentityVerification(updated)
        return progress(updated)
    }

    /** Settles the verification: FAILED with [failure], else VERIFIED and recorded on the party. */
    private fun finish(
        v: IdentityVerification,
        checks: List<IdentityCheck>,
        failure: String?,
        evidence: Map<String, Any?> = evidence(v),
    ): IdentityProgress {
        val failed = failure != null || checks.any { it.status == CheckOutcome.FAILED }
        val now = clock.instant()
        val updated = v.copy(
            status = if (failed) IdentityStatus.FAILED else IdentityStatus.VERIFIED,
            failureReason = if (failed) failure ?: "IDENTITY_MISMATCH" else null,
            evidenceJson = json.writeValueAsString(evidence + ("checks" to checksJson(checks))),
            updatedAt = now,
        )
        store.updateIdentityVerification(updated)
        if (!failed) {
            val doc = evidence["document"] as? Map<*, *>
            identity.recordVerifiedIdentity(
                v.partyId,
                if (v.method == IdentityMethod.BANK_ID) "BANK_ID" else "DOCUMENT",
                doc?.let { if (v.documentType == "PASSPORT") "PASSPORT" else "NATIONAL_ID" },
                doc?.get("number") as? String,
                doc?.get("issuingCountry") as? String,
                doc?.get("expiry") as? String,
            )
            store.upsertApplication(
                application(v.partyId).copy(identityVerifiedAt = now, identityMethod = v.method, updatedAt = now),
            )
        }
        log.infof(
            "Identity %s for party %s by %s%s",
            updated.status,
            v.partyId,
            v.method,
            failure?.let { " ($it)" } ?: "",
        )
        return progress(updated)
    }

    private fun progress(v: IdentityVerification): IdentityProgress {
        val ev = evidence(v)

        @Suppress("UNCHECKED_CAST")
        val document = (ev["document"] as? Map<String, Any?>)?.filterKeys { it != "number" }
        return IdentityProgress(v, previousChecks(v), document = document)
    }

    private fun previousChecks(v: IdentityVerification): List<IdentityCheck> =
        (evidence(v)["checks"] as? List<*>).orEmpty().mapNotNull { c ->
            (c as? Map<*, *>)?.let {
                IdentityCheck(
                    it["type"].toString(),
                    CheckOutcome.valueOf(it["status"].toString()),
                    it["detail"]?.toString().orEmpty(),
                )
            }
        }

    private fun checksJson(checks: List<IdentityCheck>) = checks.map {
        mapOf(
            "type" to it.type,
            "status" to it.status.name,
            "detail" to it.detail,
        )
    }

    private fun evidence(v: IdentityVerification): Map<String, Any?> = v.evidenceJson?.let { readMap(it) }.orEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun readMap(s: String): Map<String, Any?> = json.readValue(s, Map::class.java) as Map<String, Any?>

    private fun Any?.asPerson(): Person = (this as? Map<*, *>).let {
        Person(
            it?.get("givenName") as? String,
            it?.get("familyName") as? String,
            it?.get("dateOfBirth") as? String,
            it?.get("nationalId") as? String,
            it?.get("sex") as? String,
        )
    }

    private fun personMap(p: Person) = mapOf(
        "givenName" to p.givenName,
        "familyName" to p.familyName,
        "dateOfBirth" to p.dateOfBirth,
        "nationalId" to p.nationalId?.let { mask(it) },
        "sex" to p.sex,
    )

    private fun registerMap(r: RegisterResult) = mapOf("name" to r.name, "result" to r.result, "detail" to r.detail)

    private fun screeningMap(s: ScreeningOutcome) = mapOf(
        "checkId" to s.checkId,
        "status" to s.status,
        "topScore" to s.topScore,
        "lists" to s.lists,
    )

    private fun screened(s: ScreeningOutcome) = mapOf(
        "status" to if (s.status in CLEAR) "PASSED" else "MANUAL_REVIEW",
        "result" to "${s.status} (${s.lists}, top score %.2f)".format(s.topScore),
    )

    private fun check(ok: Boolean, result: String) = mapOf(
        "status" to if (ok) "PASSED" else "MANUAL_REVIEW",
        "result" to result,
    )

    private fun instructions(market: String, vs: String, received: Map<String, Any?>?) = if (market == "BG") {
        FirstPaymentInstructions(bgIban, "EPRO", bgIban, BigDecimal.ONE, "EUR", vs, received)
    } else {
        FirstPaymentInstructions(czAccount, czBankCode, czIban, BigDecimal.ONE, "CZK", vs, received)
    }

    /** A stable 10-digit variable symbol per applicant (never derived from personal data). */
    private fun variableSymbol(partyId: UUID): String {
        val h = MessageDigest.getInstance("SHA-256").digest(partyId.toString().toByteArray())
        val n = h.take(VS_BYTES).fold(0L) { acc, b -> (acc shl BYTE_BITS) or (b.toLong() and BYTE_MASK) }
        return "9" + Math.floorMod(n, VS_SPACE).toString().padStart(VS_DIGITS, '0')
    }

    private fun today(): LocalDate = LocalDate.now(clock.withZone(PRAGUE))

    private fun token(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    private fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun matchKey(type: String) = when (type) {
        "NAME" -> "name"
        "BIRTHDATE" -> "birthdate"
        "NATIONAL_ID" -> "nationalId"
        else -> "sex"
    }

    private fun outcome(ok: Boolean) = if (ok) CheckOutcome.PASSED else CheckOutcome.FAILED

    private fun IdentityMethod.label() = if (this == IdentityMethod.BANK_ID) "Bank iD" else "document, chip and selfie"

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val STATE_BYTES = 24
        private const val VERIFIER_BYTES = 48
        private const val LAST4 = 4
        private const val VS_BYTES = 8
        private const val BYTE_BITS = 8
        private const val BYTE_MASK = 0xFFL
        private const val VS_SPACE = 1_000_000_000L
        private const val VS_DIGITS = 9
        private val PRAGUE: ZoneId = ZoneId.of("Europe/Prague")
        private const val MRZ_OK = "MRZ check digits valid, agree with the printed data"
        private const val MRZ_BAD = "MRZ check digits or data do not agree"
        val DOCUMENT_TYPES = setOf("ID_CARD", "PASSPORT", "RESIDENCE_PERMIT")
        val SANCTIONS_LISTS = listOf("OFAC_SDN", "EU_CONSOLIDATED", "UN_CONSOLIDATED", "HM_TREASURY", "CNB_DOMESTIC")
        private val HITS = setOf("HIT", "POTENTIAL_HIT", "ESCALATED")
        private val DECIDED_OR_WAITING =
            setOf(ApplicationStatus.APPROVED, ApplicationStatus.REJECTED, ApplicationStatus.IN_REVIEW)
        private val CLEAR = setOf("CLEAR", "WHITELISTED")

        fun split(legalName: String): Pair<String, String> {
            val parts = legalName.trim().split(Regex("\\s+"), limit = 2)
            return parts.first() to parts.getOrElse(1) { parts.first() }
        }

        fun mask(value: String): String = if (value.length <=
            LAST4
        ) {
            "****"
        } else {
            "*".repeat(value.length - LAST4) + value.takeLast(LAST4)
        }
    }
}
