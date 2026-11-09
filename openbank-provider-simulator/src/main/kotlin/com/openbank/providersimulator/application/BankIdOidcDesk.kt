// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.providersimulator.domain.AccessGrant
import com.openbank.providersimulator.domain.Applicant
import com.openbank.providersimulator.domain.AuthorizationCode
import com.openbank.providersimulator.domain.AuthorizationRequest
import com.openbank.providersimulator.domain.BankIdIdentity
import com.openbank.providersimulator.domain.HsJwt
import com.openbank.providersimulator.domain.OidcClients
import com.openbank.providersimulator.domain.OidcError
import com.openbank.providersimulator.domain.OpaqueValues
import com.openbank.providersimulator.domain.People
import com.openbank.providersimulator.domain.Redirects
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The simulator's side channel: who will be at the keyboard when the bank's /auth request with [state] arrives. */
data class ExpectedLogin(val state: String, val applicant: Applicant, val at: Instant)

/** The parameters of GET /bankid/auth, as the browser brought them. */
data class AuthorizeParams(
    val responseType: String?,
    val clientId: String?,
    val redirectUri: String?,
    val scope: String?,
    val state: String?,
    val nonce: String?,
    val codeChallenge: String?,
    val codeChallengeMethod: String?,
)

/** The form of POST /bankid/token (client credentials either here or in a Basic header). */
data class TokenParams(
    val grantType: String?,
    val code: String?,
    val redirectUri: String?,
    val codeVerifier: String?,
    val clientId: String?,
    val clientSecret: String?,
)

sealed interface AuthorizeOutcome {
    /** Show the consent page for [request]; [signedInAs] is who the bank has signed in. */
    data class Consent(val request: AuthorizationRequest, val signedInAs: BankIdIdentity) : AuthorizeOutcome

    data class Refused(val reason: String) : AuthorizeOutcome
}

sealed interface TokenOutcome {
    data class Issued(val grant: AccessGrant, val idToken: String, val expiresIn: Long) : TokenOutcome

    data class Refused(val error: OidcError, val description: String) : TokenOutcome
}

/**
 * Czech Bank iD as an OpenID Connect provider (NON-PRODUCTION), in the real shape: authorization code
 * flow with PKCE (S256) for a confidential client. The bank tells the simulator out of band who is at
 * the keyboard for a `state` (a real Bank iD knows from the person's own bank login); the consent page
 * then issues a single-use code, the bank redeems it with its secret and verifier, and /userinfo answers
 * the claims — another person's under BANKID_OTHER_PERSON.
 */
