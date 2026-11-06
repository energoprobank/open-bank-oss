// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import com.openbank.libs.authz.Authorize
import io.quarkus.logging.Log
import io.smallrye.common.annotation.Blocking
import io.vertx.core.http.HttpServerRequest
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Experience v2 step 8 — the onboarding gaps and the face check, on the customer edge.
//
// Routing: CustomerEdgeResource owns `/customer/v1` and OnboardingResource `/customer/v1/onboarding`;
// a class whose path is a PREFIX of theirs would capture their routes. So every class here carries its
// own full path (cf. DemoPasswordRegistrationResource) and never a bare prefix.
//
// Identity: the party is always the token's ([CustomerPartyResolver]); a party id in a path must equal
// it (403 otherwise) and is never forwarded on its own. The routes before sign-in (code check,
// Energo-Pro prefill, contact codes) are @PermitAll in classes without a class-level @RolesAllowed —
// they carry nothing personal, are rate-limited per client address, and the upstream services do the
// real limiting per e-mail, phone and verification.

/** Upstreams, response relay and the per-address limit shared by the step-8 resources. */
@ApplicationScoped
class ExperienceEdge(val upstream: UpstreamClient) {
    @ConfigProperty(
        name = "openbank.edge.onboarding-service-url",
        defaultValue = "http://onboarding-service.onboarding.svc:8130",
    )
    lateinit var onboardingUrl: String

    @ConfigProperty(
        name = "openbank.edge.everyday-service-url",
        defaultValue = "http://everyday-service.payments.svc:8164",
    )
    lateinit var everydayUrl: String

    @ConfigProperty(name = "openbank.edge.balance-service-url")
    lateinit var balanceUrl: String

    @ConfigProperty(name = "openbank.edge.party-service-url")
    lateinit var partyUrl: String

    @ConfigProperty(name = "openbank.edge.incentive-service-url")
    lateinit var incentiveUrl: String

    @ConfigProperty(name = "openbank.edge.referral-service-url")
    lateinit var referralUrl: String

    @ConfigProperty(name = "openbank.edge.consent-service-url")
    lateinit var consentUrl: String

    @ConfigProperty(name = "openbank.edge.sca-service-url")
    lateinit var scaUrl: String

    @ConfigProperty(name = "openbank.edge.card-issuance-service-url")
    lateinit var cardIssuanceUrl: String

    @ConfigProperty(name = "openbank.edge.card-processing-service-url")
    lateinit var cardProcessingUrl: Optional<String>

    @ConfigProperty(name = "openbank.edge.rewards-service-url")
    lateinit var rewardsUrl: Optional<String>

    @ConfigProperty(
        name = "openbank.edge.product-catalog-url",
        defaultValue = "http://product-catalog.accounts.svc:8104",
    )
    lateinit var catalogUrl: String

    @jakarta.inject.Inject
    lateinit var currentRequest: io.quarkus.vertx.http.runtime.CurrentVertxRequest

    private val markets = ConcurrentHashMap<UUID, String>()

    /** cs, bg or en from the current request's Accept-Language (en when absent or malformed). */
    fun language(): String {
        val header = runCatching { currentRequest.current?.request()?.getHeader("Accept-Language") }.getOrNull()
        return header?.trim()?.takeIf { LANGUAGE.matches(it) }?.substring(0, 2)?.lowercase()?.takeIf { it in LANGUAGES }
            ?: "en"
    }
    private val hits = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    fun onboarding(path: String) = "${onboardingUrl.trimEnd('/')}$path"

    /**
     * The upstream's answer as the app sees it: 2xx verbatim; 4xx as `{code, message}`; anything that
     * means "cannot serve" (5xx, transport failure, the edge principal refused) is 503 [unavailableCode].
     */
    fun relay(response: Response, unavailableCode: String = "SERVICE_UNAVAILABLE"): Response {
        val status = response.status
        return when {
            status in SUCCESS -> Response.status(
                status,
            ).entity(response.entity ?: "{}").type(MediaType.APPLICATION_JSON).build()
            status >= SERVER_ERROR || status == UNAUTHORIZED || status == FORBIDDEN -> {
                Log.warnf("step-8 upstream answered %d; reporting %s", status, unavailableCode)
                error(SERVICE_UNAVAILABLE, unavailableCode, "This is not available right now. Please try again later.")
            }
            else -> {
                val node = EdgeJson.parse(response)
                val code =
                    node?.text("code")?.takeIf { CODE.matches(it) }
                        ?: if (status == NOT_FOUND) "NOT_FOUND" else "INVALID_REQUEST"
                error(status, code, node?.text("message") ?: node?.text("detail") ?: "request rejected")
            }
        }
    }

