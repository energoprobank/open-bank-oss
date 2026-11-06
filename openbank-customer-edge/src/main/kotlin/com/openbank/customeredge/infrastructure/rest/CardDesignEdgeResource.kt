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
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/customer/v1/cards/{id}/design")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CardDesignEdgeResource(private val s: CardEdgeSupport, parties: CustomerPartyResolver) :
    SignedInExperience(s.edge, parties) {
    @GET
    @Authorize(action = "customer.cards.read", resource = "#id")
    @Blocking
    fun get(@PathParam("id") id: UUID): Response {
        val party = party()
        s.ownCard(party, id) ?: return s.noSuchCard()
        return edge.relay(edge.upstream.get(s.cards("/api/v1/cards/$id/design"), party.toString()))
    }

    @PUT
    @Authorize(action = "customer.cards.update", resource = "#id")
    @Blocking
    fun put(@PathParam("id") id: UUID, body: String?): Response {
        val party = party()
        s.ownCard(party, id) ?: return s.noSuchCard()
        val design =
            edge.objectBody(body)?.text("design")
                ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "design is required")
        return edge.relay(
            edge.upstream.put(
                s.cards("/api/v1/cards/$id/design"),
                party.toString(),
                edge.json(
                    mapOf(
                        "design" to design,
                    ),
                ),
            ),
        )
    }
}
