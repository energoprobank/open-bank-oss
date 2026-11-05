// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import com.openbank.libs.authz.Authorize
import io.quarkus.logging.Log
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.security.MessageDigest
import java.util.Optional
import java.util.UUID

/**
 * FLUX rewards (Energo-Pro) for the customer app: a thin proxy of `openbank-rewards-service`'s
 * Customer API (`/api/v1/rewards/parties/{partyId}/...`) with the edge M2M token. The party is the
 * token's ([CustomerPartyResolver]) and the engine path is built from it — never from anything the
 * client sends; a `partyId` in a request body is dropped before forwarding.
 *
 * Unlike the Lístky projection ([CustomerLoyaltyResource]) the engine's Customer API was written FOR
 * the app (rewards-api-contract.md), so a 2xx body is relayed verbatim. A 4xx is relayed as its
 * status plus only `{code, message}`. Anything that means "the engine cannot serve this" — a 5xx,
 * a transport failure, an auth rejection of the edge principal, an unreadable body — is 503
 * `REWARDS_UNAVAILABLE`, on which the app hides rewards content rather than showing an error.
 *
 * Capability `rewards.flux` (ADR-0310 D5): switched off, every route is a 404 like any other
 * feature-gated route (cf. [BusinessPaymentBatchResource]); switched on with no engine URL, 503.
 *
 * Using a credit coupon (category LOANS: it changes a loan's rate or instalment) requires SCA,
 * bound as an APPROVAL challenge to the coupon and the exact target — see [useCoupon].
 */
