// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.application.usecase

import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import com.openbank.sca.application.port.out.EnrolledDeviceRepository
import com.openbank.sca.domain.model.AppPin
import com.openbank.sca.domain.model.AppPinHasher
import jakarta.enterprise.context.ApplicationScoped
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

interface AppPinRepository {
    suspend fun find(credentialId: String): AppPin?

    suspend fun save(pin: AppPin)
}

/**
 * Sets the app PIN of one of the party's enrolled devices (experience v2 step 8). A credential that is
 * not the party's answers the same 404 as one that does not exist, so the route reveals nothing about
 * other customers' devices.
 */
@ApplicationScoped
class AppPinService(
    private val devices: EnrolledDeviceRepository,
    private val pins: AppPinRepository,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    suspend fun setPin(partyId: UUID, credentialId: String, verifier: String) {
        require(AppPinHasher.VERIFIER.matches(verifier)) { "pin_verifier must be 64 lowercase hex characters" }
        val device = devices.findByCredentialId(credentialId)?.takeIf { it.partyId == partyId }
            ?: throw ResourceNotFoundException("No such device", "DEVICE_NOT_FOUND")
        if (device.revokedAt != null) throw ResourceConflictException("This device has been removed", "DEVICE_REVOKED")
        val now = clock.instant()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val existing = pins.find(credentialId)
        pins.save(
            AppPin(
                credentialId = credentialId,
                partyId = partyId,
                salt = salt,
                iterations = AppPinHasher.ITERATIONS,
                pinHash = AppPinHasher.hash(verifier, salt),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    private companion object {
        const val SALT_BYTES = 16
    }
}
