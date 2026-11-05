// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.application

import com.openbank.rewards.domain.BehaviourFact
import com.openbank.rewards.domain.Coupon
import com.openbank.rewards.domain.CouponState
import com.openbank.rewards.domain.CreditGuardrail
import com.openbank.rewards.domain.Cycles
import com.openbank.rewards.domain.EntrySource
import com.openbank.rewards.domain.EntryType
import com.openbank.rewards.domain.FactType
import com.openbank.rewards.domain.LedgerEntry
import com.openbank.rewards.domain.Lot
import com.openbank.rewards.domain.Market
import com.openbank.rewards.domain.Membership
import com.openbank.rewards.domain.MissionEngine
import com.openbank.rewards.domain.MissionState
import com.openbank.rewards.domain.ProgrammeSwitch
import com.openbank.rewards.domain.Reward
import com.openbank.rewards.domain.RewardsNotFoundException
import com.openbank.rewards.domain.RewardsRuleException
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Serialisation the application needs without depending on a JSON library. */
interface Codec {
    fun write(value: Any?): String
    fun reward(json: String): Reward
}

/**
 * The engine's shared core: membership (with projection backfill), fact ingest into missions,
 * earning into the ledger, coupon issue and referral release. Every method taking a [RewardsTx]
 * runs inside the caller's transaction; the caller holds the party's membership lock.
 */