@ApplicationScoped
class BankIdOidcDesk @Inject constructor(
    private val clock: Clock,
    private val scenarios: ScenarioBook,
    @ConfigProperty(name = "openbank.provider-simulator.public-url", defaultValue = "http://localhost:8163")
    private val publicUrl: String,
    @ConfigProperty(name = "openbank.provider-simulator.bankid.clients")
    clientsSpec: Optional<String>,
) {
    private val clients = OidcClients.parse(clientsSpec.orElse(""))
    private val expected = ConcurrentHashMap<String, ExpectedLogin>()
    private val pending = ConcurrentHashMap<String, AuthorizationRequest>()
    private val codes = ConcurrentHashMap<String, AuthorizationCode>()
    private val redeemed = ConcurrentHashMap<String, Pair<String, Instant>>()
    private val tokens = ConcurrentHashMap<String, AccessGrant>()

    val issuer: String get() = publicUrl.trimEnd('/') + "/bankid"

    fun expect(state: String, applicant: Applicant): ExpectedLogin {
        require(state.isNotBlank() && state.length <= MAX_PARAM) { "state must be 1..$MAX_PARAM characters" }
        require(applicant.givenName.isNotBlank() && applicant.familyName.isNotBlank()) {
            "applicant.given_name and applicant.family_name are required"
        }
        evictExpired()
        scenarios.remember(applicant)
        return ExpectedLogin(state, applicant, clock.instant()).also { expected[state] = it }
    }

    fun authorize(p: AuthorizeParams): AuthorizeOutcome {
        refusalOf(p)?.let { return AuthorizeOutcome.Refused(it) }
        val state = p.state.orEmpty()
        val login = expected[state] ?: return AuthorizeOutcome.Refused("no Bank iD login is in progress for this state")
        evictExpired()
        val request = AuthorizationRequest(
            requestId = OpaqueValues.next(),
            clientId = p.clientId.orEmpty(),
            redirectUri = p.redirectUri.orEmpty(),
            scope = p.scope.orEmpty(),
            state = state,
            nonce = p.nonce,
            codeChallenge = p.codeChallenge.orEmpty(),
            createdAt = clock.instant(),
        )
        pending[request.requestId] = request
        return AuthorizeOutcome.Consent(request, identityOf(login))
    }

    /**
     * The person confirmed (or cancelled) on the consent page. Returns where to send the browser — the
     * client's redirect_uri with a fresh code, or with `error=access_denied` — or null for an unknown or
     * already-decided request.
     */
    fun decide(requestId: String, confirmed: Boolean): String? {
        val request = pending.remove(requestId) ?: return null
        if (request.createdAt.isBefore(clock.instant().minus(REQUEST_LIFE))) return null
        if (!confirmed) {
            val denied = listOf("error" to "access_denied", "state" to request.state)
            return Redirects.withParams(request.redirectUri, denied)
        }
        val login = expected.remove(request.state) ?: return null
        val code = AuthorizationCode(
            code = OpaqueValues.next(),
            clientId = request.clientId,
            redirectUri = request.redirectUri,
            codeChallenge = request.codeChallenge,
            nonce = request.nonce,
            identity = identityOf(login),
            issuedAt = clock.instant(),
        )
        codes[code.code] = code
        return Redirects.withParams(request.redirectUri, listOf("code" to code.code, "state" to request.state))
    }

    fun token(p: TokenParams, basicCredentials: Pair<String, String>?): TokenOutcome {
        val clientId = basicCredentials?.first ?: p.clientId
        val secret = basicCredentials?.second ?: p.clientSecret
        if (clientId == null || secret == null || !clients.authenticates(clientId, secret)) {
            return TokenOutcome.Refused(OidcError.INVALID_CLIENT, "client authentication failed")
        }
        if (p.grantType != "authorization_code") {
            return TokenOutcome.Refused(OidcError.UNSUPPORTED_GRANT_TYPE, "only authorization_code is supported")
        }
        val codeValue = p.code ?: return TokenOutcome.Refused(OidcError.INVALID_REQUEST, "code is required")
        val now = clock.instant()
        val code = codes.remove(codeValue)
        if (code == null) {
            // RFC 6749 §4.1.2: a code presented twice also revokes what the first redemption issued.
            redeemed[codeValue]?.let { tokens.remove(it.first) }
            return TokenOutcome.Refused(OidcError.INVALID_GRANT, "authorization code is unknown, expired or used")
        }
        code.refusal(clientId, p.redirectUri, p.codeVerifier, now, CODE_LIFE)?.let {
            return TokenOutcome.Refused(OidcError.INVALID_GRANT, it)
        }
        val grant = AccessGrant(OpaqueValues.next(), code.clientId, code.identity, UUID.randomUUID().toString(), now)
        tokens[grant.token] = grant
        redeemed[codeValue] = grant.token to now
        return TokenOutcome.Issued(grant, idToken(code, grant, secret), TOKEN_LIFE.seconds)
    }

    /** The grant behind an `Authorization: Bearer` value; null for a missing, unknown or expired token. */
    fun userinfo(authorization: String?): AccessGrant? {
        if (authorization == null || !authorization.startsWith(BEARER, ignoreCase = true)) return null
        val grant = tokens[authorization.substring(BEARER.length).trim()] ?: return null
        return grant.takeIf { !clock.instant().isAfter(it.issuedAt.plus(TOKEN_LIFE)) }
    }

    fun discovery(): Map<String, Any> = mapOf(
        "issuer" to issuer,
        "authorization_endpoint" to "$issuer/auth",
        "token_endpoint" to "$issuer/token",
        "userinfo_endpoint" to "$issuer/userinfo",
        "jwks_uri" to "$issuer/.well-known/jwks.json",
        "response_types_supported" to listOf("code"),
        "grant_types_supported" to listOf("authorization_code"),
        "subject_types_supported" to listOf("public"),
        "id_token_signing_alg_values_supported" to listOf("HS256"),
        "token_endpoint_auth_methods_supported" to listOf("client_secret_post", "client_secret_basic"),
        "code_challenge_methods_supported" to listOf("S256"),
        "scopes_supported" to SCOPES,
        "claims_supported" to CLAIMS,
    )

    private fun refusalOf(p: AuthorizeParams): String? = listOf(
        (p.responseType == "code") to "response_type must be 'code'",
        clients.knows(p.clientId) to "unknown client_id",
        Redirects.isAcceptable(p.redirectUri.orEmpty()) to "redirect_uri must be an absolute URI",
        (p.scope.orEmpty().split(' ').contains("openid")) to "scope must include 'openid'",
        (p.state.orEmpty().length in 1..MAX_PARAM) to "state is required",
        (p.codeChallenge.orEmpty().length in 1..MAX_PARAM) to "code_challenge is required",
        (p.codeChallengeMethod == "S256") to "code_challenge_method must be S256",
        (p.nonce.orEmpty().length <= MAX_PARAM) to "nonce is too long",
    ).firstOrNull { !it.first }?.second

    private fun identityOf(login: ExpectedLogin): BankIdIdentity =
        People.bankId(login.applicant, scenarios.scenariosFor(login.applicant.nationalId))

    private fun idToken(code: AuthorizationCode, grant: AccessGrant, secret: String): String {
        val now = grant.issuedAt.epochSecond
        val claims = buildMap<String, Any> {
            put("iss", issuer)
            put("sub", code.identity.sub)
            put("aud", code.clientId)
            put("iat", now)
            put("auth_time", code.issuedAt.epochSecond)
            put("exp", now + TOKEN_LIFE.seconds)
            put("txn", grant.transactionId)
            code.nonce?.let { put("nonce", it) }
        }
        return HsJwt.sign(MAPPER.writeValueAsString(claims), secret)
    }

    private fun evictExpired() {
        val now = clock.instant()
        expected.values.removeIf { it.at.isBefore(now.minus(LOGIN_LIFE)) }
        pending.values.removeIf { it.createdAt.isBefore(now.minus(REQUEST_LIFE)) }
        codes.values.removeIf { it.issuedAt.isBefore(now.minus(CODE_LIFE)) }
        redeemed.values.removeIf { it.second.isBefore(now.minus(TOKEN_LIFE)) }
        tokens.values.removeIf { it.issuedAt.isBefore(now.minus(TOKEN_LIFE)) }
        listOf(expected, pending, codes, redeemed, tokens).forEach { map ->
            if (map.size > ProviderSimulator.MAX_SESSIONS) {
                map.keys.take(map.size - ProviderSimulator.MAX_SESSIONS).forEach { map.remove(it) }
            }
        }
    }

    companion object {
        val CODE_LIFE: Duration = Duration.ofSeconds(60)
        val TOKEN_LIFE: Duration = Duration.ofSeconds(300)
        val REQUEST_LIFE: Duration = Duration.ofMinutes(10)
        val LOGIN_LIFE: Duration = Duration.ofMinutes(30)
        const val MAX_PARAM = 512
        private const val BEARER = "Bearer "
        private val MAPPER = jacksonObjectMapper()
        val SCOPES = listOf(
            "openid",
            "profile.name",
            "profile.birthdate",
            "profile.gender",
            "profile.birthnumber",
            "profile.nationality",
        )
        val CLAIMS = listOf(
            "sub",
            "given_name",
            "family_name",
            "birthdate",
            "gender",
            "birthnumber",
            "nationalities",
            "txn",
        )
    }
}
