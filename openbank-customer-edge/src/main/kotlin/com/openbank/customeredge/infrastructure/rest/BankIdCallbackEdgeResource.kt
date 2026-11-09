// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import io.smallrye.common.annotation.Blocking
import io.vertx.core.http.HttpServerRequest
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * Bank iD's redirect target (experience v2 step 9). The applicant's browser lands here with the
 * authorization code; there is no customer token on a redirect, so the OIDC `state` (single-use,
 * bound to one verification) is what ties it to the application. onboarding-service exchanges the
 * code server-side with the PKCE verifier. The page tells the applicant to return to the app; the
 * app's web view closes on this URL and asks for the result.
 */
@Path("/customer/v1/onboarding/bank-id/callback")
class BankIdCallbackEdgeResource(private val edge: ExperienceEdge) {
    @Context
    var request: HttpServerRequest? = null

    @GET
    @PermitAll
    @Blocking
    @Produces(MediaType.TEXT_HTML)
    fun callback(
        @QueryParam("code") code: String?,
        @QueryParam("state") state: String?,
        @QueryParam("error") error: String?,
    ): Response {
        val client = request?.remoteAddress()?.hostAddress()
        if (!edge.allow("bank-id-callback", client)) return page(false, "Too many attempts — try again shortly.")
        if (state.isNullOrBlank() || state.length > MAX_PARAM || (code?.length ?: 0) > MAX_PARAM) {
            return page(false, "This Bank iD answer is not valid.")
        }
        val response = edge.upstream.postAnonymous(
            edge.onboarding("/api/v1/onboarding/bank-id/callback"),
            edge.json(mapOf("code" to code, "state" to state, "error" to error?.take(MAX_PARAM))),
        )
        val status = EdgeJson.parse(response)?.text("status")
        return page(
            status == "VERIFIED",
            if (status ==
                "VERIFIED"
            ) {
                null
            } else {
                "Bank iD could not confirm your identity."
            },
        )
    }

    private fun page(ok: Boolean, problem: String?): Response {
        val title = if (ok) "Hotovo · Done" else "Nepodařilo se · Not completed"
        val text = if (ok) {
            "Vaše identita je ověřena. Vraťte se do aplikace.<br>Your identity is confirmed. Return to the app."
        } else {
            "Ověření přes Bank iD se nepodařilo. Vraťte se do aplikace.<br>${problem.orEmpty()}"
        }
        val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            |<title>$title</title></head><body style="font-family:-apple-system,sans-serif;padding:24px">
            |<h2 id="bankid-result">$title</h2><p>$text</p></body></html>
        """.trimMargin()
        return Response.ok(html, MediaType.TEXT_HTML).build()
    }

    private companion object {
        const val MAX_PARAM = 512
    }
}
