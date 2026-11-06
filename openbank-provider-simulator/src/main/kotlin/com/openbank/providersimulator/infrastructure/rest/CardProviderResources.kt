// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.providersimulator.application.CardFactoryDesk
import com.openbank.providersimulator.application.FactoryOrder
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
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
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** The card manufacturer and courier: the bank orders a plastic card and follows it to the door. */
@Path("/api/v1/card-factory/orders")
@Tag(name = "Card manufacturer (simulated)")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class CardFactoryResource {
    @Inject
    lateinit var desk: CardFactoryDesk

    @POST
    @Operation(summary = "Order a plastic card (the same reference twice answers the first order)")
    fun place(body: FactoryOrderRequest?): Response {
        val r = requireNotNull(body) { "request body is required" }
        val o = desk.place(
            requireNotNull(r.reference) { "reference is required" },
            r.design ?: "energoBlue",
            requireNotNull(r.nameOnCard) { "name_on_card is required" },
            r.city ?: "",
            r.country ?: "CZ",
        )
        return Response.status(Response.Status.CREATED).entity(FactoryOrderView.of(o, desk)).build()
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "An order: ORDERED, IN_PRODUCTION, SHIPPED or DELIVERED, with its events")
    fun get(@PathParam("id") id: String): FactoryOrderView = FactoryOrderView.of(
        desk.order(id) ?: throw NotFoundException(),
        desk,
    )
}

/**
 * Apple Pay's token service for in-app provisioning (NON-PRODUCTION). Real provisioning encrypts the
 * pass data to Apple's certificates; this answers in the same shape — activation data, encrypted pass
 * data and an ephemeral EC public key — with simulator keys, so the bank's side of the flow is real
 * and only the wallet's cryptography is a stand-in.
 */
@Path("/api/v1/wallet/apple/provisioning")
@Tag(name = "Apple Pay token service (simulated)")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
class AppleWalletResource {
    @POST
    @Operation(summary = "Provisioning data for PKAddPaymentPassViewController")
    fun provision(body: WalletProvisionRequest?): WalletProvisionView {
        val r = requireNotNull(body) { "request body is required" }
        val card = requireNotNull(r.cardReference) { "card_reference is required" }
        require(!r.nonce.isNullOrBlank() && !r.nonceSignature.isNullOrBlank()) {
            "nonce and nonce_signature are required"
        }
        require(!r.certificates.isNullOrEmpty()) { "certificates are required" }
        val ephemeral = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val key = KeyGenerator.getInstance("AES").apply { init(AES_BITS) }.generateKey()
        val iv = ByteArray(IV_BYTES).also { RANDOM.nextBytes(it) }
        val token = "${UUID.randomUUID()}"
        val last4 = (FOUR_DIGITS_FROM + RANDOM.nextInt(FOUR_DIGITS_SPAN)).toString()
        val pass = """{"cardReference":"$card","tokenReference":"$token","dpanSuffix":"$last4","nonce":"${r.nonce}"}"""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        val b64 = Base64.getEncoder()
        return WalletProvisionView(
            activationData = b64.encodeToString(ByteArray(ACTIVATION_BYTES).also { RANDOM.nextBytes(it) }),
            encryptedPassData = b64.encodeToString(iv + cipher.doFinal(pass.toByteArray())),
            ephemeralPublicKey = b64.encodeToString(ephemeral.public.encoded),
            tokenReference = token,
            tokenLast4 = last4,
        )
    }

    private companion object {
        val RANDOM = SecureRandom()
        const val AES_BITS = 256
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val ACTIVATION_BYTES = 32
        const val FOUR_DIGITS_FROM = 1000
        const val FOUR_DIGITS_SPAN = 9000
    }
}

data class FactoryOrderRequest(
    val reference: String? = null,
    val design: String? = null,
    @JsonProperty("name_on_card") val nameOnCard: String? = null,
    val city: String? = null,
    val country: String? = null,
)

data class FactoryEventView(val status: String, val at: String, val detail: String)

data class FactoryOrderView(
    val id: String,
    val reference: String,
    val status: String,
    val carrier: String,
    @JsonProperty("tracking_number") val trackingNumber: String,
    @JsonProperty("expected_on") val expectedOn: String,
    val events: List<FactoryEventView>,
) {
    companion object {
        fun of(o: FactoryOrder, desk: CardFactoryDesk) = FactoryOrderView(
            o.id,
            o.reference,
            o.stage.name,
            desk.carrierOf(o),
            o.trackingNumber,
            o.expectedOn.toString(),
            o.events.map { FactoryEventView(it.stage.name, it.at.toString(), it.detail) },
        )
    }
}

data class WalletProvisionRequest(
    @JsonProperty("card_reference") val cardReference: String? = null,
    val certificates: List<String>? = null,
    val nonce: String? = null,
    @JsonProperty("nonce_signature") val nonceSignature: String? = null,
)

data class WalletProvisionView(
    @JsonProperty("activation_data") val activationData: String,
    @JsonProperty("encrypted_pass_data") val encryptedPassData: String,
    @JsonProperty("ephemeral_public_key") val ephemeralPublicKey: String,
    @JsonProperty("token_reference") val tokenReference: String,
    @JsonProperty("token_last4") val tokenLast4: String,
)
