// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.libs.authz.Authorize
import com.openbank.onboarding.application.usecase.AccountOpeningService
import com.openbank.onboarding.application.usecase.AccountOpeningView
import com.openbank.onboarding.application.usecase.FirstPaymentInstructions
import com.openbank.onboarding.application.usecase.IdentityProgress
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.util.UUID

/**
 * Experience v2 step 9 — the account opening, orchestrated (identity, first payment, submission).
 * Machine callers only (customer-edge, with the party from the customer's token). snake_case wire.
 */
@Path("/api/v1/onboarding/account-opening/{partyId}")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
@Blocking
class AccountOpeningResource(private val service: AccountOpeningService) {

    @GET
    @Authorize(action = "onboarding.application.read", resource = "#partyId")
    @Operation(summary = "Where the account opening stands: status, identity, first payment, review reasons")
    fun get(@PathParam("partyId") partyId: UUID): AccountOpeningWire = AccountOpeningWire.of(service.view(partyId))

    @POST
    @Path("/identity/bank-id")
    @Authorize(action = "onboarding.application.update", resource = "#partyId")
    @Operation(summary = "Start Bank iD (OIDC): answers the authorize URL to open in a browser")
    fun startBankId(@PathParam("partyId") partyId: UUID): Response =
        Response.status(Response.Status.CREATED).entity(IdentityWire.of(service.startBankId(partyId))).build()

    @POST
    @Path("/identity/documents")
    @Consumes(MediaType.APPLICATION_JSON)
    @Authorize(action = "onboarding.application.update", resource = "#partyId")
    @Operation(summary = "Start the document path: document photo, chip, selfie")
    fun startDocument(@PathParam("partyId") partyId: UUID, body: DocumentStartRequest?): Response =
        Response.status(Response.Status.CREATED)
            .entity(IdentityWire.of(service.startDocument(partyId, body?.documentType, body?.issuingCountry))).build()

    @GET
    @Path("/identity/{id}")
    @Authorize(action = "onboarding.application.read", resource = "#partyId")
    fun identity(@PathParam("partyId") partyId: UUID, @PathParam("id") id: UUID): IdentityWire =
        IdentityWire.of(service.identityProgress(partyId, id))

    @POST
    @Path("/identity/{id}/document")
    @Authorize(action = "onboarding.application.update", resource = "#partyId")
    @Operation(summary = "Read the photographed document (OCR + MRZ), expiry, register of invalid documents")
    fun document(@PathParam("partyId") partyId: UUID, @PathParam("id") id: UUID): IdentityWire =
        IdentityWire.of(service.readDocument(partyId, id))

    @POST
    @Path("/identity/{id}/chip")
    @Authorize(action = "onboarding.application.update", resource = "#partyId")
    @Operation(summary = "Read the document's NFC chip (passive authentication)")
    fun chip(@PathParam("partyId") partyId: UUID, @PathParam("id") id: UUID): IdentityWire =
        IdentityWire.of(service.readChip(partyId, id))

    @POST
    @Path("/identity/{id}/selfie")
    @Authorize(action = "onboarding.application.update", resource = "#partyId")
    @Operation(summary = "Selfie: liveness and a face match to the chip photo")
    fun selfie(@PathParam("partyId") partyId: UUID, @PathParam("id") id: UUID): IdentityWire =
        IdentityWire.of(service.takeSelfie(partyId, id))

    @GET
    @Path("/first-payment")
    @Authorize(action = "onboarding.application.read", resource = "#partyId")
    @Operation(summary = "Where to send the first payment from the applicant's own account at another bank")
    fun firstPayment(@PathParam("partyId") partyId: UUID): FirstPaymentWire =
        FirstPaymentWire.of(service.firstPayment(partyId))

    @POST
    @Path("/first-payment/simulate")
    @Authorize(action = "onboarding.application.update", resource = "#partyId")
    @Operation(summary = "SANDBOX: the applicant's other bank sends the first payment")
    fun simulateFirstPayment(@PathParam("partyId") partyId: UUID): FirstPaymentWire =
        FirstPaymentWire.of(service.simulateFirstPayment(partyId))

