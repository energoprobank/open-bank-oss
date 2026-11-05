// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.libs.authz.Authorize
import com.openbank.onboarding.application.port.out.PersonalDetailsWrite
import com.openbank.onboarding.application.port.out.ProviderCheck
import com.openbank.onboarding.application.port.out.TaxResidencyWrite
import com.openbank.onboarding.application.usecase.IdentityView
import com.openbank.onboarding.application.usecase.LivenessService
import com.openbank.onboarding.application.usecase.OnboardingApplicationService
import com.openbank.onboarding.domain.model.IdentityMethod
import com.openbank.onboarding.domain.model.LivenessPolicy
import com.openbank.onboarding.domain.model.LivenessPurpose
import com.openbank.onboarding.domain.model.LivenessSession
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.math.BigDecimal
import java.util.UUID

// Experience v2 step 8 — self-service account opening, write side. Machine callers only: customer-edge,
// which takes the party id from the customer's token (never from the app's path) and calls these with
// its M2M token. Wire names are snake_case, the same as the app's contract, so the edge passes bodies on.

private const val MACHINE = "ROLE_API"

/** E-mail + phone check before the applicant has a login. */
@Path("/api/v1/onboarding/contact-verifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(MACHINE)
@Blocking
class ContactVerificationResource(private val service: OnboardingApplicationService) {

    @POST
    @Authorize(action = "onboarding.contact-verification.create")
    @Operation(summary = "Send one-time codes to an e-mail and a phone")
    fun send(body: SendCodesRequest?): Response {
        val req = requireNotNull(body) { "request body is required" }
        val sent = service.sendContactCodes(
            requireNotNull(req.email) { "email is required" },
            requireNotNull(req.phone) { "phone is required" },
        )
        return Response.status(Response.Status.CREATED)
            .entity(
                CodesSentView(sent.id.toString(), sent.expiresInSeconds, sent.demoEmailCode, sent.demoPhoneCode),
            ).build()
    }

    @POST
    @Path("/{id}/confirm")
    @Authorize(action = "onboarding.contact-verification.confirm")
    @Operation(summary = "Check the codes the applicant typed")
    fun confirm(@PathParam("id") id: UUID, body: ConfirmCodesRequest?): CodesCheckedView {
        val result = service.confirmContactCodes(id, body?.emailCode, body?.phoneCode)
        return CodesCheckedView(result.emailVerified, result.phoneVerified)
    }
}

/** Name and supply address from Energo-Pro, released against a confirmed contact. */
@Path("/api/v1/onboarding/energo-pro")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(MACHINE)
@Blocking
class EnergoProPrefillResource(private val service: OnboardingApplicationService) {

    @POST
    @Path("/prefill")
    @Authorize(action = "onboarding.energo-pro.prefill")
    @Operation(summary = "Energo-Pro customer prefill (requires a confirmed e-mail or phone that is on the record)")
    fun prefill(body: PrefillRequest?): PrefillView {
        val req = requireNotNull(body) { "request body is required" }
        val verification = requireNotNull(req.contactVerificationId) { "contact_verification_id is required" }
        val record = service.energoProPrefill(
            requireNotNull(req.customerNumber) { "customer_number is required" },
            runCatching {
                UUID.fromString(verification)
            }.getOrElse { throw IllegalArgumentException("contact_verification_id is not valid") },
        )
        return PrefillView(record.firstName, record.lastName, record.street, record.city, record.postalCode)
    }
}

/** The application of one applicant (party id from the customer's token). */
@Path("/api/v1/onboarding/applications/{partyId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(MACHINE)
@Blocking
class OnboardingApplicationResource(private val service: OnboardingApplicationService) {

    @GET
    @Authorize(action = "onboarding.application.read", resource = "#partyId")
    @Operation(summary = "The application's own state")
    fun get(@PathParam("partyId") partyId: UUID): ApplicationView {
        val a = service.application(partyId)
        return ApplicationView(
            partyId.toString(),
            a.packageCode,
            a.personalDetailsAt?.toString(),
            a.taxResidencyAt?.toString(),
            a.identityVerifiedAt?.toString(),
            a.identityMethod?.name,
        )
    }

    @PUT
    @Path("/personal-details")
    @Authorize(action = "onboarding.application.update", resource = "#partyId")
    @Operation(summary = "Date of birth, national number, phone and address (written through to party-service)")
    fun personalDetails(@PathParam("partyId") partyId: UUID, body: PersonalDetailsRequest?): Response {
        val req = requireNotNull(body) { "request body is required" }
        val address = requireNotNull(req.address) { "address is required" }
        service.savePersonalDetails(
            partyId,
            PersonalDetailsWrite(
                dateOfBirth = requireNotNull(req.dateOfBirth) { "date_of_birth is required" },
                nationalId = requireNotNull(req.birthNumber) { "birth_number is required" },
                phone = requireNotNull(req.phone) { "phone is required" },
                street = requireNotNull(address.street) { "address.street is required" },
                city = requireNotNull(address.city) { "address.city is required" },
                postalCode = requireNotNull(address.postalCode) { "address.postal_code is required" },
                country = requireNotNull(address.country) { "address.country is required" }.uppercase(),
            ),
        )
        return Response.noContent().build()
    }

    @PUT
    @Path("/tax-residency")
    @Authorize(action = "onboarding.application.update", resource = "#partyId")
    @Operation(summary = "FATCA/CRS self-certification (written through to party-service)")
    fun taxResidency(@PathParam("partyId") partyId: UUID, body: TaxResidencyRequest?): Response {
        val req = requireNotNull(body) { "request body is required" }
        service.saveTaxResidency(
            partyId,
            requireNotNull(req.usPerson) { "us_person is required" },
            requireNotNull(req.residencies) { "residencies is required" }
                .map { TaxResidencyWrite(requireNotNull(it.country) { "residencies[].country is required" }, it.tin) },
        )
        return Response.noContent().build()
    }

    @PUT
    @Path("/package")
    @Authorize(action = "onboarding.application.update", resource = "#partyId")
    @Operation(summary = "The account package the applicant chose")
    fun choosePackage(@PathParam("partyId") partyId: UUID, body: PackageRequest?): Response {
        service.choosePackage(partyId, requireNotNull(body?.packageCode) { "package_code is required" })
        return Response.noContent().build()
    }

    @POST
    @Path("/identity-verifications")
    @Authorize(action = "onboarding.application.update", resource = "#partyId")
    @Operation(summary = "Start Bank iD (CZ) or a document scan with liveness")
    fun startIdentity(@PathParam("partyId") partyId: UUID, body: IdentityRequest?): Response {
        val req = requireNotNull(body) { "request body is required" }
        val method = runCatching { IdentityMethod.valueOf(requireNotNull(req.method) { "method is required" }) }
            .getOrElse { throw IllegalArgumentException("method must be BANK_ID or DOCUMENT_SCAN") }
        val view = service.startIdentityVerification(partyId, method, req.documentType)
        return Response.status(Response.Status.CREATED).entity(IdentityVerificationView.of(view)).build()
    }

    @GET
    @Path("/identity-verifications/{id}")
    @Authorize(action = "onboarding.application.read", resource = "#partyId")
    @Operation(summary = "An identity verification's state (asks the provider while it is in progress)")
    fun identity(@PathParam("partyId") partyId: UUID, @PathParam("id") id: UUID): IdentityVerificationView =
        IdentityVerificationView.of(service.identityVerification(partyId, id))
}

/** Face check for large payments: the customer's limit and the single-use liveness sessions. */
@Path("/api/v1/security/liveness/parties/{partyId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(MACHINE)
@Blocking
class LivenessResource(private val service: LivenessService) {

    @GET
    @Path("/policy")
    @Authorize(action = "liveness.policy.read", resource = "#partyId")
    fun policy(@PathParam("partyId") partyId: UUID): PolicyView = PolicyView.of(service.policy(partyId))

    @PUT
    @Path("/policy")
    @Authorize(action = "liveness.policy.update", resource = "#partyId")
    fun updatePolicy(@PathParam("partyId") partyId: UUID, body: PolicyRequest?): PolicyView {
        val req = requireNotNull(body) { "request body is required" }
        return PolicyView.of(
            service.updatePolicy(
                partyId,
                requireNotNull(req.enabled) { "enabled is required" },
                requireNotNull(req.threshold) { "threshold is required" },
                uuid(requireNotNull(req.sessionId) { "session_id is required" }, "session_id"),
            ),
        )
    }

    @POST
    @Path("/sessions")
    @Authorize(action = "liveness.session.create", resource = "#partyId")
    fun start(@PathParam("partyId") partyId: UUID, body: SessionRequest?): Response {
        val purpose = runCatching { LivenessPurpose.valueOf(requireNotNull(body?.purpose) { "purpose is required" }) }
            .getOrElse { throw IllegalArgumentException("purpose must be POLICY_CHANGE or PAYMENT") }
        return Response.status(Response.Status.CREATED)
            .entity(SessionView.of(service.start(partyId, purpose, body?.paymentReference))).build()
    }

    @GET
    @Path("/sessions/{id}")
    @Authorize(action = "liveness.session.read", resource = "#partyId")
    fun session(@PathParam("partyId") partyId: UUID, @PathParam("id") id: UUID): SessionView =
        SessionView.of(service.session(partyId, id))

    /** customer-edge spends a PAYMENT session on the payment that carries it (409 LIVENESS_REQUIRED otherwise). */
    @POST
    @Path("/sessions/{id}/consume")
    @Authorize(action = "liveness.session.consume", resource = "#partyId")
    fun consume(@PathParam("partyId") partyId: UUID, @PathParam("id") id: UUID): SessionView =
        SessionView.of(service.consume(partyId, id))

    private fun uuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrElse { throw IllegalArgumentException("$field is not valid") }
}

// ---------------------------------------------------------------- wire types

data class SendCodesRequest(val email: String? = null, val phone: String? = null)

data class CodesSentView(
    val id: String,
    @JsonProperty("expires_in_seconds") val expiresInSeconds: Long,
    @JsonProperty("demo_email_code") val demoEmailCode: String?,
    @JsonProperty("demo_phone_code") val demoPhoneCode: String?,
)

data class ConfirmCodesRequest(
    @JsonProperty("email_code") val emailCode: String? = null,
    @JsonProperty("phone_code") val phoneCode: String? = null,
)

data class CodesCheckedView(
    @JsonProperty("email_verified") val emailVerified: Boolean,
    @JsonProperty("phone_verified") val phoneVerified: Boolean,
)

data class PrefillRequest(
    @JsonProperty("customer_number") val customerNumber: String? = null,
    @JsonProperty("contact_verification_id") val contactVerificationId: String? = null,
)

data class PrefillView(
    @JsonProperty("first_name") val firstName: String,
    @JsonProperty("last_name") val lastName: String,
    val street: String,
    val city: String,
    @JsonProperty("postal_code") val postalCode: String,
)

data class ApplicationView(
    @JsonProperty("party_id") val partyId: String,
    @JsonProperty("package_code") val packageCode: String?,
    @JsonProperty("personal_details_at") val personalDetailsAt: String?,
    @JsonProperty("tax_residency_at") val taxResidencyAt: String?,
    @JsonProperty("identity_verified_at") val identityVerifiedAt: String?,
    @JsonProperty("identity_method") val identityMethod: String?,
)

data class AddressRequest(
    val street: String? = null,
    val city: String? = null,
    @JsonProperty("postal_code") val postalCode: String? = null,
    val country: String? = null,
)

data class PersonalDetailsRequest(
    @JsonProperty("date_of_birth") val dateOfBirth: String? = null,
    @JsonProperty("birth_number") val birthNumber: String? = null,
    val phone: String? = null,
    val address: AddressRequest? = null,
)

data class ResidencyRequest(val country: String? = null, val tin: String? = null)

data class TaxResidencyRequest(
    @JsonProperty("us_person") val usPerson: Boolean? = null,
    val residencies: List<ResidencyRequest>? = null,
)

data class PackageRequest(@JsonProperty("package_code") val packageCode: String? = null)

data class IdentityRequest(val method: String? = null, @JsonProperty("document_type") val documentType: String? = null)

data class CheckView(val type: String, val status: String)

data class IdentityVerificationView(
    val id: String,
    val method: String,
    val status: String,
    @JsonProperty("redirect_url") val redirectUrl: String?,
    val checks: List<CheckView>,
) {
    companion object {
        fun of(view: IdentityView): IdentityVerificationView {
            val v = view.verification
            // Once settled the provider is not asked again, so the checks are derived from the outcome.
            val checks = view.checks.ifEmpty { settledChecks(v.method, v.status.name) }
            return IdentityVerificationView(
                v.id.toString(),
                v.method.name,
                if (v.status.name == "IN_PROGRESS") "IN_PROGRESS" else v.status.name,
                redirectUrl = null,
                checks = checks.map { CheckView(it.type, it.status) },
            )
        }

        private fun settledChecks(method: IdentityMethod, status: String): List<ProviderCheck> {
            val types = if (method ==
                IdentityMethod.BANK_ID
            ) {
                listOf("BANK_ID")
            } else {
                listOf("DOCUMENT", "CHIP", "LIVENESS", "FACE_MATCH")
            }
            return types.mapIndexed { i, t ->
                ProviderCheck(t, if (status == "VERIFIED" || i < types.lastIndex) "PASSED" else "FAILED")
            }
        }
    }
}

data class PolicyView(
    val enabled: Boolean,
    val threshold: BigDecimal,
    val currency: String,
    @JsonProperty("last_check_at") val lastCheckAt: String?,
) {
    companion object {
        fun of(p: LivenessPolicy) = PolicyView(p.enabled, p.threshold, p.currency, p.lastCheckAt?.toString())
    }
}

data class PolicyRequest(
    val enabled: Boolean? = null,
    val threshold: BigDecimal? = null,
    @JsonProperty("session_id") val sessionId: String? = null,
)

data class SessionRequest(
    val purpose: String? = null,
    @JsonProperty("payment_reference") val paymentReference: String? = null,
)

data class SessionView(
    val id: String,
    val purpose: String,
    val status: String,
    @JsonProperty("expires_at") val expiresAt: String,
) {
    companion object {
        fun of(s: LivenessSession) = SessionView(
            s.id.toString(),
            s.purpose.name,
            s.status.name,
            s.createdAt.plus(LivenessSession.VALIDITY).toString(),
        )
    }
}
