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

/** CZ insolvency register (ISIR), simulated: proceedings by birth number. */
@Path("/api/v1/isir")
@Tag(name = "CZ registers (simulated)")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class IsirResource {
    @Inject
    lateinit var desk: RegistersDesk

    @GET
    @Path("/osoby")
    @Operation(summary = "Insolvency proceedings of a person by birth number (empty zaznamy = none)")
    fun person(@QueryParam("rc") rc: String?): Map<String, Any> {
        val (id, records) = desk.isir(requireNotNull(rc) { "query parameter 'rc' is required" })
        return mapOf(
            "rc" to id,
            "zaznamy" to records.map {
                mapOf("spisova_znacka" to it.caseNumber, "stav" to it.state, "zahajeno" to it.startedOn.toString())
            },
        )
    }
}
