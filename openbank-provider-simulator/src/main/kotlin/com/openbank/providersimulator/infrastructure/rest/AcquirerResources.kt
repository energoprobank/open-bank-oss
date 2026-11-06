// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.providersimulator.application.AcquirerDesk
import com.openbank.providersimulator.application.Checkout
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal

/** The acquirer's merchant API: the bank opens a checkout and asks for its outcome. */
@Path("/api/v1/acquirer/checkouts")
@Tag(name = "Card acquirer (simulated)")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class AcquirerResource {
    @Inject
    lateinit var desk: AcquirerDesk

    @POST
    @Operation(summary = "Open a checkout; the customer pays on checkout_url")
    fun start(body: CheckoutRequest?): Response {
        val r = requireNotNull(body) { "request body is required" }
        val c = desk.start(
            requireNotNull(r.amount) { "amount is required" },
            requireNotNull(r.currency) { "currency is required" },
            requireNotNull(r.merchantReference) { "merchant_reference is required" },
        )
        return Response.status(Response.Status.CREATED).entity(CheckoutView.of(c, desk)).build()
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "A checkout: CREATED, AUTHORISED, DECLINED or EXPIRED")
    fun get(@PathParam("id") id: String): CheckoutView =
        CheckoutView.of(desk.checkout(id) ?: throw NotFoundException(), desk)
}

/**
 * The acquirer's own card page, opened by the customer's browser (no bank token: the checkout id is
 * the capability). The bank's app never sees the card number.
 */
@Path("/acquirer/pay/{id}")
@PermitAll
class AcquirerCheckoutPage {
    @Inject
    lateinit var desk: AcquirerDesk

    @GET
    @Produces(MediaType.TEXT_HTML)
    fun page(@PathParam("id") id: String): String = render(desk.checkout(id) ?: throw NotFoundException())

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun pay(@PathParam("id") id: String, @FormParam("card") card: String?): String =
        render(desk.pay(id, card ?: "") ?: throw NotFoundException())

    private fun render(c: Checkout): String {
        val amount = String.format(java.util.Locale.ROOT, "%,.2f", c.amount).replace(',', ' ') + " " + c.currency
        val body = when (c.status) {
            "CREATED" ->
                """
                <p class="amount">$amount</p>
                <form method="post">
                  <label>Card number<input name="card" inputmode="numeric" autocomplete="cc-number" value="4242 4242 4242 4242"></label>
                  <div class="row"><label>Expiry<input value="12/29"></label><label>CVC<input value="123"></label></div>
                  <button type="submit">Pay $amount</button>
                </form>
                <p class="hint">Sandbox: 4000 0000 0000 0002 is declined. 3-D Secure is approved automatically.</p>"""
            "AUTHORISED" ->
                """
                <p class="ok">✓ Payment authorised</p><p>$amount from the card ending ${c.cardLast4}.</p>
                <a class="button" href="com.energopro.bankdemo://top-up">Back to Energo-Pro Bank</a>"""
            "DECLINED" ->
                """<p class="err">The card was declined.</p>
                <a class="button" href="com.energopro.bankdemo://top-up">Back to Energo-Pro Bank</a>"""
            else -> """<p class="err">This payment page has expired.</p>"""
        }
        return """<!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1"><title>Card payment</title>
            <style>body{font-family:-apple-system,system-ui,sans-serif;margin:0;padding:24px;background:#f4f6fa;color:#111}
            .card{max-width:420px;margin:0 auto;background:#fff;border-radius:16px;padding:24px;box-shadow:0 2px 12px #0001}
            h1{font-size:18px;margin:0 0 4px}.sub{color:#667;font-size:13px;margin:0 0 16px}.amount{font-size:28px;font-weight:600}
            label{display:block;font-size:13px;color:#445;margin:12px 0 4px}input{width:100%;box-sizing:border-box;font-size:17px;
            padding:12px;border:1px solid #ccd;border-radius:10px}.row{display:flex;gap:12px}.row label{flex:1}
            button,.button{display:block;width:100%;margin-top:20px;padding:14px;border:0;border-radius:12px;background:#1f5fbf;
            color:#fff;font-size:17px;text-align:center;text-decoration:none}.hint{font-size:12px;color:#889}
            .ok{color:#0a7d3e;font-size:20px;font-weight:600}.err{color:#b3261e;font-size:18px}</style></head>
            <body><div class="card"><h1>Secure card payment</h1><p class="sub">Sandbox acquirer · Energo-Pro Bank top-up</p>
            $body</div></body></html>"""
    }
}

data class CheckoutRequest(
    val amount: BigDecimal? = null,
    val currency: String? = null,
    @JsonProperty("merchant_reference") val merchantReference: String? = null,
)

data class CheckoutView(
    val id: String,
    val amount: BigDecimal,
    val currency: String,
    @JsonProperty("merchant_reference") val merchantReference: String,
    val status: String,
    @JsonProperty("checkout_url") val checkoutUrl: String,
    @JsonProperty("card_last4") val cardLast4: String?,
) {
    companion object {
        fun of(c: Checkout, desk: AcquirerDesk) =
            CheckoutView(c.id, c.amount, c.currency, c.merchantReference, c.status, desk.pageUrl(c), c.cardLast4)
    }
}
