// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.application

import com.openbank.rewards.domain.BehaviourFact
import com.openbank.rewards.domain.Charging
import com.openbank.rewards.domain.Coupon
import com.openbank.rewards.domain.CouponDef
import com.openbank.rewards.domain.CouponState
import com.openbank.rewards.domain.Cycles
import com.openbank.rewards.domain.EntrySource
import com.openbank.rewards.domain.EntryType
import com.openbank.rewards.domain.FactType
import com.openbank.rewards.domain.FifoLots
import com.openbank.rewards.domain.LedgerEntry
import com.openbank.rewards.domain.Level
import com.openbank.rewards.domain.Membership
import com.openbank.rewards.domain.MembershipStatus
import com.openbank.rewards.domain.MissionDef
import com.openbank.rewards.domain.MissionEngine
import com.openbank.rewards.domain.MissionInstance
import com.openbank.rewards.domain.MissionState
import com.openbank.rewards.domain.ProgrammeSwitch
import com.openbank.rewards.domain.Progress
import com.openbank.rewards.domain.RewardsNotFoundException
import com.openbank.rewards.domain.RewardsRuleException
import com.openbank.rewards.domain.TargetKind
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.util.UUID

data class MissionView(
    val def: MissionDef,
    val instance: MissionInstance?,
    val state: MissionState,
    val current: Int,
    val target: Int,
)

data class MissionsView(val active: List<MissionView>, val claimable: List<MissionView>, val history: List<MissionView>)

data class SummaryView(
    val membership: Membership,
    val params: com.openbank.rewards.domain.MarketParams,
    val balance: Int,
    val chargedUnused: Int,
    val level: Level,
    val charging: Pair<Coupon, CouponDef>?,
    val claimable: Int,
    val ready: Int,
    val promotion: Promotion?,
    val nextExpiry: Coupon?,
)

data class ClaimResult(
    val mission: MissionView,
    val entries: List<LedgerEntry>,
    val coupon: Pair<Coupon, CouponDef>?,
    val balance: Int,
)

data class ChargeResult(
    val coupon: Pair<Coupon, CouponDef>,
    val chargedOwn: Int,
    val chargedBonus: Int,
    val balance: Int,
    val levelReached: Level?,
)

data class CatalogueItem(val def: CouponDef, val locked: Boolean)

data class LessonView(val lessonId: String, val missionId: String, val questions: List<Pair<String, List<String>>>)

/** The in-app lessons (M-ONB-3); content is data, scoring is here so the answer key never leaves the engine. */
interface LessonCatalog {
    fun all(language: String): List<LessonView>
    fun score(lessonId: String, answers: List<Int>): Pair<Int, Boolean>
    fun missionFor(lessonId: String): String
}

