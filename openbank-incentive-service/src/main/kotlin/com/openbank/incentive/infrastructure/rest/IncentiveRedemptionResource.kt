// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.infrastructure.rest

import com.openbank.incentive.application.IncentiveApplication
import com.openbank.incentive.domain.RedemptionContext
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import java.util.UUID

/** Service-to-service redemption by the rewards engine (FLUX); every field is required. */
data class RedemptionRequest(
    val partyId: UUID? = null,
    val code: String? = null,
    val context: RedemptionContext? = null,
)

data class RedemptionResponse(val offerId: UUID, val reservationId: UUID, val status: String = "REDEEMED")

/**
 * Reserve AND commit a promo code for a party in one step — the FLUX rewards engine's promo-code
 * claim. Its own class, not a method on [IncentiveResource]: that class's `@RolesAllowed
 * ("ROLE_OPERATOR")` still binds a method that declares its own role list, so a ROLE_API-only
 * machine is refused there (measured in IncentiveRedemptionIT). ROLE_API admits every service
 * account, so [requireRewardsRedeemer] narrows the route to the rewards engine's own principal;
 * the OPA rule `service-rewards-incentive-redeem` (incentive_rest_ext.rego) grants the action to
 * that same principal and nobody else.
 */
@Path("/api/v1/incentives/redemptions")
@ApplicationScoped
@RolesAllowed("ROLE_API")
class IncentiveRedemptionResource(
    private val application: IncentiveApplication,
    private val identity: SecurityIdentity,
) {
    @POST
    @Authorize(action = "incentive.redemption.redeem", attributes = ["idempotency-key"])
    suspend fun redeem(@HeaderParam("Idempotency-Key") key: String?, request: RedemptionRequest): Response {
        requireRewardsRedeemer(identity)
        val partyId = requireNotNull(request.partyId) { "partyId is required" }
        require(partyId != NIL_UUID) { "partyId must not be the nil UUID" }
        val code = requireNotNull(request.code?.takeIf { it.isNotBlank() }) { "code is required" }
        val context = requireNotNull(request.context) { "context is required" }
        require(key == null || key.length <= MAX_IDEMPOTENCY_KEY_LENGTH) { "Idempotency-Key is too long" }
        val reservation = application.redeem(partyId, code, context, key, identity.principal.name)
        return Response.ok(RedemptionResponse(reservation.offerRef.id, reservation.id)).build()
    }

    private companion object {
        val NIL_UUID: UUID = UUID(0L, 0L)
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 200
    }
}
