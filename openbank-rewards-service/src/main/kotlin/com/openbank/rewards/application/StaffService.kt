// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.application

import com.openbank.rewards.domain.BudgetKind
import com.openbank.rewards.domain.BudgetLine
import com.openbank.rewards.domain.CouponDef
import com.openbank.rewards.domain.CreditGuardrail
import com.openbank.rewards.domain.EntrySource
import com.openbank.rewards.domain.EntryType
import com.openbank.rewards.domain.Market
import com.openbank.rewards.domain.MarketParams
import com.openbank.rewards.domain.MissionDef
import com.openbank.rewards.domain.ProgrammeSwitch
import com.openbank.rewards.domain.RewardsNotFoundException
import com.openbank.rewards.domain.RewardsRuleException
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class ChangeKind {
    GRANT,
    MIGRATION,
    PROMOTION,
    PROMOTION_STOP,
    BUDGET_LINE,
    MARKET,
    BILLER,
    MISSION_DEF,
    COUPON_DEF,
    OFFER_REWARD,
    KILL_SWITCH,
}

/** Typed payloads of change requests; the REST layer decodes JSON into these before [StaffService.apply]. */
sealed interface ChangePayload {
    data class Grant(val partyId: UUID, val flux: Int, val note: String) : ChangePayload
    data class Migration(val items: List<MigrationItem>) : ChangePayload
    data class MigrationItem(val partyId: UUID, val flux: Int, val sourceRef: String)
    data class NewPromotion(val p: Promotion) : ChangePayload
    data class StopPromotion(val promotionId: UUID) : ChangePayload
    data class Budget(val line: BudgetLine) : ChangePayload
    data class MarketChange(val params: MarketParams) : ChangePayload
    data class BillerChange(val biller: Biller) : ChangePayload
    data class MissionChange(val def: MissionDef) : ChangePayload
    data class CouponChange(val def: CouponDef) : ChangePayload
    data class OfferChange(val offer: OfferReward) : ChangePayload
    data class Switch(val switch: ProgrammeSwitch, val enabled: Boolean) : ChangePayload
}

data class EconomyReport(
    val market: Market,
    val earned: Long,
    val charged: Long,
    val burned: Long,
    val expired: Long,
    val balance: Long,
    val chargedUnused: Long,
    val liability: BigDecimal,
)

/**
 * The operator console's back end. Every write is a change request (maker) that a DIFFERENT
 * operator approves (checker); approval applies it. Spec: Staff API, all writes draft → approve.
 */
@ApplicationScoped
class StaffService(private val store: RewardsStore, private val engine: RewardsEngine) {

    fun submit(kind: ChangeKind, payloadJson: String, reason: String, maker: String): ChangeRequest {
        if (reason.isBlank()) throw RewardsRuleException("REASON_REQUIRED", "a reason is mandatory")
        val cr =
            ChangeRequest(
                UUID.randomUUID(), kind.name, payloadJson,
                reason.take(
                    MAX_REASON,
                ),
                maker, "PENDING", null, null, engine.now(), null,
            )
        store.tx { it.insertChangeRequest(cr) }
        return cr
    }

    fun reject(id: UUID, checker: String, note: String?): ChangeRequest = store.tx { tx ->
        val cr = pending(tx, id, checker)
        tx.decideChangeRequest(id, "REJECTED", checker, note, engine.now())
        tx.changeRequest(id)!!.also { require(it.requestId == cr.requestId) }
    }

    /** Approves and applies. The decoded [payload] must be the request's own payload, decoded by the caller. */
    fun approve(id: UUID, checker: String, payload: ChangePayload): ChangeRequest {
        store.tx { tx -> pending(tx, id, checker) }
        val result = runCatching { apply(id, payload) }
        store.tx { tx ->
            result.fold(
                { tx.decideChangeRequest(id, "APPROVED", checker, it, engine.now()) },
                { tx.decideChangeRequest(id, "FAILED", checker, it.message, engine.now()) },
            )
        }
        result.exceptionOrNull()?.let { throw it }
        return store.tx { it.changeRequest(id)!! }
    }

