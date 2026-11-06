// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.openbank.cardissuance.application.usecase.CardSelfServiceService
import com.openbank.libs.authz.Authorize
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
import org.eclipse.microprofile.openapi.annotations.Operation
import java.time.LocalDate
import java.util.UUID

@Path("/api/v1/cards/{id}/travel-notices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(SELF_SERVICE_CALLERS)
class CardTravelResource(private val service: CardSelfServiceService) {
    @GET
    @Authorize(action = "card.travel.read", resource = "#id")
    suspend fun list(@PathParam("id") id: UUID): List<TravelView> = service.travel(id).map(TravelView::of)

    @POST
    @Authorize(action = "card.travel.update", resource = "#id")
    @Operation(summary = "Payments in these countries on these dates are allowed, even with payments abroad off")
    suspend fun add(@PathParam("id") id: UUID, body: TravelRequest?): Response {
        val r = requireNotNull(body) { "request body is required" }
        val w = service.addTravel(
            id,
            requireNotNull(r.countries) { "countries are required" },
            LocalDate.parse(requireNotNull(r.startDate) { "start_date is required" }),
            LocalDate.parse(requireNotNull(r.endDate) { "end_date is required" }),
        )
        return Response.status(Response.Status.CREATED).entity(TravelView.of(w)).build()
    }

    @DELETE
    @Path("/{noticeId}")
    @Authorize(action = "card.travel.update", resource = "#id")
    suspend fun remove(@PathParam("id") id: UUID, @PathParam("noticeId") noticeId: UUID): Response {
        service.removeTravel(id, noticeId)
        return Response.noContent().build()
    }
}
