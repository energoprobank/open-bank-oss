// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test

/** Real HTTP against the running app (profile `test`: checks pass instantly, 50 generated customers). */
@QuarkusTest
class ProviderSimulatorApiIT {

    @Test
    @TestSecurity(user = "service-account-openbank-card-issuance", roles = ["ROLE_API"])
    fun `a plastic card is produced, shipped and delivered, and wallet provisioning answers in PassKit's shape`() {
        val body = """{"reference":"order-1","design":"midnight","name_on_card":"JANA NOVAKOVA",""" +
            """"city":"Praha","country":"CZ"}"""
        val id: String = given().contentType(ContentType.JSON).body(body).post("/api/v1/card-factory/orders")
            .then().statusCode(201).body("carrier", equalTo("Česká pošta")).extract().path("id")
        // stage-seconds = 0 in the test profile: every stage is reached at once
        given().get("/api/v1/card-factory/orders/$id").then().statusCode(200)
            .body("status", equalTo("DELIVERED")).body("events", hasSize<Any>(4))
        given().contentType(
            ContentType.JSON,
        ).body(body).post("/api/v1/card-factory/orders").then().body("id", equalTo(id))
        given().contentType(
            ContentType.JSON,
        ).body(body.replace("CZ", "DE")).post("/api/v1/card-factory/orders").then().statusCode(400)
        given().contentType(ContentType.JSON)
            .body(
                """{"card_reference":"card-1","certificates":["Y2VydA=="],"nonce":"bm9uY2U=","nonce_signature":"c2ln"}""",
            )
            .post("/api/v1/wallet/apple/provisioning").then().statusCode(200)
            .body("activation_data", org.hamcrest.Matchers.notNullValue())
            .body("ephemeral_public_key", org.hamcrest.Matchers.notNullValue())
        given().contentType(ContentType.JSON).body("""{"card_reference":"card-1"}""")
            .post("/api/v1/wallet/apple/provisioning").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-everyday", roles = ["ROLE_API"])
    fun `a card checkout is paid on the acquirer's page - a declined card stays declined`() {
        val open = { ref: String ->
            given().contentType(
                ContentType.JSON,
            ).body("""{"amount":1500,"currency":"CZK","merchant_reference":"$ref"}""")
                .post("/api/v1/acquirer/checkouts").then().statusCode(201).body("status", equalTo("CREATED"))
                .extract().jsonPath()
        }
        val ok = open("top-up-1")
        val id = ok.getString("id")
        org.assertj.core.api.Assertions.assertThat(ok.getString("checkout_url")).endsWith("/acquirer/pay/$id")
        given().get(
            "/acquirer/pay/$id",
        ).then().statusCode(200).body(org.hamcrest.Matchers.containsString("Pay 1 500.00 CZK"))
        given().contentType(ContentType.URLENC).formParam("card", "4242 4242 4242 4242").post("/acquirer/pay/$id")
            .then().statusCode(200).body(org.hamcrest.Matchers.containsString("Payment authorised"))
        given().get(
            "/api/v1/acquirer/checkouts/$id",
        ).then().body("status", equalTo("AUTHORISED")).body("card_last4", equalTo("4242"))

        val declined = open("top-up-2").getString("id")
        given().contentType(ContentType.URLENC).formParam("card", "4000 0000 0000 0002").post("/acquirer/pay/$declined")
        given().get("/api/v1/acquirer/checkouts/$declined").then().body("status", equalTo("DECLINED"))
        given().contentType(ContentType.JSON).body("""{"amount":0,"currency":"CZK","merchant_reference":"x"}""")
            .post("/api/v1/acquirer/checkouts").then().statusCode(400)
        given().get("/api/v1/acquirer/checkouts/nope").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-onboarding", roles = ["ROLE_API"])
    fun `Energo-Pro answers a known customer and 404 for an unknown one`() {
        given().get("/api/v1/energo-pro/customers/EP-1000001").then().statusCode(200)
            .body("customer_number", equalTo("EP-1000001"))
        given().get("/api/v1/energo-pro/customers/EP-9999999").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-bills", roles = ["ROLE_API"])
    fun `a bill is DUE until the bank's payment notice, then PAID, and a short or unknown payment is refused`() {
        given().get("/api/v1/energo-pro/customers/EP-1000001/supply-points").then().statusCode(200)
            .body("[0].customer_number", equalTo("EP-1000001"))
        val bills = given().get("/api/v1/energo-pro/customers/EP-1000001/bills").then().statusCode(200)
            .body("", hasSize<Any>(12)).body("[1].status", equalTo("PAID"))
            .extract().jsonPath()
        val reference = bills.getString("[0].reference")
        val amount = bills.getString("[0].amount")
        val currency = bills.getString("[0].currency")
        val notice = { amt: String -> """{"reference":"$reference","amount":$amt,"currency":"$currency"}""" }

        given().contentType(ContentType.JSON).body(notice("1.00"))
            .post("/api/v1/energo-pro/payments").then().statusCode(409).body("code", equalTo("AMOUNT_MISMATCH"))
        given().contentType(ContentType.JSON).body(notice(amount))
            .post("/api/v1/energo-pro/payments").then().statusCode(200).body("status", equalTo("PAID"))
        given().contentType(ContentType.JSON).body(notice(amount)) // the same notice again: the same answer
            .post("/api/v1/energo-pro/payments").then().statusCode(200).body("status", equalTo("PAID"))
        given().get("/api/v1/energo-pro/customers/EP-1000001/bills").then().body("[0].status", equalTo("PAID"))
        given().contentType(ContentType.JSON).body("""{"reference":"0000000001","amount":10,"currency":"CZK"}""")
            .post("/api/v1/energo-pro/payments").then().statusCode(404)
        given().get("/api/v1/energo-pro/collection-accounts").then().statusCode(200).body("", hasSize<Any>(2))
    }

    @Test
    @TestSecurity(user = "service-account-openbank-onboarding", roles = ["ROLE_API"])
    fun `a document scan is verified and returns the identity`() {
        val id: String = given().contentType(ContentType.JSON)
            .body(
                """{"kind":"DOCUMENT_SCAN","reference":"app-1","document_type":"ID_CARD",""" +
                    """"applicant":{"given_name":"Jana","family_name":"Nováková",""" +
                    """"date_of_birth":"1990-01-01","national_id":"9051011234"}}""",
            )
            .post("/api/v1/idv/verifications").then().statusCode(201).extract().path("id")
        given().get("/api/v1/idv/verifications/$id").then().statusCode(200)
            .body("status", equalTo("VERIFIED")).body("checks", hasSize<Any>(4))
            .body("identity.national_id", equalTo("9051011234"))
        // A Bank iD path never reveals an IDV session.
        given().get("/api/v1/bankid/sessions/$id").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-onboarding", roles = ["ROLE_API"])
    fun `a Bank iD session for a failing applicant ends FAILED`() {
        val id: String = given().contentType(ContentType.JSON)
            .body("""{"reference":"app-2","applicant":{"given_name":"Petr","family_name":"Failidv"}}""")
            .post("/api/v1/bankid/sessions").then().statusCode(201).extract().path("id")
        given().get("/api/v1/bankid/sessions/$id").then().body("status", equalTo("FAILED"))
    }

    @Test
    @TestSecurity(user = "service-account-openbank-onboarding", roles = ["ROLE_API"])
    fun `bad input is a 400, not a 500`() {
        given().contentType(ContentType.JSON).body("""{"reference":"x"}""")
            .post("/api/v1/idv/verifications").then().statusCode(400)
        given().contentType(ContentType.JSON).body("""{"channel":"SMS","recipient":"+420601000001"}""")
            .post("/api/v1/otp/messages").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-onboarding", roles = ["ROLE_API"])
    fun `an SMS is delivered but only an operator reads the outbox`() {
        given().contentType(
            ContentType.JSON,
        ).body("""{"channel":"SMS","recipient":"+420 601 000 777","text":"Kód 123456"}""")
            .post("/api/v1/otp/messages").then().statusCode(202)
        given().queryParam("recipient", "+420601000777").get("/api/v1/otp/outbox").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `the sandbox outbox shows what a phone received`() {
        given().contentType(
            ContentType.JSON,
        ).body("""{"channel":"SMS","recipient":"+420 601 000 888","text":"Kód 654321"}""")
            .post("/api/v1/otp/messages").then().statusCode(202)
        given().queryParam("recipient", "+420601000888").get("/api/v1/otp/outbox").then().statusCode(200)
            .body("[0].text", equalTo("Kód 654321"))
    }

    @Test
    fun `an anonymous caller is refused`() {
        given().get("/api/v1/energo-pro/customers/EP-1000001").then().statusCode(401)
    }
}
