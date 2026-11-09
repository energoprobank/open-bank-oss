// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.domain

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 7636 PKCE, S256 only (plain is not offered: Bank iD requires S256). */
object Pkce {
    private const val MIN_VERIFIER = 43
    private const val MAX_VERIFIER = 128
    private val VERIFIER = Regex("[A-Za-z0-9._~-]+")

    fun s256(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun verifies(verifier: String?, challenge: String): Boolean {
        if (verifier == null || verifier.length !in MIN_VERIFIER..MAX_VERIFIER || !VERIFIER.matches(verifier)) {
            return false
        }
        return MessageDigest.isEqual(s256(verifier).toByteArray(), challenge.toByteArray())
    }
}

/** The confidential clients Bank iD knows, from `id:secret,id2:secret2`; empty = any client with a secret. */
class OidcClients(private val secrets: Map<String, String>) {
    val open: Boolean get() = secrets.isEmpty()

    fun knows(clientId: String?): Boolean = !clientId.isNullOrBlank() && (open || clientId in secrets)

    fun authenticates(clientId: String?, secret: String?): Boolean {
        if (clientId.isNullOrBlank() || secret.isNullOrBlank()) return false
        if (open) return true
        val expected = secrets[clientId] ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), secret.toByteArray())
    }

    companion object {
        fun parse(spec: String?): OidcClients = OidcClients(
            spec.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).associate { pair ->
                val id = pair.substringBefore(':').trim()
                val secret = pair.substringAfter(':', "").trim()
                require(id.isNotEmpty() && secret.isNotEmpty()) { "bankid.clients entries are 'client_id:secret'" }
                id to secret
            },
        )
    }
}

/** An /auth request the consent page is showing, until the person confirms or cancels it. */
data class AuthorizationRequest(
    val requestId: String,
    val clientId: String,
    val redirectUri: String,
    val scope: String,
    val state: String,
    val nonce: String?,
    val codeChallenge: String,
    val createdAt: Instant,
)

/** A code issued at confirm: single use, short-lived, bound to the client, the redirect and the PKCE challenge. */
data class AuthorizationCode(
    val code: String,
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String,
    val nonce: String?,
    val identity: BankIdIdentity,
    val issuedAt: Instant,
) {
    /** Null when [clientId], [redirectUri] and [verifier] redeem this code at [now]; otherwise why not. */
    fun refusal(clientId: String, redirectUri: String?, verifier: String?, now: Instant, ttl: Duration): String? =
        when {
            now.isAfter(issuedAt.plus(ttl)) -> "authorization code expired"
            clientId != this.clientId -> "authorization code was issued to another client"
            redirectUri != this.redirectUri -> "redirect_uri does not match the authorization request"
            !Pkce.verifies(verifier, codeChallenge) -> "PKCE verification failed"
            else -> null
        }
}

/** An access token Bank iD issued: what /userinfo answers for it. */
data class AccessGrant(
    val token: String,
    val clientId: String,
    val identity: BankIdIdentity,
    val transactionId: String,
    val issuedAt: Instant,
)

/** OAuth 2.0 error codes the token endpoint answers with (RFC 6749 §5.2): 401 for client auth, else 400. */
enum class OidcError(val code: String, val clientAuthentication: Boolean = false) {
    INVALID_REQUEST("invalid_request"),
    INVALID_CLIENT("invalid_client", clientAuthentication = true),
    INVALID_GRANT("invalid_grant"),
    UNSUPPORTED_GRANT_TYPE("unsupported_grant_type"),
}

/** An HS256-signed JWT (OIDC Core §10.1: a confidential client's id_token may be MACed with its secret). */
object HsJwt {
    private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    private const val HEADER = """{"alg":"HS256","typ":"JWT"}"""

    fun sign(payloadJson: String, secret: String): String {
        val signingInput = ENCODER.encodeToString(HEADER.toByteArray()) + "." +
            ENCODER.encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return signingInput + "." + ENCODER.encodeToString(mac.doFinal(signingInput.toByteArray(Charsets.US_ASCII)))
    }
}

/** Where /auth sends the browser back: the client's redirect_uri with the response parameters appended. */
object Redirects {
    fun isAcceptable(uri: String): Boolean {
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
        return parsed.isAbsolute && parsed.rawFragment == null && uri.length <= MAX_URI
    }

    fun withParams(redirectUri: String, params: List<Pair<String, String>>): String {
        val query = params.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, Charsets.UTF_8) }
        return redirectUri + (if (redirectUri.contains('?')) "&" else "?") + query
    }

    /** client_id and client_secret from an HTTP Basic header (RFC 6749 §2.3.1: each form-urlencoded). */
    fun basicCredentials(header: String?): Pair<String, String>? {
        if (header == null || !header.startsWith("Basic ", ignoreCase = true)) return null
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(BASIC.length).trim())) }
            .getOrNull() ?: return null
        if (!decoded.contains(':')) return null
        return URLDecoder.decode(decoded.substringBefore(':'), Charsets.UTF_8) to
            URLDecoder.decode(decoded.substringAfter(':'), Charsets.UTF_8)
    }

    private const val MAX_URI = 2048
    private const val BASIC = "Basic "
}

/** Unguessable codes, request ids and access tokens: 256 random bits, base64url. */
object OpaqueValues {
    private const val BYTES = 32
    private val RANDOM = SecureRandom()

    fun next(): String {
        val bytes = ByteArray(BYTES)
        RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