    fun error(status: Int, code: String, message: String): Response =
        Response.status(status).entity(EdgeJson.mapper.writeValueAsString(mapOf("code" to code, "message" to message)))
            .type(MediaType.APPLICATION_JSON).build()

    /** A JSON object body, or null. */
    fun objectBody(body: String?): ObjectNode? = EdgeJson.parseObject(body) as? ObjectNode

    fun json(value: Any): String = EdgeJson.mapper.writeValueAsString(value)

    /**
     * The customer's market (CZ / BG) from party-service; immutable for a party, so cached. CZ when
     * party-service cannot be asked — the market every party had before markets existed.
     */
    fun marketOf(party: UUID): String = markets[party] ?: run {
        val response = upstream.get("${partyUrl.trimEnd('/')}/api/v1/parties/$party", party.toString())
        val market = EdgeJson.parse(response)?.text("market")?.uppercase()?.takeIf { it == "CZ" || it == "BG" }
        if (market != null) markets[party] = market
        market ?: "CZ"
    }

    /** A person's first name for "Adam invited you" — never more of them than that. */
    fun firstName(party: UUID): String? {
        val response = upstream.get("${partyUrl.trimEnd('/')}/api/v1/parties/$party", party.toString())
        if (response.status !in SUCCESS) return null
        return EdgeJson.parse(response)?.text("legalName")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf {
            it.isNotBlank()
        }
    }

    /** Pre-sign-in routes: at most [limit] calls per client address and route group per [window]. */
    fun allow(group: String, client: String?, limit: Int = PUBLIC_LIMIT, window: Duration = PUBLIC_WINDOW): Boolean {
        val key = "$group|${client ?: "unknown"}"
        val now = Instant.now()
        val recent = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(recent) {
            while (recent.isNotEmpty() && recent.first().isBefore(now.minus(window))) recent.removeFirst()
            if (recent.size >= limit) return false
            recent.addLast(now)
        }
        if (hits.size >
            MAX_TRACKED
        ) {
            hits.entries.removeIf { (_, v) ->
                synchronized(v) {
                    v.isEmpty() ||
                        v.last().isBefore(now.minus(window))
                }
            }
        }
        return true
    }

    fun tooMany(): Response =
        error(TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "Too many attempts. Please wait a few minutes.")

    companion object {
        val SUCCESS = 200..299
        const val NOT_FOUND = 404
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val SERVER_ERROR = 500
        const val SERVICE_UNAVAILABLE = 503
        const val TOO_MANY_REQUESTS = 429
        const val BAD_REQUEST = 400
        const val PUBLIC_LIMIT = 20
        val PUBLIC_WINDOW: Duration = Duration.ofMinutes(10)
        private const val MAX_TRACKED = 10_000
        val CODE = Regex("^[A-Z][A-Z0-9_]{1,63}$")
        val SEGMENT = Regex("^[A-Za-z0-9_.:-]{1,128}$")
        val LANGUAGES = setOf("cs", "bg", "en")
        val LANGUAGE = Regex("^[A-Za-z]{2}([-_][A-Za-z]{2})?([,;].{0,60})?$")
        val REFERRAL_TOKEN = Regex("^[A-Za-z0-9_-]{16,128}$")
        val PROMO_CODE = Regex("^[A-Za-z0-9-]{3,64}$")
    }
}

/** The client's address for the pre-sign-in limit: the first X-Forwarded-For hop, else the socket peer. */
private fun clientOf(request: HttpServerRequest?): String? =
    request?.getHeader("X-Forwarded-For")?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: request?.remoteAddress()?.hostAddress()

// ---------------------------------------------------------------- before sign-in

