// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.providersimulator.application.OtherBankDesk
import com.openbank.providersimulator.domain.IncomingPayment
import com.openbank.providersimulator.domain.OtherBankOrder
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal

/**
 * "The customer's other bank" (simulated). Sending is anonymous: it stands for the customer's own
 * internet banking at another bank, which never holds a token of ours. Reading what arrived is the
 * receiving bank's view of its collection account, so it takes the bank's M2M token.
 */
@Path("/api/v1/other-bank")
@Tag(name = "Other bank (simulated)")
@Produces(MediaType.APPLICATION_JSON)
class OtherBankResource {
    @Inject
    lateinit var desk: OtherBankDesk

    @POST
    @Path("/payments")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Send a payment from the customer's account at another bank")
    fun send(body: OtherBankPaymentRequest?): Response {
        val r = requireNotNull(body) { "request body is required" }
        val payment = desk.send(
            OtherBankOrder(
                payerName = requireNotNull(r.payerName) { "payer_name is required" },
                payerAccount = requireNotNull(r.payerAccount) { "payer_account is required" },
                payerBankCode = requireNotNull(r.payerBankCode) { "payer_bank_code is required" },
                amount = requireNotNull(r.amount) { "amount is required" },
                currency = requireNotNull(r.currency) { "currency is required" },
                creditorAccount = requireNotNull(r.creditorAccount) { "creditor_account is required" },
                creditorBankCode = r.creditorBankCode,
                variableSymbol = r.variableSymbol,
                nationalId = r.nationalId,
            ),
        )
        return Response.status(Response.Status.CREATED).entity(mapOf("id" to payment.id, "status" to "SENT")).build()
    }

    @GET
    @Path("/incoming")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Operation(summary = "Payments that reached a creditor account (optionally with a variable symbol), oldest first")
    fun incoming(
        @QueryParam("creditor_account") creditorAccount: String?,
        @QueryParam("variable_symbol") variableSymbol: String?,
    ): List<IncomingPaymentView> = desk.incoming(
        requireNotNull(creditorAccount) { "query parameter 'creditor_account' is required" },
        variableSymbol,
    ).map(IncomingPaymentView::of)
}

data class OtherBankPaymentRequest(
    @JsonProperty("payer_name") val payerName: String? = null,
    @JsonProperty("payer_account") val payerAccount: String? = null,
    @JsonProperty("payer_bank_code") val payerBankCode: String? = null,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    @JsonProperty("creditor_account") val creditorAccount: String? = null,
    @JsonProperty("creditor_bank_code") val creditorBankCode: String? = null,
    @JsonProperty("variable_symbol") val variableSymbol: String? = null,
    @JsonProperty("national_id") val nationalId: String? = null,
)

data class IncomingPaymentView(
    val id: String,
    @JsonProperty("payer_name") val payerName: String,
    @JsonProperty("payer_account") val payerAccount: String,
    @JsonProperty("payer_bank_code") val payerBankCode: String,
    @JsonProperty("payer_bank_name") val payerBankName: String,
    val amount: String,
    val currency: String,
    @JsonProperty("received_at") val receivedAt: String,
) {
    companion object {
        fun of(p: IncomingPayment) = IncomingPaymentView(
            p.id,
            p.payerName,
            p.payerAccount,
            p.payerBankCode,
            p.payerBankName,
            p.amount.toPlainString(),
            p.currency,
            p.receivedAt.toString(),
        )
    }
}