    @POST
    @Path("/submit")
    @Authorize(action = "onboarding.application.update", resource = "#partyId")
    @Operation(summary = "Screening, risk and the KYC assessment — straight-through approval or a person")
    fun submit(@PathParam("partyId") partyId: UUID): AccountOpeningWire = AccountOpeningWire.of(service.submit(partyId))
}

/** The browser's return from Bank iD, relayed by customer-edge (no customer token: the state binds it). */
@Path("/api/v1/onboarding/bank-id/callback")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
@Blocking
class BankIdCallbackResource(private val service: AccountOpeningService) {
    @POST
    @Authorize(action = "onboarding.bank-id.callback")
    @Operation(summary = "Complete Bank iD: exchange the code, compare the claims with the applicant")
    fun callback(body: BankIdCallbackRequest?): IdentityWire =
        IdentityWire.of(service.bankIdCallback(body?.code, body?.state, body?.error))
}

data class BankIdCallbackRequest(val code: String? = null, val state: String? = null, val error: String? = null)

data class DocumentStartRequest(
    @JsonProperty("document_type") val documentType: String? = null,
    @JsonProperty("issuing_country") val issuingCountry: String? = null,
)

data class CheckWire(val type: String, val status: String, val detail: String)

data class IdentityWire(
    val id: String,
    val method: String,
    val status: String,
    @JsonProperty("failure_reason") val failureReason: String?,
    @JsonProperty("needs_review") val needsReview: Boolean,
    @JsonProperty("redirect_url") val redirectUrl: String?,
    @JsonProperty("document_read") val documentRead: Boolean,
    @JsonProperty("chip_read") val chipRead: Boolean,
    val document: Map<String, Any?>?,
    val checks: List<CheckWire>,
) {
    companion object {
        fun of(p: IdentityProgress) = IdentityWire(
            p.verification.id.toString(),
            p.verification.method.name,
            p.verification.status.name,
            p.verification.failureReason,
            p.verification.needsReview,
            p.redirectUrl,
            p.verification.documentId != null,
            p.checks.any { it.type == "CHIP_PA" },
            p.document,
            p.checks.map { CheckWire(it.type, it.status.name, it.detail) },
        )
    }
}

data class FirstPaymentWire(
    @JsonProperty("account_number") val accountNumber: String,
    @JsonProperty("bank_code") val bankCode: String,
    val iban: String,
    val amount: String,
    val currency: String,
    @JsonProperty("variable_symbol") val variableSymbol: String,
    val received: Boolean,
    val payment: Map<String, Any?>?,
) {
    companion object {
        fun of(f: FirstPaymentInstructions) = FirstPaymentWire(
            f.accountNumber,
            f.bankCode,
            f.iban,
            f.amount.toPlainString(),
            f.currency,
            f.variableSymbol,
            f.received != null,
            f.received,
        )
    }
}

data class AccountOpeningWire(
    @JsonProperty("party_id") val partyId: String,
    val status: String,
    @JsonProperty("identity_verified_at") val identityVerifiedAt: String?,
    @JsonProperty("identity_method") val identityMethod: String?,
    @JsonProperty("identity_attempts_left") val identityAttemptsLeft: Int,
    val identity: IdentityWire?,
    @JsonProperty("first_payment") val firstPayment: FirstPaymentWire?,
    @JsonProperty("review_reasons") val reviewReasons: List<String>,
    @JsonProperty("submitted_at") val submittedAt: String?,
) {
    companion object {
        fun of(v: AccountOpeningView) = AccountOpeningWire(
            v.application.partyId.toString(),
            v.application.status.name,
            v.application.identityVerifiedAt?.toString(),
            v.application.identityMethod?.name,
            v.identityAttemptsLeft,
            v.identity?.let { IdentityWire.of(it) },
            v.firstPayment?.let { FirstPaymentWire.of(it) },
            v.reviewReasons,
            v.application.submittedAt?.toString(),
        )
    }
}
