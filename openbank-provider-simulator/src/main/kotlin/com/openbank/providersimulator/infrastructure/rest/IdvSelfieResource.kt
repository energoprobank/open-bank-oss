// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.providersimulator.application.IdvDocumentDesk
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/** The IDV vendor's selfie step: liveness, and a face match against the document's (chip) photo. */
@Path("/api/v1/idv/selfies")
@Tag(name = "Identity verification and liveness (simulated)")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class IdvSelfieResource {
    @Inject
    lateinit var desk: IdvDocumentDesk

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Check a selfie: liveness score and face-match score (404 for an unknown document)")
    fun selfie(body: SelfieRequest?): Response {
        val request = requireNotNull(body) { "request body is required" }
        val selfie = desk.selfie(
            requireNotNull(request.reference) { "reference is required" },
            requireNotNull(request.documentId) { "document_id is required" },
        ) ?: throw NotFoundException()
        val view = mapOf(
            "id" to selfie.id,
            "liveness" to mapOf("score" to selfie.findings.livenessScore, "passed" to selfie.findings.livenessPassed),
            "face_match" to mapOf("score" to selfie.findings.faceMatchScore, "against" to selfie.against),
        )
        return Response.status(Response.Status.CREATED).entity(view).build()
    }
}

data class SelfieRequest(val reference: String? = null, @JsonProperty("document_id") val documentId: String? = null)
