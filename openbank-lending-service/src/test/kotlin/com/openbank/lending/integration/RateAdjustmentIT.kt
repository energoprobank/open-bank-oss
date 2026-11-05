// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.lending.application.port.`in`.LoanOverviewUseCase
import com.openbank.lending.application.port.`in`.RateAdjustmentUseCase
import com.openbank.lending.domain.model.RateAdjustmentBlockReason
import com.openbank.lending.domain.model.RateAdjustmentBlockedException
import com.openbank.lending.domain.model.RateAdjustmentRequest
import com.openbank.lending.domain.model.RateAdjustmentSource
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.Amortization
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * CR-1 against real Postgres: V28, the entity's column names, the in-place reprice HQL, the outbox
 * write and the loan lock all run for real. The use case is driven on a Vert.x context directly —
 * the REST seam admits only a VERIFIED rewards service-account token, which `@TestSecurity` cannot
 * mint (that seam is `LoanRateAdjustmentResourceTest`); the routes and RBAC are asserted below.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class RateAdjustmentIT {

    @Inject
    lateinit var useCase: RateAdjustmentUseCase

    @Inject
    lateinit var overview: LoanOverviewUseCase

    @Inject
    lateinit var dataSource: DataSource

    private val today = LocalDate.now(java.time.ZoneOffset.UTC)

    private fun <T> onContext(block: () -> Uni<T>): T = VertxContextSupport.subscribeAndAwait { block() }

    /** A 24-month CZK annuity at 5.99 % whose first three instalments are already settled. */
    private fun seedLoan(partyId: UUID = UUID.randomUUID()): UUID {
        val appId = UUID.randomUUID()
        val loanId = UUID.randomUUID()
        val firstDue = today.minusMonths(2)
        val principal = Money.of(BigDecimal("120000.00"), "CZK")
        val rate = BigDecimal("0.0599")
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO loan_application (id, party_id, requested_amount, currency, nominal_annual_rate, " +
                    "term_periods, first_due_date, status, proposed_by, product_type) " +
                    "VALUES (?, ?, 120000.00, 'CZK', 0.0599, 24, ?, 'DISBURSED', 'it', 'CONSUMER_CREDIT')",
            ).use { s ->
                s.setObject(1, appId)
                s.setObject(2, partyId)
                s.setObject(3, firstDue)
                s.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO loan (id, application_id, party_id, principal, currency, nominal_annual_rate, " +
                    "term_periods, method, first_due_date) VALUES (?, ?, ?, 120000.00, 'CZK', 0.0599, 24, 'ANNUITY', ?)",
            ).use { s ->
                s.setObject(1, loanId)
                s.setObject(2, appId)
                s.setObject(3, partyId)
                s.setObject(4, firstDue)
                s.executeUpdate()
            }
            Amortization.schedule(principal, rate, 24, firstDue).installments.forEach { i ->
                val settled = !i.dueDate.isAfter(today)
                c.prepareStatement(
                    "INSERT INTO installment (id, loan_id, number, due_date, currency, opening_balance, principal, " +
                        "interest, payment, closing_balance, paid, interest_accrued) " +
                        "VALUES (?, ?, ?, ?, 'CZK', ?, ?, ?, ?, ?, ?, ?)",
                ).use { s ->
                    s.setObject(1, UUID.randomUUID())
                    s.setObject(2, loanId)
                    s.setInt(3, i.number)
                    s.setObject(4, i.dueDate)
                    s.setBigDecimal(5, i.openingBalance.amount)
                    s.setBigDecimal(6, i.principal.amount)
                    s.setBigDecimal(7, i.interest.amount)
                    s.setBigDecimal(8, i.payment.amount)
                    s.setBigDecimal(9, i.closingBalance.amount)
                    s.setBoolean(10, settled)
                    s.setBoolean(11, settled)
                    s.executeUpdate()
                }
            }
        }
        return loanId
    }

    private fun payments(loanId: UUID): List<BigDecimal> = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT payment FROM installment WHERE loan_id = ? AND paid = false ORDER BY number",
        ).use { s ->
            s.setObject(1, loanId)
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getBigDecimal(1) else null }.toList() }
        }
    }

    private fun count(sql: String, vararg args: Any): Int = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { s ->
            args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
            s.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun outbox(loanId: UUID, type: String): Int =
        count("SELECT count(*) FROM lending_outbox WHERE aggregate_id = ? AND event_type = ?", loanId, type)

    private fun request(ref: String) = RateAdjustmentRequest(
        deltaBps = -50,
        validFrom = today,
        validTo = today.plusMonths(12),
        source = RateAdjustmentSource.REWARDS,
        sourceRef = ref,
        approvalRef = "sca-it",
    )

    private val rewards = "service-account-openbank-rewards"

    private fun activeCount(loanId: UUID) =
        count("SELECT count(*) FROM loan_rate_adjustment WHERE loan_id = ? AND status = 'ACTIVE'", loanId)

    @Test
    fun `apply reprices the tail, writes the row and the event, and a replay writes nothing`() {
        val loanId = seedLoan()
        val booked = payments(loanId)
        val ref = "it-${UUID.randomUUID()}"

        val applied = onContext { useCase.apply(LoanId(loanId), request(ref), rewards) }
        assertThat(applied.replayed).isFalse()
        val discounted = payments(loanId)
        assertThat(discounted.first()).isLessThan(booked.first())
        assertThat(discounted).hasSameSizeAs(booked)
        assertThat(activeCount(loanId)).isEqualTo(1)
        assertThat(outbox(loanId, "loan.rate_adjusted")).isEqualTo(1)

        // Idempotent on sourceRef: same adjustment back, nothing written twice.
        val replay = onContext { useCase.apply(LoanId(loanId), request(ref), rewards) }
        assertThat(replay.replayed).isTrue()
        assertThat(replay.adjustment.adjustmentId).isEqualTo(applied.adjustment.adjustmentId)
        assertThat(replay.adjustment.after!!.nominalRatePct).isEqualByComparingTo("5.49")
        assertThat(outbox(loanId, "loan.rate_adjusted")).isEqualTo(1)
        assertThat(payments(loanId)).isEqualTo(discounted)
    }

    @Test
    fun `a second live coupon is refused and cancelling restores the contract schedule`() {
        val loanId = seedLoan()
        val booked = payments(loanId)
        val applied = onContext { useCase.apply(LoanId(loanId), request("it-${UUID.randomUUID()}"), rewards) }

        assertThatThrownBy {
            onContext<Any> { useCase.apply(LoanId(loanId), request("it-${UUID.randomUUID()}"), rewards).map { it } }
        }.isInstanceOfSatisfying(RateAdjustmentBlockedException::class.java) {
            assertThat(it.reason).isEqualTo(RateAdjustmentBlockReason.ADJUSTMENT_ALREADY_ACTIVE)
        }

        onContext { useCase.cancel(LoanId(loanId), applied.adjustment.adjustmentId, rewards) }
        assertThat(activeCount(loanId)).isZero()
        payments(loanId).zip(booked).forEach { (now, was) ->
            assertThat(now.subtract(was).abs()).isLessThanOrEqualTo(BigDecimal("0.01"))
        }
        assertThat(outbox(loanId, "loan.rate_adjustment_cancelled")).isEqualTo(1)
    }

    @Test
    fun `the overview reads a party's loans with outstanding, rate and next instalment`() {
        val party = UUID.randomUUID()
        val loanId = seedLoan(party)
        val row = onContext { overview.overview(party) }.single()
        assertThat(row.loanId).isEqualTo(loanId)
        assertThat(row.productType).isEqualTo("CONSUMER_CREDIT")
        assertThat(row.nominalRatePct).isEqualByComparingTo("5.99")
        assertThat(row.nextInstalment).isNotNull()
        assertThat(row.daysPastDue).isZero()
        assertThat(row.contractNumber).isNotBlank()
    }

    // ── routes and RBAC (the handler-level principal check is LoanRateAdjustmentResourceTest) ──

    @Test
    fun `anonymous callers are rejected before any handler runs`() {
        Given { this } When { get("/api/v1/loans/${UUID.randomUUID()}/rate-adjustments/preview?deltaBps=-50") } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_LENDING_OFFICER"])
    fun `a lending officer cannot reach the rate-adjustment writes`() {
        Given {
            contentType("application/json")
            body("{}")
        } When { post("/api/v1/loans/${UUID.randomUUID()}/rate-adjustments") } Then { statusCode(403) }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-rewards", roles = ["ROLE_API"])
    fun `ROLE_API without a verified service-account token is refused by the handler`() {
        Given {
            contentType("application/json")
            body("{}")
        } When { post("/api/v1/loans/${UUID.randomUUID()}/rate-adjustments") } Then {
            statusCode(403)
            body("code", equalTo("FORBIDDEN"))
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `the overview route is served and does not collide with loans by id`() {
        Given { this } When { get("/api/v1/lending/loans/overview?partyId=${UUID.randomUUID()}") } Then {
            statusCode(200)
            body("size()", equalTo(0))
        }
    }
}
