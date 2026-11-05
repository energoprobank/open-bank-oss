// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.treeToValue
import com.openbank.libs.authz.Authorize
import com.openbank.rewards.application.Biller
import com.openbank.rewards.application.ChangeKind
import com.openbank.rewards.application.ChangePayload
import com.openbank.rewards.application.CustomerService
import com.openbank.rewards.application.Jobs
import com.openbank.rewards.application.OfferReward
import com.openbank.rewards.application.Promotion
import com.openbank.rewards.application.RewardsStore
import com.openbank.rewards.application.StaffService
import com.openbank.rewards.domain.BudgetKind
import com.openbank.rewards.domain.BudgetLine
import com.openbank.rewards.domain.CouponDef
import com.openbank.rewards.domain.Market
import com.openbank.rewards.domain.MarketParams
import com.openbank.rewards.domain.MissionDef
import com.openbank.rewards.domain.ProgrammeSwitch
import com.openbank.rewards.domain.Reward
import com.openbank.rewards.infrastructure.persistence.RewardsJson
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
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
import java.time.Instant
import java.util.UUID

data class ChangeRequestBody(val kind: String? = null, val payload: JsonNode? = null, val reason: String? = null)
data class DecisionBody(val note: String? = null)

private data class PromotionBody(
    val market: Market,
    val name: String,
    val multiplier: BigDecimal,
    val startsAt: Instant,
    val endsAt: Instant,
    val perPartyCap: Int,
    val budgetLineId: String,
)
private data class OfferBody(val offerId: UUID, val market: Market, val reward: Reward, val budgetLineId: String)
private data class SwitchBody(val switch: ProgrammeSwitch, val enabled: Boolean)
private data class BudgetBody(
    val budgetLineId: String,
    val kind: BudgetKind,
    val market: Market,
    val amount: BigDecimal,
    val description: String,
)

/**
 * The operator console's API (spec: Staff API). Reads for the console; every write is a change
 * request that a different operator approves. Humans only: OPA (rewards_rest_ext.rego) refuses
 * machine principals on `rewards.staff.*`.
 */