/** A promo code or a friend's invite on the welcome screen: is it good, and from whom. */
@Path("/customer/v1/onboarding/promo-codes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class OnboardingCodeCheckResource(private val edge: ExperienceEdge) {
    @Context
    var request: HttpServerRequest? = null

    @POST
    @Path("/validate")
    @PermitAll
    @Blocking
    fun validate(body: String?): Response {
        if (!edge.allow("code-check", clientOf(request))) return edge.tooMany()
        val code = edge.objectBody(body)?.text("code")?.trim()
            ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "code is required")
        val token = code.substringAfter("/r/", code).substringBefore('?').substringBefore('#').trimEnd('/')
        return when {
            ExperienceEdge.REFERRAL_TOKEN.matches(token) && token.length >= MIN_TOKEN -> invite(token)
            ExperienceEdge.PROMO_CODE.matches(code) -> promo(code)
            else -> verdict(valid = false, kind = "PROMO")
        }
    }

    private fun invite(token: String): Response {
        val response = edge.upstream.get("${edge.referralUrl.trimEnd('/')}/api/v1/referrals/invites/$token/preview")
        if (response.status !in ExperienceEdge.SUCCESS) return edge.relay(response)
        val referrer =
            EdgeJson.parse(response)?.text("referrerPartyId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return verdict(valid = false, kind = "REFERRAL")
        return verdict(valid = true, kind = "REFERRAL", referrerName = edge.firstName(referrer))
    }

    private fun promo(code: String): Response {
        val response = edge.upstream.postAnonymous(
            "${edge.incentiveUrl.trimEnd('/')}/api/v1/customer-incentives/codes/check",
            edge.json(mapOf("code" to code)),
        )
        if (response.status !in ExperienceEdge.SUCCESS) return edge.relay(response)
        val node = EdgeJson.parse(response)
        val valid = node?.path("valid")?.asBoolean(false) ?: false
        return verdict(valid = valid, kind = "PROMO", benefit = node?.text("offerName")?.takeIf { valid })
    }

    private fun verdict(valid: Boolean, kind: String, benefit: String? = null, referrerName: String? = null) =
        EdgeJson.ok(mapOf("valid" to valid, "kind" to kind, "benefit" to benefit, "referrer_name" to referrerName))

    private companion object {
        const val MIN_TOKEN = 16
    }
}

/** Name and supply address from Energo-Pro, against a confirmed e-mail or phone (onboarding-service checks). */
@Path("/customer/v1/onboarding/energo-pro")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class OnboardingEnergoProResource(private val edge: ExperienceEdge) {
    @Context
    var request: HttpServerRequest? = null

    @POST
    @Path("/prefill")
    @PermitAll
    @Blocking
    fun prefill(body: String?): Response {
        if (!edge.allow("energo-pro", clientOf(request), limit = PREFILL_LIMIT)) return edge.tooMany()
        val input =
            edge.objectBody(body)
                ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "body must be a JSON object")
        val forward = mapOf(
            "customer_number" to input.text("customer_number"),
            "contact_verification_id" to input.text("contact_verification_id"),
        )
        return edge.relay(
            edge.upstream.postAnonymous(edge.onboarding("/api/v1/onboarding/energo-pro/prefill"), edge.json(forward)),
        )
    }

    private companion object {
        const val PREFILL_LIMIT = 10
    }
}

/** One-time codes to the applicant's e-mail and phone. */
@Path("/customer/v1/onboarding/contact-verifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class OnboardingContactResource(private val edge: ExperienceEdge) {
    @Context
    var request: HttpServerRequest? = null

    @POST
    @PermitAll
    @Blocking
    fun send(body: String?): Response {
        if (!edge.allow("contact-send", clientOf(request), limit = SEND_LIMIT)) return edge.tooMany()
        val input =
            edge.objectBody(body)
                ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "body must be a JSON object")
        val forward = mapOf("email" to input.text("email"), "phone" to input.text("phone"))
        return edge.relay(
            edge.upstream.postAnonymous(
                edge.onboarding("/api/v1/onboarding/contact-verifications"),
                edge.json(forward),
            ),
        )
    }

    @POST
    @Path("/{id}/confirm")
    @PermitAll
    @Blocking
    fun confirm(@PathParam("id") id: String?, body: String?): Response {
        if (!edge.allow("contact-confirm", clientOf(request))) return edge.tooMany()
        val verification = id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return edge.error(ExperienceEdge.NOT_FOUND, "VERIFICATION_NOT_FOUND", "No such verification")
        val input =
            edge.objectBody(body)
                ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "body must be a JSON object")
        val forward = mapOf("email_code" to input.text("email_code"), "phone_code" to input.text("phone_code"))
        return edge.relay(
            edge.upstream.postAnonymous(
                edge.onboarding("/api/v1/onboarding/contact-verifications/$verification/confirm"),
                edge.json(forward),
            ),
        )
    }

    private companion object {
        const val SEND_LIMIT = 6
    }
}

