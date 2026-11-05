// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.libs.authz.Authorize
import com.openbank.sca.application.usecase.AppPinService
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.util.UUID

data class AppPinRequest(@JsonProperty("pin_verifier") val pinVerifier: String? = null)

/**
 * The app PIN of an enrolled device (experience v2 step 8). Machine callers only: customer-edge,
 * which has already checked that the path party is the customer's own.
 */
@Path("/api/v1/sca/parties/{partyId}/devices/{credentialId}/app-pin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class AppPinResource(private val service: AppPinService) {
    @PUT
    @Authorize(action = "device.app-pin", resource = "#partyId")
    @Operation(
        summary = "Set or change the app PIN of one of the party's enrolled devices (verifier only, never the PIN)",
    )
    suspend fun set(
        @PathParam("partyId") partyId: UUID,
        @PathParam("credentialId") credentialId: String,
        body: AppPinRequest?,
    ): Response {
        service.setPin(partyId, credentialId, requireNotNull(body?.pinVerifier) { "pin_verifier is required" })
        return Response.noContent().build()
    }
}
