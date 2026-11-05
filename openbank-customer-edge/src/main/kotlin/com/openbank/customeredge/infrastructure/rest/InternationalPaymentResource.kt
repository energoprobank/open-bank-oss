// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * International transfer (experience v2 step 8): a quote first — scheme, rate, fee, arrival date — then
 * the order. EUR to a SEPA country is a SEPA credit transfer; anything else is SWIFT. The order is
 * executed by the edge's own SEPA / SWIFT routes ([CustomerEdgeResource.createSepaPayment],
 * [CustomerEdgeResource.createSwift]), so ownership, the SCA gate (amount, currency and creditor of the
 * transfer) and the upstream contracts stay in one place; this resource adds the quote and the face check.
 *
 * Quotes are held in this process for [QUOTE_TTL] and bound to the party and account that asked. A
 * deployment with several edge replicas needs them in the shared idempotency store instead.
 */
@Path("/customer/v1/payments/international")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class InternationalPaymentResource(
    private val edge: ExperienceEdge,
    private val parties: CustomerPartyResolver,
    private val gate: LivenessGate,
) {
    @Inject
    lateinit var payments: CustomerEdgeResource

    @ConfigProperty(name = "openbank.edge.account-service-url")
    lateinit var accountServiceUrl: String

    @ConfigProperty(name = "openbank.edge.fx-service-url")
    lateinit var fxServiceUrl: String

    private data class Quote(
        val id: String,
        val party: UUID,
        val accountId: UUID,
        val scheme: String,
        val credit: BigDecimal,
        val creditCurrency: String,
        val debit: BigDecimal,
        val debitCurrency: String,
        val fee: BigDecimal,
        val rate: BigDecimal?,
        val country: String,
        val arrival: LocalDate,
        val expiresAt: Instant,
    )

    private val quotes = ConcurrentHashMap<String, Quote>()

    // Validation-heavy like the other payment routes (cf. createDomesticPayment): each check is one early return.
    @Suppress("CyclomaticComplexMethod")
    @GET
    @Path("/quote")
    @Authorize(action = "customer.payments.initiate")
    @Blocking
    fun quote(
        @QueryParam("account_id") accountId: String?,
        @QueryParam("currency") currency: String?,
        @QueryParam("amount") amount: String?,
        @QueryParam("country") country: String?,
    ): Response {
        val party = parties.resolve(null)
        val account = accountId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return invalid("account_id is required")
        val ccy =
            currency?.trim()?.uppercase()?.takeIf { CURRENCY.matches(it) }
                ?: return invalid("currency must be an ISO 4217 code")
        val cc =
            country?.trim()?.uppercase()?.takeIf { COUNTRY.matches(it) }
                ?: return invalid("country must be an ISO 3166 code")
        val credit =
            amount?.toBigDecimalOrNull()?.takeIf {
                it.signum() > 0 && it <= MAX_AMOUNT
            }?.setScale(2, RoundingMode.HALF_UP)
                ?: return invalid("amount must be a positive number")
        val accountJson = edge.upstream.get(
            "${accountServiceUrl.trimEnd('/')}/api/v1/accounts/$account",
            party.toString(),
        )
        val node = EdgeJson.parse(accountJson)
        if (accountJson.status !in ExperienceEdge.SUCCESS ||
            node == null ||
            CustomerEdgeResource.extractOwnerPartyId(accountJson.entity.toString()) != party.toString()
        ) {
            return edge.error(ExperienceEdge.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No such account")
        }
        val accountCurrency =
            node.text("currency")
                ?: return edge.error(ExperienceEdge.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "account")
        val scheme = if (ccy == "EUR" && cc in SEPA_COUNTRIES) "SEPA" else "SWIFT"
        val rate = if (accountCurrency == ccy) {
            null
        } else {
            rate(accountCurrency, ccy) ?: return edge.error(
                ExperienceEdge.SERVICE_UNAVAILABLE,
                "FX_UNAVAILABLE",
                "Exchange rates are not available right now.",
            )
        }
        val fee = fee(scheme, accountCurrency)
        val principal = if (rate == null) credit else credit.divide(rate, 2, RoundingMode.HALF_UP)
        val quote = Quote(
            id = UUID.randomUUID().toString(), party = party, accountId = account, scheme = scheme,
            credit = credit, creditCurrency = ccy, debit = principal + fee, debitCurrency = accountCurrency, fee = fee,
            rate = rate, country = cc, arrival = arrival(scheme), expiresAt = Instant.now().plus(QUOTE_TTL),
        )
        quotes.entries.removeIf { it.value.expiresAt.isBefore(Instant.now()) }
        quotes[quote.id] = quote
        return EdgeJson.ok(view(quote))
    }

    // Validation-heavy like the other payment routes (cf. createDomesticPayment): each check is one early return.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    @POST
    @Authorize(action = "customer.payments.initiate")
    @Blocking
    fun submit(
        body: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam("X-SCA-Challenge-Id") scaChallengeId: String?,
        @HeaderParam(LivenessGate.HEADER) livenessSessionId: String?,
    ): Response {
        val party = parties.resolve(null)
        val input = edge.objectBody(body) ?: return invalid("body must be a JSON object")
        val quote = input.text("quote_id")?.let(quotes::get)
            ?.takeIf {
                it.party == party &&
                    it.accountId.toString() == input.text("account_id") &&
                    it.expiresAt.isAfter(Instant.now())
            }
            ?: return edge.error(HTTP_CONFLICT, "QUOTE_EXPIRED", "The quote has expired. Please get a new one.")
        val creditor =
            input.text("creditor_account")?.filterNot(Char::isWhitespace)?.uppercase()?.takeIf {
                it.length in
                    MIN_ACCOUNT..MAX_ACCOUNT
            }
                ?: return invalid("creditor_account is required")
        val name = input.text("creditor_name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
            ?: return invalid("creditor_name is required")
        val bic = input.text("creditor_bic")?.filterNot(Char::isWhitespace)?.uppercase()
        if (quote.scheme == "SWIFT" &&
            (bic == null || !BIC.matches(bic))
        ) {
            return invalid("creditor_bic is required for a SWIFT transfer")
        }
        gate.check(party, quote.debit, quote.debitCurrency, livenessSessionId)?.let { return it }
        val reference = input.text("remittance_information")?.take(MAX_REFERENCE)
        val response = if (quote.scheme == "SEPA") {
            payments.createSepaPayment(
                edge.json(
                    mapOf(
                        "debtorAccountId" to quote.accountId.toString(),
                        "amount" to quote.credit.toPlainString(),
                        "currency" to quote.creditCurrency,
                        "creditorIban" to creditor,
                        "creditorName" to name,
                        "creditorBic" to bic,
                        "reference" to reference,
                    ),
                ),
                idempotencyKey,
                scaChallengeId,
            )
        } else {
            payments.createSwift(
                edge.json(
                    mapOf(
                        "debtorAccountId" to quote.accountId.toString(),
                        "amount" to quote.credit.toPlainString(),
                        "currency" to quote.creditCurrency,
                        "creditorIban" to creditor,
                        "creditorName" to name,
                        "bic" to bic,
                        "reference" to reference,
                    ),
                ),
                idempotencyKey,
                scaChallengeId,
            )
        }
        if (response.status !in ExperienceEdge.SUCCESS) return response
        quotes.remove(quote.id)
        val created = EdgeJson.parse(response)
        val status = created?.text("status")?.uppercase()
        return EdgeJson.ok(
            mapOf(
                "payment_id" to (created?.text("id") ?: created?.text("paymentId") ?: quote.id),
                "scheme" to quote.scheme,
                "status" to when (status) {
                    "REJECTED", "FAILED", "RJCT" -> "REJECTED"
                    "ACCEPTED", "COMPLETED", "SETTLED", "ACSC", "ACCP" -> "ACCEPTED"
                    else -> "PENDING"
                },
            ),
            HTTP_CREATED,
        )
    }

    private fun rate(from: String, to: String): BigDecimal? {
        val response = edge.upstream.get("${fxServiceUrl.trimEnd('/')}/api/v1/fx/rates/$from/$to")
        val node = EdgeJson.parse(response)?.takeIf { response.status in ExperienceEdge.SUCCESS } ?: return null
        val bid = node.path("bidRate").takeIf { it.isNumber }?.decimalValue()
        val ask = node.path("askRate").takeIf { it.isNumber }?.decimalValue()
        val mid = node.path("midRate").takeIf { it.isNumber }?.decimalValue()
        return (
            mid
                ?: if (bid != null &&
                    ask != null
                ) {
                    (bid + ask).divide(BigDecimal(2), RATE_SCALE, RoundingMode.HALF_UP)
                } else {
                    null
                }
            )
            ?.takeIf { it.signum() > 0 }
    }

    /** Price list: SEPA is free; SWIFT costs the same everywhere, in the account's currency. */
    private fun fee(scheme: String, accountCurrency: String): BigDecimal = when {
        scheme == "SEPA" -> BigDecimal.ZERO.setScale(2)
        accountCurrency == "CZK" -> BigDecimal("350.00")
        else -> BigDecimal("15.00")
    }

    /** SEPA the next business day, SWIFT two business days later (Prague calendar, weekends only). */
    private fun arrival(scheme: String): LocalDate {
        var day = LocalDate.now(PRAGUE)
        repeat(if (scheme == "SEPA") 1 else 2) {
            do {
                day = day.plusDays(1)
            } while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY)
        }
        return day
    }

    private fun view(q: Quote) = mapOf(
        "quote_id" to q.id,
        "scheme" to q.scheme,
        "debit" to mapOf("amount" to q.debit, "currency" to q.debitCurrency),
        "credit" to mapOf("amount" to q.credit, "currency" to q.creditCurrency),
        "fee" to mapOf("amount" to q.fee, "currency" to q.debitCurrency),
        "rate" to q.rate,
        "arrival_date" to q.arrival.toString(),
        "expires_at" to q.expiresAt.toString(),
    )

    private fun invalid(message: String) = edge.error(ExperienceEdge.BAD_REQUEST, "INVALID_REQUEST", message)

    private companion object {
        val QUOTE_TTL: Duration = Duration.ofMinutes(10)
        val PRAGUE: ZoneId = ZoneId.of("Europe/Prague")
        val CURRENCY = Regex("^[A-Z]{3}$")
        val COUNTRY = Regex("^[A-Z]{2}$")
        val BIC = Regex("^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$")
        val MAX_AMOUNT = BigDecimal("1000000")
        const val RATE_SCALE = 6
        const val MIN_ACCOUNT = 8
        const val MAX_ACCOUNT = 34
        const val MAX_NAME = 140
        const val MAX_REFERENCE = 140
        const val HTTP_CONFLICT = 409
        const val HTTP_CREATED = 201

        /** EEA + SEPA members (ISO 3166-1 alpha-2). */
        val SEPA_COUNTRIES = setOf(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
            "DE", "GR", "HU", "IS", "IE", "IT", "LV", "LI", "LT", "LU",
            "MT", "NL", "NO", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "CH", "GB", "MC", "SM", "VA", "AD",
        )
    }
}