// ---------------------------------------------------------------- signed in

/** Base for the signed-in step-8 routes: the token's party, and the one-path-party rule. */
abstract class SignedInExperience(protected val edge: ExperienceEdge, private val parties: CustomerPartyResolver) {
    protected fun party(): UUID = parties.resolve(null)

    /** A party id in the path must be the customer's own; anything else is refused, never followed. */
    protected fun ownParty(pathId: String?): UUID {
        val party = party()
        if (pathId == null || runCatching { UUID.fromString(pathId) }.getOrNull() != party) throw ForbiddenException()
        return party
    }

    /**
     * The app language (cs, bg or en) from the current request's Accept-Language. Read through the
     * application-scoped [ExperienceEdge], not a @HeaderParam field: a param field would make these
     * resources request-scoped, which constructor injection cannot proxy.
     */
    protected fun lang(): String = edge.language()

    protected fun languageHeader(): Map<String, String> = mapOf("Accept-Language" to lang())
}

/** The application: personal details, tax residency and the account package (written by onboarding-service). */
@Path("/customer/v1/onboarding/applications/{applicationId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class OnboardingApplicationEdgeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) :
    SignedInExperience(edge, parties) {

    @PUT
    @Path("/personal-details")
    @Authorize(action = "customer.onboarding.application.update")
    @Blocking
    fun personalDetails(@PathParam("applicationId") applicationId: String?, body: String?): Response =
        forward(applicationId, "/personal-details", body)

    @PUT
    @Path("/tax-residency")
    @Authorize(action = "customer.onboarding.application.update")
    @Blocking
    fun taxResidency(@PathParam("applicationId") applicationId: String?, body: String?): Response =
        forward(applicationId, "/tax-residency", body)

    @PUT
    @Path("/package")
    @Authorize(action = "customer.onboarding.application.update")
    @Blocking
    fun choosePackage(@PathParam("applicationId") applicationId: String?, body: String?): Response =
        forward(applicationId, "/package", body)

    private fun forward(applicationId: String?, suffix: String, body: String?): Response {
        val party = ownParty(applicationId)
        val input =
            edge.objectBody(body)
                ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "body must be a JSON object")
        input.remove(listOf("party_id", "partyId"))
        return edge.relay(
            edge.upstream.put(
                edge.onboarding("/api/v1/onboarding/applications/$party$suffix"),
                party.toString(),
                edge.json(input),
            ),
        )
    }
}

/** Identity: Bank iD (Czech Republic) and the document scan with liveness. */
abstract class IdentityEdge(edge: ExperienceEdge, parties: CustomerPartyResolver, private val method: String) :
    SignedInExperience(edge, parties) {

    protected fun start(body: String?): Response {
        val party = party()
        val input = edge.objectBody(body) ?: EdgeJson.mapper.createObjectNode()
        val forward = mapOf("method" to method, "document_type" to input.text("document_type"))
        return edge.relay(
            edge.upstream.post(
                edge.onboarding("/api/v1/onboarding/applications/$party/identity-verifications"),
                party.toString(),
                edge.json(forward),
            ),
        )
    }

    protected fun status(id: String?): Response {
        val party = party()
        val verification = id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return edge.error(ExperienceEdge.NOT_FOUND, "VERIFICATION_NOT_FOUND", "No such verification")
        val response = edge.upstream.get(
            edge.onboarding("/api/v1/onboarding/applications/$party/identity-verifications/$verification"),
            party.toString(),
        )
        // A Bank iD id asked on the document-scan path (or the other way round) is simply not found.
        if (response.status in ExperienceEdge.SUCCESS && EdgeJson.parse(response)?.text("method") != method) {
            return edge.error(ExperienceEdge.NOT_FOUND, "VERIFICATION_NOT_FOUND", "No such verification")
        }
        return edge.relay(response)
    }
}

