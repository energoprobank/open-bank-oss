// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.referral.infrastructure.rest

import com.openbank.referral.application.ReferralLookupService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

// Experience v2 step 8: read-only lookups for account opening, machine callers (customer-edge) only.
// Each class carries its full path, so neither captures a route of ReferralResource (/api/v1/referrals).

data class InvitePreview(val valid: Boolean, val referrerPartyId: UUID?)

/** Who sent this invite, while it can still be used — "Adam invited you" before sign-in. */
@Path("/api/v1/referrals/invites/{token}/preview")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class InvitePreviewResource(private val lookup: ReferralLookupService) {
    @GET
    suspend fun preview(@PathParam("token") token: String): Response {
        val referrer = lookup.previewReferrer(token)
        return Response.ok(InvitePreview(valid = referrer != null, referrerPartyId = referrer)).build()
    }
}

/** Who referred a customer (welcome screen). 404 when nobody did. */
@Path("/api/v1/referrals/referees/{partyId}/referrer")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class RefereeReferrerResource(private val lookup: ReferralLookupService) {
    @GET
    suspend fun referrer(@PathParam("partyId") partyId: UUID): Response = lookup.referrerOf(partyId)
        ?.let { Response.ok(mapOf("referrerPartyId" to it)).build() }
        ?: Response.status(Response.Status.NOT_FOUND).build()
}