@Path("/customer/v1/rewards")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
@Suppress("TooManyFunctions")
class CustomerRewardsResource(
    private val upstream: UpstreamClient,
    private val parties: CustomerPartyResolver,
    private val audit: EdgeAuditPublisher,
) {
    @ConfigProperty(name = "openbank.edge.rewards-service-url")
    lateinit var rewardsServiceUrl: Optional<String>

    @ConfigProperty(name = "openbank.edge.capabilities.rewards-flux.enabled", defaultValue = "false")
    var fluxEnabled: Boolean = false

    @ConfigProperty(name = "openbank.edge.sca-service-url")
    lateinit var scaServiceUrl: String

    @Context
    lateinit var requestHeaders: HttpHeaders

    @GET
    @Path("/summary")
    @Authorize(action = "customer.rewards.read")
    @Blocking
    fun summary(): Response = read("/summary")

    @GET
    @Path("/missions")
    @Authorize(action = "customer.rewards.read")
    @Blocking
    fun missions(): Response = read("/missions")

    @POST
    @Path("/missions/{missionId}/claim")
    @Authorize(action = "customer.rewards.write")
    @Blocking
    fun claimMission(
        @PathParam("missionId") missionId: String?,
        body: String?,
        @HeaderParam(IDEMPOTENCY_KEY) idempotencyKey: String?,
    ): Response = write("missions.claim", "/missions/{id}/claim", missionId, body, idempotencyKey, bodyRequired = false)

    @GET
    @Path("/coupons")
    @Authorize(action = "customer.rewards.read")
    @Blocking
    fun coupons(): Response = read("/coupons")

    @GET
    @Path("/catalogue")
    @Authorize(action = "customer.rewards.read")
    @Blocking
    fun catalogue(): Response = read("/catalogue")

    /** Issue a coupon from the catalogue: `{couponDefId}` → 201 Coupon. */
    @POST
    @Path("/coupons")
    @Authorize(action = "customer.rewards.write")
    @Blocking
    fun issueCoupon(body: String?, @HeaderParam(IDEMPOTENCY_KEY) idempotencyKey: String?): Response =
        write("coupons.issue", "/coupons", null, body, idempotencyKey, bodyRequired = true)

    @POST
    @Path("/coupons/{couponId}/activate")
    @Authorize(action = "customer.rewards.write")
    @Blocking
    fun activateCoupon(
        @PathParam("couponId") couponId: String?,
        body: String?,
        @HeaderParam(IDEMPOTENCY_KEY) idempotencyKey: String?,
    ): Response =
        write("coupons.activate", "/coupons/{id}/activate", couponId, body, idempotencyKey, bodyRequired = false)

    @POST
    @Path("/coupons/{couponId}/charge")
    @Authorize(action = "customer.rewards.write")
    @Blocking
    fun chargeCoupon(
        @PathParam("couponId") couponId: String?,
        body: String?,
        @HeaderParam(IDEMPOTENCY_KEY) idempotencyKey: String?,
    ): Response = write("coupons.charge", "/coupons/{id}/charge", couponId, body, idempotencyKey, bodyRequired = true)

    /** What using the coupon on a target would change: `{before, after, notes}`. Read-only, no SCA. */
    @GET
    @Path("/coupons/{couponId}/preview")
    @Authorize(action = "customer.rewards.read")
    @Blocking
    fun previewCoupon(
        @PathParam("couponId") couponId: String?,
        @QueryParam("targetKind") targetKind: String?,
        @QueryParam("targetId") targetId: String?,
    ): Response {
        gate()
        val id = segment(couponId) ?: return notFound()
        val kind = targetKind?.takeIf { it in TARGET_KINDS }
            ?: return invalid("targetKind must be one of $TARGET_KINDS")
        val target = segment(targetId) ?: return invalid("targetId is missing or malformed")
        return read("/coupons/$id/preview?targetKind=$kind&targetId=$target")
    }

    /**
     * Use a READY coupon on `{targetKind, targetId}`.
     *
     * **SCA for credit coupons.** The edge first reads the caller's wallet (`GET /coupons`) to learn
     * the coupon's category. A coupon in category LOANS — or a LOAN_RATE_REDUCTION /
     * INSTALMENT_RELIEF benefit, or any use on a LOAN target — changes a credit agreement and is
     * gated exactly like the edge's other operation-bound writes (pension, ADR-0021): without an
     * `X-SCA-Challenge-Id` the answer is 403 `SCA_REQUIRED` carrying `scaLinking`
     * `{purpose: APPROVAL, approvalRequestId, payloadSha256}`; the app raises that challenge via
     * `POST /customer/v1/sca/challenges`, has it approved on the device, and retries with the header
     * and the SAME Idempotency-Key. The edge then spends the challenge at sca-service against the
     * same linking (single use; a challenge signed for another coupon or target is 403
     * `SCA_REJECTED`) and forwards the challenge id to the engine as its approval reference.
     *
     * Fail closed: a coupon the wallet does not list is a 404 here, never a use without SCA.
     */
    @POST
    @Path("/coupons/{couponId}/use")
    @Authorize(action = "customer.rewards.coupon.use")
    @Blocking
    @Suppress("CyclomaticComplexMethod")
    fun useCoupon(
        @PathParam("couponId") couponId: String?,
        body: String?,
        @HeaderParam(IDEMPOTENCY_KEY) idempotencyKey: String?,
        @HeaderParam(ScaConsume.HEADER) sca: String?,
    ): Response {
        gate()
        val base = baseUrl() ?: return unavailable()
        val id = segment(couponId) ?: return notFound()
        val key = idempotencyKey(idempotencyKey) ?: return missingOrLongKey(idempotencyKey)
        val input = EdgeJson.parseObject(body) ?: return invalid("body must be a JSON object")
        val targetKind = input.text("targetKind")?.takeIf { it in USE_TARGET_KINDS }
            ?: return invalid("targetKind must be one of $USE_TARGET_KINDS")
        val targetId = input.text("targetId")
        val targetOk = if (targetId == null) targetKind == "NONE" else SEGMENT.matches(targetId)
        if (!targetOk) return invalid("targetId is missing or malformed")
        val party = party()

        val coupon = when (val found = walletCoupon(base, party, id)) {
            is WalletLookup.Found -> found.coupon
            is WalletLookup.Failed -> return found.response
        }
        val payload = mapOf("targetKind" to targetKind, "targetId" to targetId)
        val extra = languageHeader().toMutableMap()
        val category = coupon.text("category")
        if (needsSca(coupon, targetKind)) {
            val linking = RewardsScaLinking.of(id, payload)
            val challenge = ScaConsume.challengeId(sca) ?: return ScaConsume.required(
                mapOf(
                    "message" to "Strong customer authentication required",
                    "scaLinking" to linking + ("purpose" to "APPROVAL"),
                ),
            )
            val consumed = ScaConsume.consume(upstream, scaServiceUrl, challenge, party.toString(), party, linking)
            if (consumed.status != OK) {
                record("coupons.use", party, id, FAILURE, mapOf("reason" to "SCA_REJECTED", "category" to category))
                return ScaConsume.rejected()
            }
            extra[ScaConsume.HEADER] = challenge.toString()
        }
        val response = upstream.post(url(base, party, "/coupons/$id/use"), party.toString(), json(payload), key, extra)
        record("coupons.use", party, id, outcome(response), mapOf("category" to category, "targetKind" to targetKind))
        return relay(response)
    }

    @GET
    @Path("/ledger")
    @Authorize(action = "customer.rewards.read")
    @Blocking
    fun ledger(@QueryParam("page") page: Int?, @QueryParam("size") size: Int?): Response {
        gate()
        val p = page ?: 0
        val s = size ?: DEFAULT_PAGE_SIZE
        if (p < 0 || s !in 1..MAX_PAGE_SIZE) return invalid("page must be >= 0 and size 1..$MAX_PAGE_SIZE")
        return read("/ledger?page=$p&size=$s")
    }

    @GET
    @Path("/settings")
    @Authorize(action = "customer.rewards.read")
    @Blocking
    fun settings(): Response = read("/settings")

    @PUT
    @Path("/settings")
    @Authorize(action = "customer.rewards.write")
    @Blocking
    fun updateSettings(body: String?, @HeaderParam(IDEMPOTENCY_KEY) idempotencyKey: String?): Response {
        gate()
        val base = baseUrl() ?: return unavailable()
        val key = idempotencyKey(idempotencyKey) ?: return missingOrLongKey(idempotencyKey)
        val input = forwardable(body, required = true) ?: return invalid("body must be a JSON object")
        val party = party()
        val response = upstream.put(url(base, party, "/settings"), party.toString(), input, key, languageHeader())
        record("settings.update", party, null, outcome(response))
        return relay(response)
    }

    /** Redeem a promo code: `{code}` → `{status: REDEEMED, reward}`; 404 CODE_UNKNOWN, 409 CODE_USED. */
    @POST
    @Path("/codes/claim")
    @Authorize(action = "customer.rewards.write")
    @Blocking
    fun claimCode(body: String?, @HeaderParam(IDEMPOTENCY_KEY) idempotencyKey: String?): Response =
        write("codes.claim", "/codes/claim", null, body, idempotencyKey, bodyRequired = true)

    @GET
    @Path("/lessons")
    @Authorize(action = "customer.rewards.read")
    @Blocking
    fun lessons(): Response = read("/lessons")

    @POST
    @Path("/lessons/{lessonId}/quiz")
    @Authorize(action = "customer.rewards.write")
    @Blocking
    fun submitQuiz(
        @PathParam("lessonId") lessonId: String?,
        body: String?,
        @HeaderParam(IDEMPOTENCY_KEY) idempotencyKey: String?,
    ): Response = write("lessons.quiz", "/lessons/{id}/quiz", lessonId, body, idempotencyKey, bodyRequired = true)

    @GET
    @Path("/referrals")
    @Authorize(action = "customer.rewards.read")
    @Blocking
    fun referrals(): Response = read("/referrals")

    // ---------------------------------------------------------------- plumbing

    /** A party-scoped GET; [path] is already validated (fixed text plus checked segments). */
    private fun read(path: String): Response {
        gate()
        val base = baseUrl() ?: return unavailable()
        val party = party()
        return relay(upstream.getWithHeaders(url(base, party, path), party.toString(), languageHeader()))
    }

    /**
     * A party-scoped POST: Idempotency-Key required and forwarded unchanged (the engine keys on
     * party + key, and the path already carries the party), `{id}` in [template] replaced by the
     * validated [rawId], and one audit event whatever the outcome.
     */
    @Suppress("LongParameterList")
    private fun write(
        operation: String,
        template: String,
        rawId: String?,
        body: String?,
        rawKey: String?,
        bodyRequired: Boolean,
    ): Response {
        gate()
        val base = baseUrl() ?: return unavailable()
        val id = if (template.contains("{id}")) segment(rawId) ?: return notFound() else null
        val key = idempotencyKey(rawKey) ?: return missingOrLongKey(rawKey)
        val input = forwardable(body, bodyRequired) ?: return invalid("body must be a JSON object")
        val party = party()
        val path = id?.let { template.replace("{id}", it) } ?: template
        val response = upstream.post(url(base, party, path), party.toString(), input, key, languageHeader())
        record(operation, party, id ?: createdId(response), outcome(response))
        return relay(response)
    }

    /**
     * The upstream answer as the app sees it. 2xx: the engine's JSON verbatim (with the replay
     * marker). 4xx: status + `{code, message}` only. Everything that means the engine cannot serve
     * the request: 503 REWARDS_UNAVAILABLE.
     */
    private fun relay(response: Response): Response {
        val status = response.status
        return when {
            status in SUCCESS_RANGE -> {
                val raw = response.entity as? String
                if (raw.isNullOrBlank() || EdgeJson.parse(response) == null) return unavailable()
                val builder = Response.status(status).entity(raw).type(MediaType.APPLICATION_JSON)
                response.getHeaderString(UpstreamClient.IDEMPOTENCY_REPLAY_HEADER)
                    ?.let { builder.header(UpstreamClient.IDEMPOTENCY_REPLAY_HEADER, it) }
                builder.build()
            }
            status >= SERVER_ERROR || status == UNAUTHORIZED || status == FORBIDDEN -> {
                // 401/403 is the engine refusing the EDGE principal (a policy/deploy fault), not
                // something the customer did or can fix.
                Log.warnf("rewards-service answered %d; reporting REWARDS_UNAVAILABLE", status)
                unavailable()
            }
            else -> {
                val node = EdgeJson.parse(response)
                val code = node?.text("code")?.takeIf { CODE.matches(it) } ?: fallbackCode(status)
                val message = node?.text("message") ?: "rewards request rejected"
                rewardsError(status, code, message)
            }
        }
    }

    private sealed interface WalletLookup {
        data class Found(val coupon: JsonNode) : WalletLookup

        data class Failed(val response: Response) : WalletLookup
    }

    private fun walletCoupon(base: String, party: UUID, couponId: String): WalletLookup {
        val response = upstream.getWithHeaders(url(base, party, "/coupons"), party.toString(), emptyMap())
        if (response.status !in SUCCESS_RANGE) return WalletLookup.Failed(relay(response))
        val items = EdgeJson.parse(response)?.path("items")?.takeIf { it.isArray }
            ?: return WalletLookup.Failed(unavailable())
        val coupon = items.firstOrNull { it.text("couponId") == couponId }
            ?: return WalletLookup.Failed(rewardsError(NOT_FOUND, "COUPON_NOT_FOUND", "coupon not found"))
        return WalletLookup.Found(coupon)
    }

    private fun needsSca(coupon: JsonNode, targetKind: String): Boolean =
        coupon.text("category") == "LOANS" || coupon.text("benefitType") in CREDIT_BENEFITS || targetKind == "LOAN"

    private fun record(
        operation: String,
        party: UUID,
        resourceId: String?,
        result: String,
        details: Map<String, String?> = emptyMap(),
    ) = audit.emit(
        eventType = "CUSTOMER_REWARDS_" + operation.replace('.', '_').uppercase(),
        partyId = party.toString(),
        operation = "rewards.$operation",
        result = result,
        resourceId = resourceId,
        details = details,
    )

    private fun outcome(response: Response) = if (response.status in SUCCESS_RANGE) SUCCESS else FAILURE

    private fun createdId(response: Response): String? =
        if (response.status in SUCCESS_RANGE) EdgeJson.parse(response)?.text("couponId") else null

    /** The body to forward: a JSON object with any `partyId` dropped; `{}` when optional and absent. */
    private fun forwardable(body: String?, required: Boolean): String? {
        if (body.isNullOrBlank()) return if (required) null else "{}"
        val node = EdgeJson.parseObject(body) as? ObjectNode ?: return null
        node.remove("partyId")
        return EdgeJson.mapper.writeValueAsString(node)
    }

    private fun json(value: Any): String = EdgeJson.mapper.writeValueAsString(value)

    /** Capability `rewards.flux` off: 404, exactly like any other feature-gated route. */
    private fun gate() {
        if (!fluxEnabled) throw NotFoundException()
    }

    private fun url(base: String, party: UUID, path: String) = "$base$API/parties/$party$path"

    private fun baseUrl(): String? =
        rewardsServiceUrl.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.orElse(null)

    private fun header(name: String): String? =
        if (this::requestHeaders.isInitialized) requestHeaders.getHeaderString(name) else null

    private fun party(): UUID = parties.resolve(header(CustomerEdgeResource.ACTING_FOR_HEADER))

    /** Lesson texts are cs/en/bg by Accept-Language; a malformed value is simply not forwarded. */
    private fun languageHeader(): Map<String, String> =
        header(ACCEPT_LANGUAGE)?.trim()?.takeIf { LANGUAGE.matches(it) }?.let { mapOf(ACCEPT_LANGUAGE to it) }
            ?: emptyMap()

    private fun idempotencyKey(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_IDEMPOTENCY_KEY_LENGTH }

    private fun missingOrLongKey(raw: String?): Response = if (raw.isNullOrBlank()) {
        rewardsError(BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required")
    } else {
        rewardsError(BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key is too long")
    }

    private fun segment(raw: String?): String? = raw?.trim()?.takeIf { SEGMENT.matches(it) }

    private fun invalid(message: String) = rewardsError(BAD_REQUEST, "INVALID_REQUEST", message)

    private fun notFound() = rewardsError(NOT_FOUND, "NOT_FOUND", "not found")

    private fun fallbackCode(status: Int) = when (status) {
        BAD_REQUEST -> "INVALID_REQUEST"
        NOT_FOUND -> "NOT_FOUND"
        CONFLICT -> "CONFLICT"
        else -> "REWARDS_REQUEST_REJECTED"
    }

    companion object {
        const val API = "/api/v1/rewards"
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val ACCEPT_LANGUAGE = "Accept-Language"
        const val UNAVAILABLE_CODE = "REWARDS_UNAVAILABLE"
        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val SERVER_ERROR = 500
        private val SUCCESS_RANGE = 200..299
        private const val SUCCESS = "SUCCESS"
        private const val FAILURE = "FAILURE"
        private const val DEFAULT_PAGE_SIZE = 20
        private const val MAX_PAGE_SIZE = 100
        private const val MAX_IDEMPOTENCY_KEY_LENGTH = 255

        val TARGET_KINDS = setOf("LOAN", "SAVINGS_ACCOUNT", "BILL_PAYMENT")
        val USE_TARGET_KINDS = TARGET_KINDS + "NONE"
        val CREDIT_BENEFITS = setOf("LOAN_RATE_REDUCTION", "INSTALMENT_RELIEF")

        /** Ids and target ids that may become a path or query segment: no `/`, `.`, `?`, `%` or `&`. */
        private val SEGMENT = Regex("^[A-Za-z0-9_-]{1,128}$")
        private val CODE = Regex("^[A-Z0-9_]{1,64}$")
        private val LANGUAGE = Regex("^[A-Za-z0-9 ,;=.*-]{1,128}$")
    }
}

/**
 * The APPROVAL dynamic-linking for a credit-coupon use. `approvalRequestId` names the operation and
 * the coupon; `payloadSha256` is the SHA-256 (lowercase hex) of the canonical JSON — keys sorted, no
 * whitespace — of `{"couponId":…,"operation":"rewards.coupon.use","payload":{"targetId":…,"targetKind":…}}`.
 * The app never has to compute it: the 403 SCA_REQUIRED body carries both values.
 */
internal object RewardsScaLinking {
    private const val OPERATION = "rewards.coupon.use"
    private val canonical = EdgeJson.mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)

    fun of(couponId: String, payload: Map<String, String?>): Map<String, String> {
        val document = mapOf("operation" to OPERATION, "couponId" to couponId, "payload" to payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.writeValueAsBytes(document))
        return mapOf(
            "approvalRequestId" to "$OPERATION:$couponId",
            "payloadSha256" to digest.joinToString("") { "%02x".format(it) },
        )
    }
}

private fun rewardsError(status: Int, code: String, message: String): Response =
    EdgeJson.error(status, message, mapOf("code" to code, "message" to message))

/** The engine cannot answer (not configured, down, or misbehaving): the app hides rewards content. */
private fun unavailable(): Response = rewardsError(
    Response.Status.SERVICE_UNAVAILABLE.statusCode,
    CustomerRewardsResource.UNAVAILABLE_CODE,
    "rewards are not available right now",
)