@Path("/customer/v1/onboarding/bank-id/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class BankIdEdgeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) :
    IdentityEdge(edge, parties, "BANK_ID") {
    @POST
    @Authorize(action = "customer.onboarding.identity.start")
    @Blocking
    fun create(body: String?): Response = start(body)

    @GET
    @Path("/{id}")
    @Authorize(action = "customer.onboarding.identity.read")
    @Blocking
    fun get(@PathParam("id") id: String?): Response = status(id)
}

@Path("/customer/v1/onboarding/identity-verifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class DocumentScanEdgeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) :
    IdentityEdge(edge, parties, "DOCUMENT_SCAN") {
    @POST
    @Authorize(action = "customer.onboarding.identity.start")
    @Blocking
    fun create(body: String?): Response = start(body)

    @GET
    @Path("/{id}")
    @Authorize(action = "customer.onboarding.identity.read")
    @Blocking
    fun get(@PathParam("id") id: String?): Response = status(id)
}

/**
 * The finale: the welcome coupon (rewards mission M-ONB-1, revealed — i.e. claimed — here, once) and who
 * invited the customer. Rewards off or not a member: no coupon, the rest still answers.
 */
@Path("/customer/v1/onboarding/welcome")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class OnboardingWelcomeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) :
    SignedInExperience(edge, parties) {
    @GET
    @Authorize(action = "customer.onboarding.welcome.read")
    @Blocking
    fun welcome(): Response {
        val party = party()
        val referrer = referrerName(party)
        return EdgeJson.ok(mapOf("coupon" to welcomeCoupon(party), "referrer_name" to referrer, "promo_code" to null))
    }

    private fun welcomeCoupon(party: UUID): JsonNode? {
        val base = edge.rewardsUrl.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.orElse(null) ?: return null
        val root = "$base/api/v1/rewards/parties/$party"
        val missions = edge.upstream.getWithHeaders("$root/missions", party.toString(), languageHeader())
        if (missions.status !in ExperienceEdge.SUCCESS) return null
        val pending = EdgeJson.parse(missions)?.path("claimable")?.firstOrNull {
            it.text("missionId") == WELCOME_MISSION
        }
        if (pending != null) {
            val claimed = edge.upstream.post(
                "$root/missions/$WELCOME_MISSION/claim",
                party.toString(),
                "{}",
                "welcome-$party",
            )
            EdgeJson.parse(claimed)?.path("coupon")?.takeIf { it.isObject }?.let { return it }
        }
        val coupons = edge.upstream.getWithHeaders("$root/coupons", party.toString(), languageHeader())
        if (coupons.status !in ExperienceEdge.SUCCESS) return null
        return EdgeJson.parse(coupons)?.path("items")
            ?.filter { it.text("couponDefId") == WELCOME_COUPON && (it.path("preCharge").asInt(0) > 0) }
            ?.minByOrNull { it.text("issuedAt") ?: "" }
    }

    private fun referrerName(party: UUID): String? {
        val response = edge.upstream.get(
            "${edge.referralUrl.trimEnd('/')}/api/v1/referrals/referees/$party/referrer",
            party.toString(),
        )
        if (response.status !in ExperienceEdge.SUCCESS) return null
        val referrer = EdgeJson.parse(response)?.text("referrerPartyId")?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        return referrer?.let(edge::firstName)
    }

    private companion object {
        const val WELCOME_MISSION = "M-ONB-1"
        const val WELCOME_COUPON = "BILL_CASHBACK_5"
    }
}

/** "Share with the Energo-Pro group": a GDPR opt-in held in consent-service (scope ENERGO_PRO_DATA_SHARING). */
@Path("/customer/v1/consents/energo-pro-data-sharing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class EnergoProDataSharingResource(edge: ExperienceEdge, parties: CustomerPartyResolver) :
    SignedInExperience(edge, parties) {
    @GET
    @Authorize(action = "customer.profile.read")
    @Blocking
    fun get(): Response {
        val party = party()
        val held =
            held(party)
                ?: return edge.error(ExperienceEdge.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "consents unavailable")
        return EdgeJson.ok(mapOf("granted" to held.isNotEmpty()))
    }

    @PUT
    @Authorize(action = "customer.profile.consent.update")
    @Blocking
    fun put(body: String?): Response {
        val party = party()
        val granted = edge.objectBody(body)?.path("granted")?.takeIf { it.isBoolean }?.booleanValue()
            ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "granted (true/false) is required")
        val held =
            held(party)
                ?: return edge.error(ExperienceEdge.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "consents unavailable")
        val base = edge.consentUrl.trimEnd('/')
        if (granted && held.isEmpty()) {
            val consent = mapOf(
                "partyId" to party.toString(),
                "granteeId" to BANK_GRANTEE,
                "granteeType" to "INTERNAL_SERVICE",
                "granteeName" to "Energo-Pro Bank",
                "scopes" to listOf(SCOPE),
                "validTo" to Instant.now().plus(Duration.ofDays(CONSENT_DAYS)).toString(),
            )
            val created = edge.upstream.post("$base/api/v1/consents", party.toString(), edge.json(consent))
            if (created.status !in ExperienceEdge.SUCCESS) return edge.relay(created)
        } else if (!granted) {
            held.forEach { id ->
                edge.upstream.delete(
                    "$base/api/v1/consents/$id?partyId=$party&granteeId=$BANK_GRANTEE",
                    party.toString(),
                )
            }
        }
        return EdgeJson.ok(mapOf("granted" to granted))
    }

    /** Ids of the customer's ACTIVE data-sharing consents, or null when consent-service cannot be asked. */
    private fun held(party: UUID): List<String>? {
        val response = edge.upstream.get(
            "${edge.consentUrl.trimEnd('/')}/api/v1/consents/party/$party",
            party.toString(),
        )
        if (response.status !in ExperienceEdge.SUCCESS) return null
        val items = EdgeJson.parse(response)?.let { if (it.isArray) it else it.path("items") } ?: return null
        return items.filter { c ->
            c.text("status") == "ACTIVE" && c.path("scopes").any { it.asText() == SCOPE }
        }.mapNotNull { it.text("id") }
    }

    private companion object {
        const val SCOPE = "ENERGO_PRO_DATA_SHARING"
        const val BANK_GRANTEE = "openbank"
        const val CONSENT_DAYS = 365L
    }
}

/** Account packages in the customer's market (product-catalog). */
@Path("/customer/v1/products/packages")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class ProductPackagesResource(edge: ExperienceEdge, parties: CustomerPartyResolver) :
    SignedInExperience(edge, parties) {
    @GET
    @Authorize(action = "customer.products.read")
    @Blocking
    fun packages(): Response {
        val party = party()
        val response = edge.upstream.get(
            "${edge.catalogUrl.trimEnd('/')}/api/v1/packages?market=${edge.marketOf(party)}&lang=${lang()}",
            party.toString(),
        )
        if (response.status !in ExperienceEdge.SUCCESS) return edge.relay(response)
        val items =
            EdgeJson.parse(response)?.takeIf { it.isArray }
                ?: return edge.relay(Response.status(ExperienceEdge.SERVER_ERROR).build())
        return EdgeJson.ok(
            items.map {
                mapOf(
                    "code" to it.text("code"),
                    "name" to it.text("name"),
                    "monthly_fee" to it.path("monthlyFee").decimalValue(),
                    "currency" to it.text("currency"),
                    "benefits" to it.path("benefits").map(JsonNode::asText),
                )
            },
        )
    }
}

/** Fee information and deposit guarantee before signing (product-catalog). */
@Path("/customer/v1/products/{code}/disclosures")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class ProductDisclosuresResource(edge: ExperienceEdge, parties: CustomerPartyResolver) :
    SignedInExperience(edge, parties) {
    @GET
    @Authorize(action = "customer.products.read")
    @Blocking
    fun disclosures(@PathParam("code") code: String?): Response {
        val party = party()
        val product = code?.takeIf { ExperienceEdge.SEGMENT.matches(it) }
            ?: return edge.error(ExperienceEdge.NOT_FOUND, "NOT_FOUND", "No such product")
        val response = edge.upstream.get(
            "${edge.catalogUrl.trimEnd('/')}/api/v1/products/by-code/$product/disclosures?lang=${lang()}",
            party.toString(),
        )
        if (response.status !in ExperienceEdge.SUCCESS) return edge.relay(response)
        val node = EdgeJson.parse(response) ?: return edge.relay(Response.status(ExperienceEdge.SERVER_ERROR).build())
        val guarantee = node.path("depositGuarantee")
        return EdgeJson.ok(
            mapOf(
                "fees" to node.path("fees").map {
                    mapOf(
                        "label" to it.text("label"),
                        "amount" to it.path("amount").decimalValue(),
                        "currency" to it.text("currency"),
                        "period" to it.text("period"),
                    )
                },
                "deposit_guarantee" to mapOf(
                    "scheme_name" to guarantee.text("schemeName"),
                    "limit" to guarantee.path("limit").decimalValue(),
                    "currency" to guarantee.text("currency"),
                    "summary" to guarantee.text("summary"),
                ),
            ),
        )
    }
}

/** The app PIN of one of the customer's own devices (sca-service stores a salted hash of the verifier). */
@Path("/customer/v1/sca/parties/{partyId}/devices/{credentialId}/app-pin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class AppPinEdgeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) : SignedInExperience(edge, parties) {
    @PUT
    @Authorize(action = "customer.sca.app-pin.update")
    @Blocking
    fun put(
        @PathParam("partyId") partyId: String?,
        @PathParam("credentialId") credentialId: String?,
        body: String?,
    ): Response {
        val party = ownParty(partyId)
        val credential = credentialId?.takeIf { CREDENTIAL.matches(it) }
            ?: return edge.error(ExperienceEdge.NOT_FOUND, "DEVICE_NOT_FOUND", "No such device")
        val verifier = edge.objectBody(body)?.text("pin_verifier")
            ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "pin_verifier is required")
        val response = edge.upstream.put(
            "${edge.scaUrl.trimEnd('/')}/api/v1/sca/parties/$party/devices/" +
                java.net.URLEncoder.encode(credential, Charsets.UTF_8) + "/app-pin",
            party.toString(),
            edge.json(mapOf("pin_verifier" to verifier)),
        )
        return if (response.status == NO_CONTENT) Response.noContent().build() else edge.relay(response)
    }

    private companion object {
        val CREDENTIAL = Regex("^[A-Za-z0-9_=+/-]{8,512}$")
        const val NO_CONTENT = 204
    }
}

