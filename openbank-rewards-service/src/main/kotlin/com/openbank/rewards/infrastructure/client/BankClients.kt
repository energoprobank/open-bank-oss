// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.infrastructure.client

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.rewards.application.AccountRef
import com.openbank.rewards.application.BankDirectoryPort
import com.openbank.rewards.application.CardRef
import com.openbank.rewards.application.CashCreditPort
import com.openbank.rewards.application.DownstreamRejected
import com.openbank.rewards.application.DownstreamUnavailable
import com.openbank.rewards.application.GlPostingPort
import com.openbank.rewards.application.IncentivePort
import com.openbank.rewards.application.InterestPort
import com.openbank.rewards.application.LendingPort
import com.openbank.rewards.application.LoanRef
import com.openbank.rewards.application.RateTerms
import com.openbank.rewards.domain.Market
import com.openbank.rewards.domain.RewardsNotFoundException
import com.openbank.rewards.infrastructure.persistence.RewardsJson
import io.quarkus.oidc.client.OidcClient
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Outbound HTTP for the engine's commands and reads. Every call carries a client-credentials
 * token of the rewards client (`service-account-openbank-rewards`); each target service's OPA
 * extension admits that principal for exactly the action it needs. Status mapping: 2xx ok,
 * 4xx = [DownstreamRejected] (the saga compensates), 5xx/IO = [DownstreamUnavailable] (retried).
 */
@ApplicationScoped
class ServiceHttp(private val oidc: Instance<OidcClient>) {
    private val log = Logger.getLogger(ServiceHttp::class.java)
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_SECONDS)).build()

    @Volatile private var token: String? = null

    @Volatile private var tokenExpiry: Instant = Instant.EPOCH

    private fun bearer(): String {
        val now = Instant.now()
        token?.takeIf { now.isBefore(tokenExpiry) }?.let { return it }
        val tokens = oidc.get().tokens.await().atMost(Duration.ofSeconds(TOKEN_SECONDS))
        val expiresAt =
            tokens.accessTokenExpiresAt?.let { Instant.ofEpochSecond(it) } ?: now.plusSeconds(DEFAULT_TOKEN_SECONDS)
        token = tokens.accessToken
        tokenExpiry = expiresAt.minusSeconds(TOKEN_SKEW_SECONDS)
        return tokens.accessToken
    }

    @Suppress("CyclomaticComplexMethod", "ThrowsCount") // one rule table; splitting hides it
    fun call(method: String, url: String, body: Any? = null, idempotencyKey: String? = null): JsonNode? {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(REQUEST_SECONDS))
            .header("Authorization", "Bearer ${bearer()}").header("Accept", "application/json")
            .header("X-Operator-Id", "rewards-service")
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        val publisher =
            body?.let { HttpRequest.BodyPublishers.ofString(RewardsJson.write(it)) }
                ?: HttpRequest.BodyPublishers.noBody()
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, publisher)
        val response = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw DownstreamUnavailable("$method $url: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw DownstreamUnavailable("$method $url interrupted", e)
        }
        val json = response.body()?.takeIf {
            it.isNotBlank()
        }?.let { runCatching { RewardsJson.mapper.readTree(it) }.getOrNull() }
        return when (response.statusCode()) {
            in OK_RANGE -> json
            // The downstream's own message, never our internal URL: it reaches the app.
            HTTP_NOT_FOUND -> throw RewardsNotFoundException(
                json?.get("code")?.asText() ?: "NOT_FOUND",
                json?.get("message")?.asText() ?: "not found",
            )
            in CLIENT_ERRORS -> {
                log.warnf("%s %s -> %d %s", method, url, response.statusCode(), response.body()?.take(LOG_BODY))
                throw DownstreamRejected(
                    json?.get("code")?.asText() ?: json?.get("error")?.asText()?.takeIf { it.matches(CODE) }
                        ?: "DOWNSTREAM_REJECTED",
                    json?.get("message")?.asText() ?: json?.get("error")?.asText() ?: "HTTP ${response.statusCode()}",
                )
            }
            else -> throw DownstreamUnavailable("$method $url -> ${response.statusCode()}")
        }
    }

    companion object {
        const val CONNECT_SECONDS = 3L

        // Below the edge's upstream timeout: a slow saga answers PENDING (retried) instead of an edge 503.
        const val REQUEST_SECONDS = 6L
        const val TOKEN_SECONDS = 10L
        const val DEFAULT_TOKEN_SECONDS = 240L
        const val TOKEN_SKEW_SECONDS = 30L
        const val HTTP_NOT_FOUND = 404
        const val LOG_BODY = 300
        val OK_RANGE = 200..299
        val CLIENT_ERRORS = 400..499
        val CODE = Regex("[A-Z_]{3,64}")
    }
}

