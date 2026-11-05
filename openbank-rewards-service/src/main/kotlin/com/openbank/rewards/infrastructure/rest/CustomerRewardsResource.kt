// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.rewards.application.CustomerService
import com.openbank.rewards.application.FulfilmentService
import com.openbank.rewards.application.RewardsStore
import com.openbank.rewards.application.StoredResponse
import com.openbank.rewards.domain.RewardsRuleException
import com.openbank.rewards.domain.TargetKind
import com.openbank.rewards.infrastructure.persistence.RewardsJson
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.util.UUID

data class ChargeRequest(val flux: Int? = null)
data class AddCouponRequest(val couponDefId: String? = null)
data class UseRequest(val targetKind: String? = null, val targetId: String? = null)
data class CodeRequest(val code: String? = null)
data class QuizRequest(val answers: List<Int>? = null)
data class SettingsRequest(
    val participating: Boolean? = null,
    val quietMode: Boolean? = null,
    val notifications: Map<String, Boolean>? = null,
)

/**
 * The customer API. Reached only through customer-edge, which injects the authoritative partyId
 * from the customer's token (the same arrangement as loyalty and engagement surfaces). OPA admits
 * the edge principal for `rewards.customer.*` (rewards_rest_ext.rego). Writes need an
 * Idempotency-Key; a replay returns the stored response.
 *
 * Every nullable parameter is nullable on purpose: JAX-RS injects null for an absent header and a
 * non-null Kotlin parameter would answer 500 instead of the intended 400 (fleet pitfall).
 */
