// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.providersimulator.application.ProviderSimulator
import com.openbank.providersimulator.domain.Applicant
import com.openbank.providersimulator.domain.DeliveredMessage
import com.openbank.providersimulator.domain.EnergoProCustomer
import com.openbank.providersimulator.domain.Verification
import com.openbank.providersimulator.domain.VerificationKind
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

// NON-PRODUCTION third-party providers (see ProviderSimulator). Machine callers only: the bank's
// services with their M2M token. Wire names are snake_case, like the vendors they stand in for.

/** Energo-Pro's customer system: look a customer up by the number on their invoice. */
@Path("/api/v1/energo-pro")
@Tag(name = "Energo-Pro customer system (simulated)")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class EnergoProResource {
    @Inject
    lateinit var simulator: ProviderSimulator

    @GET
    @Path("/customers/{customerNumber}")
    @Operation(summary = "An Energo-Pro electricity customer by customer number (404 when Energo-Pro has none)")
    fun customer(@PathParam("customerNumber") customerNumber: String): EnergoProCustomerView {
        require(customerNumber.length <= MAX_NUMBER_INPUT) { "customer number is too long" }
        val customer = simulator.energoProCustomer(customerNumber) ?: throw NotFoundException()
        return EnergoProCustomerView.of(customer)
    }

    companion object {
        const val MAX_NUMBER_INPUT = 32
    }
}

/** The e-mail and SMS gateway: delivers a text; the sandbox outbox shows what a recipient got. */
@Path("/api/v1/otp")
@Tag(name = "E-mail and SMS gateway (simulated)")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class OtpGatewayResource {
    @Inject
    lateinit var simulator: ProviderSimulator

    @POST
    @Path("/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Deliver one e-mail or SMS")
    fun send(request: SendMessageRequest?): Response {
        val body = requireNotNull(request) { "request body is required" }
        val channel = requireNotNull(body.channel) { "channel is required" }
        val message = simulator.deliver(
            channel,
            requireNotNull(body.recipient) {
                "recipient is required"
            },
            requireNotNull(body.text) { "text is required" },
        )
        return Response.accepted(MessageView.of(message)).build()
    }

    @GET
    @Path("/outbox")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Operation(summary = "Sandbox: what was delivered to one recipient (newest first)")
    fun outbox(@QueryParam("recipient") recipient: String?): List<MessageView> =
        simulator.sentTo(requireNotNull(recipient) { "query parameter 'recipient' is required" }).map(MessageView::of)
}

/** Czech Bank iD: the applicant confirms their identity in their own bank; the session reports the result. */
@Path("/api/v1/bankid")
@Tag(name = "Bank iD (simulated)")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class BankIdResource {
    @Inject
    lateinit var simulator: ProviderSimulator

    @POST
    @Path("/sessions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Start a Bank iD identification for an applicant")
    fun start(request: VerificationRequest?): Response =
        Response.status(Response.Status.CREATED).entity(startView(simulator, VerificationKind.BANK_ID, request)).build()

    @GET
    @Path("/sessions/{id}")
    @Operation(summary = "A Bank iD session's state, and the identity once confirmed")
    fun session(@PathParam("id") id: String): VerificationView = view(simulator, id, VerificationKind.BANK_ID)
}

/** The identity-verification vendor: document scan with chip, liveness and face match; or liveness alone. */
@Path("/api/v1/idv")
@Tag(name = "Identity verification and liveness (simulated)")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class IdvResource {
    @Inject
    lateinit var simulator: ProviderSimulator

    @POST
    @Path("/verifications")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Start a document scan (DOCUMENT_SCAN) or a liveness check (LIVENESS)")
    fun start(request: VerificationRequest?): Response {
        val kind = requireNotNull(request?.kind) { "kind is required" }
        require(kind != VerificationKind.BANK_ID) { "Bank iD sessions start at /api/v1/bankid/sessions" }
        return Response.status(Response.Status.CREATED).entity(startView(simulator, kind, request)).build()
    }

    @GET
    @Path("/verifications/{id}")
    @Operation(summary = "A verification's checks and result")
    fun verification(@PathParam("id") id: String): VerificationView {
        val current = simulator.verificationOfAnyKind(id) ?: throw NotFoundException()
        return VerificationView.of(current, simulator)
    }
}

private fun startView(
    simulator: ProviderSimulator,
    kind: VerificationKind,
    request: VerificationRequest?,
): VerificationView {
    val body = requireNotNull(request) { "request body is required" }
    val applicant = requireNotNull(body.applicant) { "applicant is required" }
    val started = simulator.startVerification(
        kind,
        requireNotNull(body.reference) { "reference is required" },
        Applicant(
            givenName = applicant.givenName.orEmpty(),
            familyName = applicant.familyName.orEmpty(),
            dateOfBirth = applicant.dateOfBirth,
            nationalId = applicant.nationalId,
        ),
        body.documentType,
    )
    return VerificationView.of(started, simulator)
}

private fun view(simulator: ProviderSimulator, id: String, kind: VerificationKind): VerificationView =
    VerificationView.of(simulator.verification(id, kind) ?: throw NotFoundException(), simulator)

// ---------------------------------------------------------------- wire types

data class EnergoProCustomerView(
    @JsonProperty("customer_number") val customerNumber: String,
    val market: String,
    @JsonProperty("first_name") val firstName: String,
    @JsonProperty("last_name") val lastName: String,
    val street: String,
    val city: String,
    @JsonProperty("postal_code") val postalCode: String,
    val email: String,
    val phone: String,
) {
    companion object {
        fun of(c: EnergoProCustomer) = EnergoProCustomerView(
            c.customerNumber, c.market.name, c.firstName, c.lastName, c.street, c.city, c.postalCode, c.email, c.phone,
        )
    }
}

data class SendMessageRequest(
    val channel: DeliveredMessage.Channel? = null,
    val recipient: String? = null,
    val text: String? = null,
)

data class MessageView(
    val id: String,
    val channel: String,
    val recipient: String,
    val text: String,
    @JsonProperty("sent_at") val sentAt: String,
) {
    companion object {
        fun of(m: DeliveredMessage) = MessageView(m.id, m.channel.name, m.recipient, m.text, m.sentAt.toString())
    }
}

data class ApplicantDto(
    @JsonProperty("given_name") val givenName: String? = null,
    @JsonProperty("family_name") val familyName: String? = null,
    @JsonProperty("date_of_birth") val dateOfBirth: String? = null,
    @JsonProperty("national_id") val nationalId: String? = null,
)

data class VerificationRequest(
    val kind: VerificationKind? = null,
    /** The bank's own reference (application or payment), echoed back. */
    val reference: String? = null,
    val applicant: ApplicantDto? = null,
    @JsonProperty("document_type") val documentType: String? = null,
)

data class CheckView(val type: String, val status: String)

data class VerificationView(
    val id: String,
    val kind: String,
    val reference: String,
    val status: String,
    val checks: List<CheckView>,
    val identity: ApplicantDto?,
) {
    companion object {
        fun of(v: Verification, simulator: ProviderSimulator): VerificationView {
            val now = simulator.now()
            val identity = v.identityAt(now)?.let {
                ApplicantDto(it.givenName, it.familyName, it.dateOfBirth, it.nationalId)
            }
            return VerificationView(
                v.id,
                v.kind.name,
                v.reference,
                v.statusAt(now).name,
                v.checksAt(now).map { CheckView(it.type.name, it.status.name) },
                identity,
            )
        }
    }
}
