// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.providersimulator.application.ScenarioBook
import com.openbank.providersimulator.domain.ScenarioEntry
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * The person registry's fixtures at runtime: which identity scenarios hold for a national id. A PUT
 * overrides the scenarios file for that person until DELETE; in memory only.
 */
@Path("/api/v1/sim/scenarios")
@Tag(name = "Identity scenarios (simulator control)")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_ADMIN")
class ScenarioResource {
    @Inject
    lateinit var book: ScenarioBook

    @GET
    @Operation(summary = "Every person with scenarios (file and runtime; runtime wins)")
    fun list(): List<ScenarioView> = book.all().map(ScenarioView::of)

    @PUT
    @Path("/{nationalId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Set a person's scenarios (an empty list overrides the file with none)")
    fun put(@PathParam("nationalId") nationalId: String, body: ScenarioRequest?): ScenarioView {
        val request = requireNotNull(body) { "request body is required" }
        val scenarios = requireNotNull(request.scenarios) { "scenarios is required" }
        return ScenarioView.of(book.put(nationalId, scenarios, request.note))
    }

    @DELETE
    @Path("/{nationalId}")
    @Operation(summary = "Remove a person's runtime scenarios (the file's, if any, hold again)")
    fun delete(@PathParam("nationalId") nationalId: String): Response {
        book.delete(nationalId)
        return Response.noContent().build()
    }
}

data class ScenarioRequest(val scenarios: List<String>? = null, val note: String? = null)

data class ScenarioView(
    @JsonProperty("national_id") val nationalId: String,
    val scenarios: List<String>,
    val note: String?,
    val source: String,
) {
    companion object {
        fun of(e: ScenarioEntry) =
            ScenarioView(e.nationalId, e.scenarios.map { it.name }.sorted(), e.note, e.source.name)
    }
}
