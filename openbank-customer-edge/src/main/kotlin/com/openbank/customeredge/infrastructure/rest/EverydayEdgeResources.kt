// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

// Experience v2 step 3 — everyday money. The party always comes from the customer's token; the
// everyday-service routes take it in the path and admit only this edge's machine principal.
// Each class has its own full path: a class whose path is a prefix of another's would capture its routes.

/** Billers the customer can pay in their market. */
@Path("/customer/v1/billers")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class BillersEdgeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) : SignedInExperience(edge, parties) {
    @GET
    @Authorize(action = "customer.bills.read")
    @Blocking
    fun billers(): Response {
        val party = party()
        return edge.relay(edge.upstream.get(edge.everyday(bills(party, "/billers")), party.toString()))
    }
}

/** The Energo-Pro electricity account, its bills, and bill reminders. */
@Path("/customer/v1/bills")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class BillsEdgeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) : SignedInExperience(edge, parties) {

    @GET
    @Path("/energo-pro")
    @Authorize(action = "customer.bills.read")
    @Blocking
    fun electricity(): Response = get("/energo-pro")

    @GET
    @Path("/energo-pro/bills")
    @Authorize(action = "customer.bills.read")
    @Blocking
    fun history(): Response = get("/energo-pro/bills")

    @POST
    @Path("/energo-pro/link")
    @Authorize(action = "customer.bills.update")
    @Blocking
    fun link(body: String?): Response {
        val party = party()
        val input = edge.objectBody(body)
            ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "body must be a JSON object")
        // Only the two fields the app may set: never a party id or anything else from the app.
        val forward = mapOf(
            "customer_number" to input.text("customer_number"),
            "data_sharing_consent" to input.path("data_sharing_consent").asBoolean(false),
        )
        return edge.relay(
            edge.upstream.post(edge.everyday(bills(party, "/energo-pro/link")), party.toString(), edge.json(forward)),
        )
    }

    @DELETE
    @Path("/energo-pro/link")
    @Authorize(action = "customer.bills.update")
    @Blocking
    fun unlink(): Response {
        val party = party()
        return edge.relay(edge.upstream.delete(edge.everyday(bills(party, "/energo-pro/link")), party.toString()))
    }

    @GET
    @Path("/reminders")
    @Authorize(action = "customer.bills.read")
    @Blocking
    fun reminders(): Response = get("/reminders")

    @PUT
    @Path("/reminders")
    @Authorize(action = "customer.bills.update")
    @Blocking
    fun saveReminders(body: String?): Response {
        val party = party()
        val input = edge.objectBody(body)
            ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "body must be a JSON object")
        val forward = mutableMapOf<String, Any?>("enabled" to input.path("enabled").asBoolean(false))
        if (input.has("days_before")) forward["days_before"] = input.path("days_before").asInt()
        return edge.relay(
            edge.upstream.put(edge.everyday(bills(party, "/reminders")), party.toString(), edge.json(forward)),
        )
    }

    private fun get(path: String): Response {
        val party = party()
        return edge.relay(edge.upstream.get(edge.everyday(bills(party, path)), party.toString()))
    }
}

private fun bills(party: UUID, path: String) = "/api/v1/everyday/parties/$party/bills$path"

/** A URL on everyday-service (its base comes from the shared [ExperienceEdge] configuration). */
private fun ExperienceEdge.everyday(path: String) = "${everydayUrl.trimEnd('/')}$path"