/**
 * Apple Wallet provisioning: a network token for one of the customer's own cards, from card-processing's
 * tokenisation (the wallet provider simulator in this sandbox).
 */
@Path("/customer/v1/cards/{id}/wallet-provisioning")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class WalletProvisioningResource(edge: ExperienceEdge, parties: CustomerPartyResolver) :
    SignedInExperience(edge, parties) {
    @POST
    @Authorize(action = "customer.cards.wallet")
    @Blocking
    fun provision(@PathParam("id") id: String?, body: String?): Response {
        val party = party()
        val card = id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return edge.error(ExperienceEdge.NOT_FOUND, "CARD_NOT_FOUND", "No such card")
        val base = edge.cardProcessingUrl.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.orElse(null)
            ?: return edge.error(
                ExperienceEdge.SERVICE_UNAVAILABLE,
                "WALLET_UNAVAILABLE",
                "Wallet provisioning is not available",
            )
        val owned = edge.upstream.get("${edge.cardIssuanceUrl.trimEnd('/')}/api/v1/cards/$card", party.toString())
        val owner = EdgeJson.parse(owned)?.text("ownerPartyId")
        if (owned.status !in ExperienceEdge.SUCCESS || owner != party.toString()) {
            return edge.error(ExperienceEdge.NOT_FOUND, "CARD_NOT_FOUND", "No such card")
        }
        val wallet = edge.objectBody(body)?.text("wallet")?.takeIf { it in WALLETS } ?: "APPLE_PAY"
        val response = edge.upstream.post(
            "$base/api/v1/card-tokens",
            party.toString(),
            edge.json(
                mapOf(
                    "cardId" to card.toString(),
                    "requestorId" to wallet,
                    "requestorLabel" to WALLETS.getValue(wallet),
                ),
            ),
            "wallet-$card-$wallet",
        )
        if (response.status !in ExperienceEdge.SUCCESS) return edge.relay(response, "WALLET_UNAVAILABLE")
        val status = EdgeJson.parse(response)?.text("status")
        return EdgeJson.ok(mapOf("status" to if (status == "ACTIVE") "ADDED" else "PENDING"))
    }

    private companion object {
        val WALLETS = mapOf("APPLE_PAY" to "Apple Wallet", "GOOGLE_PAY" to "Google Wallet")
    }
}