    @Suppress("ThrowsCount") // one rule table; splitting hides it
    private fun pending(tx: RewardsTx, id: UUID, checker: String): ChangeRequest {
        val cr =
            tx.changeRequest(id, forUpdate = true)
                ?: throw RewardsNotFoundException("CHANGE_REQUEST_UNKNOWN", id.toString())
        if (cr.status != "PENDING") throw RewardsRuleException("ALREADY_DECIDED", "the request is ${cr.status}")
        if (cr.maker.equals(
                checker,
                ignoreCase = true,
            )
        ) {
            throw RewardsRuleException("FOUR_EYES", "the maker cannot approve their own request")
        }
        return cr
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ThrowsCount") // one rule table; splitting hides it
    private fun apply(id: UUID, payload: ChangePayload): String {
        val approval = "CR-$id"
        return when (payload) {
            is ChangePayload.Grant -> {
                val m0 = engine.ensureMembership(payload.partyId)
                store.tx { tx ->
                    val m = tx.membership(payload.partyId, forUpdate = true)!!
                    engine.grant(tx, m, payload.flux, EntrySource.MANUAL_GRANT, "grant:$id", "ENG-${m0.market}")
                }
                "granted ${payload.flux} FLUX"
            }
            is ChangePayload.Migration -> {
                var migrated = 0L
                payload.items.forEach { item ->
                    val m0 = engine.ensureMembership(item.partyId)
                    if (item.flux > 0) {
                        store.tx { tx ->
                            val m = tx.membership(item.partyId, forUpdate = true)!!
                            // 1 Lístek = 1 FLUX; idempotent on the party and its source reference.
                            val entry = engine.grant(
                                tx,
                                m,
                                item.flux,
                                EntrySource.MIGRATED_LOYALTY,
                                "loyalty:${item.sourceRef}",
                                "MIG-${m0.market}",
                                EntryType.MIGRATION,
                            )
                            if (entry != null) migrated += item.flux
                        }
                    }
                }
                "migrated $migrated FLUX for ${payload.items.size} parties"
            }
            is ChangePayload.NewPromotion -> store.tx { tx ->
                val p = payload.p
                if (p.multiplier > MAX_MULTIPLIER) throw RewardsRuleException("MULTIPLIER_TOO_HIGH", "at most x3")
                val previous = tx.promotions(p.market).filter {
                    it.promotionId != p.promotionId
                }.maxByOrNull { it.endsAt }
                if (previous != null && previous.endsAt.plus(PROMOTION_GAP).isAfter(p.startsAt)) {
                    throw RewardsRuleException("PROMOTION_TOO_SOON", "at least 30 days between promotions")
                }
                tx.budgetLine(p.budgetLineId) ?: throw RewardsNotFoundException("BUDGET_LINE_UNKNOWN", p.budgetLineId)
                tx.insertPromotion(p, approval)
                "promotion ${p.promotionId}"
            }
            is ChangePayload.StopPromotion -> store.tx { tx ->
                tx.stopPromotion(payload.promotionId)
                "stopped"
            }
            is ChangePayload.Budget -> store.tx { tx ->
                tx.upsertBudgetLine(payload.line)
                "budget ${payload.line.budgetLineId}"
            }
            is ChangePayload.MarketChange -> store.tx { tx ->
                tx.upsertMarketParams(payload.params)
                "market ${payload.params.market}"
            }
            is ChangePayload.BillerChange -> store.tx { tx ->
                tx.upsertBiller(payload.biller)
                "biller ${payload.biller.billerId}"
            }
            is ChangePayload.MissionChange -> store.tx { tx ->
                if (!CreditGuardrail.admissible(payload.def.rule.fact)) {
                    throw RewardsRuleException("CREDIT_FACT_DENIED", "G-CR-01: ${payload.def.rule.fact} can never earn")
                }
                tx.upsertMissionDef(payload.def, approval)
                "mission ${payload.def.missionId} v${payload.def.version}"
            }
            is ChangePayload.CouponChange -> store.tx { tx ->
                val d = payload.def
                if (d.benefitType.credit && tx.budgetLine(d.budgetLineId)?.kind != BudgetKind.CREDIT) {
                    throw RewardsRuleException(
                        "CREDIT_BUDGET_REQUIRED",
                        "G-CR-09: credit coupons use a CREDIT budget line",
                    )
                }
                tx.upsertCouponDef(d, approval)
                "coupon ${d.couponDefId}"
            }
            is ChangePayload.OfferChange -> store.tx { tx ->
                tx.upsertOfferReward(payload.offer, approval)
                "offer ${payload.offer.offerId}"
            }
            is ChangePayload.Switch -> store.tx { tx ->
                tx.setSwitch(payload.switch, payload.enabled, "CR-$id", engine.now())
                "${payload.switch} ${if (payload.enabled) "on" else "off"}"
            }
        }
    }

    fun changeRequests(status: String?): List<ChangeRequest> = store.tx { it.changeRequests(status, LIST_LIMIT) }

    fun changeRequest(id: UUID): ChangeRequest =
        store.tx { it.changeRequest(id) } ?: throw RewardsNotFoundException("CHANGE_REQUEST_UNKNOWN", id.toString())

    fun economy(): List<EconomyReport> = store.tx { tx ->
        tx.allMarketParams().map { p ->
            val (balance, charged) = tx.liabilityFlux(p.market)
            val totals = tx.memberships(p.market, Int.MAX_VALUE).flatMap { tx.entries(it.partyId, 0, Int.MAX_VALUE) }
            EconomyReport(
                market = p.market,
                earned = totals.filter {
                    it.type == EntryType.EARN || it.type == EntryType.MIGRATION
                }.sumOf { it.flux.toLong() },
                charged = totals.filter { it.type == EntryType.CHARGE }.sumOf { it.couponFlux.toLong() },
                burned = totals.filter { it.type == EntryType.BURN }.sumOf { -it.couponFlux.toLong() },
                expired = totals.filter { it.type == EntryType.EXPIRE }.sumOf { -(it.flux + it.couponFlux).toLong() },
                balance = balance,
                chargedUnused = charged,
                liability = p.valueOf(balance + charged),
            )
        }
    }

    companion object {
        const val MAX_REASON = 400
        const val LIST_LIMIT = 200
        val MAX_MULTIPLIER: BigDecimal = BigDecimal("3")
        val PROMOTION_GAP: Duration = Duration.ofDays(30)

        fun isFuture(at: Instant, now: Instant) = at.isAfter(now)
    }
}