/** Customer commands and reads, called through customer-edge with the authoritative partyId. */
@ApplicationScoped
@Suppress("TooManyFunctions")
class CustomerService(
    private val store: RewardsStore,
    private val engine: RewardsEngine,
    private val lessons: LessonCatalog,
    private val incentive: IncentivePort,
) {

    fun summary(partyId: UUID): SummaryView {
        val m = engine.ensureMembership(partyId)
        return store.tx { tx ->
            val coupons = tx.coupons(partyId)
            val holding = coupons.filter { it.holding }
            val charging = coupons.firstOrNull { it.state == CouponState.CHARGING }
            SummaryView(
                membership = m,
                params = tx.marketParams(m.market),
                balance = tx.balance(partyId),
                chargedUnused = holding.sumOf { it.charged },
                level = m.level,
                charging = charging?.let { it to tx.couponDef(m.market, it.couponDefId)!! },
                claimable = tx.instancesOf(partyId).count { it.state == MissionState.CLAIM_PENDING },
                ready = coupons.count { it.state == CouponState.READY },
                promotion = tx.activePromotion(m.market, engine.now()),
                nextExpiry = holding.filter { it.expiresAt != null && it.charged > 0 }.minByOrNull { it.expiresAt!! },
            )
        }
    }

    @Suppress("CyclomaticComplexMethod") // one rule table; splitting hides it
    fun missions(partyId: UUID): MissionsView {
        val m = engine.ensureMembership(partyId)
        return store.tx { tx ->
            val now = engine.now()
            val defs = tx.missionDefs(m.market).filter { it.active }
            val byId = defs.associateBy { it.missionId }
            val instances = tx.instancesOf(partyId)
            val claimable = instances.filter {
                it.state == MissionState.CLAIM_PENDING
            }.mapNotNull { i -> byId[i.missionId]?.let { view(it, i) } }
            val history = instances.filter { i ->
                i.state == MissionState.CLAIMED ||
                    i.state == MissionState.LAPSED ||
                    (i.state == MissionState.IN_PROGRESS && i.windowEndsAt != null && !i.windowEndsAt.isAfter(now))
            }.sortedByDescending { it.claimedAt ?: it.windowEndsAt ?: it.startedAt }.take(HISTORY_LIMIT)
                .mapNotNull { i ->
                    byId[i.missionId]?.let { d ->
                        view(d, i).let { v ->
                            if (i.state ==
                                MissionState.IN_PROGRESS
                            ) {
                                v.copy(state = MissionState.LAPSED)
                            } else {
                                v
                            }
                        }
                    }
                }
            val active = defs.sortedBy { it.sortOrder }.mapNotNull { def ->
                val cycle = Cycles.keyFor(def.cadence, now, m.market.zone)
                val current = instances.firstOrNull { it.missionId == def.missionId && it.cycle == cycle }
                val oneTimeDone =
                    def.cadence.name == "ONCE" && current != null && current.state != MissionState.IN_PROGRESS
                when {
                    oneTimeDone -> null
                    current == null && def.rule.hidden -> null
                    current == null -> view(def, null)
                    current.state == MissionState.IN_PROGRESS -> view(def, current)
                    else -> null
                }
            }
            MissionsView(active, claimable, history)
        }
    }

    fun view(def: MissionDef, i: MissionInstance?): MissionView {
        val progress = i?.progress ?: Progress()
        return MissionView(
            def,
            i,
            i?.state ?: MissionState.NOT_STARTED,
            MissionEngine.current(def.rule, progress),
            def.rule.target,
        )
    }

    fun claim(partyId: UUID, missionId: String, auto: Boolean = false): ClaimResult {
        val m0 = engine.ensureMembership(partyId)
        return store.tx { tx ->
            val m = tx.membership(partyId, forUpdate = true)!!
            if (!auto) engine.requireActive(m)
            val pending = tx.instancesOf(partyId).filter { it.missionId == missionId }
            val instance = pending.filter { it.state == MissionState.CLAIM_PENDING }.minByOrNull { it.completedAt!! }
                ?: throw RewardsRuleException("MISSION_NOT_CLAIMABLE", "mission $missionId has nothing to claim")
            val def = tx.missionDefs(m0.market, includeInactive = true).first {
                it.missionId == missionId &&
                    it.version == instance.version
            }
            val claimed = MissionEngine.claim(instance, engine.now())
            tx.upsertInstance(claimed)
            val sourceRef = "mission:$missionId:${instance.cycle}"
            if (def.cadence.name ==
                "ROLLING"
            ) {
                tx.archiveRollingInstance(partyId, missionId, "R-${engine.now().epochSecond}")
            }
            val source = if (missionId.startsWith("M-REF-1")) EntrySource.REFERRAL else EntrySource.MISSION
            val (entry, coupon) = engine.payReward(tx, m, instance.reward, source, sourceRef, def.budgetLineId)
            tx.enqueue(
                OutboxEvent(
                    "mission_claimed",
                    partyId,
                    mapOf(
                        "missionId" to missionId,
                        "cycle" to instance.cycle,
                        "auto" to auto,
                    ),
                ),
                engine.now(),
            )
            ClaimResult(
                view(def, claimed),
                listOfNotNull(entry),
                coupon?.let {
                    it to
                        tx.couponDef(m.market, it.couponDefId)!!
                },
                tx.balance(partyId),
            )
        }
    }

    fun coupons(partyId: UUID): List<Pair<Coupon, CouponDef>> {
        val m = engine.ensureMembership(partyId)
        val since = engine.now().minus(java.time.Duration.ofDays(WALLET_HISTORY_DAYS))
        return store.tx { tx ->
            tx.coupons(partyId).filter { it.holding || (it.usedAt ?: it.expiresAt ?: it.issuedAt).isAfter(since) }
                .map { it to tx.couponDef(m.market, it.couponDefId)!! }
        }
    }

    fun catalogue(partyId: UUID): List<CatalogueItem> {
        val m = engine.ensureMembership(partyId)
        return store.tx { tx ->
            tx.couponDefs(m.market).filter { it.active && it.inCatalogue }.sortedBy { it.sortOrder }
                .map { CatalogueItem(it, locked = m.level.index < it.levelGate) }
        }
    }

    @Suppress("ThrowsCount") // one rule table; splitting hides it
    fun addCoupon(partyId: UUID, couponDefId: String): Pair<Coupon, CouponDef> {
        engine.ensureMembership(partyId)
        return store.tx { tx ->
            val m = tx.membership(partyId, forUpdate = true)!!
            engine.requireActive(m)
            val def = tx.couponDef(m.market, couponDefId)?.takeIf { it.active && it.inCatalogue }
                ?: throw RewardsNotFoundException("COUPON_DEF_UNKNOWN", couponDefId)
            if (m.level.index <
                def.levelGate
            ) {
                throw RewardsRuleException("LEVEL_LOCKED", "unlocks at level ${def.levelGate}")
            }
            val wallet = tx.coupons(partyId)
            if (wallet.any { it.couponDefId == couponDefId && it.holding }) {
                throw RewardsRuleException("COUPON_ALREADY_IN_WALLET", "this coupon is already in the wallet")
            }
            val coupon = engine.issueCoupon(
                tx,
                m,
                couponDefId,
                0,
                EntrySource.REDEMPTION,
                "catalogue:$couponDefId:${UUID.randomUUID()}",
            )
            // The first coupon starts charging by itself; credit coupons never do (G-CR-02: no pressure).
            val activated = if (!def.benefitType.credit &&
                wallet.none { it.state == CouponState.CHARGING } &&
                coupon.state == CouponState.ISSUED
            ) {
                coupon.copy(state = CouponState.CHARGING).also(tx::updateCoupon)
            } else {
                coupon
            }
            activated to def
        }
    }

    fun activate(partyId: UUID, couponId: UUID): Pair<Coupon, CouponDef> {
        engine.ensureMembership(partyId)
        return store.tx { tx ->
            val m = tx.membership(partyId, forUpdate = true)!!
            engine.requireActive(m)
            val coupon = owned(tx, partyId, couponId)
            val activated = activateInTx(tx, coupon)
            activated to tx.couponDef(m.market, coupon.couponDefId)!!
        }
    }

    private fun activateInTx(tx: RewardsTx, coupon: Coupon): Coupon {
        if (coupon.state == CouponState.CHARGING) return coupon
        if (coupon.state !=
            CouponState.ISSUED
        ) {
            throw RewardsRuleException("COUPON_NOT_CHARGEABLE", "coupon is ${coupon.state}")
        }
        // B5: free switching. The previous coupon keeps what it holds and goes back to the wallet.
        tx.coupons(coupon.partyId).filter {
            it.state == CouponState.CHARGING
        }.forEach { tx.updateCoupon(it.copy(state = CouponState.ISSUED)) }
        return coupon.copy(state = CouponState.CHARGING).also(tx::updateCoupon)
    }

    @Suppress("LongMethod") // one rule table; splitting hides it
    fun charge(partyId: UUID, couponId: UUID, requested: Int, idempotencyKey: String): ChargeResult {
        engine.ensureMembership(partyId)
        return store.tx { tx ->
            val m = tx.membership(partyId, forUpdate = true)!!
            engine.requireActive(m)
            engine.requireSwitch(tx, ProgrammeSwitch.CHARGE)
            val def0 = tx.couponDef(m.market, owned(tx, partyId, couponId).couponDefId)!!
            val coupon = activateInTx(tx, owned(tx, partyId, couponId, forUpdate = true))
            val now = engine.now()
            // G-CR-02: credit coupons are never in promotions or multipliers.
            val promotion = tx.activePromotion(m.market, now)?.takeUnless { def0.benefitType.credit }
            val params = tx.marketParams(m.market)
            val bonusCapLeft = promotion?.let { p ->
                val byCap = p.perPartyCap - tx.bonusReceived(partyId, p.promotionId)
                val line = tx.budgetLine(p.budgetLineId, forUpdate = true)
                val byBudget = line?.remaining?.divide(params.fluxValue, 0, java.math.RoundingMode.DOWN)?.toInt() ?: 0
                minOf(byCap, byBudget)
            } ?: 0
            val plan = Charging.plan(coupon, requested, tx.balance(partyId), promotion?.multiplier, bonusCapLeft)
            if (plan.own <= 0) throw RewardsRuleException("COUPON_FULL", "the coupon is already full")
            val chargeEntry = LedgerEntry(
                UUID.randomUUID(), partyId, EntryType.CHARGE, EntrySource.REDEMPTION, -plan.own, plan.own, couponId,
                "charge:$idempotencyKey", RewardsEngine.RULE_VERSION, null, m.market, now,
            )
            // The natural-key index is the race guard behind the REST idempotency record.
            if (!tx.insertEntry(
                    chargeEntry,
                )
            ) {
                throw RewardsRuleException("DUPLICATE_REQUEST", "this charge was already applied")
            }
            FifoLots.allocate(tx.openLots(partyId), plan.own).forEach { tx.debitLot(it.lotId, it.amount) }
            if (plan.bonus > 0 && promotion != null) {
                tx.insertEntry(
                    LedgerEntry(
                        UUID.randomUUID(),
                        partyId,
                        EntryType.CHARGE,
                        EntrySource.PROMOTION_BONUS,
                        0,
                        plan.bonus,
                        couponId,
                        "bonus:${promotion.promotionId}:$idempotencyKey",
                        RewardsEngine.RULE_VERSION,
                        promotion.budgetLineId,
                        m.market,
                        now,
                    ),
                )
                tx.consumeBudget(promotion.budgetLineId, params.valueOf(plan.bonus.toLong()))
            }
            val charged = Charging.apply(coupon, plan)
            tx.updateCoupon(charged)
            val before = m.level
            val updated = m.copy(lifetimeCharged = m.lifetimeCharged + plan.own, lastActivityAt = now)
            tx.updateMembership(updated)
            val reached = updated.level.takeIf { it != before }
            tx.enqueue(
                OutboxEvent(
                    "coupon_charged",
                    partyId,
                    engine.couponEvent(charged) + mapOf("own" to plan.own, "bonus" to plan.bonus),
                ),
                now,
            )
            if (charged.state ==
                CouponState.READY
            ) {
                tx.enqueue(OutboxEvent("coupon_ready", partyId, engine.couponEvent(charged)), now)
            }
            if (reached !=
                null
            ) {
                tx.enqueue(
                    OutboxEvent(
                        "level_reached",
                        partyId,
                        mapOf(
                            "level" to reached.name,
                            "index" to reached.index,
                        ),
                    ),
                    now,
                )
            }
            ChargeResult(charged to def0, plan.own, plan.bonus, tx.balance(partyId), reached)
        }
    }

    fun ledger(partyId: UUID, page: Int, size: Int): Pair<List<LedgerEntry>, Long> {
        engine.ensureMembership(partyId)
        return store.tx { tx -> tx.entries(partyId, page, size) to tx.countEntries(partyId) }
    }

    fun settings(partyId: UUID): Membership = engine.ensureMembership(partyId)

    fun updateSettings(
        partyId: UUID,
        participating: Boolean,
        quiet: Boolean,
        missions: Boolean,
        coupons: Boolean,
        expiry: Boolean,
    ): Membership {
        engine.ensureMembership(partyId)
        return store.tx { tx ->
            val m = tx.membership(partyId, forUpdate = true)!!
            val updated = m.copy(
                status = if (participating) MembershipStatus.ACTIVE else MembershipStatus.OPTED_OUT,
                quietMode = quiet,
                notifyMissions = missions,
                notifyCoupons = coupons,
                notifyExpiry = expiry,
            )
            tx.updateMembership(updated)
            updated
        }
    }

    /** Promo code (T01): incentive-service reserves and commits it; the engine pays the offer's reward. */
    fun claimCode(partyId: UUID, code: String): Pair<LedgerEntry?, Pair<Coupon, CouponDef>?> {
        val m0 = engine.ensureMembership(partyId)
        engine.requireActive(m0)
        val offerId = incentive.redeem(partyId, code.trim().uppercase(), "HUB")
        return store.tx { tx ->
            val m = tx.membership(partyId, forUpdate = true)!!
            val offer =
                tx.offerReward(offerId)
                    ?: throw RewardsRuleException("OFFER_HAS_NO_REWARD", "this code has no reward in the programme")
            val (entry, coupon) = engine.payReward(
                tx,
                m,
                engine.codec.reward(offer.rewardJson),
                EntrySource.PROMO_CODE,
                "offer:$offerId",
                offer.budgetLineId,
            )
            entry to coupon?.let { it to tx.couponDef(m.market, it.couponDefId)!! }
        }
    }

    fun lessons(language: String): List<LessonView> = lessons.all(language)

    /** Quiz answers are scored here; passing records QUIZ_PASSED, which completes M-ONB-3. */
    fun quiz(partyId: UUID, lessonId: String, answers: List<Int>): Triple<Boolean, Int, MissionView?> {
        val m = engine.ensureMembership(partyId)
        val (score, passed) = lessons.score(lessonId, answers)
        if (passed) {
            engine.ingest(
                BehaviourFact(
                    "QUIZ_PASSED:$lessonId:$partyId",
                    partyId,
                    FactType.QUIZ_PASSED,
                    engine.now(),
                    ref = lessonId,
                ),
            )
        }
        val mission = store.tx { tx ->
            val missionId = lessons.missionFor(lessonId)
            tx.missionDefs(m.market).firstOrNull { it.missionId == missionId }?.let { def ->
                view(def, tx.instancesOf(partyId).firstOrNull { it.missionId == missionId })
            }
        }
        return Triple(passed, score, mission)
    }

    fun referrals(partyId: UUID): List<ReferralReward> {
        engine.ensureMembership(partyId)
        return store.tx { tx -> tx.referralRewardsOf(partyId) }
    }

    fun owned(tx: RewardsTx, partyId: UUID, couponId: UUID, forUpdate: Boolean = false): Coupon =
        tx.coupon(couponId, forUpdate)?.takeIf { it.partyId == partyId }
            ?: throw RewardsNotFoundException("COUPON_UNKNOWN", couponId.toString())

    companion object {
        const val HISTORY_LIMIT = 50
        const val WALLET_HISTORY_DAYS = 90L

        fun targetKindOf(def: CouponDef): TargetKind = def.benefitType.target

        fun sum(values: List<BigDecimal>): BigDecimal = values.fold(BigDecimal.ZERO, BigDecimal::add)
    }
}
