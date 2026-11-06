// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.math.BigDecimal
import java.util.UUID

/** The card's own payments, newest first, from card-processing's authorisations. */
@Path("/customer/v1/cards/{id}/transactions")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CardSpendingEdgeResource(private val s: CardEdgeSupport, parties: CustomerPartyResolver) :
    SignedInExperience(s.edge, parties) {
    @GET
    @Authorize(action = "customer.cards.read", resource = "#id")
    @Blocking
    fun list(@PathParam("id") id: UUID): Response {
        val party = party()
        s.ownCard(party, id) ?: return s.noSuchCard()
        val url =
            s.processing("/api/v1/card-authorizations/card/$id?limit=$LIMIT") ?: return EdgeJson.ok(emptyList<Any>())
        val r = edge.upstream.get(url, party.toString())
        if (r.status !in ExperienceEdge.SUCCESS) return edge.relay(r)
        val rows = EdgeJson.parse(r)?.let { if (it.isArray) it else it.path("data") }?.map(::row).orEmpty()
        return EdgeJson.ok(rows)
    }

    /** One authorisation as the app shows a card payment: negative for a payment, positive for a refund. */
    private fun row(a: JsonNode): Map<String, Any?> {
        val minor = a.path("amountMinorUnits").asLong()
        val refund = a.text("status") == "REVERSED"
        return mapOf(
            "id" to a.text("id"),
            "merchant" to (a.text("merchantName") ?: "Card payment"),
            "category" to (a.text("category") ?: "OTHER"),
            "amount" to BigDecimal.valueOf(if (refund) minor else -minor, 2),
            "currency" to (a.text("currency") ?: "CZK"),
            "booked_at" to (a.text("clearedAt") ?: a.text("authorizedAt")),
            "state" to STATES[a.text("status")].orEmpty().ifEmpty { "HELD" },
        )
    }

    private companion object {
        const val LIMIT = 50
        val STATES = mapOf(
            "CLEARED" to "BOOKED",
            "PARTIALLY_CLEARED" to "BOOKED",
            "REVERSED" to "BOOKED",
            "DECLINED" to "DECLINED",
            "APPROVED" to "PENDING",
        )
    }
}
