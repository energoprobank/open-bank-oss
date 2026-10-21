// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DemoPasswordRegistrationResourceTest {

    private val partyId = "5f0c2a1e-0000-4000-8000-000000000001"
    private val tickets = mockk<EnrollmentTicketService> {
        every { verify("good-ticket") } returns partyId
        every { verify("bad-ticket") } returns null
    }
    private val keycloak = mockk<WebAuthnKeycloakClient>()

    private fun resource(enabled: Boolean = true) =
        DemoPasswordRegistrationResource(tickets, keycloak).also { it.enabled = enabled }

    private val valid = DemoCredentialsRequest("Petr@Example.com", "correct horse", "Petr Ukázka")

    @Test
    fun `is invisible unless the demo flag is on`() {
        val response = resource(enabled = false).register("Bearer good-ticket", valid)

        assertThat(response.status).isEqualTo(404)
        verify(exactly = 0) { keycloak.createPasswordUser(any(), any(), any(), any()) }
    }

    @Test
    fun `rejects a missing or invalid enrollment ticket`() {
        assertThat(resource().register(null, valid).status).isEqualTo(401)
        assertThat(resource().register("Bearer bad-ticket", valid).status).isEqualTo(401)
        verify(exactly = 0) { keycloak.createPasswordUser(any(), any(), any(), any()) }
    }

    @Test
    fun `validates e-mail and password before touching Keycloak`() {
        assertThat(resource().register("Bearer good-ticket", valid.copy(email = "nope")).status).isEqualTo(400)
        assertThat(resource().register("Bearer good-ticket", valid.copy(password = "short")).status).isEqualTo(400)
        assertThat(resource().register("Bearer good-ticket", null).status).isEqualTo(400)
        verify(exactly = 0) { keycloak.createPasswordUser(any(), any(), any(), any()) }
    }

    @Test
    fun `binds the new login to the party from the ticket, never from the body`() {
        every { keycloak.createPasswordUser(any(), any(), any(), any()) } returns "kc-user-1"

        val response = resource().register("Bearer good-ticket", valid)

        assertThat(response.status).isEqualTo(201)
        assertThat(response.entity).isEqualTo(DemoCredentialsResponse(partyId, "petr@example.com"))
        verify { keycloak.createPasswordUser("petr@example.com", partyId, "Petr Ukázka", "correct horse") }
    }

    @Test
    fun `answers 409 when the e-mail is already registered`() {
        every { keycloak.createPasswordUser(any(), any(), any(), any()) } throws
            UserAlreadyExistsException("petr@example.com")

        assertThat(resource().register("Bearer good-ticket", valid).status).isEqualTo(409)
    }
}