@ApplicationScoped
@Suppress("TooManyFunctions")
class RewardsEngine(
    private val store: RewardsStore,
    private val directory: BankDirectoryPort,
    val codec: Codec,
    val clock: Clock,
) {
    private val log = Logger.getLogger(RewardsEngine::class.java)

    fun now(): Instant = clock.instant()

    /**
     * Returns the party's membership, creating it on first contact. Programme membership is under
     * the programme T&C (spec: consent basis), default on. First contact backfills the account and
     * card projections from the bank's own services, because Kafka keeps only 7 days of events.
     */
    fun ensureMembership(partyId: UUID): Membership {
        store.tx { it.membership(partyId) }?.let { return it }
        val accounts = runCatching { directory.accountsOf(partyId) }
            .onFailure {
                log.warnf("account backfill for %s failed: %s", partyId, it.message)
            }.getOrDefault(emptyList())
        val cards = runCatching { directory.cardsOf(partyId) }
            .onFailure { log.warnf("card backfill for %s failed: %s", partyId, it.message) }.getOrDefault(emptyList())
        return store.tx { tx ->
            tx.membership(partyId, forUpdate = true) ?: run {
                accounts.forEach(tx::upsertAccount)
                cards.forEach(tx::upsertCard)
                val market = Market.forCurrency(
                    accounts.firstOrNull { it.accountType == "CURRENT" }?.currency ?: accounts.firstOrNull()?.currency,
                )
                val m = Membership.join(partyId, market, now())
                tx.insertMembership(m)
                backfillFacts(tx, m, accounts, cards)
                tx.membership(partyId, forUpdate = true)!!
            }
        }
    }

    /**
     * Re-reads the party's accounts and cards from the bank and re-derives the facts they imply
     * (operator action, e.g. after a source service was down at first contact). Idempotent: facts
     * have natural keys, so nothing is counted twice.
     */
    fun refreshProjections(partyId: UUID): Pair<Int, Int> {
        ensureMembership(partyId)
        val accounts = directory.accountsOf(partyId)
        val cards = directory.cardsOf(partyId)
        store.tx { tx ->
            accounts.forEach(tx::upsertAccount)
            cards.forEach(tx::upsertCard)
            backfillFacts(tx, tx.membership(partyId, forUpdate = true)!!, accounts, cards)
        }
        return accounts.size to cards.size
    }

    /** Membership inside an open transaction, without backfill (used when a second party is touched). */
    fun membershipInTx(tx: RewardsTx, partyId: UUID, fallbackMarket: Market): Membership =
        tx.membership(partyId, forUpdate = true)
            ?: Membership.join(partyId, fallbackMarket, now()).also(tx::insertMembership)

    private fun backfillFacts(tx: RewardsTx, m: Membership, accounts: List<AccountRef>, cards: List<CardRef>) {
        accounts.filter { it.accountType == "CURRENT" && it.status == "ACTIVE" }.forEach { a ->
            ingestInTx(
                tx,
                m,
                BehaviourFact(
                    "ACCOUNT_ACTIVATED:${a.accountId}",
                    m.partyId,
                    FactType.ACCOUNT_ACTIVATED,
                    a.openedAt ?: now(),
                ),
                null,
            )
        }
        cards.filter { it.status == "ACTIVE" }.forEach { c ->
            ingestInTx(
                tx,
                tx.membership(m.partyId)!!,
                BehaviourFact("CARD_ACTIVATED:${c.cardId}", m.partyId, FactType.CARD_ACTIVATED, now()),
                null,
            )
        }
    }

    /** Event-driven entry point: one fact, its own transaction. Returns false when nothing changed. */
    fun ingest(fact: BehaviourFact, payload: String? = null): Boolean {
        if (!CreditGuardrail.admissible(fact.type)) {
            log.debugf("credit fact %s refused by G-CR-01", fact.type)
            return false
        }
        ensureMembership(fact.partyId)
        return store.tx { tx -> ingestInTx(tx, tx.membership(fact.partyId, forUpdate = true)!!, fact, payload) }
    }

    fun ingestInTx(tx: RewardsTx, membership: Membership, fact: BehaviourFact, payload: String?): Boolean {
        if (!CreditGuardrail.admissible(fact.type)) return false
        if (!membership.active) return false
        if (!tx.insertFact(fact, payload, now())) return false
        val m = membership.copy(lastActivityAt = maxOf(membership.lastActivityAt, fact.occurredAt))
        tx.updateMembership(m)
        val params = tx.marketParams(m.market)
        val zone = m.market.zone
        for (def in tx.missionDefs(m.market).filter { it.active && MissionEngine.matches(it, fact, params) }) {
            val cycle = Cycles.keyFor(def.cadence, fact.occurredAt, zone)
            val existing = tx.instance(m.partyId, def.missionId, cycle, forUpdate = true)
            val base = existing ?: MissionEngine.newInstance(def, m.partyId, fact.occurredAt, zone)
            val updated = MissionEngine.apply(def, base, fact, zone)
            if (existing == null || updated != existing) tx.upsertInstance(updated)
            if (updated.state == MissionState.CLAIM_PENDING && base.state != MissionState.CLAIM_PENDING) {
                tx.enqueue(
                    OutboxEvent(
                        "mission_completed",
                        m.partyId,
                        mapOf(
                            "missionId" to def.missionId,
                            "version" to def.version,
                            "cycle" to updated.cycle,
                            "completedAt" to updated.completedAt,
                        ),
                    ),
                    now(),
                )
            }
        }
        if (fact.type == FactType.FUNDING_RECEIVED && fact.amount != null && fact.amount >= params.fundingMinimum) {
            releaseReferrals(tx, m, fact)
        }
        return true
    }

    /** Spec C4: the referee's first funding at or above the market minimum releases both sides. */
    private fun releaseReferrals(tx: RewardsTx, referee: Membership, fact: BehaviourFact) {
        for (r in tx.vestedReferralsForReferee(referee.partyId)) {
            for (side in tx.referralRewardsForInvite(r.inviteId).filter { it.state == ReferralState.VESTED }) {
                if (fact.occurredAt.isAfter(side.releaseBy)) continue
                tx.updateReferralReward(side.copy(state = ReferralState.RELEASED, releasedAt = now()))
                tx.enqueue(
                    OutboxEvent(
                        "referral_reward_released",
                        side.partyId,
                        mapOf(
                            "inviteId" to side.inviteId,
                            "side" to side.side,
                            "flux" to side.flux,
                        ),
                    ),
                    now(),
                )
                val type = if (side.side ==
                    ReferralSide.REFERRER
                ) {
                    FactType.REFERRAL_RELEASED_REFERRER
                } else {
                    FactType.REFERRAL_RELEASED_REFEREE
                }
                val member = if (side.partyId ==
                    referee.partyId
                ) {
                    tx.membership(referee.partyId)!!
                } else {
                    membershipInTx(tx, side.partyId, referee.market)
                }
                ingestInTx(
                    tx,
                    member,
                    BehaviourFact(
                        "$type:${side.inviteId}",
                        side.partyId,
                        type,
                        fact.occurredAt,
                        ref = side.inviteId.toString(),
                    ),
                    null,
                )
            }
        }
    }

    fun requireSwitch(tx: RewardsTx, switch: ProgrammeSwitch) {
        if (!tx.switchEnabled(
                switch,
            )
        ) {
            throw RewardsRuleException("PROGRAMME_PAUSED", "the programme has paused ${switch.name.lowercase()}")
        }
    }

    fun requireActive(m: Membership) {
        if (m.frozenReason !=
            null
        ) {
            throw RewardsRuleException("PROGRAMME_PAUSED", "rewards are paused for this customer")
        }
        if (!m.active) throw RewardsRuleException("NOT_A_MEMBER", "the customer has left the programme")
    }

    /** Credits [flux] to the balance as one lot. Idempotent: a repeated (type, source, sourceRef) is a no-op returning null. */
    @Suppress("LongParameterList")
    fun grant(
        tx: RewardsTx,
        m: Membership,
        flux: Int,
        source: EntrySource,
        sourceRef: String,
        budgetLineId: String,
        type: EntryType = EntryType.EARN,
    ): LedgerEntry? {
        requireSwitch(tx, ProgrammeSwitch.EARN)
        val value = tx.marketParams(m.market).valueOf(flux.toLong())
        val line =
            tx.budgetLine(budgetLineId, forUpdate = true)
                ?: throw RewardsNotFoundException("BUDGET_LINE_UNKNOWN", budgetLineId)
        if (!line.canConsume(
                value,
            )
        ) {
            throw RewardsRuleException("BUDGET_EXHAUSTED", "budget line $budgetLineId is exhausted")
        }
        val at = now()
        val entry =
            LedgerEntry(
                UUID.randomUUID(),
                m.partyId,
                type,
                source,
                flux,
                0,
                null,
                sourceRef,
                RULE_VERSION,
                budgetLineId,
                m.market,
                at,
            )
        if (!tx.insertEntry(entry)) return null
        tx.insertLot(Lot(UUID.randomUUID(), m.partyId, flux, flux, at))
        tx.consumeBudget(budgetLineId, value)
        tx.enqueue(
            OutboxEvent(
                "flux_earned",
                m.partyId,
                mapOf(
                    "entryId" to entry.entryId,
                    "flux" to flux,
                    "source" to source.wire,
                    "sourceRef" to sourceRef,
                ),
            ),
            at,
        )
        return entry
    }

    /** Issues a coupon (idempotent on [sourceRef]); its pre-charge is valued at issue against [budgetLineId]. */
    @Suppress("ThrowsCount", "LongParameterList")
    fun issueCoupon(
        tx: RewardsTx,
        m: Membership,
        couponDefId: String,
        preChargePct: Int,
        source: EntrySource,
        sourceRef: String,
        budgetLineId: String? = null,
    ): Coupon {
        tx.couponBySource(m.partyId, sourceRef)?.let { return it }
        val def =
            tx.couponDef(m.market, couponDefId) ?: throw RewardsNotFoundException("COUPON_DEF_UNKNOWN", couponDefId)
        val at = now()
        val coupon = Coupon.issue(def, m.partyId, preChargePct, sourceRef, at)
        tx.insertCoupon(coupon)
        if (coupon.preCharge > 0) {
            val line = budgetLineId ?: def.budgetLineId
            val value = tx.marketParams(m.market).valueOf(coupon.preCharge.toLong())
            val budget =
                tx.budgetLine(line, forUpdate = true) ?: throw RewardsNotFoundException("BUDGET_LINE_UNKNOWN", line)
            if (!budget.canConsume(
                    value,
                )
            ) {
                throw RewardsRuleException("BUDGET_EXHAUSTED", "budget line $line is exhausted")
            }
            tx.consumeBudget(line, value)
            tx.insertEntry(
                LedgerEntry(
                    UUID.randomUUID(),
                    m.partyId,
                    EntryType.CHARGE,
                    source,
                    0,
                    coupon.preCharge,
                    coupon.couponId,
                    "precharge:${coupon.couponId}",
                    RULE_VERSION,
                    line,
                    m.market,
                    at,
                ),
            )
        }
        tx.enqueue(OutboxEvent("coupon_issued", m.partyId, couponEvent(coupon)), at)
        if (coupon.state ==
            CouponState.READY
        ) {
            tx.enqueue(OutboxEvent("coupon_ready", m.partyId, couponEvent(coupon)), at)
        }
        return coupon
    }

    fun payReward(
        tx: RewardsTx,
        m: Membership,
        reward: Reward,
        source: EntrySource,
        sourceRef: String,
        budgetLineId: String,
    ): Pair<LedgerEntry?, Coupon?> {
        val entry = reward.flux?.let { grant(tx, m, it, source, sourceRef, budgetLineId) }
        val coupon = reward.couponDefId?.let {
            issueCoupon(tx, m, it, reward.preChargePct ?: 0, source, "$sourceRef:coupon", budgetLineId)
        }
        return entry to coupon
    }

    fun couponEvent(c: Coupon): Map<String, Any?> = mapOf(
        "couponId" to c.couponId,
        "couponDefId" to c.couponDefId,
        "state" to c.state,
        "charged" to c.charged,
        "capacity" to c.capacity,
    )

    companion object {
        const val RULE_VERSION = "2026-11"
        val VESTING_WINDOW: Duration = Duration.ofDays(60)
    }
}
