// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
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
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.math.BigDecimal
import java.util.UUID

@Path("/customer/v1/cards/physical")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class PhysicalCardEdgeResource(private val s: CardEdgeSupport, parties: CustomerPartyResolver) :
    SignedInExperience(s.edge, parties) {

    /** The fee (the first plastic card is free), the designs, the address on file and the delivery time. */
    @GET
    @Path("/options")
    @Authorize(action = "customer.cards.read")
    @Blocking
    fun options(@QueryParam("account_id") accountId: UUID?): Response {
        val party = party()
        val account = s.ownAccount(party, requireNotNull(accountId) { "query parameter 'account_id' is required" })
            ?: return edge.error(ExperienceEdge.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No such account")
        val currency = account.text("currencyCode") ?: "CZK"
        val cards = EdgeJson.parse(edge.upstream.get(s.cards("/api/v1/cards/party/$party"), party.toString()))
        val plastic =
            cards?.count { it.text("cardType") == "DEBIT" && it.text("status") !in setOf("CANCELLED", "EXPIRED") } ?: 0
        val partyNode = EdgeJson.parse(
            edge.upstream.get("${edge.partyUrl.trimEnd('/')}/api/v1/parties/$party", party.toString()),
        )
        val address = partyNode?.path("address")?.takeIf { it.isObject }?.let {
            mapOf(
                "line1" to it.text("line1"),
                "line2" to it.text("line2"),
                "city" to it.text("city"),
                "postal_code" to it.text("postalCode"),
                "country_code" to it.text("countryCode"),
            )
        }
        val fee = if (plastic == 0) {
            BigDecimal.ZERO
        } else if (currency == "EUR") {
            BigDecimal("8")
        } else {
            BigDecimal("200")
        }
        return EdgeJson.ok(
            mapOf(
                "fee" to fee,
                "currency" to currency,
                "designs" to listOf("energoBlue", "midnight", "hydro", "copper"),
                "address_on_file" to address,
                "delivery_working_days" to DELIVERY_DAYS,
            ),
        )
    }

    /** Issues the plastic card (PENDING until the customer activates it) and sends it to the manufacturer. */
    @POST
    @Authorize(action = "customer.cards.issue")
    @Blocking
    fun order(body: String?): Response {
        val party = party()
        val input =
            edge.objectBody(body)
                ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "body must be a JSON object")
        val accountId = input.text("account_id")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "account_id is required")
        val account =
            s.ownAccount(party, accountId)
                ?: return edge.error(ExperienceEdge.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No such account")
        val name = input.text("name_on_card")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
            ?: return edge.error(
                ExperienceEdge.BAD_REQUEST,
                "INVALID_REQUEST",
                "name_on_card is 1..$MAX_NAME characters",
            )
        val address = input.path("delivery_address") as? ObjectNode
            ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "delivery_address is required")
        s.sca(
            party,
            input.text("sca_challenge_id"),
            accountId.toString(),
            "ORDER_PHYSICAL",
            "cards.physical.order",
        )?.let {
            return it
        }

        val issued = issue(party, accountId, account, name, address)
        val cardId = EdgeJson.parse(issued)?.text("id")
        if (issued.status !in ExperienceEdge.SUCCESS || cardId == null) return edge.relay(issued)
        val order = register(party, accountId, cardId, input.text("design") ?: "energoBlue", name, address)
        return s.audited(party, "cards.physical.order", "CUSTOMER_PHYSICAL_CARD_ORDERED", cardId, edge.relay(order))
    }

    /** Issues the DEBIT card (PENDING until the customer activates the delivered plastic). */
    private fun issue(party: UUID, accountId: UUID, account: JsonNode, name: String, address: ObjectNode): Response {
        val currency = account.text("currencyCode") ?: "CZK"
        val productCode = account.text("productId")?.let { pid ->
            EdgeJson.parse(
                edge.upstream.get("${s.catalogUrl.trimEnd('/')}/api/v1/products/$pid", party.toString()),
            )?.text("code")
        } ?: "CURRENT_$currency"
        val line = listOfNotNull(
            address.text("line1"),
            address.text("line2"),
            "${address.text("postal_code").orEmpty()} ${address.text("city").orEmpty()}".trim(),
        ).joinToString(", ")
        val body = mapOf(
            "partyId" to party.toString(), "accountId" to accountId.toString(), "productCode" to productCode,
            "cardType" to "DEBIT",
            "network" to "VISA", "cardholderName" to name, "embossedName" to name.uppercase(), "currency" to currency,
            "deliveryAddress" to line,
        )
        return edge.upstream.post(
            s.cards("/api/v1/cards"),
            party.toString(),
            edge.json(body),
            "physical-card-${UUID.randomUUID()}",
        )
    }

    /** Registers the plastic card order with card-issuance, which sends it to the manufacturer. */
    @Suppress("LongParameterList")
    private fun register(
        party: UUID,
        accountId: UUID,
        cardId: String,
        design: String,
        name: String,
        a: ObjectNode,
    ): Response {
        val address = mapOf(
            "line1" to a.text("line1"),
            "line2" to a.text("line2"),
            "city" to a.text("city"),
            "postal_code" to a.text("postal_code"),
            "country_code" to (a.text("country_code") ?: "CZ"),
        )
        val body = mapOf(
            "party_id" to party.toString(),
            "account_id" to accountId.toString(),
            "card_id" to cardId,
            "design" to design,
            "name_on_card" to name,
            "delivery_address" to address,
        )
        return edge.upstream.post(s.cards("/api/v1/card-orders"), party.toString(), edge.json(body))
    }

    @GET
    @Path("/orders")
    @Authorize(action = "customer.cards.read")
    @Blocking
    fun orders(): Response = party().let {
        edge.relay(edge.upstream.get(s.cards("/api/v1/card-orders?partyId=$it"), it.toString()))
    }

    @GET
    @Path("/orders/{orderId}")
    @Authorize(action = "customer.cards.read")
    @Blocking
    fun order(@PathParam("orderId") orderId: UUID): Response = party().let {
        edge.relay(edge.upstream.get(s.cards("/api/v1/card-orders/$orderId?partyId=$it"), it.toString()))
    }

    private companion object {
        const val MAX_NAME = 26
        const val DELIVERY_DAYS = 5
    }
}
