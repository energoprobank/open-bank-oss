// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday

import com.openbank.everyday.application.BillsService
import com.openbank.everyday.it.EverydayPostgresTestResource
import com.openbank.everyday.it.FakeEnergoPro
import com.openbank.everyday.it.People
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.isIn
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import jakarta.enterprise.inject.Any as AnyBean

/**
 * Bills through real HTTP, a real PostgreSQL (V1) and the real Kafka channel (in memory): link an
 * Energo-Pro account, see the due bill, pay it (a domestic-payment event), see it PAID and Energo-Pro
 * told — and the cases that must not work.
 */
@QuarkusTest
@QuarkusTestResource(EverydayPostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
class BillsIT {

    @Inject
    lateinit var energoPro: FakeEnergoPro

    @Inject
    lateinit var bills: BillsService

    @Inject
    lateinit var ds: DataSource

    @AnyBean
    @Inject
    lateinit var connector: InMemoryConnector

    private fun base(party: UUID) = "/api/v1/everyday/parties/$party/bills"

    private fun link(party: UUID, number: String, consent: Boolean = true) = given().contentType(ContentType.JSON)
        .body("""{"customer_number":"$number","data_sharing_consent":$consent}""")
        .post("${base(party)}/energo-pro/link")

    private fun paymentEvent(party: UUID, creditor: String, bank: String, vs: String?, amount: String) =
        """{"eventType":"DOMESTIC_PAYMENT_CREATED","paymentId":"${UUID.randomUUID()}","debtorPartyId":"$party",""" +
            """"creditorAccountNumber":"$creditor","creditorBankCode":"$bank","amount":$amount,"currency":"CZK",""" +
            """"occurredAt":"2026-11-06T09:00:00Z"""" + (vs?.let { ""","variableSymbol":"$it"""" } ?: "") + "}"

    @Test
    fun `billers are the customer's market`() {
        given().get("${base(People.PETR)}/billers").then().statusCode(200)
            .body("id", hasItem("EP-CZ")).body("id", not(hasItem("EP-BG")))
        given().get("${base(People.VELINA)}/billers").then().statusCode(200).body("id", equalTo(listOf("EP-BG")))
    }

    @Test
    fun `an Energo-Pro account links only with consent, a known number and the customer's own name`() {
        link(People.PETR, FakeEnergoPro.JANA_NUMBER, consent = false).then().statusCode(400)
        link(People.PETR, "4000001").then().statusCode(400)
        // Jana's number, Petr's name: the same answer as an unknown number
        link(People.PETR, FakeEnergoPro.JANA_NUMBER).then().statusCode(404).body("code", equalTo("NO_MATCH"))
        link(People.PETR, "EP-9999999").then().statusCode(404).body("code", equalTo("NO_MATCH"))
        given().get("${base(People.PETR)}/energo-pro").then().statusCode(200).body("linked", equalTo(false))
            .body("current_bill", nullValue())
    }

    @Test
    fun `a linked customer sees the due bill, pays it and sees it PAID, and Energo-Pro is told`() {
        link(People.JANA, " ep-4000001 ").then().statusCode(200)
            .body("linked", equalTo(true)).body("supplier_name", equalTo("Energo-Pro, a.s."))
            .body(
                "current_bill.status",
                equalTo("DUE"),
            ).body("current_bill.reference", equalTo(FakeEnergoPro.DUE_REFERENCE))
        given().get("${base(People.JANA)}/energo-pro/bills").then().statusCode(200).body("", hasSize<Any>(3))
        given().get("${base(People.JANA)}/reminders").then().statusCode(200).body("enabled", equalTo(false))
            .body("upcoming", hasSize<Any>(1))
        given().contentType(ContentType.JSON).body("""{"enabled":true,"days_before":5}""")
            .put("${base(People.JANA)}/reminders").then().statusCode(200).body("days_before", equalTo(5))
        given().contentType(ContentType.JSON).body("""{"enabled":true,"days_before":40}""")
            .put("${base(People.JANA)}/reminders").then().statusCode(400)

        val payments = connector.source<String>("domestic-payments-in")
        // Not a bill: another creditor, and Energo-Pro's account without a reference
        payments.send(paymentEvent(People.JANA, "1501018", "0100", FakeEnergoPro.DUE_REFERENCE, "1480.40"))
        payments.send(paymentEvent(People.JANA, "2001234561", "2010", null, "1480.40"))
        payments.send("""{"eventType":"DOMESTIC_PAYMENT_STATUS_CHANGED"}""")
        payments.send("not json")
        // The bill, paid to Energo-Pro's account with leading zeros as a client might type it. The event
        // names the edge's service account, not Jana: domestic-payment records the caller's token
        // subject (seen live, 2026-11-09). The bill is still hers, by its reference.
        payments.send(
            paymentEvent(EDGE_SERVICE_ACCOUNT, "0002001234561", "2010", FakeEnergoPro.DUE_REFERENCE, "1480.40"),
        )

        // The handler is @Blocking: the event is processed on a worker thread, after send() returns.
        awaitCount(
            "SELECT count(*) FROM bill_payments WHERE reference = '${FakeEnergoPro.DUE_REFERENCE}' AND notice_sent_at IS NOT NULL",
            1,
        )
        val paymentIds = ids(
            "SELECT payment_id FROM bill_payments WHERE biller_id = 'EP-CZ' AND reference = '${FakeEnergoPro.DUE_REFERENCE}'",
        )
        given().get("${base(People.JANA)}/energo-pro").then().body("current_bill.status", equalTo("PAID"))
            .body("current_bill.payment_id", isIn(paymentIds))
        assertThat(energoPro.notices).contains(FakeEnergoPro.DUE_REFERENCE)

        given().delete("${base(People.JANA)}/energo-pro/link").then().statusCode(204)
        given().get("${base(People.JANA)}/energo-pro").then().body("linked", equalTo(false))
    }

    @Test
    fun `a notice Energo-Pro did not take is sent again by the retry job`() {
        val party = People.PETR
        energoPro.reachable = false
        try {
            connector.source<String>("domestic-payments-in")
                .send(paymentEvent(party, "2001234561", "2010", "4000001092", "1120.00"))
            awaitCount("SELECT count(*) FROM bill_payments WHERE party_id = '$party' AND notice_attempts = 1", 1)
        } finally {
            energoPro.reachable = true
        }
        assertThat(count("SELECT count(*) FROM bill_payments WHERE party_id = '$party' AND notice_sent_at IS NULL"))
            .isEqualTo(1)
        assertThat(bills.retryNotices()).isGreaterThanOrEqualTo(1)
        assertThat(count("SELECT count(*) FROM bill_payments WHERE party_id = '$party' AND notice_sent_at IS NULL"))
            .isEqualTo(0)
    }

    private fun awaitCount(sql: String, expected: Long) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (count(sql) != expected && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertThat(count(sql)).isEqualTo(expected)
    }

    private fun ids(sql: String): List<String> = ds.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }
    }

    private companion object {
        val EDGE_SERVICE_ACCOUNT: UUID = UUID.fromString("5379a6f8-962e-4d37-a037-f36907e60bc0")
        const val AWAIT_MS = 10_000L
        const val POLL_MS = 100L
    }

    private fun count(sql: String): Long = ds.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }
}
