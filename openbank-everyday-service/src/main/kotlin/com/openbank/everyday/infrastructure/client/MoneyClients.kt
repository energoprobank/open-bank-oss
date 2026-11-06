// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.everyday.application.AccountsPort
import com.openbank.everyday.application.AcquirerPort
import com.openbank.everyday.application.AcquirerSession
import com.openbank.everyday.application.TransactionsPort
import com.openbank.everyday.domain.AccountRef
import com.openbank.everyday.domain.Txn
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import jakarta.ws.rs.Produces as JaxrsProduces

// ---------------------------------------------------------------- account-service

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountDto(
    val id: UUID,
    val accountNumber: String,
    val currencyCode: String,
    val accountType: String,
    val status: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Pagination(val nextCursor: String? = null, val hasNextPage: Boolean = false)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountPage(val data: List<AccountDto> = emptyList(), val pagination: Pagination? = null)

@RegisterRestClient(configKey = "account-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/accounts")
@JaxrsProduces(MediaType.APPLICATION_JSON)
interface AccountClient {
    @GET
    fun list(@QueryParam("partyId") partyId: UUID, @QueryParam("limit") limit: Int): AccountPage
}

@ApplicationScoped
class AccountsAdapter(@RestClient private val client: AccountClient) : AccountsPort {
    override fun accounts(partyId: UUID): List<AccountRef> = client.list(partyId, PAGE).data.map {
        AccountRef(it.id, it.accountNumber, it.currencyCode, it.accountType, it.status)
    }

    private companion object {
        const val PAGE = 50
    }
}

// ---------------------------------------------------------------- transaction-service

@JsonIgnoreProperties(ignoreUnknown = true)
data class TransactionDto(
    val id: UUID,
    val type: String,
    val sourceAccountId: UUID? = null,
    val targetAccountId: UUID? = null,
    val amount: BigDecimal,
    val currencyCode: String,
    val status: String,
    val description: String? = null,
    val bookingDate: String,
    val rail: String? = null,
    val instructionType: String? = null,
    val category: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TransactionPage(val data: List<TransactionDto> = emptyList(), val pagination: Pagination? = null)

data class CreditRequest(
    val idempotencyKey: String,
    val type: String,
    val targetAccountId: UUID,
    val amount: BigDecimal,
    val currencyCode: String,
    val description: String,
    val valueDate: String,
    val initiatedByPartyId: UUID,
    val rail: String,
)

@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/transactions")
@JaxrsProduces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface TransactionClient {
    @GET
    fun list(
        @QueryParam("accountId") accountId: UUID,
        @QueryParam("limit") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): TransactionPage

    @POST
    fun create(request: CreditRequest, @HeaderParam("Idempotency-Key") idempotencyKey: String): TransactionDto
}

@ApplicationScoped
class TransactionsAdapter(@RestClient private val client: TransactionClient) : TransactionsPort {
    override fun payments(account: AccountRef, since: LocalDate, own: Set<UUID>): List<Txn> {
        val out = mutableListOf<Txn>()
        var cursor: String? = null
        var pages = 0
        while (pages++ < MAX_PAGES) {
            val p = client.list(account.id, PAGE, cursor)
            val rows = p.data.filter { it.status == "COMPLETED" }.map { it.toTxn(account, own) }
            out += rows.filter { !it.date.isBefore(since) }
            val more = p.pagination
            cursor = more?.nextCursor
            // Newest first: once a page reaches back past `since`, the rest is older still.
            if (cursor == null || more?.hasNextPage != true || rows.any { it.date.isBefore(since) }) break
        }
        return out
    }

    override fun credit(
        accountId: UUID,
        amount: BigDecimal,
        currency: String,
        description: String,
        idempotencyKey: String,
        party: UUID,
    ): UUID = client.create(
        CreditRequest(
            idempotencyKey = idempotencyKey,
            type = "CREDIT",
            targetAccountId = accountId,
            amount = amount,
            currencyCode = currency,
            description = description,
            valueDate = LocalDate.now().toString(),
            initiatedByPartyId = party,
            rail = "CARD",
        ),
        idempotencyKey,
    ).id

    private fun TransactionDto.toTxn(account: AccountRef, own: Set<UUID>): Txn {
        val outgoing = sourceAccountId == account.id
        val other = if (outgoing) targetAccountId else sourceAccountId
        return Txn(
            id, account.id, outgoing, amount, currencyCode, description ?: "", LocalDate.parse(bookingDate.take(DATE)),
            category, rail, instructionType, other != null && other in own,
        )
    }

    private companion object {
        const val PAGE = 100
        const val MAX_PAGES = 30
        const val DATE = 10
    }
}

// ---------------------------------------------------------------- card acquirer (provider simulator)

data class CheckoutRequest(
    val amount: BigDecimal,
    val currency: String,
    @JsonProperty("merchant_reference") val merchantReference: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CheckoutDto(val id: String, @JsonProperty("checkout_url") val checkoutUrl: String, val status: String)

@RegisterRestClient(configKey = "acquirer")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/acquirer/checkouts")
@JaxrsProduces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface AcquirerClient {
    @POST
    fun start(request: CheckoutRequest): CheckoutDto

    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): CheckoutDto
}

@ApplicationScoped
class AcquirerAdapter(@RestClient private val client: AcquirerClient) : AcquirerPort {
    override fun start(amount: BigDecimal, currency: String, reference: String): AcquirerSession =
        client.start(CheckoutRequest(amount, currency, reference)).let {
            AcquirerSession(it.id, it.checkoutUrl, it.status)
        }

    override fun status(acquirerRef: String): String = client.get(acquirerRef).status
}
