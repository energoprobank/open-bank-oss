// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.everyday.application.EnergoProCustomer
import com.openbank.everyday.application.EnergoProPort
import com.openbank.everyday.application.PartyFacts
import com.openbank.everyday.application.PartyPort
import com.openbank.everyday.domain.Bill
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import jakarta.ws.rs.Produces as JaxrsProduces

private const val HTTP_NOT_FOUND = 404

/** The service's clock; tests can replace it. */
@Singleton
class ClockProducer {
    @Produces
    @Singleton
    fun clock(): Clock = Clock.systemUTC()
}

// ---------------------------------------------------------------- Energo-Pro (provider simulator locally)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EnergoProCustomerDto(
    @JsonProperty("customer_number") val customerNumber: String,
    val market: String,
    @JsonProperty("first_name") val firstName: String,
    @JsonProperty("last_name") val lastName: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EnergoProBillDto(
    val id: String,
    val period: String,
    val amount: BigDecimal,
    val currency: String,
    @JsonProperty("due_date") val dueDate: String,
    val status: String,
    @JsonProperty("collection_iban") val collectionIban: String,
    val reference: String,
)

data class PaymentNoticeDto(
    val reference: String,
    val amount: BigDecimal,
    val currency: String,
    @JsonProperty("bank_reference") val bankReference: String,
)

@RegisterRestClient(configKey = "energo-pro")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/energo-pro")
@JaxrsProduces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface EnergoProClient {
    @GET
    @Path("/customers/{number}")
    fun customer(@PathParam("number") number: String): EnergoProCustomerDto

    @GET
    @Path("/customers/{number}/bills")
    fun bills(@PathParam("number") number: String): List<EnergoProBillDto>

    @POST
    @Path("/payments")
    fun payment(notice: PaymentNoticeDto): EnergoProBillDto
}

@ApplicationScoped
class EnergoProAdapter(@RestClient private val client: EnergoProClient) : EnergoProPort {
    override fun customer(customerNumber: String): EnergoProCustomer? = notFoundAsNull {
        client.customer(customerNumber).let {
            EnergoProCustomer(it.customerNumber, it.market, it.firstName, it.lastName)
        }
    }

    override fun bills(customerNumber: String): List<Bill>? = notFoundAsNull {
        client.bills(customerNumber).map {
            Bill(
                it.id,
                it.period,
                it.amount,
                it.currency,
                LocalDate.parse(it.dueDate),
                it.status,
                it.collectionIban,
                it.reference,
            )
        }
    }

    override fun notifyPaid(reference: String, amount: BigDecimal, currency: String, bankReference: String) {
        client.payment(PaymentNoticeDto(reference, amount, currency, bankReference))
    }
}

// ---------------------------------------------------------------- party-service

@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyDto(val legalName: String, val market: String? = null)

@RegisterRestClient(configKey = "party-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/parties")
@JaxrsProduces(MediaType.APPLICATION_JSON)
interface PartyClient {
    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: UUID): PartyDto
}

@ApplicationScoped
class PartyAdapter(@RestClient private val client: PartyClient) : PartyPort {
    override fun party(partyId: UUID): PartyFacts? = notFoundAsNull {
        client.get(partyId).let { PartyFacts(partyId, it.legalName, (it.market ?: "CZ").uppercase()) }
    }
}

private fun <T> notFoundAsNull(call: () -> T): T? = try {
    call()
} catch (e: WebApplicationException) {
    if (e.response?.status == HTTP_NOT_FOUND) null else throw e
}
