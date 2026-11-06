// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.openbank.cardissuance.application.usecase.CardSelfServiceService
import com.openbank.libs.authz.Authorize
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
import org.eclipse.microprofile.openapi.annotations.Operation
import java.util.UUID

@Path("/api/v1/cards/{id}/pin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(SELF_SERVICE_CALLERS)
class CardPinResource(private val service: CardSelfServiceService) {
    @GET
    @Authorize(action = "card.pin.read", resource = "#id")
    @Operation(summary = "Whether the card has a PIN, when it was set and wrong entries left")
    suspend fun status(@PathParam("id") id: UUID): PinStatusView =
        service.pinStatus(id).let { PinStatusView(it.pinSet, it.lastChangedAt?.toString(), it.attemptsLeft) }

    @POST
    @Path("/view")
    @Authorize(action = "card.pin.view", resource = "#id")
    @Operation(summary = "The PIN itself (the caller has checked SCA); never cached")
    suspend fun view(@PathParam("id") id: UUID): Response =
        Response.ok(PinView(service.viewPin(id), VISIBLE_SECONDS)).header("Cache-Control", "no-store").build()

    @PUT
    @Authorize(action = "card.pin.update", resource = "#id")
    @Operation(summary = "Set a new PIN (four digits, not a run or a year; the caller has checked SCA)")
    suspend fun change(@PathParam("id") id: UUID, body: NewPinRequest?): Response {
        service.changePin(id, requireNotNull(body?.newPin) { "new_pin is required" })
        return Response.noContent().build()
    }

    private companion object {
        const val VISIBLE_SECONDS = 10
    }
}
