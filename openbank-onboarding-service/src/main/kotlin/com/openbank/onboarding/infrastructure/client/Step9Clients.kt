// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.onboarding.application.port.out.BankIdOidcPort
import com.openbank.onboarding.application.port.out.BankIdTokens
import com.openbank.onboarding.application.port.out.ChipRead
import com.openbank.onboarding.application.port.out.DocumentRead
import com.openbank.onboarding.application.port.out.DocumentVerificationPort
import com.openbank.onboarding.application.port.out.IdentityCheckUnavailable
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
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.net.URLEncoder
import java.util.UUID

// Experience v2 step 9 adapters. Each one FAILS CLOSED: a dependency that does not answer becomes
// IdentityCheckUnavailable (503 to the app), never a silent pass.

private fun <T> closed(what: String, call: () -> T): T = try {
    call()
} catch (e: WebApplicationException) {
    if ((e.response?.status ?: 0) >= HTTP_SERVER_ERROR) throw IdentityCheckUnavailable(what, e) else throw e
} catch (e: ProcessingException) {
    throw IdentityCheckUnavailable(what, e)
}

private const val HTTP_SERVER_ERROR = 500
private const val HTTP_NOT_FOUND_9 = 404

// ---------------------------------------------------------------- pid-service

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PidResolveDto(
    val givenName: String,
    val familyName: String,
    val birthdate: String,
    val birthNumberRaw: String,
    val nationalities: List<String>,
    val market: String,
    val partyId: UUID? = null,
    val partyType: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PidResolveResponse(val decision: String = "", val partyId: UUID? = null, val caseId: UUID? = null)

@RegisterRestClient(configKey = "pid-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface PidClient {
    @POST
    @Path("/resolve")
    fun resolve(body: PidResolveDto): PidResolveResponse

    @POST
    @Path("/register-identity")
    fun register(body: PidResolveDto)
}

@ApplicationScoped
class PidAdapter(@RestClient private val client: PidClient) : PidPort {
    override fun resolve(applicant: PidApplicant): PidResolution = closed("Identity resolution") {
        val r = client.resolve(dto(applicant))
        val decision = PidDecision.entries.firstOrNull { it.name == r.decision }
            ?: throw IdentityCheckUnavailable("Identity resolution (answered '${r.decision}')")
        PidResolution(decision, r.partyId, r.caseId)
    }

    override fun register(partyId: UUID, applicant: PidApplicant) = closed("Identity registration") {
        client.register(dto(applicant).copy(partyId = partyId, partyType = "NATURAL_PERSON"))
    }

    private fun dto(a: PidApplicant) = PidResolveDto(
        a.givenName,
        a.familyName,
        a.birthdate,
        a.nationalId,
        listOfNotNull(a.nationality),
        a.market,
    )
}

// ---------------------------------------------------------------- Bank iD (OIDC)

data class ExpectedLoginDto(val state: String, val applicant: ApplicantDto)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TokenDto(@JsonProperty("access_token") val accessToken: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class UserinfoDto(
    @JsonProperty("given_name") val givenName: String? = null,
    @JsonProperty("family_name") val familyName: String? = null,
    val birthdate: String? = null,
    val gender: String? = null,
    val birthnumber: String? = null,
)

/** The simulator's side channel (machine-authenticated like every provider call). */
@RegisterRestClient(configKey = "bank-id")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/bankid/oidc")
@Consumes(MediaType.APPLICATION_JSON)
interface BankIdSimulatorClient {
    @POST
    @Path("/expected-logins")
    fun expect(body: ExpectedLoginDto)
}

/** The OIDC endpoints proper: the bank's client credentials, not the platform's token. */
@RegisterRestClient(configKey = "bank-id")
@Path("/bankid")
@Produces(MediaType.APPLICATION_JSON)
interface BankIdOidcClient {
    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Suppress("LongParameterList") // the token request's form fields, RFC 6749 §4.1.3 + PKCE
    fun token(
        @FormParam("grant_type") grantType: String,
        @FormParam("code") code: String,
        @FormParam("redirect_uri") redirectUri: String,
        @FormParam("code_verifier") codeVerifier: String,
        @FormParam("client_id") clientId: String,
        @FormParam("client_secret") clientSecret: String,
    ): TokenDto

    @GET
    @Path("/userinfo")
    fun userinfo(@HeaderParam("Authorization") authorization: String): UserinfoDto
}

@ApplicationScoped
class BankIdOidcAdapter(
    @RestClient private val simulator: BankIdSimulatorClient,
    @RestClient private val oidc: BankIdOidcClient,
    @ConfigProperty(name = "openbank.onboarding.bank-id.public-url", defaultValue = "http://localhost:8163")
    private val publicUrl: String,
    @ConfigProperty(name = "openbank.onboarding.bank-id.client-id", defaultValue = "openbank-onboarding")
    private val clientId: String,
    @ConfigProperty(name = "openbank.onboarding.bank-id.client-secret", defaultValue = "sandbox-bank-id-secret")
    private val clientSecret: String,
    @ConfigProperty(
        name = "openbank.onboarding.bank-id.redirect-uri",
        defaultValue = "http://localhost:8128/customer/v1/onboarding/bank-id/callback",
    )
    private val redirectUri: String,
) : BankIdOidcPort {
    override fun expectLogin(state: String, applicant: Person) = closed("Bank iD") {
        simulator.expect(
            ExpectedLoginDto(
                state,
                ApplicantDto(applicant.givenName, applicant.familyName, applicant.dateOfBirth, applicant.nationalId),
            ),
        )
    }

    override fun authorizeUrl(state: String, nonce: String, codeChallenge: String): String {
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
        return "${publicUrl.trimEnd('/')}/bankid/auth?response_type=code&client_id=${enc(clientId)}" +
            "&redirect_uri=${enc(redirectUri)}&scope=${enc(SCOPE)}&state=${enc(state)}&nonce=${enc(nonce)}" +
            "&code_challenge=${enc(codeChallenge)}&code_challenge_method=S256"
    }

    override fun exchange(code: String, codeVerifier: String): BankIdTokens = closed("Bank iD") {
        BankIdTokens(
            oidc.token("authorization_code", code, redirectUri, codeVerifier, clientId, clientSecret).accessToken,
        )
    }

    override fun userinfo(tokens: BankIdTokens): Person = closed("Bank iD") {
        val u = oidc.userinfo("Bearer ${tokens.accessToken}")
        Person(
            u.givenName,
            u.familyName,
            u.birthdate,
            u.birthnumber,
            u.gender?.let {
                if (it.startsWith("f")) "F" else "M"
            },
        )
    }

    private companion object {
        const val SCOPE = "openid profile.name profile.birthdate profile.birthnumber profile.gender"
    }
}

// ---------------------------------------------------------------- IDV: document, chip, selfie

data class DocumentRequestDto(
    val reference: String,
    @JsonProperty("document_type") val documentType: String,
    @JsonProperty("issuing_country") val issuingCountry: String,
    val applicant: ApplicantDto,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OcrDto(
    @JsonProperty("given_name") val givenName: String? = null,
    @JsonProperty("family_name") val familyName: String? = null,
    @JsonProperty("date_of_birth") val dateOfBirth: String? = null,
    val sex: String? = null,
    @JsonProperty("national_id") val nationalId: String? = null,
    @JsonProperty("document_number") val documentNumber: String? = null,
    @JsonProperty("issuing_country") val issuingCountry: String? = null,
    val nationality: String? = null,
    @JsonProperty("expiry_date") val expiryDate: String? = null,
    @JsonProperty("check_digits_valid") val checkDigitsValid: Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DocumentDto(val id: String = "", val ocr: OcrDto = OcrDto(), val mrz: OcrDto = OcrDto())

@JsonIgnoreProperties(ignoreUnknown = true)
data class PassiveAuthDto(val status: String = "", val signer: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChipDto(
    val dg1: OcrDto = OcrDto(),
    @JsonProperty("passive_authentication") val passiveAuthentication: PassiveAuthDto = PassiveAuthDto(),
)

data class SelfieRequestDto(val reference: String, @JsonProperty("document_id") val documentId: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScoreDto(val score: Double = 0.0)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SelfieDto(
    val liveness: ScoreDto = ScoreDto(),
    @JsonProperty("face_match") val faceMatch: ScoreDto = ScoreDto(),
)

@RegisterRestClient(configKey = "idv")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/idv")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface IdvDocumentClient {
    @POST
    @Path("/documents")
    fun document(body: DocumentRequestDto): DocumentDto

    @POST
    @Path("/documents/{id}/chip")
    fun chip(@PathParam("id") id: String, body: Map<String, Any?>): ChipDto

    @POST
    @Path("/selfies")
    fun selfie(body: SelfieRequestDto): SelfieDto
}

private fun OcrDto.person() = Person(givenName, familyName, dateOfBirth, nationalId, sex)

@ApplicationScoped
class DocumentVerificationAdapter(@RestClient private val client: IdvDocumentClient) : DocumentVerificationPort {
    override fun readDocument(reference: String, documentType: String, issuingCountry: String, applicant: Person) =
        closed("Document check") {
            val d = client.document(
                DocumentRequestDto(
                    reference,
                    documentType,
                    issuingCountry,
                    ApplicantDto(
                        applicant.givenName,
                        applicant.familyName,
                        applicant.dateOfBirth,
                        applicant.nationalId,
                    ),
                ),
            )
            DocumentRead(
                d.id,
                d.ocr.person(),
                d.ocr.documentNumber.orEmpty(),
                d.ocr.issuingCountry ?: issuingCountry,
                d.ocr.nationality,
                d.ocr.expiryDate,
                mrzValid = d.mrz.checkDigitsValid == true && d.mrz.documentNumber == d.ocr.documentNumber,
            )
        }

    override fun readChip(documentId: String) = closed("Document chip") {
        val c = client.chip(documentId, emptyMap())
        ChipRead(c.dg1.person(), c.dg1.documentNumber, c.passiveAuthentication.status, c.passiveAuthentication.signer)
    }

    override fun selfie(reference: String, documentId: String) = closed("Selfie check") {
        val s = client.selfie(SelfieRequestDto(reference, documentId))
        SelfieResult(s.liveness.score, s.faceMatch.score)
    }
}

// ---------------------------------------------------------------- public registers

@JsonIgnoreProperties(ignoreUnknown = true)
data class InvalidDocumentDto(val evidovan: Boolean = false, val duvod: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IsirDto(val zaznamy: List<Map<String, Any?>> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class GraoDto(
    @JsonProperty("first_name") val firstName: String? = null,
    @JsonProperty("sur_name") val surName: String? = null,
    @JsonProperty("family_name") val familyName: String? = null,
    @JsonProperty("birth_date") val birthDate: String? = null,
    val gender: String? = null,
    val egn: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MvrDocumentDto(val status: String = "", @JsonProperty("valid_to") val validTo: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProceedingsDto(val proceedings: List<Map<String, Any?>> = emptyList())

@RegisterRestClient(configKey = "registers")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface RegistersClient {
    @GET
    @Path("/mvcr/neplatne-doklady")
    fun invalidDocument(@QueryParam("doklad") number: String): InvalidDocumentDto

    @GET
    @Path("/isir/osoby")
    fun isir(@QueryParam("rc") rc: String): IsirDto

    @POST
    @Path("/regix/operations/GRAO.PersonDataSearch")
    fun grao(body: Map<String, String>): GraoDto

    @POST
    @Path("/regix/operations/MVR.GetPersonalIdentityV2")
    fun mvr(body: Map<String, String>): MvrDocumentDto

    @POST
    @Path("/regix/operations/BG.InsolvencyCheck")
    fun bgInsolvency(body: Map<String, String>): ProceedingsDto
}

@ApplicationScoped
class RegistersAdapter(@RestClient private val client: RegistersClient) : RegistersPort {
    override fun czInvalidDocument(documentNumber: String) = closed("Register of invalid documents") {
        val r = client.invalidDocument(documentNumber)
        RegisterResult(
            "MVCR_INVALID_DOCUMENTS",
            if (r.evidovan) "LISTED" else "NO_RECORD",
            if (r.evidovan) "listed as invalid (${r.duvod ?: "no reason given"})" else "not listed",
        )
    }

    override fun czInsolvency(nationalId: String) = closed("Insolvency register") {
        val r = client.isir(nationalId)
        RegisterResult(
            "ISIR",
            if (r.zaznamy.isEmpty()) "NO_RECORD" else "RECORD",
            if (r.zaznamy.isEmpty()) {
                "no insolvency proceedings"
            } else {
                r.zaznamy.joinToString {
                    "${it["spisova_znacka"]} ${it["stav"]}"
                }
            },
        )
    }

    override fun bgPerson(egn: String): Person? = closed("RegiX GRAO") {
        try {
            val p = client.grao(mapOf("egn" to egn))
            Person(p.firstName, listOfNotNull(p.familyName).joinToString(" "), p.birthDate, p.egn, p.gender)
        } catch (e: WebApplicationException) {
            if (e.response?.status == HTTP_NOT_FOUND_9) null else throw e
        }
    }

    override fun bgDocument(egn: String, documentNumber: String) = closed("RegiX MVR") {
        val r = client.mvr(mapOf("egn" to egn, "document_number" to documentNumber))
        RegisterResult(
            "REGIX_MVR",
            if (r.status == "VALID") "NO_RECORD" else "LISTED",
            "identity document ${r.status.lowercase()}${r.validTo?.let { ", valid to $it" } ?: ""}",
        )
    }

    override fun bgInsolvency(egn: String) = closed("RegiX insolvency") {
        val r = client.bgInsolvency(mapOf("egn" to egn))
        RegisterResult(
            "REGIX_INSOLVENCY",
            if (r.proceedings.isEmpty()) "NO_RECORD" else "RECORD",
            if (r.proceedings.isEmpty()) "no insolvency proceedings" else "${r.proceedings.size} proceeding(s)",
        )
    }
}

// ---------------------------------------------------------------- the other bank

data class OtherBankPaymentDto(
    @JsonProperty("payer_name") val payerName: String,
    @JsonProperty("payer_account") val payerAccount: String,
    @JsonProperty("payer_bank_code") val payerBankCode: String,
    val amount: String,
    val currency: String,
    @JsonProperty("creditor_account") val creditorAccount: String,
    @JsonProperty("creditor_bank_code") val creditorBankCode: String,
    @JsonProperty("variable_symbol") val variableSymbol: String,
    @JsonProperty("national_id") val nationalId: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IncomingDto(
    val id: String = "",
    @JsonProperty("payer_name") val payerName: String = "",
    @JsonProperty("payer_account") val payerAccount: String = "",
    @JsonProperty("payer_bank_code") val payerBankCode: String = "",
    @JsonProperty("payer_bank_name") val payerBankName: String? = null,
    val amount: BigDecimal = BigDecimal.ZERO,
    val currency: String = "",
    @JsonProperty("received_at") val receivedAt: String? = null,
)

@RegisterRestClient(configKey = "other-bank")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/other-bank")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface OtherBankClient {
    @POST
    @Path("/payments")
    fun send(body: OtherBankPaymentDto)

    @GET
    @Path("/incoming")
    fun incoming(
        @QueryParam("creditor_account") creditorAccount: String,
        @QueryParam("variable_symbol") variableSymbol: String,
    ): List<IncomingDto>
}

@ApplicationScoped
class OtherBankAdapter(@RestClient private val client: OtherBankClient) : OtherBankPort {
    override fun send(payment: OutgoingPayment) = closed("The other bank") {
        with(payment) {
            client.send(
                OtherBankPaymentDto(
                    payerName, payerAccount, payerBankCode, amount.toPlainString(), currency,
                    creditorAccount, creditorBankCode, variableSymbol, nationalId,
                ),
            )
        }
    }

    override fun incoming(creditorAccount: String, variableSymbol: String) = closed("Incoming payments") {
        client.incoming(creditorAccount, variableSymbol).map {
            IncomingPayment(
                it.id,
                it.payerName,
                it.payerAccount,
                it.payerBankCode,
                it.payerBankName,
                it.amount,
                it.currency,
                it.receivedAt,
            )
        }
    }
}

// ---------------------------------------------------------------- sanctions-service

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScreenDto(
    val idempotencyKey: String,
    val entityType: String,
    val name: String,
    val dateOfBirth: String?,
    val nationality: String?,
    val identifiers: Map<String, String>,
    val listTypes: List<String>,
    val monitor: Boolean,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScreenResponseDto(val id: String? = null, val status: String = "", val overallScore: Double? = null)

@RegisterRestClient(configKey = "sanctions-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/sanctions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface SanctionsClient {
    @POST
    @Path("/screen")
    fun screen(body: ScreenDto): ScreenResponseDto
}

@ApplicationScoped
class ScreeningAdapter(@RestClient private val client: SanctionsClient) : ScreeningPort {
    override fun screen(
        partyId: UUID,
        name: String,
        dateOfBirth: String?,
        nationality: String?,
        lists: List<String>,
        key: String,
    ) = closed("Screening") {
        val r = client.screen(
            ScreenDto(
                key,
                "INDIVIDUAL",
                name,
                dateOfBirth,
                nationality,
                mapOf("partyId" to partyId.toString()),
                lists,
                true,
            ),
        )
        ScreeningOutcome(r.id, r.status, r.overallScore ?: 0.0, lists.joinToString(","))
    }
}

// ---------------------------------------------------------------- kyc-service

@JsonIgnoreProperties(ignoreUnknown = true)
data class KycCaseDto(val id: String? = null, val status: String = "", val reviewedBy: String? = null)

@RegisterRestClient(configKey = "kyc-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/kyc/cases/party")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface KycClient {
    @POST
    @Path("/{partyId}/assessment")
    fun assess(@PathParam("partyId") partyId: UUID, body: Map<String, Any?>): KycCaseDto

    @GET
    @Path("/{partyId}")
    fun latest(@PathParam("partyId") partyId: UUID): KycCaseDto
}

@ApplicationScoped
class KycAssessmentAdapter(@RestClient private val client: KycClient) : KycAssessmentPort {
    override fun assess(partyId: UUID, body: Map<String, Any?>) = closed("KYC") {
        client.assess(partyId, body).let { KycDecision(it.id, it.status, it.reviewedBy) }
    }

    override fun status(partyId: UUID): KycDecision? = closed("KYC") {
        try {
            client.latest(partyId).let { KycDecision(it.id, it.status, it.reviewedBy) }
        } catch (e: WebApplicationException) {
            if (e.response?.status == HTTP_NOT_FOUND_9) null else throw e
        }
    }
}

// ---------------------------------------------------------------- party-service extras

@JsonIgnoreProperties(ignoreUnknown = true)
data class NationalIdDto(val nationalId: String? = null)

data class VerifiedIdentityDto(
    val method: String,
    val documentType: String?,
    val documentNumber: String?,
    val issuingCountry: String?,
    val expiryDate: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AmlPepDto(val isPep: Boolean = false)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AmlProfileDto(
    val purpose: List<String> = emptyList(),
    val incomeSources: List<String> = emptyList(),
    val occupation: String = "OTHER",
    val expectedMonthlyTurnover: String = "UP_TO_30K",
    val cashIntensive: Boolean = false,
    val pep: AmlPepDto = AmlPepDto(),
    val taxResidencies: List<Any?> = emptyList(),
    val usPerson: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyNationalityDto(val nationality: String? = null)

@RegisterRestClient(configKey = "party-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/parties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface PartyIdentityClient {
    @GET
    @Path("/{id}/identity/national-id")
    fun nationalId(@PathParam("id") id: UUID): NationalIdDto

    @PUT
    @Path("/{id}/identity/verified")
    fun verified(@PathParam("id") id: UUID, body: VerifiedIdentityDto)

    @GET
    @Path("/{id}/aml-profile")
    fun amlProfile(@PathParam("id") id: UUID): AmlProfileDto

    @GET
    @Path("/{id}")
    fun party(@PathParam("id") id: UUID): PartyNationalityDto
}

@ApplicationScoped
class PartyIdentityAdapter(@RestClient private val client: PartyIdentityClient) : PartyIdentityPort {
    override fun nationalId(partyId: UUID) = closed("Party") { client.nationalId(partyId).nationalId }

    @Suppress("LongParameterList")
    override fun recordVerifiedIdentity(
        partyId: UUID,
        method: String,
        documentType: String?,
        documentNumber: String?,
        issuingCountry: String?,
        expiryDate: String?,
    ) = closed("Party") {
        client.verified(partyId, VerifiedIdentityDto(method, documentType, documentNumber, issuingCountry, expiryDate))
    }

    override fun financialProfile(partyId: UUID): FinancialProfile? = closed("Party") {
        try {
            client.amlProfile(partyId).let { p ->
                FinancialProfile(
                    purposes = p.purpose.toSet(),
                    incomeSources = p.incomeSources.toSet(),
                    occupation = p.occupation,
                    expectedMonthlyTurnover = p.expectedMonthlyTurnover,
                    cashIntensive = p.cashIntensive,
                    pep = p.pep.isPep,
                    taxResidencies = p.taxResidencies.mapNotNull { r ->
                        when (r) {
                            is String -> r
                            is Map<*, *> -> r["country"]?.toString()
                            else -> null
                        }
                    },
                    usPerson = p.usPerson,
                )
            }
        } catch (e: WebApplicationException) {
            if (e.response?.status == HTTP_NOT_FOUND_9) null else throw e
        }
    }

    override fun nationality(partyId: UUID): String? = closed("Party") { client.party(partyId).nationality }
}
