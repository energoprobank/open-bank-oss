// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class RewardsDomainTest {
    private val party = UUID.randomUUID()
    private val t0 = Instant.parse("2026-11-04T10:00:00Z")
    private val cz =
        MarketParams(
            Market.CZ,
            "CZK",
            BigDecimal("0.25"),
            BigDecimal("250"),
            BigDecimal("50"),
            BigDecimal("250"),
            "cs-CZ",
        )
    private val zone: ZoneId = Market.CZ.zone

    private fun def(capacity: Int = 150) = CouponDef(
        "BILL_CASHBACK_10", Market.CZ, BenefitType.BILL_CASHBACK, CouponCategory.BILLS,
        BenefitParams(
            percent = 10,
        ),
        Rarity.GOLD,
        capacity, 90, 2, true, "ENG-CZ", true, 10,
    )

    private fun coupon(capacity: Int = 150, own: Int = 0, state: CouponState = CouponState.CHARGING) =
        Coupon.issue(def(capacity), party, 0, "t", t0).copy(chargedOwn = own, state = state)

    @Nested
    inner class ChargingRules {
        @Test
        fun `AC-RW-03 the customer never pays for capacity the coupon does not have`() {
            // Balance 135, capacity 150 with 60 charged, the customer charges 135.
            val plan = Charging.plan(
                coupon(own = 60),
                requested = 135,
                balance = 135,
                multiplier = null,
                bonusCapLeft = 0,
            )
            assertThat(plan).isEqualTo(ChargePlan(own = 90, bonus = 0))
            val after = Charging.apply(coupon(own = 60), plan)
            assertThat(after.state).isEqualTo(CouponState.READY)
            assertThat(after.charged).isEqualTo(150)
        }

        @Test
        fun `AC-RW-04 a x2 promotion fills the coupon twice as fast and only own FLUX moves`() {
            val plan = Charging.plan(
                coupon(capacity = 200),
                requested = 25,
                balance = 100,
                multiplier = BigDecimal("2"),
                bonusCapLeft = 100,
            )
            assertThat(plan).isEqualTo(ChargePlan(own = 25, bonus = 25))
        }

        @Test
        fun `the promotion bonus stops at the per-party cap`() {
            val plan = Charging.plan(
                coupon(capacity = 200),
                requested = 50,
                balance = 100,
                multiplier = BigDecimal("3"),
                bonusCapLeft = 10,
            )
            assertThat(plan.bonus).isEqualTo(10)
        }

        @Test
        fun `own FLUX needed at a multiplier is rounded up and never overfills`() {
            val c = coupon(capacity = 60, own = 55)
            val plan = Charging.plan(c, requested = 50, balance = 50, multiplier = BigDecimal("2"), bonusCapLeft = 100)
            assertThat(plan.own + plan.bonus).isLessThanOrEqualTo(5)
            assertThat(plan.own).isEqualTo(3)
        }

        @Test
        fun `an empty balance or a used coupon cannot be charged`() {
            assertThatThrownBy { Charging.plan(coupon(), 10, 0, null, 0) }.hasMessageContaining("balance")
            assertThatThrownBy { Charging.plan(coupon(state = CouponState.USED), 10, 10, null, 0) }
                .isInstanceOf(RewardsRuleException::class.java)
        }

        @Test
        fun `a fully pre-charged coupon is issued ready`() {
            val c = Coupon.issue(def(80), party, 100, "s", t0)
            assertThat(c.state).isEqualTo(CouponState.READY)
            assertThat(c.preCharge).isEqualTo(80)
            assertThat(Coupon.issue(def(80), party, 40, "s", t0).preCharge).isEqualTo(32)
        }
    }

    @Nested
    inner class Ledger {
        @Test
        fun `lots are consumed oldest first`() {
            val a = Lot(UUID.randomUUID(), party, 50, 20, t0)
            val b = Lot(UUID.randomUUID(), party, 50, 50, t0.plusSeconds(60))
            assertThat(
                FifoLots.allocate(listOf(b, a), 30),
            ).containsExactly(LotDebit(a.lotId, 20), LotDebit(b.lotId, 10))
        }

        @Test
        fun `an uncovered amount is refused`() {
            assertThatThrownBy { FifoLots.allocate(listOf(Lot(UUID.randomUUID(), party, 5, 5, t0)), 6) }
                .isInstanceOf(RewardsRuleException::class.java)
        }
    }

    @Nested
    inner class Levels {
        @Test
        fun `levels follow lifetime own charged FLUX`() {
            assertThat(Level.forLifetime(0)).isEqualTo(Level.SPARK)
            assertThat(Level.forLifetime(99)).isEqualTo(Level.SPARK)
            assertThat(Level.forLifetime(100)).isEqualTo(Level.CELL)
            assertThat(Level.forLifetime(1500)).isEqualTo(Level.POWER_PLANT)
            assertThat(Level.POWER_PLANT.next).isNull()
        }
    }

    @Nested
    inner class Missions {
        private val cardWeek = MissionDef(
            "M-CARD-3", 1, Market.CZ, MissionKind.RECURRING, Cadence.WEEKLY,
            MissionRule(
                FactType.CARD_PAYMENT,
                count = 5,
                minAmount = MinAmount.CARD,
                distinctMerchants = 3,
            ),
            Reward(flux = 25),
            "cards", 70, "ENG-CZ", true,
        )

        private fun pay(amount: String, merchant: String, at: Instant = t0) = BehaviourFact(
            "CARD_PAYMENT:${UUID.randomUUID()}",
            party,
            FactType.CARD_PAYMENT,
            at,
            BigDecimal(amount),
            "CZK",
            merchant,
        )

        @Test
        fun `Card Week needs five payments of at least the minimum at three merchants`() {
            assertThat(MissionEngine.matches(cardWeek, pay("49.99", "a"), cz)).isFalse()
            var i = MissionEngine.newInstance(cardWeek, party, t0, zone)
            repeat(5) { i = MissionEngine.apply(cardWeek, i, pay("60", if (it < 4) "a" else "b"), zone) }
            assertThat(i.state).describedAs("five payments, two merchants").isEqualTo(MissionState.IN_PROGRESS)
            i = MissionEngine.apply(cardWeek, i, pay("60", "c"), zone)
            assertThat(i.state).isEqualTo(MissionState.CLAIM_PENDING)
            assertThat(i.cycle).isEqualTo("2026-W45")
            assertThat(i.windowEndsAt).isEqualTo(Instant.parse("2026-11-08T23:00:00Z"))
        }

        @Test
        fun `bill rhythm counts consecutive calendar months`() {
            assertThat(MissionEngine.longestRun(setOf("2026-09", "2026-10", "2026-12"))).isEqualTo(2)
            assertThat(MissionEngine.longestRun(setOf("2026-11", "2026-12", "2027-01"))).isEqualTo(3)
            assertThat(
                MissionEngine.isComplete(
                    MissionRule(FactType.BILL_PAID, consecutiveMonths = 3),
                    Progress(months = setOf("2026-10", "2026-11", "2026-12")),
                ),
            )
                .isTrue()
        }

        @Test
        fun `a quiz mission only counts its own lesson`() {
            val quiz = cardWeek.copy(
                missionId = "M-ONB-3",
                cadence = Cadence.ONCE,
                rule = MissionRule(FactType.QUIZ_PASSED, ref = "deposit-guarantee"),
            )
            assertThat(
                MissionEngine.matches(
                    quiz,
                    BehaviourFact("q", party, FactType.QUIZ_PASSED, t0, ref = "deposit-guarantee"),
                    cz,
                ),
            ).isTrue()
            assertThat(
                MissionEngine.matches(quiz, BehaviourFact("q", party, FactType.QUIZ_PASSED, t0, ref = "other"), cz),
            ).isFalse()
        }

        @Test
        fun `claim needs a completed mission and is idempotent once claimed`() {
            val pending = MissionEngine.newInstance(
                cardWeek,
                party,
                t0,
                zone,
            ).copy(state = MissionState.CLAIM_PENDING, completedAt = t0)
            val claimed = MissionEngine.claim(pending, t0)
            assertThat(claimed.state).isEqualTo(MissionState.CLAIMED)
            assertThat(MissionEngine.claim(claimed, t0)).isEqualTo(claimed)
            assertThatThrownBy { MissionEngine.claim(MissionEngine.newInstance(cardWeek, party, t0, zone), t0) }
                .isInstanceOf(RewardsRuleException::class.java)
            assertThat(pending.autoClaimAt).isEqualTo(t0.plusSeconds(30L * 24 * 3600))
        }

        @Test
        fun `cycle keys follow the market's calendar`() {
            val newYearsEvePrague = Instant.parse("2026-12-31T23:30:00Z") // already 2027 in Prague
            assertThat(Cycles.keyFor(Cadence.MONTHLY, newYearsEvePrague, zone)).isEqualTo("2027-01")
            assertThat(Cycles.keyFor(Cadence.YEARLY, newYearsEvePrague, zone)).isEqualTo("2027")
            assertThat(Cycles.keyFor(Cadence.ONCE, newYearsEvePrague, zone)).isEqualTo("once")
        }
    }

    @Nested
    inner class Guardrails {
        @Test
        fun `G-CR-01 credit facts are deny-listed in code`() {
            listOf(
                FactType.LOAN_APPLIED,
                FactType.LOAN_APPROVED,
                FactType.LOAN_DISBURSED,
                FactType.CREDIT_LIMIT_INCREASED,
                FactType.CREDIT_UTILISED,
                FactType.MINIMUM_PAYMENT_MADE,
            )
                .forEach { assertThat(CreditGuardrail.admissible(it)).describedAs(it.name).isFalse() }
            assertThat(CreditGuardrail.admissible(FactType.INSTALMENT_PAID_ON_TIME)).isTrue()
        }

        @Test
        fun `credit coupons are the loan instruments only`() {
            assertThat(
                BenefitType.entries.filter {
                    it.credit
                },
            ).containsExactlyInAnyOrder(BenefitType.LOAN_RATE_REDUCTION, BenefitType.INSTALMENT_RELIEF)
        }

        @Test
        fun `market follows the account currency`() {
            assertThat(Market.forCurrency("EUR")).isEqualTo(Market.BG)
            assertThat(Market.forCurrency("CZK")).isEqualTo(Market.CZ)
            assertThat(cz.valueOf(135)).isEqualByComparingTo("33.75")
        }
    }
}
