// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.rest

import com.openbank.kyc.application.KycService
import com.openbank.kyc.domain.model.AssessedCheck
import com.openbank.kyc.domain.model.CheckType
import com.openbank.kyc.domain.model.KycAssessment
import com.openbank.kyc.domain.model.RiskLevel
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * The onboarding assessment (experience v2 step 9). The onboarding orchestrator reports every check
 * it ran with the risk and evidence; kyc-service decides — straight through for a clean LOW-risk
 * applicant when enabled, otherwise UNDER_REVIEW for a person. Only the onboarding service's own
 * identity may call it (OPA pins the principal, kyc_rest_ext.rego).
 */
@Path("/api/v1/kyc/cases/party/{partyId}/assessment")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "KYC")
class KycAssessmentResource {

    @Inject lateinit var kycService: KycService

    @POST
    @RolesAllowed(Roles.API, Roles.ADMIN, Roles.KYC_OPENER)
    @Authorize(action = "kyc.case.assess", resource = "#partyId")
    @Operation(summary = "Apply an onboarding assessment to the party's KYC case (straight-through or review)")
    suspend fun assess(
        @PathParam("partyId") partyId: UUID,
        req: AssessmentRequest?,
        @Context secCtx: SecurityContext,
    ): Response {
        requireNotNull(req) { "request body is required" }
        val actor = secCtx.userPrincipal?.name ?: return Response.status(Response.Status.UNAUTHORIZED).build()
        return Response.ok(kycService.applyAssessment(partyId, req.toDomain(), actor)).build()
    }
}

data class AssessmentRequest(
    val policyVersion: String?,
    val riskLevel: String?,
    val dueDiligence: String?,
    val checks: Map<String, AssessedCheckRequest>?,
    val reviewReasons: List<String>?,
    val evidence: Map<String, Any?>?,
) {
    fun toDomain() = KycAssessment(
        policyVersion = requireNotNull(policyVersion) { "policyVersion is required" },
        riskLevel = enumOf<RiskLevel>("riskLevel", requireNotNull(riskLevel) { "riskLevel is required" }),
        dueDiligence = dueDiligence ?: "CDD",
        checks = checks.orEmpty().entries.associate { (type, check) ->
            enumOf<CheckType>("checks", type) to AssessedCheck(
                enumOf<com.openbank.kyc.domain.model.CheckStatus>(
                    "checks.$type.status",
                    requireNotNull(check.status) {
                        "checks.$type.status is required"
                    },
                ),
                check.result,
            )
        },
        reviewReasons = reviewReasons.orEmpty(),
        evidence = evidence,
    )
}

data class AssessedCheckRequest(val status: String?, val result: String?)

private inline fun <reified E : Enum<E>> enumOf(field: String, value: String): E =
    enumValues<E>().firstOrNull { it.name == value.uppercase() }
        ?: throw IllegalArgumentException("$field: '$value' is not one of ${enumValues<E>().joinToString()}")
