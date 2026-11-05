// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0344 against the real database: V33 seeds the FLUX accounts by their fixed ids, the provision
 * template posts to them through the full posting path (locks, account checks, outbox), a replayed
 * sourceRef posts nothing, a replay with different content is a 409, and the reward cash-credit
 * journal transaction-service builds (Dr provision / Cr deposit-control, sub = customer account)
 * is accepted by the ledger as posted.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresRedpandaTestResource::class)
@TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
class RewardProvisionPostingIT {

    private val today = LocalDate.now().toString()

    private fun body(market: String, currency: String, delta: String, sourceRef: String) = """
        {"postingDate":"$today","market":"$market","currency":"$currency","deltaAmount":"$delta",
         "sourceRef":"$sourceRef"}
    """.trimIndent()

    private fun post(body: String, status: Int): String = Given {
        contentType("application/json")
        body(body)
    } When {
        post("/api/v1/ledger/reward-provisions")
    } Then {
        statusCode(status)
    } Extract {
        asString()
    }

    private fun journalLines(journalId: String): List<Map<String, Any>> = Given { this } When {
        get("/api/v1/journals/$journalId")
    } Then {
        statusCode(200)
    } Extract {
        path("lines")
    }

    private fun id(code: String) = "a0000000-0000-0000-0000-00000000$code"

    @Test
    fun `a CZ accrual posts Dr 5300 Cr 2400, and the same sourceRef replays it`() {
        val ref = "it-cz-${UUID.randomUUID()}"
        val first = post(body("CZ", "CZK", "250.00", ref), 201)
        val journalId = io.restassured.path.json.JsonPath.from(first).getString("journalId")
        assertThat(io.restassured.path.json.JsonPath.from(first).getString("status")).isEqualTo("POSTED")

        val lines = journalLines(journalId).map { Triple(it["glAccountId"], it["side"], it["currencyCode"]) }
        assertThat(lines).containsExactlyInAnyOrder(
            Triple(id("5300"), "DEBIT", "CZK"),
            Triple(id("2400"), "CREDIT", "CZK"),
        )

        val replay = post(body("CZ", "CZK", "250.00", ref), 200)
        assertThat(io.restassured.path.json.JsonPath.from(replay).getString("journalId")).isEqualTo(journalId)
        assertThat(io.restassured.path.json.JsonPath.from(replay).getString("status")).isEqualTo("REPLAYED")

        // Same reference, different amount: refused, never silently answered with the old journal.
        post(body("CZ", "CZK", "260.00", ref), 409)
    }

    @Test
    fun `a BG release posts Dr 2401 Cr 5301`() {
        val first = post(body("BG", "EUR", "-1.50", "it-bg-${UUID.randomUUID()}"), 201)
        val journalId = io.restassured.path.json.JsonPath.from(first).getString("journalId")

        val lines = journalLines(journalId).map { Triple(it["glAccountId"], it["side"], it["currencyCode"]) }
        assertThat(lines).containsExactlyInAnyOrder(
            Triple(id("2401"), "DEBIT", "EUR"),
            Triple(id("5301"), "CREDIT", "EUR"),
        )
    }

    @Test
    fun `a market and currency that disagree, or a zero delta, are refused`() {
        post(body("CZ", "EUR", "1.00", "it-mismatch-${UUID.randomUUID()}"), 422)
        post(body("BG", "EUR", "0", "it-zero-${UUID.randomUUID()}"), 422)
        post("""{"market":"CZ"}""", 400)
    }

    /** The cash-credit journal transaction-service posts; [onProvision] misplaces the customer dimension. */
    private fun redemptionJournal(customerAccount: UUID, onProvision: Boolean): String {
        val sub = ",\"subAccountId\":\"$customerAccount\""
        val provisionSub = if (onProvision) sub else ""
        val depositSub = if (onProvision) "" else sub
        return """
            {"idempotencyKey":"it-redeem-${UUID.randomUUID()}","transactionId":"${UUID.randomUUID()}",
             "entryDate":"$today","valueDate":"$today","description":"FLUX odměna / FLUX reward",
             "createdBy":"00000000-0000-0000-0000-000000000099","lines":[
               {"glAccountId":"${id("2400")}","side":"DEBIT","amount":"50.00","currencyCode":"CZK",
                "baseAmount":"50.00","baseCurrencyCode":"CZK"$provisionSub},
               {"glAccountId":"a0000000-0000-0000-0000-000000000002","side":"CREDIT","amount":"50.00",
                "currencyCode":"CZK","baseAmount":"50.00","baseCurrencyCode":"CZK"$depositSub}]}
        """.trimIndent()
    }

    @Test
    fun `the redemption journal transaction-service posts is accepted - Dr provision, Cr customer deposit`() {
        val customerAccount = UUID.randomUUID()
        Given {
            contentType("application/json")
            body(redemptionJournal(customerAccount, onProvision = false))
        } When {
            post("/api/v1/journals")
        } Then {
            statusCode(201)
            body("status", equalTo("POSTED"))
        }
        // The provision is a plain liability: it may never carry a customer sub-ledger dimension.
        Given {
            contentType("application/json")
            body(redemptionJournal(customerAccount, onProvision = true))
        } When {
            post("/api/v1/journals")
        } Then {
            statusCode(422)
        }
    }
}
