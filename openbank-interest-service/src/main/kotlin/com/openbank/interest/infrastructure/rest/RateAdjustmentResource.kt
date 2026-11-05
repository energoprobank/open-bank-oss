// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.rest

import com.openbank.interest.application.port.`in`.ManageRateAdjustmentUseCase
import com.openbank.interest.domain.model.RateAdjustment
import com.openbank.interest.infrastructure.rest.dto.CreateRateAdjustmentRequest
import com.openbank.interest.infrastructure.rest.dto.RateAdjustmentResponse
import com.openbank.interest.infrastructure.rest.dto.toResponse
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Additive, time-boxed rate adjustments on a deposit account (FLUX rewards "Deposit Rate Boost").
 *
 * Authorization is identity-scoped in `interest_rest_ext.rego`: create and cancel are granted ONLY
 * to `service-account-openbank-rewards`; list additionally to human operators/admins. The action
 * names deliberately avoid the `.list`/`.read`/`.create` suffixes so no generic base-policy reason
 * (operator-read-any, party-self-service, the role/action matrix) can reach them.
 * `@RolesAllowed` is the coarse first layer: the rewards client carries ROLE_API.
 */
@Path("/api/v1/interest/accounts/{accountId}/rate-adjustments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Rate adjustments", description = "Additive time-boxed rate adjustments (rewards rate boost)")
class RateAdjustmentResource(
    private val useCase: ManageRateAdjustmentUseCase,
    private val identity: SecurityIdentity,
    private val clock: Clock,
) {
    @POST
    @Operation(summary = "Add a rate adjustment to an account (idempotent on sourceRef)")
    @RolesAllowed("ROLE_API")
    @Authorize(action = "interest.adjust", resource = "#accountId")
    fun create(@PathParam("accountId") accountId: UUID, request: CreateRateAdjustmentRequest?): Uni<Response> {
        requireNotNull(request) { "request body is required" }
        val adjustment = RateAdjustment(
            accountId = accountId,
            deltaBps = requireNotNull(request.deltaBps) { "deltaBps is required" },
            validFrom = requireNotNull(request.validFrom) { "validFrom is required" },
            validTo = requireNotNull(request.validTo) { "validTo is required" },
            source = requireNotNull(request.source) { "source is required" },
            sourceRef = requireNotNull(request.sourceRef) { "sourceRef is required" },
            approvalRef = requireNotNull(request.approvalRef) { "approvalRef is required" },
            createdBy = actor(),
            createdAt = OffsetDateTime.now(clock),
        )
        return useCase.create(adjustment).map { result ->
            Response.status(if (result.changed) CREATED else OK).entity(result.toResponse()).build()
        }
    }

    @GET
    @Operation(summary = "List an account's rate adjustments (every status, newest first)")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "interest.adjustList", resource = "#accountId")
    fun list(@PathParam("accountId") accountId: UUID): Uni<List<RateAdjustmentResponse>> =
        useCase.list(accountId).map { adjustments -> adjustments.map { it.toResponse() } }

    @DELETE
    @Path("/{adjustmentId}")
    @Operation(summary = "Cancel a rate adjustment (compensation; idempotent)")
    @RolesAllowed("ROLE_API")
    @Authorize(action = "interest.adjustCancel", resource = "#accountId")
    fun cancel(@PathParam("accountId") accountId: UUID, @PathParam("adjustmentId") adjustmentId: UUID): Uni<Response> =
        useCase.cancel(accountId, adjustmentId, actor())
            .map { Response.ok(it.toResponse()).build() }

    private fun actor(): String = identity.principal?.name?.takeIf { it.isNotBlank() } ?: "unknown"

    private companion object {
        const val CREATED = 201
        const val OK = 200
    }
}
