// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.infrastructure.rest

import com.openbank.providersimulator.application.RegistersDesk
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/** CZ Ministry of the Interior's register of invalid documents (neplatné doklady), simulated. */
@Path("/api/v1/mvcr")
@Tag(name = "CZ registers (simulated)")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class MvcrResource {
    @Inject
    lateinit var desk: RegistersDesk

    @GET
    @Path("/neplatne-doklady")
    @Operation(summary = "Is a document number registered as invalid (lost, stolen)?")
    fun invalidDocument(
        @QueryParam("doklad") doklad: String?,
        @QueryParam("serie") serie: String?,
    ): Map<String, Any?> {
        val number = requireNotNull(doklad) { "query parameter 'doklad' is required" }
        require((serie?.length ?: 0) <= MAX_SERIE) { "serie is too long" }
        val listed = desk.invalidDocument(number)
        return mapOf("doklad" to number, "evidovan" to listed, "duvod" to if (listed) "ODCIZEN" else null)
    }

    private companion object {
        const val MAX_SERIE = 10
    }
}
