// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

/** Activation of a delivered plastic card (the last four digits printed on it). */
@Path("/customer/v1/cards/{id}/activate")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CardActivateEdgeResource(private val s: CardEdgeSupport, parties: CustomerPartyResolver) :
    SignedInExperience(s.edge, parties) {
    @POST
    @Authorize(action = "customer.cards.activate", resource = "#id")
    @Blocking
    fun activate(@PathParam("id") id: UUID, body: String?): Response {
        val party = party()
        s.ownCard(party, id) ?: return s.noSuchCard()
        val last4 =
            edge.objectBody(body)?.text("last4")
                ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "last4 is required")
        val r = edge.upstream.post(
            s.cards("/api/v1/cards/$id/self-activation"),
            party.toString(),
            edge.json(
                mapOf(
                    "last4" to last4,
                ),
            ),
        )
        return s.audited(party, "cards.activate", "CUSTOMER_CARD_ACTIVATED", id.toString(), edge.relay(r))
    }
}
