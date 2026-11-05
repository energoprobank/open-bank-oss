// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.domain

import java.time.Instant
import java.util.UUID

enum class OfferStatus { DRAFT, PENDING_APPROVAL, PUBLISHED, RETIRED }

enum class StackingPolicy { EXCLUSIVE, STACKABLE }

/**
 * Where a promo code is claimed directly (reserve + commit in one step) by the rewards engine
 * (FLUX programme). The context is recorded as the reservation's `productRef`.
 */
enum class RedemptionContext { ONBOARDING, HUB, TERM_DEPOSIT }

data class OfferRef(val id: UUID, val name: String, val version: Int)

data class IncentiveOffer(
    val ref: OfferRef,
    val productScope: Set<String>,
    val effectiveFrom: Instant,
    val expiresAt: Instant,
    val totalLimit: Int,
    val perPartyLimit: Int,
    val stackingPolicy: StackingPolicy,
    val status: OfferStatus,
    val maker: String,
    val checker: String? = null,
    /**
     * Claimable by a one-step redemption in any [RedemptionContext], whatever [productScope] says.
     * Product-bound reservations (`accepts`) still require a scope match.
     */
    val claimableInHub: Boolean = false,
) {
    init {
        require(ref.name.isNotBlank()) { "offer name is required" }
        require(ref.version > 0) { "offer version must be positive" }
        require(productScope.isNotEmpty() || claimableInHub) {
            "product scope is required unless the offer is claimable in the hub"
        }
        require(effectiveFrom < expiresAt) { "offer expiry must follow its effective time" }
        require(totalLimit > 0) { "total limit must be positive" }
        require(perPartyLimit in 1..totalLimit) { "per-party limit must be within total limit" }
        require(maker.isNotBlank()) { "maker is required" }
    }

    fun submit(requester: String): IncentiveOffer {
        if (status != OfferStatus.DRAFT) throw IncentiveConflict("only a draft can be submitted")
        if (requester != maker) throw IncentiveConflict("only the maker can submit this offer")
        return copy(status = OfferStatus.PENDING_APPROVAL)
    }

    fun publish(checker: String): IncentiveOffer {
        if (status != OfferStatus.PENDING_APPROVAL) throw IncentiveConflict("offer is not pending approval")
        if (checker.isBlank() || checker == maker) throw IncentiveConflict("checker must be independent")
        return copy(status = OfferStatus.PUBLISHED, checker = checker)
    }

    fun retire(): IncentiveOffer {
        require(status == OfferStatus.PUBLISHED) { "only a published offer can be retired" }
        return copy(status = OfferStatus.RETIRED)
    }

    fun accepts(productRef: String, at: Instant): Boolean = status == OfferStatus.PUBLISHED &&
        productRef in productScope &&
        !at.isBefore(effectiveFrom) &&
        at.isBefore(expiresAt)

    /**
     * A one-step redemption is admitted when the offer is live and either claimable in the hub,
     * not bound to any product, or scoped to the context by name (e.g. `TERM_DEPOSIT`).
     */
    fun claimableIn(context: RedemptionContext, at: Instant): Boolean = status == OfferStatus.PUBLISHED &&
        (claimableInHub || productScope.isEmpty() || context.name in productScope) &&
        !at.isBefore(effectiveFrom) &&
        at.isBefore(expiresAt)

    /** Stacking at commit: any EXCLUSIVE offer on either side refuses a second live benefit. */
    fun stacksWith(others: Collection<IncentiveOffer>): Boolean = others.isEmpty() ||
        (stackingPolicy == StackingPolicy.STACKABLE && others.all { it.stackingPolicy == StackingPolicy.STACKABLE })
}
