// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.crypto

import com.openbank.party.application.port.out.NationalIdProtection
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.Optional
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for the national number (same shape as card-issuance's `AesGcmCardSecretCipher`):
 * `v<version>:` + base64(IV ‖ ciphertext ‖ tag), with the party id as additional authenticated data.
 *
 * `@Startup` so a misconfiguration fails the boot, not the first onboarding request: a key that is
 * not 32 bytes always fails; a missing key fails when `openbank.party.national-id.encryption-required`
 * is true (set wherever real customers are onboarded) and otherwise leaves the number unencrypted —
 * the pre-step-9 behaviour — with a warning at boot.
 */
@Startup
@ApplicationScoped
class AesGcmNationalIdCipher(
    @ConfigProperty(name = "openbank.party.national-id.key")
    key: Optional<String>,
    @ConfigProperty(name = "openbank.party.national-id.encryption-required", defaultValue = "false")
    required: Boolean,
    @ConfigProperty(name = "openbank.party.national-id.key-version", defaultValue = "1")
    private val keyVersion: Int,
) : NationalIdProtection {

    private val secret: SecretKeySpec? = key.filter { it.isNotBlank() }.map { b64 ->
        val bytes = Base64.getDecoder().decode(b64.trim())
        check(bytes.size == KEY_BYTES) { "openbank.party.national-id.key must be 32 bytes (base64), got ${bytes.size}" }
        SecretKeySpec(bytes, "AES")
    }.orElse(null)

    private val random = SecureRandom()

    init {
        if (secret == null) {
            check(!required) {
                "openbank.party.national-id.key is required (encryption-required=true): national numbers " +
                    "must not be stored in clear"
            }
            Logger.getLogger(AesGcmNationalIdCipher::class.java).warn(
                "openbank.party.national-id.key is not set: national numbers are stored UNENCRYPTED (dev only)",
            )
        }
    }

    override val enabled: Boolean get() = secret != null

    override fun seal(partyId: UUID, nationalId: String): String {
        val k = requireNotNull(secret) { "no national-id key configured" }
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(partyId))
        val ct = cipher.doFinal(nationalId.toByteArray(Charsets.UTF_8))
        return "v$keyVersion:" + Base64.getEncoder().encodeToString(iv + ct)
    }

    override fun open(partyId: UUID, sealed: String): String {
        val k = requireNotNull(secret) { "no national-id key configured" }
        val payload = Base64.getDecoder().decode(sealed.substringAfter(':'))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES))
        cipher.updateAAD(aad(partyId))
        return String(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun aad(partyId: UUID): ByteArray = ByteBuffer.allocate(
        UUID_BYTES,
    ).putLong(partyId.mostSignificantBits).putLong(partyId.leastSignificantBits).array()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val UUID_BYTES = 16
    }
}
