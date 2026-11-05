// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.application.usecase

import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import com.openbank.sca.application.port.out.EnrolledDeviceRepository
import com.openbank.sca.domain.model.AppPin
import com.openbank.sca.domain.model.EnrolledDevice
import com.openbank.sca.domain.model.SignatureAlgorithm
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class AppPinServiceTest {
    private val party = UUID.randomUUID()
    private val verifier = "a".repeat(64)
    private val saved = mutableMapOf<String, AppPin>()
    private val pins = object : AppPinRepository {
        override suspend fun find(credentialId: String) = saved[credentialId]
        override suspend fun save(pin: AppPin) {
            saved[pin.credentialId] = pin
        }
    }
    private val devices = mockk<EnrolledDeviceRepository>()
    private val service =
        AppPinService(devices, pins, Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC))

    private fun device(owner: UUID, revoked: Boolean = false) = EnrolledDevice(
        partyId = owner,
        credentialId = "cred-1",
        publicKeySpkiB64 = "x",
        algorithm = SignatureAlgorithm.entries.first(),
        createdAt = OffsetDateTime.now(),
        revokedAt = if (revoked) OffsetDateTime.now() else null,
    )

    @Test
    fun `the PIN is stored as a salted hash of the verifier and checks only that verifier`(): Unit = runBlocking {
        coEvery { devices.findByCredentialId("cred-1") } returns device(party)
        service.setPin(party, "cred-1", verifier)
        val pin = saved.getValue("cred-1")
        assertThat(pin.pinHash).doesNotContain(verifier).hasSize(64)
        assertThat(pin.matches(verifier)).isTrue()
        assertThat(pin.matches("b".repeat(64))).isFalse()
        service.setPin(party, "cred-1", verifier)
        assertThat(saved.getValue("cred-1").salt).isNotEqualTo(pin.salt)
    }

    @Test
    fun `someone else's device and a missing one look the same, a removed device refuses`(): Unit = runBlocking {
        coEvery { devices.findByCredentialId("cred-1") } returns device(UUID.randomUUID())
        assertThatThrownBy { runBlocking { service.setPin(party, "cred-1", verifier) } }
            .isInstanceOf(ResourceNotFoundException::class.java)
        coEvery { devices.findByCredentialId("cred-1") } returns device(party, revoked = true)
        assertThatThrownBy { runBlocking { service.setPin(party, "cred-1", verifier) } }
            .isInstanceOf(ResourceConflictException::class.java)
        assertThatThrownBy { runBlocking { service.setPin(party, "cred-1", "1234") } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
