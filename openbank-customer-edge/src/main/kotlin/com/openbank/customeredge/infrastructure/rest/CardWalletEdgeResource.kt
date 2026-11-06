// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

/** Apple Wallet: whether the card is in a wallet, and in-app provisioning data for adding it. */
@Path("/customer/v1/cards/{id}/wallet")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CardWalletEdgeResource(private val s: CardEdgeSupport, parties: CustomerPartyResolver) :
    SignedInExperience(s.edge, parties) {
    @GET
    @Authorize(action = "customer.cards.read", resource = "#id")
    @Blocking
    fun status(@PathParam("id") id: UUID): Response {
        val party = party()
        s.ownCard(party, id) ?: return s.noSuchCard()
        val url = s.processing("/api/v1/card-tokens/card/$id")
            ?: return EdgeJson.ok(mapOf("in_wallet" to false, "device_name" to null, "token_last4" to null))
        val tokens = EdgeJson.parse(edge.upstream.get(url, party.toString()))
        // card-processing answers {tokens, source, degradedReason, count}.
        val active = tokens?.path("tokens")?.firstOrNull {
            it.text("status") ==
                "ACTIVE"
        }
        return EdgeJson.ok(
            mapOf(
                "in_wallet" to (active != null),
                "device_name" to active?.text("requestorLabel"),
                "token_last4" to active?.text("last4"),
            ),
        )
    }

    /** PassKit's certificates and nonce in, the token service's provisioning data out; the token is registered with card-processing. */
    @POST
    @Path("/provision")
    @Authorize(action = "customer.cards.wallet", resource = "#id")
    @Blocking
    fun provision(@PathParam("id") id: UUID, body: String?): Response {
        val party = party()
        s.ownCard(party, id) ?: return s.noSuchCard()
        val input =
            edge.objectBody(body)
                ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "body must be a JSON object")
        val data = edge.upstream.post(
            s.cards("/api/v1/cards/$id/wallet-provisioning-data"),
            party.toString(),
            edge.json(
                mapOf(
                    "certificates" to input.path("certificates").takeIf { it.isArray }?.map { it.asText() },
                    "nonce" to input.text("nonce"),
                    "nonce_signature" to input.text("nonce_signature"),
                ),
            ),
        )
        if (data.status !in ExperienceEdge.SUCCESS) return edge.relay(data, "WALLET_UNAVAILABLE")
        val node = EdgeJson.parse(data) as? ObjectNode ?: return edge.relay(data, "WALLET_UNAVAILABLE")
        s.processing("/api/v1/card-tokens")?.let { url ->
            edge.upstream.post(
                url,
                party.toString(),
                edge.json(
                    mapOf(
                        "cardId" to id.toString(),
                        "requestorId" to "APPLE_PAY",
                        "requestorLabel" to "Apple Wallet",
                    ),
                ),
                "wallet-$id-APPLE_PAY",
            )
        }
        node.remove("token_reference")
        node.remove("token_last4")
        s.audited(party, "cards.wallet.provision", "CUSTOMER_CARD_WALLET_PROVISIONED", id.toString(), data)
        return Response.ok(edge.json(node)).type(MediaType.APPLICATION_JSON).header("Cache-Control", "no-store").build()
    }
}
