// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.infrastructure.rest

import com.openbank.providersimulator.application.AuthorizeOutcome
import com.openbank.providersimulator.application.AuthorizeParams
import com.openbank.providersimulator.application.BankIdOidcDesk
import com.openbank.providersimulator.application.TokenOutcome
import com.openbank.providersimulator.application.TokenParams
import com.openbank.providersimulator.domain.Redirects
import jakarta.annotation.security.PermitAll
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.net.URI

/**
 * Czech Bank iD's OpenID Connect endpoints (NON-PRODUCTION). Anonymous by design: the person's
 * browser opens /auth and posts the consent form; the bank authenticates at /token with its client
 * secret and at /userinfo with the access token it got there — never with a token of this platform.
 */
@Path("/bankid")
@Tag(name = "Bank iD OIDC (simulated)")
@PermitAll
class BankIdOidcResource {
    @Inject
    lateinit var desk: BankIdOidcDesk

    @GET
    @Path("/.well-known/openid-configuration")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "OpenID Provider metadata (issuer <public-url>/bankid)")
    fun configuration(): Map<String, Any> = desk.discovery()

    @GET
    @Path("/.well-known/jwks.json")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "No public keys: id_tokens are HS256, MACed with the client's secret")
    fun jwks(): Map<String, Any> = mapOf("keys" to emptyList<Any>())

    @GET
    @Path("/auth")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "The consent page: pick your bank, confirm (authorization code + PKCE S256)")
    @Suppress("LongParameterList")
    fun auth(
        @QueryParam("response_type") responseType: String?,
        @QueryParam("client_id") clientId: String?,
        @QueryParam("redirect_uri") redirectUri: String?,
        @QueryParam("scope") scope: String?,
        @QueryParam("state") state: String?,
        @QueryParam("nonce") nonce: String?,
        @QueryParam("code_challenge") codeChallenge: String?,
        @QueryParam("code_challenge_method") codeChallengeMethod: String?,
    ): Response {
        val params = AuthorizeParams(
            responseType,
            clientId,
            redirectUri,
            scope,
            state,
            nonce,
            codeChallenge,
            codeChallengeMethod,
        )
        return when (val outcome = desk.authorize(params)) {
            is AuthorizeOutcome.Consent ->
                html(Response.Status.OK, BankIdPages.consent(outcome.request, outcome.signedInAs))
            is AuthorizeOutcome.Refused -> html(Response.Status.BAD_REQUEST, BankIdPages.error(outcome.reason))
        }
    }

    @POST
    @Path("/auth/confirm")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "The consent form: 302 to redirect_uri with code and state (or error=access_denied)")
    fun confirm(@FormParam("request_id") requestId: String?, @FormParam("action") action: String?): Response {
        val target = requestId?.let { desk.decide(it, confirmed = action != "cancel") }
            ?: return html(
                Response.Status.BAD_REQUEST,
                BankIdPages.error("this sign-in has expired or was already used", "../assets/bankid.css"),
            )
        return Response.status(Response.Status.FOUND).header(HttpHeaders.LOCATION, URI.create(target)).build()
    }

    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Redeem an authorization code (confidential client + PKCE verifier)")
    @Suppress("LongParameterList")
    fun token(
        @FormParam("grant_type") grantType: String?,
        @FormParam("code") code: String?,
        @FormParam("redirect_uri") redirectUri: String?,
        @FormParam("code_verifier") codeVerifier: String?,
        @FormParam("client_id") clientId: String?,
        @FormParam("client_secret") clientSecret: String?,
        @HeaderParam(HttpHeaders.AUTHORIZATION) authorization: String?,
    ): Response {
        val params = TokenParams(grantType, code, redirectUri, codeVerifier, clientId, clientSecret)
        val response = when (val outcome = desk.token(params, Redirects.basicCredentials(authorization))) {
            is TokenOutcome.Issued -> Response.ok(
                mapOf(
                    "access_token" to outcome.grant.token,
                    "token_type" to "Bearer",
                    "expires_in" to outcome.expiresIn,
                    "id_token" to outcome.idToken,
                ),
            )
            is TokenOutcome.Refused -> {
                val status = if (outcome.error.clientAuthentication) {
                    Response.Status.UNAUTHORIZED
                } else {
                    Response.Status.BAD_REQUEST
                }
                Response.status(status)
                    .entity(mapOf("error" to outcome.error.code, "error_description" to outcome.description))
            }
        }
        return response.header(HttpHeaders.CACHE_CONTROL, "no-store").build()
    }

    @GET
    @Path("/userinfo")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "The signed-in person's claims, for the access token in Authorization: Bearer")
    fun userinfo(@HeaderParam(HttpHeaders.AUTHORIZATION) authorization: String?): Response {
        val grant = desk.userinfo(authorization)
            ?: return Response.status(Response.Status.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"").build()
        val id = grant.identity
        return Response.ok(
            mapOf(
                "sub" to id.sub,
                "given_name" to id.givenName,
                "family_name" to id.familyName,
                "birthdate" to id.birthdate?.toString(),
                "gender" to id.sex?.oidc,
                "birthnumber" to id.birthNumber,
                "nationalities" to id.nationalities,
                "txn" to grant.transactionId,
            ),
        ).header(HttpHeaders.CACHE_CONTROL, "no-store").build()
    }

    @GET
    @Path("/assets/bankid.css")
    @Produces("text/css")
    fun stylesheet(): String = BankIdPages.STYLESHEET

    private fun html(status: Response.Status, body: String): Response =
        Response.status(status).type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).entity(body).build()
}
