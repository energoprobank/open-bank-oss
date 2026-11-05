// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.integration

import com.openbank.rewards.application.ChangeKind
import com.openbank.rewards.application.ChangePayload
import com.openbank.rewards.application.CustomerService
import com.openbank.rewards.application.FulfilmentService
import com.openbank.rewards.application.Jobs
import com.openbank.rewards.application.LoanRef
import com.openbank.rewards.application.RewardsStore
import com.openbank.rewards.application.StaffService
import com.openbank.rewards.domain.CouponState
import com.openbank.rewards.domain.EntryType
import com.openbank.rewards.domain.Market
import com.openbank.rewards.domain.MissionState
import com.openbank.rewards.domain.RewardsRuleException
import com.openbank.rewards.domain.TargetKind
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import jakarta.enterprise.inject.Any as AnyBean

/**
 * The engine end to end against a real Postgres: ledger, missions, coupons, sagas, maker-checker,
 * consumers (in-memory channels) and the customer HTTP contract. Downstream bank services are
 * fakes (TestSupport.kt); their own behaviour is tested in their services.
 */
@QuarkusTest
@QuarkusTestResource(RewardsPostgresTestResource::class)
class RewardsEngineIT {
    @Inject lateinit var customers: CustomerService

    @Inject lateinit var fulfilment: FulfilmentService

    @Inject lateinit var staff: StaffService

    @Inject lateinit var jobs: Jobs

    @Inject lateinit var store: RewardsStore

    @Inject lateinit var directory: FakeDirectory

    @Inject lateinit var interest: FakeInterest

    @Inject lateinit var lending: FakeLending

    @Inject lateinit var cash: FakeCash

    @Inject lateinit var gl: FakeGl

    @Inject lateinit var incentive: FakeIncentive

    @Inject lateinit var ds: DataSource

    @AnyBean
    @Inject
    lateinit var connector: InMemoryConnector

    private fun grant(party: UUID, flux: Int) {
        val cr = staff.submit(ChangeKind.GRANT, """{"partyId":"$party","flux":$flux}""", "test grant", "maker@bank")
        staff.approve(cr.requestId, "checker@bank", ChangePayload.Grant(party, flux, ""))
    }

    private fun count(sql: String, vararg args: Any?): Long = ds.connection.use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    @Test
    fun `M-ONB-1 completes on first contact for an active account and its claim issues a half-charged coupon`() {
        val party = UUID.randomUUID()
        directory.currentAndSavings(party)
        val missions = customers.missions(party)
        assertThat(missions.claimable.map { it.def.missionId }).contains("M-ONB-1")

        val result = customers.claim(party, "M-ONB-1")
        assertThat(result.coupon!!.first.couponDefId).isEqualTo("BILL_CASHBACK_5")
        assertThat(result.coupon!!.first.preCharge).isEqualTo(30)
        assertThat(
            customers.missions(party).history.single {
                it.def.missionId == "M-ONB-1"
            }.state,
        ).isEqualTo(MissionState.CLAIMED)
        assertThatThrownBy { customers.claim(party, "M-ONB-1") }.isInstanceOf(RewardsRuleException::class.java)
        // Pre-charge is valued at issue: it is in the liability (coupon holdings), not in the balance.
        assertThat(store.tx { it.liabilityFlux(Market.CZ) }.second).isGreaterThanOrEqualTo(30)
    }

    @Test
    fun `a manual grant needs a second operator`() {
        val party = UUID.randomUUID()
        directory.currentAndSavings(party)
        val cr = staff.submit(ChangeKind.GRANT, """{"partyId":"$party","flux":40}""", "goodwill", "maker@bank")
        assertThatThrownBy { staff.approve(cr.requestId, "MAKER@bank", ChangePayload.Grant(party, 40, "")) }
            .hasFieldOrPropertyWithValue("code", "FOUR_EYES")
        staff.approve(cr.requestId, "checker@bank", ChangePayload.Grant(party, 40, ""))
        assertThat(customers.summary(party).balance).isEqualTo(40)
    }

