// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/customer/v1/cards/notification-settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CardNotificationsEdgeResource(private val s: CardEdgeSupport, parties: CustomerPartyResolver) :
    SignedInExperience(s.edge, parties) {
    @GET
    @Authorize(action = "customer.cards.read")
    @Blocking
    fun get(): Response {
        val party = party()
        val currency = if (edge.marketOf(party) == "BG") "EUR" else "CZK"
        return edge.relay(
            edge.upstream.get(
                s.cards("/api/v1/card-notification-settings/$party?currency=$currency"),
                party.toString(),
            ),
        )
    }

    @PUT
    @Authorize(action = "customer.cards.update")
    @Blocking
    fun put(body: String?): Response {
        val party = party()
        val input =
            edge.objectBody(body)
                ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "body must be a JSON object")
        val forward = mapOf(
            "every_payment" to input.path("every_payment").asBoolean(true),
            "declined_payments" to input.path("declined_payments").asBoolean(true),
            "payments_abroad" to input.path("payments_abroad").asBoolean(true),
            "online_payments" to input.path("online_payments").asBoolean(false),
            "weekly_summary" to input.path("weekly_summary").asBoolean(true),
            "large_payment_threshold" to input.path("large_payment_threshold").takeIf { it.isNumber }?.decimalValue(),
            "currency" to if (edge.marketOf(party) == "BG") "EUR" else "CZK",
        )
        return edge.relay(
            edge.upstream.put(
                s.cards("/api/v1/card-notification-settings/$party"),
                party.toString(),
                edge.json(forward),
            ),
        )
    }
}