/** Face check for large payments: the policy and the liveness sessions (onboarding-service). */
@Path("/customer/v1/security/liveness")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class LivenessEdgeResource(edge: ExperienceEdge, parties: CustomerPartyResolver) : SignedInExperience(edge, parties) {
    private fun root(party: UUID) = edge.onboarding("/api/v1/security/liveness/parties/$party")

    @GET
    @Path("/policy")
    @Authorize(action = "customer.security.read")
    @Blocking
    fun policy(): Response {
        val party = party()
        return edge.relay(edge.upstream.get("${root(party)}/policy", party.toString()))
    }

    @PUT
    @Path("/policy")
    @Authorize(action = "customer.security.update")
    @Blocking
    fun updatePolicy(body: String?): Response {
        val party = party()
        val input =
            edge.objectBody(body)
                ?: return edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", "body must be a JSON object")
        val forward = mapOf(
            "enabled" to input.path("enabled").asBoolean(),
            "threshold" to input.path("threshold").decimalValue(),
            "session_id" to input.text("session_id"),
        )
        return edge.relay(edge.upstream.put("${root(party)}/policy", party.toString(), edge.json(forward)))
    }

    @POST
    @Path("/sessions")
    @Authorize(action = "customer.security.update")
    @Blocking
    fun start(body: String?): Response {
        val party = party()
        val input = edge.objectBody(body) ?: EdgeJson.mapper.createObjectNode()
        val forward = mapOf("purpose" to input.text("purpose"), "payment_reference" to input.text("payment_reference"))
        return edge.relay(edge.upstream.post("${root(party)}/sessions", party.toString(), edge.json(forward)))
    }

    @GET
    @Path("/sessions/{id}")
    @Authorize(action = "customer.security.read")
    @Blocking
    fun session(@PathParam("id") id: String?): Response {
        val party = party()
        val session = id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return edge.error(ExperienceEdge.NOT_FOUND, "SESSION_NOT_FOUND", "No such face check")
        return edge.relay(edge.upstream.get("${root(party)}/sessions/$session", party.toString()))
    }
}

