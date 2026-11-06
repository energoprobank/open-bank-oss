// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.openbank.cardissuance.application.usecase.CardSelfServiceService
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import java.util.UUID

@Path("/api/v1/cards/{id}/design")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(SELF_SERVICE_CALLERS)
class CardDesignResource(private val service: CardSelfServiceService) {
    @GET
    @Authorize(action = "card.design.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID): DesignView = DesignView(service.design(id))

    @PUT
    @Authorize(action = "card.design.update", resource = "#id")
    suspend fun set(@PathParam("id") id: UUID, body: DesignView?): DesignView =
        DesignView(service.setDesign(id, requireNotNull(body?.design) { "design is required" }))
}
