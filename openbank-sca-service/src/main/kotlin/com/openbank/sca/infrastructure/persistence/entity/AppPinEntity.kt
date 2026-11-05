// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.persistence.entity

import com.openbank.sca.domain.model.AppPin
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** V18 `sca_app_pins`: one row per device credential. The id is application-assigned — save with `merge`. */
@Entity
@Table(name = "sca_app_pins")
class AppPinEntity {
    @Id
    @Column(name = "credential_id")
    lateinit var credentialId: String

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "salt", nullable = false)
    lateinit var salt: String

    @Column(name = "iterations", nullable = false)
    var iterations: Int = 0

    @Column(name = "pin_hash", nullable = false)
    lateinit var pinHash: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    fun toDomain() = AppPin(credentialId, partyId, salt, iterations, pinHash, createdAt, updatedAt)

    companion object {
        fun from(pin: AppPin) = AppPinEntity().apply {
            credentialId = pin.credentialId
            partyId = pin.partyId
            salt = pin.salt
            iterations = pin.iterations
            pinHash = pin.pinHash
            createdAt = pin.createdAt
            updatedAt = pin.updatedAt
        }
    }
}