@Path("/api/v1/rewards/parties/{partyId}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
@Suppress("TooManyFunctions")
class CustomerRewardsResource(
    private val customers: CustomerService,
    private val fulfilment: FulfilmentService,
    private val store: RewardsStore,
) {

    @GET
    @Path("/summary")
    @Authorize(action = "rewards.customer.read", resource = "#partyId")
    fun summary(@PathParam("partyId") partyId: UUID): Response = ok(Views.summary(customers.summary(partyId)))

    @GET
    @Path("/missions")
    @Authorize(action = "rewards.customer.read", resource = "#partyId")
    fun missions(@PathParam("partyId") partyId: UUID): Response {
        val v = customers.missions(partyId)
        return ok(
            mapOf(
                "active" to v.active.map(Views::mission),
                "claimable" to v.claimable.map(Views::mission),
                "history" to v.history.map(Views::mission),
            ),
        )
    }

    @POST
    @Path("/missions/{missionId}/claim")
    @Authorize(action = "rewards.customer.write", resource = "#partyId")
    fun claim(
        @PathParam("partyId") partyId: UUID,
        @PathParam("missionId") missionId: String,
        @HeaderParam("Idempotency-Key") key: String?,
    ): Response = idempotent(partyId, key, "claim:$missionId") {
        val r = customers.claim(partyId, missionId)
        mapOf(
            "mission" to Views.mission(r.mission),
            "entries" to r.entries.map(Views::entry),
            "coupon" to r.coupon?.let { Views.coupon(it.first, it.second) },
            "balance" to r.balance,
        )
    }

    @GET
    @Path("/coupons")
    @Authorize(action = "rewards.customer.read", resource = "#partyId")
    fun coupons(@PathParam("partyId") partyId: UUID): Response = ok(
        mapOf(
            "items" to customers.coupons(partyId).map { Views.coupon(it.first, it.second) },
        ),
    )

    @GET
    @Path("/catalogue")
    @Authorize(action = "rewards.customer.read", resource = "#partyId")
    fun catalogue(@PathParam("partyId") partyId: UUID): Response = ok(
        mapOf(
            "items" to customers.catalogue(partyId).map { Views.catalogue(it, null) },
        ),
    )

    @POST
    @Path("/coupons")
    @Authorize(action = "rewards.customer.write", resource = "#partyId")
    fun add(
        @PathParam("partyId") partyId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        body: AddCouponRequest?,
    ): Response {
        val defId = requireNotNull(body?.couponDefId) { "couponDefId is required" }
        return idempotent(partyId, key, "add:$defId", CREATED) {
            customers.addCoupon(partyId, defId).let { Views.coupon(it.first, it.second) }
        }
    }

    @POST
    @Path("/coupons/{couponId}/activate")
    @Authorize(action = "rewards.customer.write", resource = "#partyId")
    fun activate(@PathParam("partyId") partyId: UUID, @PathParam("couponId") couponId: UUID): Response =
        ok(customers.activate(partyId, couponId).let { Views.coupon(it.first, it.second) })

    @POST
    @Path("/coupons/{couponId}/charge")
    @Authorize(action = "rewards.customer.write", resource = "#partyId")
    fun charge(
        @PathParam("partyId") partyId: UUID,
        @PathParam("couponId") couponId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        body: ChargeRequest?,
    ): Response {
        val flux = requireNotNull(body?.flux) { "flux is required" }
        return idempotent(partyId, key, "charge:$couponId") {
            val r = customers.charge(partyId, couponId, flux, key!!)
            mapOf(
                "coupon" to Views.coupon(r.coupon.first, r.coupon.second),
                "chargedOwn" to r.chargedOwn,
                "chargedBonus" to r.chargedBonus,
                "balance" to r.balance,
                "levelReached" to r.levelReached?.let { Views.level(it, 0) },
            )
        }
    }

    @GET
    @Path("/coupons/{couponId}/preview")
    @Authorize(action = "rewards.customer.read", resource = "#partyId")
    fun preview(
        @PathParam("partyId") partyId: UUID,
        @PathParam("couponId") couponId: UUID,
        @QueryParam("targetKind") targetKind: String?,
        @QueryParam("targetId") targetId: String?,
    ): Response {
        val p = fulfilment.preview(partyId, couponId, kind(targetKind), targetId)
        return ok(mapOf("before" to Views.terms(p.before), "after" to Views.terms(p.after), "notes" to p.notes))
    }

    @POST
    @Path("/coupons/{couponId}/use")
    @Authorize(action = "rewards.customer.write", resource = "#partyId")
    fun use(
        @PathParam("partyId") partyId: UUID,
        @PathParam("couponId") couponId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        body: UseRequest?,
    ): Response = idempotent(partyId, key, "use:$couponId", storeWhen = {
        it["fulfilment"].let { f ->
            (f as Map<*, *>)["state"].toString() !=
                "PENDING"
        }
    }) {
        val r = fulfilment.use(partyId, couponId, kind(body?.targetKind), body?.targetId)
        mapOf(
            "coupon" to Views.coupon(r.coupon.first, r.coupon.second),
            "fulfilment" to Views.fulfilment(r.fulfilment),
        )
    }

    @GET
    @Path("/ledger")
    @Authorize(action = "rewards.customer.read", resource = "#partyId")
    fun ledger(
        @PathParam("partyId") partyId: UUID,
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("20") size: Int,
    ): Response {
        require(page >= 0 && size in 1..MAX_PAGE) { "page >= 0 and size 1..$MAX_PAGE" }
        val (items, total) = customers.ledger(partyId, page, size)
        return ok(mapOf("items" to items.map(Views::entry), "page" to page, "size" to size, "total" to total))
    }

    @GET
    @Path("/settings")
    @Authorize(action = "rewards.customer.read", resource = "#partyId")
    fun settings(@PathParam("partyId") partyId: UUID): Response = ok(Views.settings(customers.settings(partyId)))

    @PUT
    @Path("/settings")
    @Authorize(action = "rewards.customer.write", resource = "#partyId")
    fun updateSettings(@PathParam("partyId") partyId: UUID, body: SettingsRequest?): Response {
        val current = customers.settings(partyId)
        val n = body?.notifications.orEmpty()
        val m = customers.updateSettings(
            partyId,
            participating = body?.participating ?: (current.status.name == "ACTIVE"),
            quiet = body?.quietMode ?: current.quietMode,
            missions = n["missions"] ?: current.notifyMissions,
            coupons = n["coupons"] ?: current.notifyCoupons,
            expiry = n["expiry"] ?: current.notifyExpiry,
        )
        return ok(Views.settings(m))
    }

    @POST
    @Path("/codes/claim")
    @Authorize(action = "rewards.customer.write", resource = "#partyId")
    fun claimCode(
        @PathParam("partyId") partyId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        body: CodeRequest?,
    ): Response {
        val code = requireNotNull(body?.code?.takeIf { it.isNotBlank() }) { "code is required" }
        return idempotent(partyId, key, "code:${code.uppercase()}") {
            val (entry, coupon) = customers.claimCode(partyId, code)
            mapOf(
                "status" to "REDEEMED",
                "reward" to mapOf(
                    "type" to if (coupon != null && entry == null) "COUPON" else "FLUX",
                    "flux" to entry?.flux,
                    "couponDefId" to coupon?.first?.couponDefId,
                    "coupon" to coupon?.let { Views.coupon(it.first, it.second) },
                ),
            )
        }
    }

    @GET
    @Path("/lessons")
    @Authorize(action = "rewards.customer.read", resource = "#partyId")
    @Suppress("UnusedParameter") // partyId scopes the call (path + OPA); lessons are the same for everyone
    fun lessons(@PathParam("partyId") partyId: UUID, @HeaderParam("Accept-Language") language: String?): Response = ok(
        mapOf(
            "items" to customers.lessons(language ?: "en").map { l ->
                mapOf(
                    "lessonId" to l.lessonId,
                    "missionId" to l.missionId,
                    "questions" to l.questions.map { mapOf("question" to it.first, "options" to it.second) },
                )
            },
        ),
    )

    @POST
    @Path("/lessons/{lessonId}/quiz")
    @Authorize(action = "rewards.customer.write", resource = "#partyId")
    fun quiz(
        @PathParam("partyId") partyId: UUID,
        @PathParam("lessonId") lessonId: String,
        body: QuizRequest?,
    ): Response {
        val answers = requireNotNull(body?.answers) { "answers are required" }
        val (passed, score, mission) = customers.quiz(partyId, lessonId, answers)
        return ok(mapOf("passed" to passed, "score" to score, "mission" to mission?.let(Views::mission)))
    }

    @GET
    @Path("/referrals")
    @Authorize(action = "rewards.customer.read", resource = "#partyId")
    fun referrals(@PathParam("partyId") partyId: UUID): Response = ok(
        mapOf(
            "items" to customers.referrals(partyId).map(Views::referral),
        ),
    )

    private fun kind(value: String?): TargetKind = runCatching {
        TargetKind.valueOf(requireNotNull(value))
    }.getOrElse { throw IllegalArgumentException("targetKind must be one of ${TargetKind.entries}") }

    /**
     * Idempotent write: a replay of (party, key, operation) returns the stored response. Business
     * refusals are NOT stored, so the customer can retry after fixing the cause.
     */
    private fun idempotent(
        partyId: UUID,
        key: String?,
        operation: String,
        status: Int = OK,
        storeWhen: (Map<String, Any?>) -> Boolean = { true },
        block: () -> Any,
    ): Response {
        val k =
            requireNotNull(
                key?.takeIf {
                    it.isNotBlank() && it.length <= MAX_KEY
                },
            ) { "Idempotency-Key header is required (max $MAX_KEY chars)" }
        store.tx {
            it.storedResponse(partyId, k, operation)
        }?.let { return Response.status(it.statusCode).entity(it.body).type(MediaType.APPLICATION_JSON).build() }
        val body = try {
            block()
        } catch (e: RewardsRuleException) {
            if (e.code == "DUPLICATE_REQUEST") {
                store.tx {
                    it.storedResponse(partyId, k, operation)
                }?.let {
                    return Response.status(it.statusCode).entity(it.body).type(MediaType.APPLICATION_JSON).build()
                }
            }
            throw e
        }
        @Suppress("UNCHECKED_CAST")
        if (body !is Map<*, *> || storeWhen(body as Map<String, Any?>)) {
            store.tx {
                it.storeResponse(partyId, k, operation, StoredResponse(status, RewardsJson.write(body)), Instant.now())
            }
        }
        return Response.status(status).entity(RewardsJson.write(body)).type(MediaType.APPLICATION_JSON).build()
    }

    private fun ok(body: Any): Response = Response.ok(RewardsJson.write(body)).type(MediaType.APPLICATION_JSON).build()

    companion object {
        const val OK = 200
        const val CREATED = 201
        const val MAX_PAGE = 100
        const val MAX_KEY = 128
    }
}
