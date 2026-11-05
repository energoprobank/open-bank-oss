// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.transaction.application.port.`in`.RewardCreditCommand
import com.openbank.transaction.application.port.`in`.RewardCreditUseCase
import com.openbank.transaction.domain.reward.RewardReason
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.net.URI
import java.util.UUID

/**
 * ADR-0344 D3 — FLUX reward cash credit: the rewards engine pays a bill cashback, instalment relief or
 * referral cash into the customer's own current account. A dedicated endpoint with its own action,
 * deliberately not a flavour of `POST /api/v1/transactions`:
 *
 *  - `transaction.rewardCredit` is granted by identity to `service-account-openbank-rewards` ALONE
 *    (transaction_rest_ext.rego); that principal holds no `transaction.create`, so it can pay only a
 *    capped, provision-funded credit to a checked account — never an arbitrary transfer.
 *  - Only this path can set a reward reason, i.e. draw on the FLUX provision GL. The public
 *    initiate body has no such field.
 *  - Not a `credit` verb: `credit` is in rules.yaml four_eyes.verbs, and this is an automated call.
 *
 * RBAC admits ROLE_API only (no operator path; an operator correction is an ordinary transaction).
 */
@Path("/api/v1/reward-credits")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Reward credits", description = "FLUX reward cash credits (rewards engine only)")
class RewardCreditResource(private val useCase: RewardCreditUseCase) {

    @POST
    @RolesAllowed(Roles.API)
    @Authorize(action = "transaction.rewardCredit", resource = "")
    @Operation(summary = "Pay a FLUX reward in cash to the customer's own current account (ADR-0344)")
    suspend fun credit(request: RewardCreditRequest?): Response {
        // Nullable: an absent body (or field) must be a 400, never a Kotlin NPE answered as 500.
        requireNotNull(request) { "a request body is required" }
        val command = RewardCreditCommand(
            partyId = requireNotNull(request.partyId) { "partyId is required" },
            accountId = requireNotNull(request.accountId) { "accountId is required" },
            amount = requireNotNull(request.amount) { "amount is required" },
            currency = requireNotNull(request.currency) { "currency is required" },
            reason = parseReason(requireNotNull(request.reason) { "reason is required" }),
            sourceRef = requireNotNull(request.sourceRef) { "sourceRef is required" },
        )
        val result = useCase.credit(command)
        val body = RewardCreditResponse(result.creditId, result.transactionId, result.status.name)
        val builder = if (result.replayed) {
            Response.ok()
        } else {
            Response.created(URI.create("/api/v1/transactions/${result.transactionId}"))
        }
        return builder.entity(body)
            .type(MediaType.APPLICATION_JSON)
            .header(IDEMPOTENT_REPLAYED_HEADER, result.replayed.toString())
            .build()
    }

    private fun parseReason(value: String): RewardReason = RewardReason.entries.firstOrNull { it.name == value }
        ?: throw IllegalArgumentException("reason must be one of ${RewardReason.entries.joinToString()}")

    companion object {
        const val IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed"
    }
}

/** Every field nullable so a missing one is a 400 from the resource, never a Jackson 500. */
data class RewardCreditRequest(
    val partyId: UUID? = null,
    val accountId: UUID? = null,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val reason: String? = null,
    val sourceRef: String? = null,
)

data class RewardCreditResponse(val creditId: UUID, val transactionId: UUID, val status: String)
