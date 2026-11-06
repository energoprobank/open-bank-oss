// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
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

/**
 * Spending categories the card declines. The app's codes are card-issuance's taxonomy ids; this keeps
 * whatever monthly caps the customer set and only changes which categories are blocked.
 */
@Path("/customer/v1/cards/{id}/merchant-categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CardCategoriesEdgeResource(private val s: CardEdgeSupport, parties: CustomerPartyResolver) :
    SignedInExperience(s.edge, parties) {
    @GET
    @Authorize(action = "customer.cards.read", resource = "#id")
    @Blocking
    fun get(@PathParam("id") id: UUID): Response {
        val party = party()
        s.ownCard(party, id) ?: return s.noSuchCard()
        val r = edge.upstream.get(s.cards("/api/v1/cards/$id/category-limits"), party.toString())
        if (r.status !in ExperienceEdge.SUCCESS) return edge.relay(r)
        return EdgeJson.ok(mapOf("blocked" to blocked(EdgeJson.parse(r))))
    }

    @PUT
    @Authorize(action = "customer.cards.update", resource = "#id")
    @Blocking
    fun put(@PathParam("id") id: UUID, body: String?): Response {
        val party = party()
        s.ownCard(party, id) ?: return s.noSuchCard()
        val wanted =
            edge.objectBody(body)?.path("blocked")?.takeIf { it.isArray }?.map { it.asText().uppercase() }?.toSet()
                ?: return edge.error(
                    ExperienceEdge.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "blocked must be a list of category codes",
                )
        val current = EdgeJson.parse(edge.upstream.get(s.cards("/api/v1/cards/$id/category-limits"), party.toString()))
        val caps = current?.path("limits")?.filter {
            !it.path("monthlyLimitMinorUnits").isNull &&
                it.has("monthlyLimitMinorUnits")
        }
            ?.associate { it.text("category").orEmpty() to it.path("monthlyLimitMinorUnits").asLong() }.orEmpty()
        val limits = (caps.keys + wanted).map { c ->
            mapOf("category" to c, "blocked" to (c in wanted), "monthlyLimitMinorUnits" to caps[c])
        }
        val r = edge.upstream.put(
            s.cards("/api/v1/cards/$id/category-limits"),
            party.toString(),
            edge.json(
                mapOf(
                    "limits" to limits,
                ),
            ),
        )
        if (r.status !in ExperienceEdge.SUCCESS) return edge.relay(r)
        return EdgeJson.ok(mapOf("blocked" to blocked(EdgeJson.parse(r))))
    }

    private fun blocked(node: JsonNode?): List<String> = node?.path("limits")?.filter {
        it.path("blocked").asBoolean(false)
    }?.mapNotNull { it.text("category") }.orEmpty()
}
