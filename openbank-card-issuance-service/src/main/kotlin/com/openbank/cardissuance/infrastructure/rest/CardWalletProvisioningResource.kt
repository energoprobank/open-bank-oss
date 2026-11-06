// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.openbank.cardissuance.application.usecase.CardSelfServiceService
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.util.UUID

@Path("/api/v1/cards/{id}/wallet-provisioning-data")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(SELF_SERVICE_CALLERS)
class CardWalletProvisioningResource(private val service: CardSelfServiceService) {
    @POST
    @Authorize(action = "card.wallet.provision", resource = "#id")
    @Operation(summary = "In-app Apple Wallet provisioning data from the wallet's token service")
    suspend fun provision(@PathParam("id") id: UUID, body: WalletRequest?): Response {
        val r = requireNotNull(body) { "request body is required" }
        val data = service.walletProvisioning(
            id,
            requireNotNull(r.certificates) { "certificates are required" },
            requireNotNull(r.nonce) {
                "nonce is required"
            },
            requireNotNull(r.nonceSignature) { "nonce_signature is required" },
        )
        return Response.ok(WalletView.of(data)).header("Cache-Control", "no-store").build()
    }
}
