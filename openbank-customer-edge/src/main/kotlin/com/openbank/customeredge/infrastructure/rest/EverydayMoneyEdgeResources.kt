// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import com.openbank.libs.authz.Authorize
import io.quarkus.logging.Log
import io.quarkus.vertx.http.runtime.CurrentVertxRequest
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.math.BigDecimal
import java.util.UUID

// Experience v2 step 3 — everyday money on everyday-service. The party is always the customer's own
// (from the token); account ids the app sends are checked as the customer's own by everyday-service,
// which lists the party's accounts. Every class carries its full path (a prefix class would capture
// another's routes), and only the fields the app may set are forwarded.

private fun ExperienceEdge.money(party: UUID, path: String) =
    "${everydayUrl.trimEnd('/')}/api/v1/everyday/parties/$party$path"

private fun ExperienceEdge.badBody(): Response =
    error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "body must be a JSON object")

private fun ObjectNode.pick(vararg fields: String): Map<String, Any?> =
    fields.filter { has(it) && !get(it).isNull }.associateWith { f ->
        get(f).let {
            if (it.isNumber) {
                it.decimalValue()
            } else if (it.isBoolean) {
                it.booleanValue()
            } else {
                it.asText()
            }
        }
    }

@Path("/customer/v1/direct-debits/mandates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class DirectDebitsEdgeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) :
    SignedInExperience(edge, parties) {
    @GET
    @Authorize(action = "customer.direct-debits.read")
    @Blocking
    fun list(): Response = party().let {
        edge.relay(edge.upstream.get(edge.money(it, "/direct-debits/mandates"), it.toString()))
    }

    @POST
    @Authorize(action = "customer.direct-debits.create")
    @Blocking
    fun create(body: String?): Response {
        val party = party()
        val input = edge.objectBody(body) ?: return edge.badBody()
        val forward = input.pick(
            "scheme",
            "creditor_name",
            "creditor_reference",
            "debtor_account_id",
            "limit",
            "currency",
            "frequency",
        )
        return edge.relay(
            edge.upstream.post(edge.money(party, "/direct-debits/mandates"), party.toString(), edge.json(forward)),
        )
    }

    @DELETE
    @Path("/{id}")
    @Authorize(action = "customer.direct-debits.delete")
    @Blocking
    fun cancel(@PathParam("id") id: UUID): Response =
        party().let { edge.relay(edge.upstream.delete(edge.money(it, "/direct-debits/mandates/$id"), it.toString())) }
}

@Path("/customer/v1/subscriptions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class SubscriptionsEdgeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) :
    SignedInExperience(edge, parties) {
    @GET
    @Authorize(action = "customer.subscriptions.read")
    @Blocking
    fun list(): Response = party().let {
        edge.relay(edge.upstream.get(edge.money(it, "/subscriptions"), it.toString()))
    }

    @POST
    @Path("/{id}/cancel")
    @Authorize(action = "customer.subscriptions.update")
    @Blocking
    fun cancel(@PathParam("id") id: String): Response {
        require(SUBSCRIPTION_ID.matches(id)) { "id is not a subscription id" }
        return party().let {
            edge.relay(edge.upstream.post(edge.money(it, "/subscriptions/$id/cancel"), it.toString(), "{}"))
        }
    }

    @PUT
    @Path("/{id}/reminder")
    @Authorize(action = "customer.subscriptions.update")
    @Blocking
    fun reminder(@PathParam("id") id: String, body: String?): Response {
        require(SUBSCRIPTION_ID.matches(id)) { "id is not a subscription id" }
        val input = edge.objectBody(body) ?: return edge.badBody()
        return party().let {
            edge.relay(
                edge.upstream.put(
                    edge.money(it, "/subscriptions/$id/reminder"),
                    it.toString(),
                    edge.json(input.pick("enabled")),
                ),
            )
        }
    }

    private companion object {
        val SUBSCRIPTION_ID = Regex("^[0-9a-f]{16}$")
    }
}

@Path("/customer/v1/accounts/{accountId}/insights")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class InsightsEdgeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) : SignedInExperience(edge, parties) {
    @GET
    @Path("/spending")
    @Authorize(action = "customer.insights.read")
    @Blocking
    fun spending(@PathParam("accountId") accountId: UUID, @QueryParam("month") month: String?): Response {
        val m = requireNotNull(month) { "query parameter 'month' is required" }
        require(MONTH.matches(m)) { "month must be yyyy-MM" }
        return party().let {
            edge.relay(
                edge.upstream.get(edge.money(it, "/accounts/$accountId/insights/spending?month=$m"), it.toString()),
            )
        }
    }

    /** Today's balance comes from balance-service (the edge reads it for the app anyway); the forecast from everyday. */
    @GET
    @Path("/cash-flow")
    @Authorize(action = "customer.insights.read")
    @Blocking
    fun cashFlow(@PathParam("accountId") accountId: UUID): Response {
        val party = party()
        val balances = edge.upstream.get("${edge.balanceUrl.trimEnd('/')}/api/v1/balances/$accountId", party.toString())
        val today = EdgeJson.parse(balances)?.path("balances")?.firstOrNull()?.path("availableAmount")?.decimalValue()
            ?: BigDecimal.ZERO
        return edge.relay(
            edge.upstream.get(
                edge.money(party, "/accounts/$accountId/insights/cash-flow?balance=${today.toPlainString()}"),
                party.toString(),
            ),
        )
    }

    private companion object {
        val MONTH = Regex("^\\d{4}-(0[1-9]|1[0-2])$")
    }
}

@Path("/customer/v1/accounts/{accountId}/exports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class ExportsEdgeResource(
    edge: ExperienceEdge,
    parties: CustomerPartyResolver,
    private val request: CurrentVertxRequest,
) : SignedInExperience(edge, parties) {
    @POST
    @Authorize(action = "customer.exports.create")
    @Blocking
    fun create(@PathParam("accountId") accountId: UUID, body: String?): Response {
        val party = party()
        val input = edge.objectBody(body) ?: return edge.badBody()
        val forward = input.pick("format", "from", "to", "direction")
        return withDownloadUrl(
            edge.upstream.post(edge.money(party, "/accounts/$accountId/exports"), party.toString(), edge.json(forward)),
        )
    }

    @GET
    @Path("/{exportId}")
    @Authorize(action = "customer.exports.read")
    @Blocking
    fun status(@PathParam("accountId") accountId: UUID, @PathParam("exportId") exportId: UUID): Response = party().let {
        withDownloadUrl(edge.upstream.get(edge.money(it, "/accounts/$accountId/exports/$exportId"), it.toString()))
    }

    /** everyday-service answers a download token; the app gets a link it can share, valid while the token is. */
    private fun withDownloadUrl(upstream: Response): Response {
        if (upstream.status !in ExperienceEdge.SUCCESS) return edge.relay(upstream)
        val node = EdgeJson.parse(upstream) as? ObjectNode ?: return edge.relay(upstream)
        val token = node.text("download_token")
        node.remove("download_token")
        if (token != null) {
            val base =
                runCatching { request.current.request().let { "${it.scheme()}://${it.authority()}" } }.getOrNull()
                    ?: "http://localhost:8128"
            node.put("download_url", "$base/customer/v1/export-files/$token/${node.text("file_name") ?: "payments"}")
        }
        return Response.status(upstream.status).entity(edge.json(node)).type(MediaType.APPLICATION_JSON).build()
    }
}

/** An export file by its download link: the token in the link is the capability (30 minutes), no sign-in. */
@Path("/customer/v1/export-files")
@PermitAll
class ExportFileEdgeResource(private val edge: ExperienceEdge) {
    @GET
    @Path("/{token}/{fileName}")
    @Blocking
    fun file(@PathParam("token") token: String, @PathParam("fileName") fileName: String): Response {
        if (!TOKEN.matches(token)) return Response.status(Response.Status.NOT_FOUND).build()
        val file = edge.upstream.getRaw(
            "${edge.everydayUrl.trimEnd('/')}/api/v1/everyday/export-files/$token",
            SYSTEM,
            "*/*",
        )
        if (file.status != Response.Status.OK.statusCode) return Response.status(Response.Status.NOT_FOUND).build()
        val safeName = fileName.filter { it.isLetterOrDigit() || it in "-._" }.ifEmpty { "payments" }
        return Response.fromResponse(file).header("Content-Disposition", "attachment; filename=\"$safeName\"")
            .header("Cache-Control", "no-store").build()
    }

    private companion object {
        val TOKEN = Regex("^[A-Za-z0-9_-]{20,64}$")
        const val SYSTEM = "00000000-0000-0000-0000-000000000000"
    }
}

@Path("/customer/v1/transactions/{transactionId}/recall")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class RecallEdgeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) : SignedInExperience(edge, parties) {
    @POST
    @Authorize(action = "customer.payments.recall")
    @Blocking
    fun recall(@PathParam("transactionId") transactionId: UUID, body: String?): Response {
        val party = party()
        val input = edge.objectBody(body) ?: return edge.badBody()
        return edge.relay(
            edge.upstream.post(
                edge.money(party, "/money/transactions/$transactionId/recall"),
                party.toString(),
                edge.json(input.pick("reason", "note")),
            ),
        )
    }
}

