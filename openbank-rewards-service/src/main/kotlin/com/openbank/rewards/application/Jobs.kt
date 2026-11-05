// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.application

import com.openbank.rewards.domain.BehaviourFact
import com.openbank.rewards.domain.CouponState
import com.openbank.rewards.domain.EntrySource
import com.openbank.rewards.domain.EntryType
import com.openbank.rewards.domain.FactType
import com.openbank.rewards.domain.LedgerEntry
import com.openbank.rewards.domain.Lot
import com.openbank.rewards.domain.MissionState
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The engine's periodic work; each method is idempotent and safe to run on any replica. */
@ApplicationScoped
class Jobs(
    private val store: RewardsStore,
    private val engine: RewardsEngine,
    private val customers: CustomerService,
    private val gl: GlPostingPort,
) {
    private val log = Logger.getLogger(Jobs::class.java)

    /** B7: a completed mission is claimed for the customer after 30 days. */
    fun autoClaim(): Int {
        val due = store.tx {
            it.claimPendingBefore(
                engine.now().minus(com.openbank.rewards.domain.MissionInstance.AUTO_CLAIM_AFTER),
                BATCH,
            )
        }
        var n = 0
        due.forEach { i ->
            runCatching { customers.claim(i.partyId, i.missionId, auto = true) }
                .onSuccess { n++ }
                .onFailure { log.warnf("auto-claim %s/%s: %s", i.partyId, i.missionId, it.message) }
        }
        return n
    }

    /** A cycle that ended in progress lapses (Hub history shows it). */
    fun lapseMissions(): Int = store.tx { tx ->
        tx.inProgressEndedBefore(engine.now(), BATCH).onEach {
            tx.upsertInstance(it.copy(state = MissionState.LAPSED))
        }.size
    }

    /** No release by the deadline: the vested referral reward lapses. */
    fun lapseReferrals(): Int = store.tx { tx ->
        tx.vestedReferralsDueBefore(engine.now(), BATCH).onEach {
            tx.updateReferralReward(it.copy(state = ReferralState.LAPSED, note = "NOT_RELEASED_IN_TIME"))
        }.size
    }

    /** A5: on coupon expiry the customer's own charged FLUX returns; bonus and pre-charge are lost. */
    fun expireCoupons(): Int {
        val due = store.tx { it.expiringCoupons(engine.now(), BATCH) }
        due.forEach { c0 ->
            store.tx { tx ->
                tx.membership(c0.partyId, forUpdate = true)
                val c = tx.coupon(c0.couponId, forUpdate = true)!!
                if (c.state !in setOf(CouponState.ISSUED, CouponState.CHARGING, CouponState.READY)) return@tx
                val now = engine.now()
                if (c.chargedOwn > 0) {
                    tx.insertEntry(
                        LedgerEntry(
                            UUID.randomUUID(),
                            c.partyId,
                            EntryType.RETURN,
                            EntrySource.EXPIRY_RETURN,
                            c.chargedOwn,
                            -c.chargedOwn,
                            c.couponId,
                            "expiry:${c.couponId}",
                            RewardsEngine.RULE_VERSION,
                            null,
                            c.market,
                            now,
                        ),
                    )
                    tx.insertLot(Lot(UUID.randomUUID(), c.partyId, c.chargedOwn, c.chargedOwn, now))
                }
                val lost = c.chargedBonus + c.preCharge
                if (lost > 0) {
                    tx.insertEntry(
                        LedgerEntry(
                            UUID.randomUUID(),
                            c.partyId,
                            EntryType.EXPIRE,
                            EntrySource.EXPIRY_RETURN,
                            0,
                            -lost,
                            c.couponId,
                            "expire-bonus:${c.couponId}",
                            RewardsEngine.RULE_VERSION,
                            null,
                            c.market,
                            now,
                        ),
                    )
                }
                tx.updateCoupon(c.copy(state = CouponState.EXPIRED))
                tx.enqueue(
                    OutboxEvent(
                        "coupon_expired",
                        c.partyId,
                        engine.couponEvent(c) + mapOf("returned" to c.chargedOwn, "lost" to lost),
                    ),
                    now,
                )
            }
        }
        return due.size
    }

    /** A5: the balance expires only after 24 months without any qualifying activity. */
    fun expireInactiveBalances(): Int {
        val cutoff = engine.now().minus(INACTIVITY)
        val stale = store.tx { tx -> tx.memberships(null, Int.MAX_VALUE).filter { it.lastActivityAt.isBefore(cutoff) } }
        var n = 0
        stale.forEach { m ->
            store.tx { tx ->
                tx.membership(m.partyId, forUpdate = true)
                val lots = tx.openLots(m.partyId)
                val total = lots.sumOf { it.remaining }
                if (total <= 0) return@tx
                lots.forEach { tx.debitLot(it.lotId, it.remaining) }
                val day = LocalDate.ofInstant(engine.now(), ZoneOffset.UTC)
                tx.insertEntry(
                    LedgerEntry(
                        UUID.randomUUID(),
                        m.partyId,
                        EntryType.EXPIRE,
                        EntrySource.EXPIRY_RETURN,
                        -total,
                        0,
                        null,
                        "inactivity:$day",
                        RewardsEngine.RULE_VERSION,
                        null,
                        m.market,
                        engine.now(),
                    ),
                )
                n++
            }
        }
        return n
    }

    /** Tenure anniversary (migrated Lístky source): one fact per current account per year. */
    fun tenureAnniversaries(): Int {
        val today = LocalDate.ofInstant(engine.now(), ZoneOffset.UTC)
        val accounts = store.tx { it.accountsOpenedOn(today.format(MONTH_DAY)) }
            .filter {
                it.accountType == "CURRENT" &&
                    it.openedAt != null &&
                    LocalDate.ofInstant(it.openedAt, ZoneOffset.UTC).year < today.year
            }
        return accounts.count { a ->
            engine.ingest(
                BehaviourFact(
                    "TENURE_ANNIVERSARY:${a.accountId}:${today.year}",
                    a.partyId,
                    FactType.TENURE_ANNIVERSARY,
                    engine.now(),
                    ref = a.accountId.toString(),
                ),
            )
        }
    }

    /**
     * Daily liability (spec: Reward accounting). Liability = (balance + charged-but-unused FLUX)
     * x FLUX value; the change since the last POSTED snapshot is booked Dr FLUX reward expense /
     * Cr FLUX provision (or reversed). A failed posting leaves the delta for the next run.
     */
    fun postLiability(): List<LiabilitySnapshot> {
        val date = LocalDate.ofInstant(engine.now(), ZoneOffset.UTC)
        val rows = store.tx { tx ->
            tx.allMarketParams().map { p ->
                val (balance, charged) = tx.liabilityFlux(p.market)
                val value = p.valueOf(balance + charged)
                val base = tx.lastSnapshot(p.market)?.takeIf { it.glRef != null }
                Triple(
                    p,
                    LiabilitySnapshot(date, p.market, balance, charged, value, p.currency, null, null),
                    value.subtract(
                        base?.value ?: BigDecimal.ZERO,
                    ),
                )
            }
        }
        return rows.map { (p, snapshot, delta) ->
            val posted = if (delta.signum() == 0) {
                snapshot.copy(postedDelta = BigDecimal.ZERO, glRef = "NO_CHANGE")
            } else {
                runCatching {
                    gl.postLiabilityDelta(
                        date,
                        p.market,
                        p.currency,
                        delta,
                        "liability:${p.market}:$date:${snapshot.value}",
                    )
                }
                    .map { ref -> snapshot.copy(postedDelta = delta, glRef = ref) }
                    .getOrElse { e ->
                        log.warnf("liability posting %s %s failed: %s", p.market, date, e.message)
                        snapshot
                    }
            }
            store.tx { it.upsertSnapshot(posted, engine.now()) }
            posted
        }
    }

    companion object {
        const val BATCH = 200
        val INACTIVITY: Duration = Duration.ofDays(730)
        val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
    }
}
