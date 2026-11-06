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
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/customer/v1/cards/{id}/pin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CardPinEdgeResource(private val s: CardEdgeSupport, parties: CustomerPartyResolver) :
    SignedInExperience(s.edge, parties) {
    @GET
    @Authorize(action = "customer.cards.read", resource = "#id")
    @Blocking
    fun status(@PathParam("id") id: UUID): Response {
        val party = party()
        s.ownCard(party, id) ?: return s.noSuchCard()
        return edge.relay(edge.upstream.get(s.cards("/api/v1/cards/$id/pin"), party.toString()))
    }

    /** The PIN itself, after SCA (VIEW_PIN). Never cached; audited by reference only. */
    @POST
    @Path("/view")
    @Authorize(action = "customer.cards.details.read", resource = "#id")
    @Blocking
    fun view(@PathParam("id") id: UUID, body: String?): Response {
        val party = party()
        s.ownCard(party, id) ?: return s.noSuchCard()
        s.sca(
            party,
            edge.objectBody(body)?.text("sca_challenge_id"),
            id.toString(),
            "VIEW_PIN",
            "cards.pin.view",
        )?.let {
            return it
        }
        val r = edge.upstream.post(s.cards("/api/v1/cards/$id/pin/view"), party.toString(), "{}")
        s.audited(party, "cards.pin.view", "CUSTOMER_CARD_PIN_VIEWED", id.toString(), r)
        if (r.status !in ExperienceEdge.SUCCESS) return edge.relay(r)
        return Response.ok(
            r.entity,
        ).type(MediaType.APPLICATION_JSON).header("Cache-Control", "no-store").header("Pragma", "no-cache").build()
    }

    /** A new PIN, after SCA (CHANGE_PIN). */
    @PUT
    @Authorize(action = "customer.cards.update", resource = "#id")
    @Blocking
    fun change(@PathParam("id") id: UUID, body: String?): Response {
        val party = party()
        s.ownCard(party, id) ?: return s.noSuchCard()
        val input =
            edge.objectBody(body)
                ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "body must be a JSON object")
        s.sca(party, input.text("sca_challenge_id"), id.toString(), "CHANGE_PIN", "cards.pin.change")?.let { return it }
        val r = edge.upstream.put(
            s.cards("/api/v1/cards/$id/pin"),
            party.toString(),
            edge.json(
                mapOf(
                    "new_pin" to input.text("new_pin"),
                ),
            ),
        )
        return s.audited(party, "cards.pin.change", "CUSTOMER_CARD_PIN_CHANGED", id.toString(), edge.relay(r))
    }
}
