// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.providersimulator.application.RegistersDesk
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * Bulgaria's RegiX register exchange, simulated: one path per operation, as RegiX names them. GRAO
 * knows the people the IDV vendor has met (404 for an EGN nobody has presented).
 */
@Path("/api/v1/regix/operations")
@Tag(name = "BG RegiX (simulated)")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class RegixResource {
    @Inject
    lateinit var desk: RegistersDesk

    @POST
    @Path("/{operation}")
    @Operation(summary = "GRAO.PersonDataSearch, MVR.GetPersonalIdentityV2 or BG.InsolvencyCheck")
    fun operation(@PathParam("operation") operation: String, body: RegixRequest?): Map<String, Any?> {
        val request = requireNotNull(body) { "request body is required" }
        val egn = requireNotNull(request.egn) { "egn is required" }
        return when (operation) {
            GRAO -> {
                val r = desk.grao(egn) ?: throw NotFoundException("GRAO has no person with this EGN")
                mapOf(
                    "egn" to r.egn,
                    "first_name" to r.firstName,
                    "sur_name" to r.surName,
                    "family_name" to r.familyName,
                    "birth_date" to r.birthDate?.toString(),
                    "gender" to r.sex?.code,
                )
            }
            MVR -> {
                val number = requireNotNull(request.documentNumber) { "document_number is required" }
                val s = desk.mvr(egn, number)
                mapOf(
                    "document_number" to s.documentNumber,
                    "status" to if (s.valid) "VALID" else "INVALID",
                    "valid_to" to s.validTo?.toString(),
                )
            }
            INSOLVENCY -> mapOf(
                "proceedings" to desk.bgInsolvency(egn).map {
                    mapOf(
                        "case_number" to it.caseNumber,
                        "court" to it.court,
                        "status" to it.state,
                        "opened_on" to it.startedOn.toString(),
                    )
                },
            )
            else -> throw NotFoundException("unknown RegiX operation")
        }
    }

    private companion object {
        const val GRAO = "GRAO.PersonDataSearch"
        const val MVR = "MVR.GetPersonalIdentityV2"
        const val INSOLVENCY = "BG.InsolvencyCheck"
    }
}

data class RegixRequest(val egn: String? = null, @JsonProperty("document_number") val documentNumber: String? = null)
