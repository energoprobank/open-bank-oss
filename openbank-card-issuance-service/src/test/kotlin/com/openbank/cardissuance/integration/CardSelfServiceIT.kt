// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.integration

import com.openbank.cardissuance.application.port.out.CardFactoryPort
import com.openbank.cardissuance.application.port.out.WalletProvisioning
import com.openbank.cardissuance.application.port.out.WalletTokenServicePort
import com.openbank.cardissuance.domain.model.FactoryStatus
import com.openbank.cardissuance.domain.model.FactoryStatusEvent
import com.openbank.cardissuance.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The manufacturer: an order's stage is whatever the test says it is. */
@Alternative
@Priority(1)
@ApplicationScoped
class FakeCardFactory : CardFactoryPort {
    @Volatile
    var stage = "ORDERED"

    override suspend fun place(reference: String, design: String, nameOnCard: String, city: String, country: String) =
        "factory-$reference"

    override suspend fun status(factoryRef: String) = FactoryStatus(
        stage,
        "Česká pošta",
        "RR123456789CZ",
        "2026-11-11",
        listOf(FactoryStatusEvent(stage, "2026-11-06T10:00:00Z", null)),
    )
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeWalletTokenService : WalletTokenServicePort {
    override suspend fun provision(
        cardReference: String,
        certificates: List<String>,
        nonce: String,
        nonceSignature: String,
    ) = WalletProvisioning("act", "pass", "ephemeral", "token-$cardReference", "9876")
}

class CardSelfServiceProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "authz.enforce" to "false",
        "openbank.outbox.dispatch-enabled" to "false",
    )
}

/**
 * A plastic card from order to activation, its PIN and design, travel notices and card notifications,
 * through real HTTP and PostgreSQL (V10). The manufacturer and the wallet's token service are fakes.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(CardSelfServiceProfile::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API", "ROLE_OPERATOR"])
@OidcSecurity(
    claims = [
        Claim(key = "azp", value = "openbank-edge"),
        Claim(key = "sub", value = "synthetic-subject"),
        Claim(key = "preferred_username", value = "service-account-openbank-edge"),
    ],
)
class CardSelfServiceIT {

    @Inject
    lateinit var factory: FakeCardFactory

    private val party = UUID.randomUUID()
    private val account = UUID.randomUUID()

    private fun json(body: String) = given().contentType(ContentType.JSON).body(body)

    private var lastMaskedPan = ""

    private fun issueDebitCard(): String = json(
        """{"partyId":"$party","accountId":"$account","productCode":"CURRENT_CZK","cardType":"DEBIT","network":"VISA",
           "cardholderName":"Jana Novakova","embossedName":"JANA NOVAKOVA","currency":"CZK"}""",
    ).header("Idempotency-Key", "it-${UUID.randomUUID()}").post("/api/v1/cards").then().statusCode(201)
        .body("status", equalTo("PENDING")).extract().jsonPath().let {
            lastMaskedPan = it.getString("maskedPan")
            it.getString("id")
        }

    @Test
    fun `a plastic card is ordered, gets a PIN, is delivered and activated with the digits printed on it`() {
        factory.stage = "ORDERED"
        val card = issueDebitCard()
        val order: String = json(
            """{"party_id":"$party","account_id":"$account","card_id":"$card","design":"midnight","name_on_card":"Jana Novakova",
               "delivery_address":{"line1":"Na Bělidle 1","city":"Praha 5","postal_code":"150 00","country_code":"CZ"}}""",
        ).post("/api/v1/card-orders").then().statusCode(201)
            .body("status", equalTo("ORDERED")).body("carrier", equalTo("Česká pošta")).extract().path("id")
        given().get("/api/v1/card-orders?partyId=$party").then().statusCode(200).body("", hasSize<Any>(1))

        // PIN: set at order time; a weak PIN is refused, a good one replaces it
        given().get(
            "/api/v1/cards/$card/pin",
        ).then().statusCode(200).body("pin_set", equalTo(true)).body("attempts_left", equalTo(3))
        val first: String = json("{}").post("/api/v1/cards/$card/pin/view").then().statusCode(200).extract().path("pin")
        assertThat(first).hasSize(4)
        json("""{"new_pin":"1234"}""").put("/api/v1/cards/$card/pin").then().statusCode(400)
        json("""{"new_pin":"4826"}""").put("/api/v1/cards/$card/pin").then().statusCode(204)
        json("{}").post("/api/v1/cards/$card/pin/view").then().body("pin", equalTo("4826"))

        // Design: chosen at order, changeable until the card is printed
        given().get("/api/v1/cards/$card/design").then().body("design", equalTo("midnight"))
        json(
            """{"design":"copper"}""",
        ).put("/api/v1/cards/$card/design").then().statusCode(200).body("design", equalTo("copper"))
        json("""{"design":"gold"}""").put("/api/v1/cards/$card/design").then().statusCode(400)
        factory.stage = "SHIPPED"
        json("""{"design":"hydro"}""").put("/api/v1/cards/$card/design").then().statusCode(409)

        // Activation: only once delivered, only with the right last four digits
        json("""{"last4":"0000"}""").post("/api/v1/cards/$card/self-activation").then().statusCode(409)
            .body("code", equalTo("NOT_DELIVERED"))
        factory.stage = "DELIVERED"
        json("""{"last4":"0000"}""").post("/api/v1/cards/$card/self-activation").then().statusCode(400)
        val masked = lastMaskedPan
        json("""{"last4":"${masked.takeLast(4)}"}""").post("/api/v1/cards/$card/self-activation").then().statusCode(200)
            .body("status", equalTo("ACTIVE"))
        given().get("/api/v1/card-orders/$order?partyId=$party").then().body("status", equalTo("ACTIVATED"))

        // Wallet: an active card gets provisioning data
        json("""{"certificates":["Y2VydA=="],"nonce":"bm9uY2U=","nonce_signature":"c2ln"}""")
            .post(
                "/api/v1/cards/$card/wallet-provisioning-data",
            ).then().statusCode(200).body("token_last4", equalTo("9876"))
    }

    @Test
    fun `travel notices and card notifications`() {
        val card = issueDebitCard()
        val today = LocalDate.now()
        val notice: String = json(
            """{"countries":["hr","SI"],"start_date":"$today","end_date":"${today.plusDays(7)}"}""",
        )
            .post(
                "/api/v1/cards/$card/travel-notices",
            ).then().statusCode(201).body("countries", equalTo(listOf("HR", "SI")))
            .extract().path("id")
        json("""{"countries":["HR"],"start_date":"$today","end_date":"${today.minusDays(1)}"}""")
            .post("/api/v1/cards/$card/travel-notices").then().statusCode(400)
        given().get("/api/v1/cards/$card/travel-notices").then().body("", hasSize<Any>(1))
        given().delete("/api/v1/cards/$card/travel-notices/$notice").then().statusCode(204)
        given().delete("/api/v1/cards/$card/travel-notices/$notice").then().statusCode(404)

        given().get("/api/v1/card-notification-settings/$party?currency=CZK").then().statusCode(200)
            .body("every_payment", equalTo(true)).body("online_payments", equalTo(false))
        json(
            """{"every_payment":false,"declined_payments":true,"payments_abroad":true,"online_payments":true,"weekly_summary":false,
               "large_payment_threshold":2000,"currency":"CZK"}""",
        ).put("/api/v1/card-notification-settings/$party").then().statusCode(200)
        given().get("/api/v1/card-notification-settings/$party").then().body("every_payment", equalTo(false))
            .body("large_payment_threshold", equalTo(2000.0f))
    }
}
