// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.usecase.OnboardingStatusService
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import java.util.UUID

/**
 * `GET /api/v1/accounts/onboarding-status?partyId=&legalName=` → `{"state": "OPEN" | "IN_REVIEW" | "PENDING"}`.
 * customer-edge calls it for the app (partyId from the customer's JWT, legalName from party-service).
 */
@Path("/api/v1/accounts/onboarding-status")
@Produces(MediaType.APPLICATION_JSON)
class OnboardingStatusResource(private val service: OnboardingStatusService) {

    data class OnboardingStatusResponse(val state: String)

    @GET
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#partyId")
    suspend fun status(
        @QueryParam("partyId") partyId: UUID?,
        @QueryParam("legalName") legalName: String?,
    ): OnboardingStatusResponse {
        if (partyId == null || legalName.isNullOrBlank()) throw BadRequestException("partyId and legalName are required")
        return OnboardingStatusResponse(service.state(partyId, legalName.trim()).name)
    }
}
