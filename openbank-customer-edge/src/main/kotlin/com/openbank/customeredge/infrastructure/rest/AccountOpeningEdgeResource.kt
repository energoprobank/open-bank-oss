// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import com.openbank.libs.authz.Authorize
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
import java.util.UUID

/**
 * Experience v2 step 9 — the account opening as onboarding-service orchestrates it: identity (Bank iD
 * or document + chip + selfie), the first payment from the applicant's own account at another bank,
 * and the submission that screening, risk and KYC decide on. Always the token's own party.
 */
@Path("/customer/v1/onboarding/account-opening")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
@Suppress("TooManyFunctions") // one route per step of the account opening, all thin relays
class AccountOpeningEdgeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) :
    SignedInExperience(edge, parties) {

    @GET
    @Authorize(action = "customer.onboarding.identity.read")
    @Blocking
    fun status(): Response = get("")

    @POST
    @Path("/identity/bank-id")
    @Authorize(action = "customer.onboarding.identity.start")
    @Blocking
    fun startBankId(): Response = post("/identity/bank-id", "{}")

    @POST
    @Path("/identity/documents")
    @Consumes(MediaType.APPLICATION_JSON)
    @Authorize(action = "customer.onboarding.identity.start")
    @Blocking
    fun startDocument(body: String?): Response {
        val input = edge.objectBody(body) ?: EdgeJson.mapper.createObjectNode()
        val forward = mapOf(
            "document_type" to input.text("document_type"),
            "issuing_country" to input.text("issuing_country"),
        )
        return post("/identity/documents", edge.json(forward))
    }

    @GET
    @Path("/identity/{id}")
    @Authorize(action = "customer.onboarding.identity.read")
    @Blocking
    fun identity(@PathParam("id") id: String?): Response = withId(id) { get("/identity/$it") }

    @POST
    @Path("/identity/{id}/document")
    @Authorize(action = "customer.onboarding.identity.start")
    @Blocking
    fun document(@PathParam("id") id: String?): Response = withId(id) { post("/identity/$it/document", "{}") }

    @POST
    @Path("/identity/{id}/chip")
    @Authorize(action = "customer.onboarding.identity.start")
    @Blocking
    fun chip(@PathParam("id") id: String?): Response = withId(id) { post("/identity/$it/chip", "{}") }

    @POST
    @Path("/identity/{id}/selfie")
    @Authorize(action = "customer.onboarding.identity.start")
    @Blocking
    fun selfie(@PathParam("id") id: String?): Response = withId(id) { post("/identity/$it/selfie", "{}") }

    @GET
    @Path("/first-payment")
    @Authorize(action = "customer.onboarding.identity.read")
    @Blocking
    fun firstPayment(): Response = get("/first-payment")

    @POST
    @Path("/first-payment/simulate")
    @Authorize(action = "customer.onboarding.application.update")
    @Blocking
    fun simulateFirstPayment(): Response = post("/first-payment/simulate", "{}")

    @POST
    @Path("/submit")
    @Authorize(action = "customer.onboarding.application.update")
    @Blocking
    fun submit(): Response = post("/submit", "{}")

    private fun url(party: UUID, suffix: String) = edge.onboarding("/api/v1/onboarding/account-opening/$party$suffix")

    private fun get(suffix: String): Response {
        val party = party()
        return edge.relay(edge.upstream.get(url(party, suffix), party.toString()))
    }

    private fun post(suffix: String, body: String): Response {
        val party = party()
        return edge.relay(edge.upstream.post(url(party, suffix), party.toString(), body))
    }

    private fun withId(id: String?, call: (UUID) -> Response): Response =
        id?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let(call)
            ?: edge.error(ExperienceEdge.NOT_FOUND, "VERIFICATION_NOT_FOUND", "No such verification")
}