@Path("/customer/v1/top-ups")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class TopUpsEdgeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) : SignedInExperience(edge, parties) {
    @POST
    @Authorize(action = "customer.top-ups.create")
    @Blocking
    fun create(body: String?): Response {
        val party = party()
        val input = edge.objectBody(body) ?: return edge.badBody()
        return edge.relay(
            edge.upstream.post(
                edge.money(party, "/money/top-ups"),
                party.toString(),
                edge.json(input.pick("account_id", "amount", "currency")),
            ),
        )
    }

    @GET
    @Path("/{id}")
    @Authorize(action = "customer.top-ups.read")
    @Blocking
    fun status(@PathParam("id") id: UUID): Response =
        party().let { edge.relay(edge.upstream.get(edge.money(it, "/money/top-ups/$id"), it.toString())) }
}

@Path("/customer/v1/payments/protection")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class PaymentProtectionEdgeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) :
    SignedInExperience(edge, parties) {
    @GET
    @Authorize(action = "customer.security.read")
    @Blocking
    fun get(): Response = party().let {
        edge.relay(edge.upstream.get(edge.money(it, "/money/payment-protection"), it.toString()))
    }

    @PUT
    @Authorize(action = "customer.security.update")
    @Blocking
    fun put(body: String?): Response {
        val party = party()
        val input = edge.objectBody(body) ?: return edge.badBody()
        val forward = input.pick(
            "hold_unusual",
            "street_mode",
            "street_mode_limit",
            "trusted_approver_name",
            "trusted_approver_phone",
        )
        return edge.relay(
            edge.upstream.put(edge.money(party, "/money/payment-protection"), party.toString(), edge.json(forward)),
        )
    }
}

