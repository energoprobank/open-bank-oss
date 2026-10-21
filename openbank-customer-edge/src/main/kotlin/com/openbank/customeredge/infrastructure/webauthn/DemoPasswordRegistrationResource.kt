// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import io.quarkus.logging.Log
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty

/** The e-mail is already bound to a Keycloak user. */
class UserAlreadyExistsException(email: String) : RuntimeException("user already exists: $email")

data class DemoCredentialsRequest(
    val email: String? = null,
    val password: String? = null,
    val displayName: String? = null,
)

data class DemoCredentialsResponse(val partyId: String, val username: String)

/**
 * DEMO-ONLY registration completion: turns the enrollment ticket from `POST /onboarding/start`
 * into a username/password login in the customers realm, instead of the passkey ceremony
 * (`/webauthn/register/begin|complete`). Passkeys need an associated domain on the `open-bank.tech` RP, which a
 * locally run backend and a Simulator/side-loaded app cannot have.
 *
 * OFF unless `openbank.demo.password-registration.enabled=true` — it answers 404 otherwise, so a
 * deployment that never sets the flag exposes nothing. Never enable it outside a local demo:
 * password login is weaker than the passkey-only production realm (ADR-0066).
 */
@Path("/customer/v1/onboarding/demo-credentials")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class DemoPasswordRegistrationResource(
    private val ticketService: EnrollmentTicketService,
    private val keycloakClient: WebAuthnKeycloakClient,
) {

    @ConfigProperty(name = "openbank.demo.password-registration.enabled", defaultValue = "false")
    var enabled: Boolean = false

    @POST
    @PermitAll
    @Blocking
    fun register(@HeaderParam("Authorization") authorization: String?, request: DemoCredentialsRequest?): Response {
        if (!enabled) return Response.status(Response.Status.NOT_FOUND).build()

        val ticket = authorization?.removePrefix("Bearer ")?.trim()
        val partyId = ticket?.takeIf { it.isNotEmpty() }?.let(ticketService::verify)
            ?: return problem(Response.Status.UNAUTHORIZED, "Invalid or expired enrollment ticket")

        val email = request?.email?.trim()?.lowercase()
        val password = request?.password
        if (email.isNullOrEmpty() || !EMAIL.matches(email)) {
            return problem(Response.Status.BAD_REQUEST, "A valid e-mail is required")
        }
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            return problem(Response.Status.BAD_REQUEST, "Password must be at least $MIN_PASSWORD_LENGTH characters")
        }

        return try {
            keycloakClient.createPasswordUser(
                email = email,
                partyId = partyId,
                displayName = request.displayName?.takeIf { it.isNotBlank() } ?: "Energo-Pro Bank Customer",
                password = password,
            )
            Log.infof("demo password registration created a login for party %s", partyId)
            Response.status(Response.Status.CREATED).entity(DemoCredentialsResponse(partyId, email)).build()
        } catch (e: UserAlreadyExistsException) {
            Log.infof("demo password registration refused for party %s: %s", partyId, e.message)
            problem(Response.Status.CONFLICT, "This e-mail is already registered")
        }
    }

    private fun problem(status: Response.Status, detail: String): Response =
        Response.status(status).entity(mapOf("status" to status.statusCode, "detail" to detail)).build()

    companion object {
        private const val MIN_PASSWORD_LENGTH = 8
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
