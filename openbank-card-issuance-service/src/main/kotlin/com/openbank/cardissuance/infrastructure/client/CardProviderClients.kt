// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.cardissuance.application.port.out.CardFactoryPort
import com.openbank.cardissuance.application.port.out.WalletProvisioning
import com.openbank.cardissuance.application.port.out.WalletTokenServicePort
import com.openbank.cardissuance.domain.model.FactoryStatus
import com.openbank.cardissuance.domain.model.FactoryStatusEvent
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient

// The card manufacturer (plastic cards, courier) and the wallet's token service. Locally both are faces
// of openbank-provider-simulator; each has its own base URL, so a real vendor is a URL/adapter change.

data class FactoryOrderRequest(
    val reference: String,
    val design: String,
    @JsonProperty("name_on_card") val nameOnCard: String,
    val city: String,
    val country: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FactoryEventDto(val status: String = "", val at: String = "", val detail: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FactoryOrderDto(
    val id: String = "",
    val status: String = "",
    val carrier: String? = null,
    @JsonProperty("tracking_number") val trackingNumber: String? = null,
    @JsonProperty("expected_on") val expectedOn: String? = null,
    val events: List<FactoryEventDto> = emptyList(),
)

@RegisterRestClient(configKey = "card-factory")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/card-factory/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface CardFactoryClient {
    @POST
    suspend fun place(request: FactoryOrderRequest): FactoryOrderDto

    @GET
    @Path("/{id}")
    suspend fun get(@PathParam("id") id: String): FactoryOrderDto
}

data class WalletProvisionRequest(
    @JsonProperty("card_reference") val cardReference: String,
    val certificates: List<String>,
    val nonce: String,
    @JsonProperty("nonce_signature") val nonceSignature: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WalletProvisionDto(
    @JsonProperty("activation_data") val activationData: String = "",
    @JsonProperty("encrypted_pass_data") val encryptedPassData: String = "",
    @JsonProperty("ephemeral_public_key") val ephemeralPublicKey: String = "",
    @JsonProperty("token_reference") val tokenReference: String = "",
    @JsonProperty("token_last4") val tokenLast4: String = "",
)

@RegisterRestClient(configKey = "wallet-token-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/wallet/apple/provisioning")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface WalletTokenServiceClient {
    @POST
    suspend fun provision(request: WalletProvisionRequest): WalletProvisionDto
}

@ApplicationScoped
class CardFactoryAdapter(@RestClient private val client: CardFactoryClient) : CardFactoryPort {
    override suspend fun place(
        reference: String,
        design: String,
        nameOnCard: String,
        city: String,
        country: String,
    ): String = client.place(FactoryOrderRequest(reference, design, nameOnCard, city, country)).id

    override suspend fun status(factoryRef: String): FactoryStatus = client.get(factoryRef).let { d ->
        FactoryStatus(
            d.status,
            d.carrier,
            d.trackingNumber,
            d.expectedOn,
            d.events.map {
                FactoryStatusEvent(it.status, it.at, it.detail)
            },
        )
    }
}

@ApplicationScoped
class WalletTokenServiceAdapter(@RestClient private val client: WalletTokenServiceClient) : WalletTokenServicePort {
    override suspend fun provision(
        cardReference: String,
        certificates: List<String>,
        nonce: String,
        nonceSignature: String,
    ) = client.provision(WalletProvisionRequest(cardReference, certificates, nonce, nonceSignature)).let {
        WalletProvisioning(
            it.activationData,
            it.encryptedPassData,
            it.ephemeralPublicKey,
            it.tokenReference,
            it.tokenLast4,
        )
    }
}
