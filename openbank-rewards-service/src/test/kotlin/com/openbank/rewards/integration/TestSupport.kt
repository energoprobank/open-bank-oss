// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.integration

import com.openbank.rewards.application.AccountRef
import com.openbank.rewards.application.BankDirectoryPort
import com.openbank.rewards.application.CardRef
import com.openbank.rewards.application.CashCreditPort
import com.openbank.rewards.application.DownstreamRejected
import com.openbank.rewards.application.DownstreamUnavailable
import com.openbank.rewards.application.GlPostingPort
import com.openbank.rewards.application.IncentivePort
import com.openbank.rewards.application.InterestPort
import com.openbank.rewards.application.LendingPort
import com.openbank.rewards.application.LoanRef
import com.openbank.rewards.application.RateTerms
import com.openbank.rewards.domain.Market
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.opentest4j.TestAbortedException
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RewardsPostgresTestResource : QuarkusTestResourceLifecycleManager {
    private var postgres: PostgreSQLContainer<*>? = null

    override fun start(): Map<String, String> {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            throw TestAbortedException(
                "Docker not available — skipping rewards IT",
            )
        }
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"))
            .withUsername("openbank").withPassword("openbank_secret").withDatabaseName("openbank_rewards_it")
        pg.start()
        postgres = pg
        return mapOf(
            "quarkus.datasource.jdbc.url" to pg.jdbcUrl,
            "quarkus.datasource.username" to "openbank",
            "quarkus.datasource.password" to "openbank_secret",
            // The tests read outbox rows as evidence; a background dispatch would race them.
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    override fun stop() {
        postgres?.stop()
    }
}

/** The bank as seen by the engine in tests: accounts and cards per party, set by each test. */
@Alternative
@Priority(1)
@ApplicationScoped
class FakeDirectory : BankDirectoryPort {
    val accounts = ConcurrentHashMap<UUID, List<AccountRef>>()
    val cards = ConcurrentHashMap<UUID, List<CardRef>>()

    override fun accountsOf(partyId: UUID): List<AccountRef> = accounts[partyId].orEmpty()

    override fun cardsOf(partyId: UUID): List<CardRef> = cards[partyId].orEmpty()

    fun currentAndSavings(partyId: UUID, currency: String = "CZK"): Pair<UUID, UUID> {
        val current = AccountRef(UUID.randomUUID(), partyId, "CURRENT", currency, "ACTIVE", null)
        val savings = AccountRef(UUID.randomUUID(), partyId, "SAVINGS", currency, "ACTIVE", null)
        accounts[partyId] = listOf(current, savings)
        return current.accountId to savings.accountId
    }
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeInterest : InterestPort {
    val calls = mutableListOf<Pair<UUID, Int>>()

    @Volatile var failure: RuntimeException? = null

    override fun addRateAdjustment(
        accountId: UUID,
        deltaBps: Int,
        validFrom: LocalDate,
        validTo: LocalDate,
        sourceRef: String,
        approvalRef: String,
    ): String {
        failure?.let { throw it }
        calls += accountId to deltaBps
        return "adj-$sourceRef"
    }

    override fun cancelRateAdjustment(accountId: UUID, adjustmentId: String) = Unit
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeLending : LendingPort {
    val loans = ConcurrentHashMap<UUID, LoanRef>()

    override fun loan(partyId: UUID, loanId: UUID): LoanRef =
        loans[loanId]?.takeIf { it.partyId == partyId } ?: throw DownstreamRejected("LOAN_UNKNOWN", "no loan")

    override fun preview(loanId: UUID, deltaBps: Int, months: Int): Pair<RateTerms, RateTerms> =
        RateTerms(BigDecimal("8.90"), BigDecimal("9.30"), BigDecimal("4100"), "CZK") to
            RateTerms(BigDecimal("8.40"), BigDecimal("8.77"), BigDecimal("4080"), "CZK")

    override fun addRateAdjustment(
        loanId: UUID,
        deltaBps: Int,
        validFrom: LocalDate,
        validTo: LocalDate,
        sourceRef: String,
        approvalRef: String,
    ): String {
        if ((loans[loanId]?.daysPastDue ?: 0) >
            ARREARS_LIMIT
        ) {
            throw DownstreamRejected("CREDIT_COUPON_BLOCKED", "loan in arrears")
        }
        return "loan-adj-$sourceRef"
    }

    companion object {
        const val ARREARS_LIMIT = 30
    }
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeCash : CashCreditPort {
    val credits = mutableListOf<Triple<UUID, BigDecimal, String>>()

    override fun credit(
        partyId: UUID,
        accountId: UUID,
        amount: BigDecimal,
        currency: String,
        reason: String,
        sourceRef: String,
    ): String {
        credits += Triple(accountId, amount, reason)
        return "txn-$sourceRef"
    }
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeGl : GlPostingPort {
    val postings = mutableListOf<Pair<Market, BigDecimal>>()

    @Volatile var down = false

    override fun postLiabilityDelta(
        date: LocalDate,
        market: Market,
        currency: String,
        delta: BigDecimal,
        sourceRef: String,
    ): String {
        if (down) throw DownstreamUnavailable("ledger down")
        postings += market to delta
        return "je-${postings.size}"
    }
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeIncentive : IncentivePort {
    val offers = ConcurrentHashMap<String, UUID>()
    private val used = ConcurrentHashMap.newKeySet<String>()

    override fun redeem(partyId: UUID, code: String, context: String): UUID {
        val offer = offers[code] ?: throw DownstreamRejected("CODE_UNKNOWN", "unknown code")
        if (!used.add("$partyId:$code")) throw DownstreamRejected("CODE_USED", "already used")
        return offer
    }
}