fun JsonNode.items(): List<JsonNode> = when {
    isArray -> toList()
    has("items") -> this["items"].toList()
    has("content") -> this["content"].toList()
    has("data") -> this["data"].toList()
    else -> emptyList()
}

private fun JsonNode.text(field: String): String? = this[field]?.takeUnless { it.isNull }?.asText()
private fun JsonNode.uuid(field: String): UUID? = text(field)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
private fun JsonNode.decimal(field: String): BigDecimal? =
    this[field]?.takeUnless { it.isNull }?.let { runCatching { BigDecimal(it.asText()) }.getOrNull() }

@ApplicationScoped
class HttpBankDirectory(
    private val http: ServiceHttp,
    @ConfigProperty(name = "rewards.clients.account-url") private val accountUrl: String,
    @ConfigProperty(name = "rewards.clients.card-url") private val cardUrl: String,
) : BankDirectoryPort {
    override fun accountsOf(partyId: UUID): List<AccountRef> =
        http.call("GET", "$accountUrl/api/v1/accounts?partyId=$partyId&limit=100")?.items().orEmpty().mapNotNull { a ->
            AccountRef(
                accountId = a.uuid("id") ?: a.uuid("accountId") ?: return@mapNotNull null,
                partyId = a.uuid("partyId") ?: partyId,
                accountType = a.text("accountType") ?: return@mapNotNull null,
                currency = a.text("currencyCode") ?: a.text("currency") ?: "CZK",
                status = a.text("status"),
                openedAt = a.text("openedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }.filterNot { it.accountType.startsWith("GL_") }

    override fun cardsOf(partyId: UUID): List<CardRef> =
        http.call("GET", "$cardUrl/api/v1/cards/party/$partyId")?.items().orEmpty().mapNotNull { c ->
            CardRef(c.uuid("id") ?: return@mapNotNull null, partyId, c.uuid("accountId"), c.text("status"))
        }
}

@ApplicationScoped
class HttpInterest(
    private val http: ServiceHttp,
    @ConfigProperty(name = "rewards.clients.interest-url") private val url: String,
) : InterestPort {
    override fun addRateAdjustment(
        accountId: UUID,
        deltaBps: Int,
        validFrom: LocalDate,
        validTo: LocalDate,
        sourceRef: String,
        approvalRef: String,
    ): String {
        val body = mapOf(
            "deltaBps" to deltaBps,
            "validFrom" to validFrom,
            "validTo" to validTo,
            "source" to "REWARDS",
            "sourceRef" to sourceRef,
            "approvalRef" to approvalRef,
        )
        val r = http.call("POST", "$url/api/v1/interest/accounts/$accountId/rate-adjustments", body, sourceRef)
        return r?.text("adjustmentId") ?: sourceRef
    }

    override fun cancelRateAdjustment(accountId: UUID, adjustmentId: String) {
        http.call("DELETE", "$url/api/v1/interest/accounts/$accountId/rate-adjustments/$adjustmentId")
    }
}

@ApplicationScoped
class HttpLending(
    private val http: ServiceHttp,
    @ConfigProperty(name = "rewards.clients.lending-url") private val url: String,
) : LendingPort {
    override fun loan(partyId: UUID, loanId: UUID): LoanRef {
        val l = http.call("GET", "$url/api/v1/lending/loans/overview?partyId=$partyId")?.items().orEmpty()
            .firstOrNull { it.uuid("loanId") == loanId }
            ?: throw DownstreamRejected("TARGET_INVALID", "choose one of your loans")
        val next = l["nextInstalment"]
        val money = next?.get("amount")
        return LoanRef(
            loanId = loanId,
            partyId = partyId,
            currency = money?.text("currency") ?: l["outstanding"]?.text("currency") ?: "CZK",
            nextInstalment = money?.decimal("amount"),
            nextDueDate = next?.text("dueDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            daysPastDue = l["daysPastDue"]?.asInt() ?: 0,
            status = l.text("status"),
        )
    }

    override fun preview(loanId: UUID, deltaBps: Int, months: Int): Pair<RateTerms, RateTerms> {
        val r = http.call("GET", "$url/api/v1/loans/$loanId/rate-adjustments/preview?deltaBps=$deltaBps&months=$months")
            ?: throw DownstreamUnavailable("empty preview")
        if (r["eligible"]?.asBoolean() ==
            false
        ) {
            throw DownstreamRejected("CREDIT_COUPON_BLOCKED", r.text("reason") ?: "not eligible")
        }
        return terms(r["before"]) to terms(r["after"])
    }

    private fun terms(t: JsonNode?) = RateTerms(
        nominalRatePct = t?.decimal("nominalRatePct"),
        aprcPct = t?.decimal("aprcPct"),
        instalment = t?.get("instalment")?.let {
            it.decimal("amount")
                ?: runCatching { BigDecimal(it.asText()) }.getOrNull()
        },
        currency = t?.get("instalment")?.text("currency"),
    )

    override fun addRateAdjustment(
        loanId: UUID,
        deltaBps: Int,
        validFrom: LocalDate,
        validTo: LocalDate,
        sourceRef: String,
        approvalRef: String,
    ): String {
        val body = mapOf(
            "deltaBps" to deltaBps,
            "validFrom" to validFrom,
            "validTo" to validTo,
            "source" to "REWARDS",
            "sourceRef" to sourceRef,
            "approvalRef" to approvalRef,
        )
        return http.call("POST", "$url/api/v1/loans/$loanId/rate-adjustments", body, sourceRef)?.text("adjustmentId")
            ?: sourceRef
    }
}

@ApplicationScoped
class HttpCashCredit(
    private val http: ServiceHttp,
    @ConfigProperty(name = "rewards.clients.cash-credit-url") private val url: String,
) : CashCreditPort {
    override fun credit(
        partyId: UUID,
        accountId: UUID,
        amount: BigDecimal,
        currency: String,
        reason: String,
        sourceRef: String,
    ): String {
        val body = mapOf(
            "partyId" to partyId,
            "accountId" to accountId,
            "amount" to amount,
            "currency" to currency,
            "reason" to reason,
            "sourceRef" to sourceRef,
        )
        val r = http.call("POST", url, body, sourceRef)
        return r?.text("transactionId") ?: r?.text("creditId") ?: sourceRef
    }
}

@ApplicationScoped
class HttpGlPosting(
    private val http: ServiceHttp,
    @ConfigProperty(name = "rewards.clients.liability-posting-url") private val url: String,
) : GlPostingPort {
    override fun postLiabilityDelta(
        date: LocalDate,
        market: Market,
        currency: String,
        delta: BigDecimal,
        sourceRef: String,
    ): String {
        val body = mapOf(
            "postingDate" to date,
            "market" to market.name,
            "currency" to currency,
            "deltaAmount" to delta,
            "sourceRef" to sourceRef,
        )
        val r = http.call("POST", url, body, sourceRef)
        return r?.text("journalId") ?: r?.text("journalEntryId") ?: r?.text("entryNumber") ?: r?.text("id") ?: sourceRef
    }
}

@ApplicationScoped
class HttpIncentive(
    private val http: ServiceHttp,
    @ConfigProperty(name = "rewards.clients.incentive-url") private val url: String,
) : IncentivePort {
    override fun redeem(partyId: UUID, code: String, context: String): UUID {
        val r = http.call(
            "POST",
            "$url/api/v1/incentives/redemptions",
            mapOf(
                "partyId" to partyId,
                "code" to code,
                "context" to context,
            ),
            "redeem:$partyId:$code",
        )
        return r?.uuid("offerId") ?: throw DownstreamRejected("CODE_UNKNOWN", "the code was not recognised")
    }
}