/**
 * The face-check rule on payments: when the customer's policy is on and [amount] (in the policy's
 * currency) is above the limit, the payment must carry a PASSED, unused PAYMENT face check, which is
 * spent here. Returns null to proceed, or the 403 LIVENESS_REQUIRED to send.
 *
 * Fail-open when the policy cannot be read: the face check is an extra, customer-chosen step on top of
 * SCA, and an outage of it must not stop every payment. Logged.
 */
@ApplicationScoped
class LivenessGate(private val edge: ExperienceEdge) {
    fun check(party: UUID, amount: java.math.BigDecimal?, currency: String?, sessionHeader: String?): Response? {
        if (amount == null) return null
        val base = edge.onboarding("/api/v1/security/liveness/parties/$party")
        val policy = edge.upstream.get("$base/policy", party.toString())
        if (policy.status !in ExperienceEdge.SUCCESS) {
            Log.warnf("face-check policy unreadable for %s (%d): payment proceeds without it", party, policy.status)
            return null
        }
        val node = EdgeJson.parse(policy) ?: return null
        val applies = node.path("enabled").asBoolean(false) &&
            node.text("currency") == currency &&
            amount > node.path("threshold").decimalValue()
        if (!applies) return null
        val session = sessionHeader?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
            ?: return required()
        val consumed = edge.upstream.post("$base/sessions/$session/consume", party.toString(), "{}")
        return if (consumed.status in ExperienceEdge.SUCCESS) null else required()
    }

    private fun required() = edge.error(
        ExperienceEdge.FORBIDDEN,
        "LIVENESS_REQUIRED",
        "A face check is needed for a payment above your limit.",
    )

    companion object {
        const val HEADER = "X-Liveness-Session-Id"
    }
}
