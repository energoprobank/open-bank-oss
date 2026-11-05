// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.port.`in`.PostRewardProvisionCommand
import com.openbank.ledger.application.port.`in`.RewardProvisionResult
import com.openbank.ledger.application.port.`in`.RewardProvisionStatus
import com.openbank.ledger.application.port.`in`.RewardProvisionUseCase
import com.openbank.ledger.domain.model.RewardMarket
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
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
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * ADR-0344 D2 — the FLUX provision posting the rewards engine drives once a day per market.
 *
 * A fixed template, not a general journal: the caller sends a signed delta and the ledger chooses
 * both accounts (FLUX reward expense / FLUX provision). Its own action `ledger.rewardProvision` so
 * OPA can grant it to `service-account-openbank-rewards` ALONE (ledger_rest_ext.rego) without that
 * principal ever holding `ledger.create`. Deliberately not a `post`/`credit` verb: those are
 * four-eyes verbs (rules.yaml four_eyes.verbs), and this is an automated daily M2M call.
 * RBAC admits ROLE_API only — no operator path; an operator correction is an ordinary journal.
 */
@Path("/api/v1/ledger/reward-provisions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Ledger", description = "General ledger journal entries")
class RewardProvisionResource(private val useCase: RewardProvisionUseCase) {

    @POST
    @RolesAllowed(Roles.API)
    @Authorize(action = "ledger.rewardProvision", resource = "")
    @Operation(summary = "Post the day's FLUX provision change for one market (ADR-0344)")
    suspend fun post(request: RewardProvisionRequest?): Response {
        // Nullable for the same reason as LedgerResource.postJournal: an absent body must be a 400.
        requireNotNull(request) { "a request body is required" }
        val command = PostRewardProvisionCommand(
            postingDate = parseDate(requireNotNull(request.postingDate) { "postingDate is required" }),
            market = parseMarket(requireNotNull(request.market) { "market is required" }),
            currency = requireNotNull(request.currency) { "currency is required" },
            deltaAmount = requireNotNull(request.deltaAmount) { "deltaAmount is required" },
            sourceRef = requireNotNull(request.sourceRef) { "sourceRef is required" },
        )
        val result = useCase.post(command)
        val builder = if (result.status == RewardProvisionStatus.POSTED) {
            Response.created(URI.create("/api/v1/journals/${result.journalId}"))
        } else {
            Response.ok()
        }
        return builder.entity(result.toResponse())
            .type(MediaType.APPLICATION_JSON)
            .header(IDEMPOTENT_REPLAYED_HEADER, (result.status == RewardProvisionStatus.REPLAYED).toString())
            .build()
    }

    private fun parseDate(value: String): LocalDate = try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("postingDate must be YYYY-MM-DD, got '$value'", e)
    }

    private fun parseMarket(value: String): RewardMarket = RewardMarket.entries.firstOrNull { it.name == value }
        ?: throw IllegalArgumentException("market must be one of ${RewardMarket.entries.joinToString()}")
}

/** Every field nullable so an absent one is a 400 from the resource, never a Jackson 500. */
data class RewardProvisionRequest(
    val postingDate: String? = null,
    val market: String? = null,
    val currency: String? = null,
    val deltaAmount: BigDecimal? = null,
    val sourceRef: String? = null,
)

data class RewardProvisionResponse(
    val status: String,
    val journalId: UUID,
    val entryNumber: Long?,
    val postingDate: String,
    val market: String,
    val currency: String,
    val deltaAmount: BigDecimal,
    val sourceRef: String,
)

private fun RewardProvisionResult.toResponse() = RewardProvisionResponse(
    status = status.name,
    journalId = journalId,
    entryNumber = entryNumber,
    postingDate = postingDate.toString(),
    market = market.name,
    currency = currency,
    deltaAmount = deltaAmount,
    sourceRef = sourceRef,
)
