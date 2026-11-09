// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.infrastructure.rest

import com.openbank.providersimulator.application.BankIdOidcDesk
import com.openbank.providersimulator.domain.Applicant
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * SIMULATOR side channel (no real Bank iD has one): the bank says who is at the keyboard for an
 * authorization `state`, because a real Bank iD knows that from the person's own bank login.
 */
@Path("/api/v1/bankid/oidc/expected-logins")
@Tag(name = "Bank iD OIDC (simulated)")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class BankIdExpectedLoginResource {
    @Inject
    lateinit var desk: BankIdOidcDesk

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Expect a login: the person who will confirm the /bankid/auth request with this state")
    fun expect(body: ExpectedLoginRequest?): Response {
        val request = requireNotNull(body) { "request body is required" }
        val applicant = requireNotNull(request.applicant) { "applicant is required" }
        val login = desk.expect(
            requireNotNull(request.state) { "state is required" },
            Applicant(
                givenName = applicant.givenName.orEmpty(),
                familyName = applicant.familyName.orEmpty(),
                dateOfBirth = applicant.dateOfBirth,
                nationalId = applicant.nationalId,
            ),
        )
        val expiresAt = login.at.plus(BankIdOidcDesk.LOGIN_LIFE).toString()
        return Response.status(Response.Status.CREATED)
            .entity(mapOf("state" to login.state, "expires_at" to expiresAt)).build()
    }
}

data class ExpectedLoginRequest(val state: String? = null, val applicant: ApplicantDto? = null)
