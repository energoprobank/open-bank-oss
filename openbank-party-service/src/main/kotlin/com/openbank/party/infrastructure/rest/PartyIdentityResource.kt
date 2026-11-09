// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.authz.Authorize
import com.openbank.party.application.port.`in`.PartyUseCase
import com.openbank.party.application.port.`in`.VerifiedIdentityCommand
import com.openbank.party.domain.model.DocumentType
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * The identity side of a person's record (experience v2 step 9): what the bank verified at account
 * opening, and — for those who need it — the national number, which is otherwise stored encrypted
 * and shown masked. Every reveal is audited.
 */
@Path("/api/v1/parties/{id}/identity")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Parties")
class PartyIdentityResource {

    @Inject lateinit var partyUseCase: PartyUseCase

    @Inject lateinit var auditPublisher: AuditEventPublisher

    @Inject lateinit var securityIdentity: SecurityIdentity

    @PUT
    @Path("/verified")
    @RolesAllowed("ROLE_API", "ROLE_ADMIN")
    @Authorize(action = "party.verified-identity", resource = "#id")
    @Operation(summary = "Record how the bank verified the person (method and verified document)")
    suspend fun recordVerified(@PathParam("id") id: UUID, req: VerifiedIdentityRequest?): Response {
        val body = requireNotNull(req) { "request body is required" }
        val type = body.documentType?.let { t ->
            DocumentType.entries.firstOrNull { it.name == t.uppercase() }
                ?: throw IllegalArgumentException("documentType must be one of ${DocumentType.entries.joinToString()}")
        }
        val party = partyUseCase.recordVerifiedIdentity(
            VerifiedIdentityCommand(
                id,
                requireNotNull(body.method) { "method is required" }.uppercase(),
                type,
                body.documentNumber?.trim(),
                body.issuingCountry?.trim(),
                body.expiryDate,
            ),
        )
        return Response.ok(party.toResponse()).build()
    }

    /** Step 9 backfill (operators): record a person's national number that source data states. Audited. */
    @PUT
    @Path("/national-id")
    @RolesAllowed("ROLE_ADMIN")
    @Authorize(action = "party.national-id.set", resource = "#id")
    @Operation(summary = "Record a missing national number (set once, validated, deduplicated; audited)")
    suspend fun backfill(@PathParam("id") id: UUID, req: NationalIdRequest?): Response {
        val party = partyUseCase.backfillNationalId(id, requireNotNull(req?.nationalId) { "nationalId is required" })
        auditPublisher.publish(
            AuditEvent(
                actorId = securityIdentity.principal?.name ?: "unknown",
                actorType = "USER",
                operation = "party.national-id.backfilled",
                resourceType = "party",
                resourceId = id.toString(),
                result = AuditResult.SUCCESS,
                payload = emptyMap(),
            ),
        )
        return Response.ok(party.toResponse()).build()
    }

    @GET
    @Path("/national-id")
    @RolesAllowed("ROLE_API", "ROLE_KYC", "ROLE_KYC_REVIEWER", "ROLE_COMPLIANCE", "ROLE_ADMIN")
    @Authorize(action = "party.national-id.reveal", resource = "#id")
    @Operation(summary = "The person's national number in clear (audited)")
    suspend fun reveal(@PathParam("id") id: UUID): Response {
        val party = partyUseCase.getParty(id)
        val actor = securityIdentity.principal?.name ?: "unknown"
        auditPublisher.publish(
            AuditEvent(
                actorId = actor,
                actorType = if (actor.startsWith("service-account-")) "SERVICE" else "USER",
                operation = "party.national-id.revealed",
                resourceType = "party",
                resourceId = id.toString(),
                result = AuditResult.SUCCESS,
                payload = mapOf("present" to (party.taxId != null).toString()),
            ),
        )
        return Response.ok(mapOf("partyId" to id, "market" to party.market, "nationalId" to party.taxId)).build()
    }
}

data class NationalIdRequest(val nationalId: String? = null)

data class VerifiedIdentityRequest(
    val method: String? = null,
    val documentType: String? = null,
    val documentNumber: String? = null,
    val issuingCountry: String? = null,
    val expiryDate: String? = null,
)
