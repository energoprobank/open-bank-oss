// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

// Experience v2 step 4 — cards beyond issuance. card-issuance owns the PIN, design, plastic orders,
// travel notices and card notifications; card-processing the authorisations and wallet tokens. The
// card is always checked to be the customer's own (its holder) first; the PIN and a plastic order
// also need a CARD_MANAGEMENT SCA challenge bound to the card (or, for an order, the account) and the
// action — VIEW_PIN, CHANGE_PIN, ORDER_PHYSICAL — consumed exactly once.

/** URLs, ownership and the card SCA gate shared by the step-4 card routes. */
@ApplicationScoped
class CardEdgeSupport(val edge: ExperienceEdge, private val audit: EdgeAuditPublisher) {
    @ConfigProperty(name = "openbank.edge.account-service-url")
    lateinit var accountUrl: String

    @ConfigProperty(
        name = "openbank.edge.product-catalog-url",
        defaultValue = "http://product-catalog.accounts.svc:8104",
    )
    lateinit var catalogUrl: String

    fun cards(path: String) = "${edge.cardIssuanceUrl.trimEnd('/')}$path"

    fun processing(path: String): String? = edge.cardProcessingUrl.map {
        it.trim().trimEnd('/')
    }.filter { it.isNotEmpty() }.orElse(null)?.let { "$it$path" }

    /** The card, when its holder is [party]; null otherwise (answered as "no such card", never as "not yours"). */
    fun ownCard(party: UUID, card: UUID): JsonNode? {
        val r = edge.upstream.get(cards("/api/v1/cards/$card"), party.toString())
        val node = EdgeJson.parse(r) ?: return null
        return node.takeIf { r.status in ExperienceEdge.SUCCESS && node.text("ownerPartyId") == party.toString() }
    }

    fun ownAccount(party: UUID, account: UUID): JsonNode? {
        val r = edge.upstream.get("${accountUrl.trimEnd('/')}/api/v1/accounts/$account", party.toString())
        val node = EdgeJson.parse(r) ?: return null
        return node.takeIf { r.status in ExperienceEdge.SUCCESS && node.text("partyId") == party.toString() }
    }

    fun noSuchCard(): Response = edge.error(ExperienceEdge.NOT_FOUND, "CARD_NOT_FOUND", "No such card")

    /**
     * Consumes the CARD_MANAGEMENT challenge bound to [boundTo] and [action]; null when it passes, an
     * audited 403 SCA_REQUIRED otherwise. Same compare-and-consume as the edge's other card actions.
     */
    fun sca(party: UUID, challenge: String?, boundTo: String, action: String, operation: String): Response? {
        val id = challenge?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
        val consumed = id != null &&
            edge.upstream.post(
                "${edge.scaUrl.trimEnd('/')}/api/v1/sca/challenges/$id/consume",
                party.toString(),
                edge.json(mapOf("partyId" to party.toString(), "cardId" to boundTo, "cardAction" to action)),
            ).status in ExperienceEdge.SUCCESS
        if (consumed) return null
        audit.emit(
            eventType = "CUSTOMER_CARD_ACTION_REFUSED",
            partyId = party.toString(),
            operation = operation,
            result = "DENIED",
            resourceId = boundTo,
            details = mapOf("reason" to "SCA_REQUIRED", "cardAction" to action),
        )
        return edge.error(
            Response.Status.FORBIDDEN.statusCode,
            "SCA_REQUIRED",
            "Strong customer authentication required",
        )
    }

    fun audited(party: UUID, operation: String, eventType: String, card: String, r: Response): Response {
        audit.emit(
            eventType,
            party.toString(),
            operation,
            if (r.status in
                ExperienceEdge.SUCCESS
            ) {
                "SUCCESS"
            } else {
                "FAILED"
            },
            card,
        )
        return r
    }
}