@Path("/api/v1/rewards/staff")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Suppress("TooManyFunctions")
class StaffRewardsResource(
    private val staff: StaffService,
    private val customers: CustomerService,
    private val jobs: Jobs,
    private val store: RewardsStore,
) {
    @Inject
    lateinit var identity: SecurityIdentity

    @GET
    @Path("/overview")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "rewards.staff.read")
    fun overview(): Response = ok(
        store.tx { tx ->
            mapOf(
                "markets" to tx.allMarketParams(),
                "budgetLines" to
                    tx.budgetLines().map {
                        mapOf(
                            "budgetLineId" to it.budgetLineId,
                            "kind" to it.kind,
                            "market" to it.market,
                            "amount" to it.amount,
                            "consumed" to it.consumed,
                            "remaining" to it.remaining,
                            "description" to it.description,
                        )
                    },
                "switches" to ProgrammeSwitch.entries.associate { it.name to tx.switchEnabled(it) },
                "billers" to tx.billers(null),
                "promotions" to tx.promotions(null),
                "pendingRequests" to tx.changeRequests("PENDING", Int.MAX_VALUE).size,
            )
        },
    )

    @GET
    @Path("/missions")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "rewards.staff.read")
    fun missions(@QueryParam("market") market: String?): Response =
        ok(store.tx { tx -> markets(market).flatMap { tx.missionDefs(it, includeInactive = true) } })

    @GET
    @Path("/coupon-defs")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "rewards.staff.read")
    fun couponDefs(@QueryParam("market") market: String?): Response =
        ok(store.tx { tx -> markets(market).flatMap { tx.couponDefs(it, includeInactive = true) } })

    @GET
    @Path("/parties/{partyId}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "rewards.staff.read", resource = "#partyId")
    fun party(@PathParam("partyId") partyId: UUID): Response {
        val missions = customers.missions(partyId)
        return ok(
            mapOf(
                "summary" to Views.summary(customers.summary(partyId)),
                "coupons" to customers.coupons(partyId).map { Views.coupon(it.first, it.second) },
                "missions" to
                    mapOf(
                        "active" to missions.active.map(Views::mission),
                        "claimable" to missions.claimable.map(Views::mission),
                        "history" to missions.history.map(Views::mission),
                    ),
                "ledger" to customers.ledger(partyId, 0, PARTY_LEDGER).first.map(Views::entry),
                "referrals" to customers.referrals(partyId).map(Views::referral),
            ),
        )
    }

    @GET
    @Path("/change-requests")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "rewards.staff.read")
    fun changeRequests(@QueryParam("status") status: String?): Response =
        ok(staff.changeRequests(status).map(Views::change))

    @GET
    @Path("/change-requests/{id}")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "rewards.staff.read")
    fun changeRequest(@PathParam("id") id: UUID): Response = ok(Views.change(staff.changeRequest(id)))

    @POST
    @Path("/change-requests")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "rewards.staff.write")
    fun submit(body: ChangeRequestBody?): Response {
        val kind = runCatching {
            ChangeKind.valueOf(requireNotNull(body?.kind))
        }.getOrElse { throw IllegalArgumentException("kind must be one of ${ChangeKind.entries}") }
        val payload = requireNotNull(body?.payload) { "payload is required" }
        decode(kind, payload, UUID.randomUUID()) // validate now, so a malformed request never waits for a checker
        val cr = staff.submit(kind, RewardsJson.write(payload), body?.reason.orEmpty(), maker())
        return Response.status(
            CREATED,
        ).entity(RewardsJson.write(Views.change(cr))).type(MediaType.APPLICATION_JSON).build()
    }

    @POST
    @Path("/change-requests/{id}/approve")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "rewards.staff.approve")
    fun approve(@PathParam("id") id: UUID): Response {
        val cr = staff.changeRequest(id)
        val payload = decode(ChangeKind.valueOf(cr.kind), RewardsJson.mapper.readTree(cr.payload), id)
        return ok(Views.change(staff.approve(id, maker(), payload)))
    }

    @POST
    @Path("/change-requests/{id}/reject")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "rewards.staff.approve")
    fun reject(@PathParam("id") id: UUID, body: DecisionBody?): Response =
        ok(Views.change(staff.reject(id, maker(), body?.note)))

    @GET
    @Path("/reports/economy")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "rewards.staff.read")
    fun economy(): Response = ok(staff.economy())

    @GET
    @Path("/reports/liability")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "rewards.staff.read")
    fun liability(): Response = ok(store.tx { it.snapshots(LIABILITY_ROWS) }.map(Views::snapshot))

    /** Runs the daily liability snapshot and GL posting now (idempotent per day and value). */
    @POST
    @Path("/liability/run")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "rewards.staff.write")
    fun runLiability(): Response = ok(jobs.postLiability().map(Views::snapshot))

    private fun maker(): String = identity.principal?.name?.takeIf { it.isNotBlank() } ?: error("no principal")

    private fun markets(market: String?): List<Market> = market?.let { listOf(Market.valueOf(it)) } ?: Market.entries

    @Suppress("CyclomaticComplexMethod", "ThrowsCount") // one rule table; splitting hides it
    private fun decode(kind: ChangeKind, n: JsonNode, id: UUID): ChangePayload {
        val m = RewardsJson.mapper
        return try {
            when (kind) {
                ChangeKind.GRANT -> ChangePayload.Grant(
                    UUID.fromString(n["partyId"].asText()),
                    n["flux"].asInt().also {
                        require(
                            it > 0,
                        ) { "flux must be positive" }
                    },
                    n["note"]?.asText().orEmpty(),
                )
                ChangeKind.MIGRATION -> ChangePayload.Migration(
                    n["items"].map {
                        ChangePayload.MigrationItem(
                            UUID.fromString(it["partyId"].asText()),
                            it["flux"].asInt(),
                            it["sourceRef"]?.asText() ?: it["partyId"].asText(),
                        )
                    },
                )
                ChangeKind.PROMOTION -> m.treeToValue<PromotionBody>(n).let {
                    ChangePayload.NewPromotion(
                        Promotion(
                            id, it.market, it.name, it.multiplier, it.startsAt, it.endsAt,
                            it.perPartyCap, it.budgetLineId, true,
                        ),
                    )
                }
                ChangeKind.PROMOTION_STOP -> ChangePayload.StopPromotion(UUID.fromString(n["promotionId"].asText()))
                ChangeKind.BUDGET_LINE -> m.treeToValue<BudgetBody>(n).let {
                    ChangePayload.Budget(
                        BudgetLine(it.budgetLineId, it.kind, it.market, it.amount, BigDecimal.ZERO, it.description),
                    )
                }
                ChangeKind.MARKET -> ChangePayload.MarketChange(m.treeToValue<MarketParams>(n))
                ChangeKind.BILLER -> ChangePayload.BillerChange(m.treeToValue<Biller>(n))
                ChangeKind.MISSION_DEF -> ChangePayload.MissionChange(m.treeToValue<MissionDef>(n))
                ChangeKind.COUPON_DEF -> ChangePayload.CouponChange(m.treeToValue<CouponDef>(n))
                ChangeKind.OFFER_REWARD -> m.treeToValue<OfferBody>(n).let {
                    ChangePayload.OfferChange(
                        OfferReward(it.offerId, it.market, RewardsJson.write(it.reward), it.budgetLineId),
                    )
                }
                ChangeKind.KILL_SWITCH -> m.treeToValue<SwitchBody>(n).let {
                    ChangePayload.Switch(it.switch, it.enabled)
                }
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: NullPointerException) { // a missing JSON field
            throw IllegalArgumentException("payload for $kind is incomplete", e)
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            throw IllegalArgumentException("payload for $kind is invalid: ${e.originalMessage}", e)
        }
    }

    private fun ok(body: Any): Response = Response.ok(RewardsJson.write(body)).type(MediaType.APPLICATION_JSON).build()

    companion object {
        const val CREATED = 201
        const val PARTY_LEDGER = 50
        const val LIABILITY_ROWS = 60
    }
}
