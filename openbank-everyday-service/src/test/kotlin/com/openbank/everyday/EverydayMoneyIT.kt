// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday

import com.openbank.everyday.it.EverydayPostgresTestResource
import com.openbank.everyday.it.FakeBank
import com.openbank.everyday.it.People
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Everyday money through real HTTP and PostgreSQL, over Jana's fake accounts and payments. */
@QuarkusTest
@QuarkusTestResource(EverydayPostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
class EverydayMoneyIT {

    @Inject
    lateinit var bank: FakeBank

    private val jana = "/api/v1/everyday/parties/${People.JANA}"
    private val account = "$jana/accounts/${FakeBank.CURRENT}"

    private fun json(body: String) = given().contentType(ContentType.JSON).body(body)

    @Test
    fun `direct debits - inkaso and SIPO on the CZK account, refused when malformed, foreign or twice`() {
        val mandate = """{"scheme":"SIPO","creditor_name":"Česká pošta","creditor_reference":"1234567890",""" +
            """"debtor_account_id":"${FakeBank.CURRENT}","limit":3000,"currency":"CZK","frequency":"MONTHLY"}"""
        val id: String = json(mandate).post("$jana/direct-debits/mandates").then().statusCode(201)
            .body("status", equalTo("ACTIVE")).extract().path("id")
        json(
            mandate,
        ).post("$jana/direct-debits/mandates").then().statusCode(409).body("code", equalTo("MANDATE_EXISTS"))
        json(mandate.replace("1234567890", "12345")).post("$jana/direct-debits/mandates").then().statusCode(400)
        json(mandate.replace("SIPO", "SEPA_CORE").replace("1234567890", "CZ12ZZZ12345678"))
            .post("$jana/direct-debits/mandates").then().statusCode(400) // SEPA Core needs a EUR account
        json(mandate.replace(FakeBank.CURRENT.toString(), UUID.randomUUID().toString()))
            .post("$jana/direct-debits/mandates").then().statusCode(404)
        given().get("$jana/direct-debits/mandates").then().statusCode(200).body("id", hasItem(id))
        given().delete("$jana/direct-debits/mandates/$id").then().statusCode(200).body("status", equalTo("CANCELLED"))
        given().delete("/api/v1/everyday/parties/${People.PETR}/direct-debits/mandates/$id").then().statusCode(404)
    }

    @Test
    fun `subscriptions are detected, and the customer's choices about them stick`() {
        val subs = given().get("$jana/subscriptions").then().statusCode(200).extract().jsonPath()
        val netflix = subs.getList<Map<String, Any>>("").first { (it["merchant_name"] as String).startsWith("NETFLIX") }
        assertThat(netflix["frequency"]).isEqualTo("MONTHLY")
        assertThat(netflix["paid_by"]).isEqualTo("CARD")
        val id = netflix["id"] as String
        json(
            """{"enabled":true}""",
        ).put("$jana/subscriptions/$id/reminder").then().statusCode(200).body("reminder_enabled", equalTo(true))
        json(
            "{}",
        ).post("$jana/subscriptions/$id/cancel").then().statusCode(200).body("status", equalTo("CANCEL_REQUESTED"))
        given().get("$jana/subscriptions").then().body("find { it.id == '$id' }.status", equalTo("CANCEL_REQUESTED"))
        json("{}").post("$jana/subscriptions/0000000000000000/cancel").then().statusCode(404)
    }

    @Test
    fun `spending by category leaves own transfers out, and the cash flow adds what is still expected`() {
        val month = YearMonth.now()
        given().get("$account/insights/spending?month=$month").then().statusCode(200)
            .body("currency", equalTo("CZK")).body("categories.id", hasItem("GROCERIES"))
            .body("categories.id", hasItem("SUBSCRIPTIONS"))
        given().get("$account/insights/spending?month=2026-13").then().statusCode(400)
        given().get("$account/insights/cash-flow?balance=10000").then().statusCode(200)
            .body("balance_today", equalTo(10000)).body("balance_end_of_month", notNullValue())
        given().get("$jana/accounts/${UUID.randomUUID()}/insights/spending?month=$month").then().statusCode(404)
    }

    @Test
    fun `an export is a CSV or PDF behind a download token`() {
        val from = LocalDate.now().minusDays(60)
        val csv = json("""{"format":"CSV","from":"$from","to":"${LocalDate.now()}","direction":"OUTGOING"}""")
            .post("$account/exports").then().statusCode(201).body("status", equalTo("READY")).extract().jsonPath()
        val body = given().get(
            "/api/v1/everyday/export-files/${csv.getString("download_token")}",
        ).then().statusCode(200)
            .extract().asByteArray().toString(Charsets.UTF_8)
        assertThat(body).contains("date;direction;amount").contains("NETFLIX").doesNotContain("Mzda")
        given().get("$account/exports/${csv.getString("id")}").then().statusCode(200).body("format", equalTo("CSV"))
        val pdf = json("""{"format":"PDF","from":"$from","to":"${LocalDate.now()}"}""").post("$account/exports").then()
            .statusCode(201).extract().path<String>("download_token")
        val bytes = given().get("/api/v1/everyday/export-files/$pdf").then().statusCode(200).extract().asByteArray()
        assertThat(String(bytes.copyOfRange(0, 5))).isEqualTo("%PDF-")
        given().get("/api/v1/everyday/export-files/not-a-token").then().statusCode(404)
        json(
            """{"format":"XLS","from":"$from","to":"${LocalDate.now()}"}""",
        ).post("$account/exports").then().statusCode(400)
    }

    @Test
    fun `a transfer is recalled once, a card payment or an own transfer never`() {
        val payments = bank.payments(bank.accounts(People.JANA).first(), LocalDate.now().minusDays(400), emptySet())
        val transfer = payments.first { it.description.startsWith("Jan Novák") }.id
        val card = payments.first { it.description.startsWith("ALBERT") }.id
        val own = payments.first { it.description.startsWith("Převod") }.id
        val recall = """{"reason":"WRONG_ACCOUNT","note":"Wrong digit"}"""
        json(recall).post("$jana/money/transactions/$transfer/recall").then().statusCode(201)
            .body("status", equalTo("SENT_TO_BANK")).body("reference", notNullValue())
        json(
            recall,
        ).post(
            "$jana/money/transactions/$transfer/recall",
        ).then().statusCode(409).body("code", equalTo("RECALL_EXISTS"))
        json(
            recall,
        ).post("$jana/money/transactions/$card/recall").then().statusCode(409).body("code", equalTo("USE_DISPUTE"))
        json(
            recall,
        ).post("$jana/money/transactions/$own/recall").then().statusCode(409).body("code", equalTo("NOT_RECALLABLE"))
        json("""{"reason":"BORED"}""").post("$jana/money/transactions/$transfer/recall").then().statusCode(400)
    }

    @Test
    fun `a top-up is credited once, after the acquirer authorised`() {
        bank.acquirerStatus = "CREATED"
        val id: String = json("""{"account_id":"${FakeBank.CURRENT}","amount":1500,"currency":"CZK"}""")
            .post("$jana/money/top-ups").then().statusCode(201)
            .body("checkout_url", notNullValue()).body("status", equalTo("CREATED")).extract().path("id")
        given().get("$jana/money/top-ups/$id").then().body("status", equalTo("CREATED"))
        bank.acquirerStatus = "AUTHORISED"
        given().get(
            "$jana/money/top-ups/$id",
        ).then().body("status", equalTo("CREDITED")).body("checkout_url", nullValue())
        given().get("$jana/money/top-ups/$id").then().body("status", equalTo("CREDITED"))
        assertThat(bank.credits.filter { it == "top-up:$id" }).hasSize(1)
        json(
            """{"account_id":"${FakeBank.CURRENT}","amount":1500,"currency":"EUR"}""",
        ).post("$jana/money/top-ups").then().statusCode(400)
        json(
            """{"account_id":"${FakeBank.CURRENT}","amount":10,"currency":"CZK"}""",
        ).post("$jana/money/top-ups").then().statusCode(400)
    }

    @Test
    fun `payment protection keeps what the customer set`() {
        given().get("$jana/money/payment-protection").then().statusCode(200).body("street_mode", equalTo(false))
        json(
            """{"hold_unusual":true,"street_mode":true,"street_mode_limit":1500,"trusted_approver_name":"Adam","trusted_approver_phone":"+420 777 000 222"}""",
        )
            .put("$jana/money/payment-protection").then().statusCode(200)
            .body("street_mode_limit", equalTo(1500.0f)).body("trusted_approver_phone", equalTo("+420777000222"))
        json(
            """{"street_mode":true,"street_mode_limit":0}""",
        ).put("$jana/money/payment-protection").then().statusCode(400)
    }

    @Test
    fun `the energy trend needs a linked account`() {
        given().get("/api/v1/everyday/parties/${People.PETR}/money/energy-trend").then().statusCode(404)
        given().get("$jana/subscriptions").then().body("", hasSize<Any>(org.hamcrest.Matchers.greaterThan(0)))
    }
}