    @Test
    fun `charging, the Deposit Rate Boost saga and the burn`() {
        val party = UUID.randomUUID()
        val (_, savings) = directory.currentAndSavings(party)
        grant(party, 200)
        val (coupon, _) = customers.addCoupon(party, "DEPOSIT_BOOST")
        assertThat(coupon.state).isEqualTo(CouponState.CHARGING)

        val charged = customers.charge(party, coupon.couponId, 135, "k-${UUID.randomUUID()}")
        assertThat(charged.chargedOwn).describedAs("capacity 80").isEqualTo(80)
        assertThat(charged.coupon.first.state).isEqualTo(CouponState.READY)
        assertThat(charged.balance).isEqualTo(120)

        val used = fulfilment.use(party, coupon.couponId, TargetKind.SAVINGS_ACCOUNT, savings.toString())
        assertThat(used.coupon.first.state).isEqualTo(CouponState.USED)
        assertThat(interest.calls).contains(savings to 50)
        assertThat(
            count(
                "select count(*) from ledger_entry where party_id = ? and entry_type = 'BURN' and coupon_flux = -80",
                party,
            ),
        ).isEqualTo(1)
        // A retry of the same use answers with the same saga instead of a second command.
        assertThat(
            fulfilment.use(
                party,
                coupon.couponId,
                TargetKind.SAVINGS_ACCOUNT,
                savings.toString(),
            ).fulfilment.fulfilmentId,
        )
            .isEqualTo(used.fulfilment.fulfilmentId)
        assertThat(interest.calls.count { it.first == savings }).isEqualTo(1)
    }

    @Test
    fun `a refused benefit compensates the coupon back to READY`() {
        val party = UUID.randomUUID()
        val (_, savings) = directory.currentAndSavings(party)
        grant(party, 80)
        val (coupon, _) = customers.addCoupon(party, "DEPOSIT_BOOST")
        customers.charge(party, coupon.couponId, 80, "k-${UUID.randomUUID()}")
        interest.failure = com.openbank.rewards.application.DownstreamRejected("ACCOUNT_CLOSED", "closed")
        try {
            assertThatThrownBy {
                fulfilment.use(party, coupon.couponId, TargetKind.SAVINGS_ACCOUNT, savings.toString())
            }
                .hasFieldOrPropertyWithValue("code", "ACCOUNT_CLOSED")
        } finally {
            interest.failure = null
        }
        assertThat(store.tx { it.coupon(coupon.couponId) }!!.state).isEqualTo(CouponState.READY)
    }