@Path("/customer/v1/financial-health/energy-trend")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class EnergyTrendEdgeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) :
    SignedInExperience(edge, parties) {
    @GET
    @Authorize(action = "customer.insights.read")
    @Blocking
    fun trend(): Response = party().let {
        edge.relay(edge.upstream.get(edge.money(it, "/money/energy-trend"), it.toString()))
    }
}

/**
 * Street mode (payment protection, experience v2 step 3): while it is on, a payment above the
 * customer's street-mode limit is refused with STREET_MODE. Fails open, like the face-check gate, when
 * the setting cannot be read: protection settings must not stop all payments when everyday-service is down.
 */
@ApplicationScoped
class StreetModeGate(private val edge: ExperienceEdge) {
    fun check(party: UUID, amount: BigDecimal?): Response? {
        if (amount == null) return null
        val response = edge.upstream.get(edge.money(party, "/money/payment-protection"), party.toString())
        if (response.status !in ExperienceEdge.SUCCESS) {
            Log.warnf(
                "payment protection unreadable for %s (%d): payment proceeds without street mode",
                party,
                response.status,
            )
            return null
        }
        val node = EdgeJson.parse(response) ?: return null
        val limit = node.path("street_mode_limit").decimalValue()
        if (!node.path("street_mode").asBoolean(false) || amount <= limit) return null
        return edge.error(
            Response.Status.FORBIDDEN.statusCode,
            "STREET_MODE",
            "Street mode is on: payments above ${limit.toPlainString()} wait until you turn it off.",
        )
    }
}
