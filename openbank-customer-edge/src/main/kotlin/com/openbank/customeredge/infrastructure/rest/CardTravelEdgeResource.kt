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
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/customer/v1/cards/{id}/travel-notices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CardTravelEdgeResource(private val s: CardEdgeSupport, parties: CustomerPartyResolver) :
    SignedInExperience(s.edge, parties) {
    @GET
    @Authorize(action = "customer.cards.read", resource = "#id")
    @Blocking
    fun list(@PathParam("id") id: UUID): Response {
        val party = party()
        s.ownCard(party, id) ?: return s.noSuchCard()
        return edge.relay(edge.upstream.get(s.cards("/api/v1/cards/$id/travel-notices"), party.toString()))
    }

    @POST
    @Authorize(action = "customer.cards.update", resource = "#id")
    @Blocking
    fun add(@PathParam("id") id: UUID, body: String?): Response {
        val party = party()
        s.ownCard(party, id) ?: return s.noSuchCard()
        val input =
            edge.objectBody(body)
                ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "body must be a JSON object")
        val forward = mapOf(
            "countries" to input.path("countries").takeIf { it.isArray }?.map { it.asText() },
            "start_date" to input.text("start_date"),
            "end_date" to input.text("end_date"),
        )
        return edge.relay(
            edge.upstream.post(s.cards("/api/v1/cards/$id/travel-notices"), party.toString(), edge.json(forward)),
        )
    }

    @DELETE
    @Path("/{noticeId}")
    @Authorize(action = "customer.cards.update", resource = "#id")
    @Blocking
    fun remove(@PathParam("id") id: UUID, @PathParam("noticeId") noticeId: UUID): Response {
        val party = party()
        s.ownCard(party, id) ?: return s.noSuchCard()
        return edge.relay(edge.upstream.delete(s.cards("/api/v1/cards/$id/travel-notices/$noticeId"), party.toString()))
    }
}
