// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.openbank.cardissuance.application.usecase.CardSelfServiceService
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import java.util.UUID

@Path("/api/v1/cards/{id}/self-activation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(SELF_SERVICE_CALLERS)
class CardSelfActivationResource(private val service: CardSelfServiceService) {
    @POST
    @Authorize(action = "card.self-activate", resource = "#id")
    @Operation(summary = "Activate a delivered plastic card with the last four digits printed on it")
    suspend fun activate(@PathParam("id") id: UUID, body: ActivationRequest?): ActivationView {
        val card = service.activate(id, requireNotNull(body?.last4) { "last4 is required" }, "cardholder")
        return ActivationView(card.id.toString(), card.status.name)
    }
}