    @Test
    fun `AC-RW-05 an expired coupon returns own FLUX and loses the bonus`() {
        val party = UUID.randomUUID()
        directory.currentAndSavings(party)
        grant(party, 40)
        val (coupon, _) = customers.addCoupon(party, "BILL_CASHBACK_5")
        customers.charge(party, coupon.couponId, 40, "k-${UUID.randomUUID()}")
        store.tx { tx ->
            val c = tx.coupon(coupon.couponId)!!
            tx.updateCoupon(c.copy(chargedBonus = 10, expiresAt = Instant.now().minusSeconds(60)))
        }
        jobs.expireCoupons()
        assertThat(store.tx { it.coupon(coupon.couponId) }!!.state).isEqualTo(CouponState.EXPIRED)
        assertThat(customers.summary(party).balance).isEqualTo(40)
        assertThat(
            count(
                "select count(*) from ledger_entry where party_id = ? and entry_type = 'EXPIRE' and coupon_flux = -10",
                party,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `Card Week through the card-processing channel, merchants joined from the authorisation`() {
        val party = UUID.randomUUID()
        directory.currentAndSavings(party)
        customers.summary(party)
        val source = connector.source<String>("card-processing-in")
        listOf("Albert", "Albert", "Lidl", "Billa", "Albert").forEach { merchant ->
            val auth = UUID.randomUUID().toString()
            source.send(
                """{"eventType":"card.authorised.v1","authorizationId":"$auth","partyId":"$party","merchantName":"$merchant","currencyCode":"CZK"}""",
            )
            source.send(
                """{"eventType":"card.cleared.v1","authorizationId":"$auth","clearedAmountMinorUnits":12000,"currencyCode":"CZK","fullyCleared":true}""",
            )
        }
        awaitTrue(Duration.ofSeconds(20)) { customers.missions(party).claimable.any { it.def.missionId == "M-CARD-3" } }
        assertThat(customers.missions(party).claimable.map { it.def.missionId }).contains("M-CARD-2", "M-CARD-3")
        assertThat(customers.claim(party, "M-CARD-3").balance).isEqualTo(25)
    }

    @Test
    fun `a cleared event that overtakes its authorisation is parked, then counted`() {
        val party = UUID.randomUUID()
        directory.currentAndSavings(party)
        customers.summary(party)
        val source = connector.source<String>("card-processing-in")
        val auth = UUID.randomUUID().toString()
        source.send(
            """{"eventType":"card.cleared.v1","authorizationId":"$auth","clearedAmountMinorUnits":9900,"currencyCode":"CZK","fullyCleared":true}""",
        )
        source.send(
            """{"eventType":"card.authorised.v1","authorizationId":"$auth","partyId":"$party","merchantName":"Albert","currencyCode":"CZK"}""",
        )
        awaitTrue(Duration.ofSeconds(20)) { customers.missions(party).claimable.any { it.def.missionId == "M-CARD-2" } }
        assertThat(
            count(
                "select count(*) from behaviour_fact where party_id = ? and fact_type = 'CARD_PAYMENT' and ref = 'Albert'",
                party,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `G-CR-01 a credit fact never reaches a mission, and lending only feeds on-time repayments`() {
        val party = UUID.randomUUID()
        directory.currentAndSavings(party)
        customers.summary(party)
        val source = connector.source<String>("lending-in")
        val loan = UUID.randomUUID()
        source.send("""{"eventType":"loan.disbursed","loanId":"$loan","partyId":"$party","amount":100000}""")
        source.send(
            """{"eventType":"loan.instalment_paid","loanId":"$loan","partyId":"$party","instalmentNumber":1,"onTime":false}""",
        )
        source.send(
            """{"eventType":"loan.instalment_paid","loanId":"$loan","partyId":"$party","instalmentNumber":2,"onTime":true}""",
        )
        awaitTrue(Duration.ofSeconds(20)) {
            customers.missions(party).claimable.any { it.def.missionId == "M-REPAY-1" }
        }
        assertThat(
            count("select count(*) from behaviour_fact where party_id = ?", party),
        ).describedAs("account activation + one on-time instalment").isEqualTo(2)
    }

    @Test
    fun `referral vests on qualification and releases both sides on the referee's first funding`() {
        val referrer = UUID.randomUUID()
        val referee = UUID.randomUUID()
        directory.currentAndSavings(referrer)
        val (refereeCurrent, _) = directory.currentAndSavings(referee)
        val invite = UUID.randomUUID()
        connector.source<String>(
            "referral-in",
        ).send(
            """{"eventType":"Qualified","inviteId":"$invite","referrerPartyId":"$referrer","refereePartyId":"$referee"}""",
        )
        awaitTrue(Duration.ofSeconds(20)) { customers.referrals(referee).isNotEmpty() }
        assertThat(customers.referrals(referrer).single().state.name).isEqualTo("VESTED")

        val tx = UUID.randomUUID()
        val transactions = connector.source<String>("transactions-in")
        transactions.send(
            """{"eventType":"TransactionInitiated","aggregateId":"$tx","type":"CREDIT","targetAccountId":"$refereeCurrent","amount":300,"currencyCode":"CZK","rail":"DOMESTIC","instructionType":"ONE_OFF"}""",
        )
        transactions.send("""{"eventType":"TransactionCompleted","aggregateId":"$tx"}""")
        awaitTrue(Duration.ofSeconds(20)) {
            customers.missions(referrer).claimable.any { it.def.missionId == "M-REF-1" }
        }
        assertThat(customers.claim(referrer, "M-REF-1").balance).isEqualTo(250)
        assertThat(customers.claim(referee, "M-REF-1-REFEREE").balance).isEqualTo(100)
        assertThat(customers.missions(referee).history.map { it.def.missionId }).contains("M-REF-1-REFEREE")
    }

    @Test
    fun `migration is idempotent per party and source`() {
        val party = UUID.randomUUID()
        directory.currentAndSavings(party)
        repeat(2) {
            val cr = staff.submit(
                ChangeKind.MIGRATION,
                """{"items":[{"partyId":"$party","flux":1240,"sourceRef":"$party"}]}""",
                "Listky import",
                "maker@bank",
            )
            staff.approve(
                cr.requestId,
                "checker@bank",
                ChangePayload.Migration(listOf(ChangePayload.MigrationItem(party, 1240, party.toString()))),
            )
        }
        assertThat(customers.summary(party).balance).isEqualTo(1240)
        assertThat(
            count(
                "select count(*) from ledger_entry where party_id = ? and entry_type = ?",
                party,
                EntryType.MIGRATION.name,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `instalment relief pays half an instalment, capped, to the current account`() {
        val party = UUID.randomUUID()
        val (current, _) = directory.currentAndSavings(party)
        val loan = UUID.randomUUID()
        lending.loans[loan] = LoanRef(loan, party, "CZK", BigDecimal("4100"), null, 0, "ACTIVE")
        grant(party, 120)
        val (coupon, _) = customers.addCoupon(party, "INSTALMENT_RELIEF")
        assertThat(
            coupon.state,
        ).describedAs("credit coupons never start charging on their own").isEqualTo(CouponState.ISSUED)
        customers.charge(party, coupon.couponId, 120, "k-${UUID.randomUUID()}")
        val preview = fulfilment.preview(party, coupon.couponId, TargetKind.LOAN, loan.toString())
        assertThat(preview.after!!.instalment).isEqualByComparingTo("3600")
        fulfilment.use(party, coupon.couponId, TargetKind.LOAN, loan.toString())
        assertThat(cash.credits).contains(Triple(current, BigDecimal("500.00"), "INSTALMENT_RELIEF"))
    }

    @Test
    fun `a promo code pays its offer's reward once`() {
        val party = UUID.randomUUID()
        directory.currentAndSavings(party)
        val offer = UUID.randomUUID()
        incentive.offers["EPWELCOME"] = offer
        val cr = staff.submit(
            ChangeKind.OFFER_REWARD,
            """{"offerId":"$offer","market":"CZ","reward":{"flux":50},"budgetLineId":"OFFER-CZ"}""",
            "onboarding code",
            "maker@bank",
        )
        staff.approve(
            cr.requestId,
            "checker@bank",
            ChangePayload.OfferChange(
                com.openbank.rewards.application.OfferReward(offer, Market.CZ, """{"flux":50}""", "OFFER-CZ"),
            ),
        )
        assertThat(customers.claimCode(party, "epwelcome").first!!.flux).isEqualTo(50)
        assertThatThrownBy { customers.claimCode(party, "EPWELCOME") }.hasFieldOrPropertyWithValue("code", "CODE_USED")
    }

    @Test
    fun `the daily liability posting books only the change`() {
        val party = UUID.randomUUID()
        directory.currentAndSavings(party)
        grant(party, 400)
        val first = jobs.postLiability().single { it.market == Market.CZ }
        assertThat(first.glRef).isNotNull()
        val again = jobs.postLiability().single { it.market == Market.CZ }
        assertThat(again.postedDelta).isEqualByComparingTo(BigDecimal.ZERO)
        grant(party, 4)
        assertThat(jobs.postLiability().single { it.market == Market.CZ }.postedDelta).isEqualByComparingTo("1.00")
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `HTTP contract - summary shape, idempotent claim replay, 409 code body`() {
        val party = UUID.randomUUID()
        directory.currentAndSavings(party)
        given().get("/api/v1/rewards/parties/$party/summary").then().statusCode(200)
            .body("market", equalTo("CZ")).body("level.code", equalTo("SPARK")).body("counts.claimable", equalTo(1))
            .body("fluxValue.amount", equalTo("0.25"))
        val key = UUID.randomUUID().toString()
        val first = given().header(
            "Idempotency-Key",
            key,
        ).contentType("application/json").post("/api/v1/rewards/parties/$party/missions/M-ONB-1/claim")
            .then().statusCode(200).extract().asString()
        val replay = given().header(
            "Idempotency-Key",
            key,
        ).contentType("application/json").post("/api/v1/rewards/parties/$party/missions/M-ONB-1/claim")
            .then().statusCode(200).extract().asString()
        assertThat(replay).isEqualTo(first)
        given().header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
            .post(
                "/api/v1/rewards/parties/$party/missions/M-ONB-1/claim",
            ).then().statusCode(409).body("code", equalTo("MISSION_NOT_CLAIMABLE"))
        given().contentType(
            "application/json",
        ).body("""{"flux":5}""").post("/api/v1/rewards/parties/$party/coupons/${UUID.randomUUID()}/charge")
            .then().statusCode(400)
    }

    private fun awaitTrue(timeout: Duration, check: () -> Boolean) {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            if (runCatching(check).getOrDefault(false)) return
            Thread.sleep(POLL_MS)
        }
        assertThat(check()).describedAs("condition within $timeout").isTrue()
    }

    companion object {
        const val POLL_MS = 200L
    }
}
