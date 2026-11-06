// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.port.out

import com.openbank.cardissuance.domain.model.FactoryStatus

/** The card manufacturer and its courier (locally: openbank-provider-simulator). */
interface CardFactoryPort {
    /** Places the order (the same [reference] twice answers the first order); returns the factory's id. */
    suspend fun place(reference: String, design: String, nameOnCard: String, city: String, country: String): String

    suspend fun status(factoryRef: String): FactoryStatus
}

/** In-app provisioning data from the wallet's token service (Apple Pay). */
data class WalletProvisioning(
    val activationData: String,
    val encryptedPassData: String,
    val ephemeralPublicKey: String,
    val tokenReference: String,
    val tokenLast4: String,
)

interface WalletTokenServicePort {
    suspend fun provision(
        cardReference: String,
        certificates: List<String>,
        nonce: String,
        nonceSignature: String,
    ): WalletProvisioning
}
