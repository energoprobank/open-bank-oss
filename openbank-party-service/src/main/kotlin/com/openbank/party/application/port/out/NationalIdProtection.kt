// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.port.out

import java.util.UUID

/**
 * Encryption at rest for a natural person's national number (rodné číslo / ЕГН), experience v2
 * step 9. The ciphertext is bound to the party it belongs to, so a value copied onto another row
 * does not decrypt. [enabled] is false only where no key is configured (and not required).
 */
interface NationalIdProtection {
    val enabled: Boolean

    /** Ciphertext with its key-version prefix (`v1:...`). */
    fun seal(partyId: UUID, nationalId: String): String

    fun open(partyId: UUID, sealed: String): String
}
