// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.domain.model

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The app PIN of one enrolled device (experience v2 step 8): the fallback to Face ID. The bank never
 * sees the PIN — the app sends a verifier — and keeps only a salted PBKDF2 of that verifier, so a
 * leaked table does not give away anything the app would accept.
 */
data class AppPin(
    val credentialId: String,
    val partyId: UUID,
    val salt: String,
    val iterations: Int,
    val pinHash: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun matches(verifier: String): Boolean =
        MessageDigest.isEqual(AppPinHasher.hash(verifier, salt, iterations).toByteArray(), pinHash.toByteArray())
}

object AppPinHasher {
    /** OWASP 2023 guidance for PBKDF2-HMAC-SHA256. */
    const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    /** A verifier is 64 lowercase hex characters (SHA-256); anything else is not one. */
    val VERIFIER = Regex("^[0-9a-f]{64}$")

    fun hash(verifier: String, saltHex: String, iterations: Int = ITERATIONS): String {
        val spec = PBEKeySpec(verifier.toCharArray(), hexToBytes(saltHex), iterations, KEY_BITS)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded.toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String) = hex.chunked(2).map { it.toInt(HEX).toByte() }.toByteArray()

    private const val HEX = 16
}
