// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.openbank.cardissuance.application.usecase.CardSelfServiceService
import com.openbank.cardissuance.domain.model.DeliveryAddress
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.util.UUID

@Path("/api/v1/card-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(SELF_SERVICE_CALLERS)
class CardOrderResource(private val service: CardSelfServiceService) {
    @POST
    @Authorize(action = "card.order.create")
    @Operation(summary = "Register a plastic card order for a card the caller issued, and send it to the manufacturer")
    suspend fun create(body: OrderRequest?): Response {
        val r = requireNotNull(body) { "request body is required" }
        val a = requireNotNull(r.deliveryAddress) { "delivery_address is required" }
        val o = service.order(
            UUID.fromString(requireNotNull(r.partyId) { "party_id is required" }),
            UUID.fromString(requireNotNull(r.accountId) { "account_id is required" }),
            UUID.fromString(requireNotNull(r.cardId) { "card_id is required" }),
            r.design ?: "energoBlue",
            requireNotNull(r.nameOnCard) { "name_on_card is required" },
            DeliveryAddress(
                a.line1 ?: "",
                a.line2,
                a.city ?: "",
                a.postalCode ?: "",
                (a.countryCode ?: "CZ").uppercase(),
            ),
        )
        return Response.status(Response.Status.CREATED).entity(OrderDto.of(o)).build()
    }

    @GET
    @Authorize(action = "card.order.read")
    suspend fun list(@QueryParam("partyId") partyId: UUID?): List<OrderDto> =
        service.orders(requireNotNull(partyId) { "query parameter 'partyId' is required" }).map(OrderDto::of)

    @GET
    @Path("/{orderId}")
    @Authorize(action = "card.order.read")
    suspend fun get(@PathParam("orderId") orderId: UUID, @QueryParam("partyId") partyId: UUID?): OrderDto =
        OrderDto.of(service.order(requireNotNull(partyId) { "query parameter 'partyId' is required" }, orderId))
}
