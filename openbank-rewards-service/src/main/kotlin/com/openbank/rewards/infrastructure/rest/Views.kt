// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.infrastructure.rest

import com.openbank.rewards.application.CatalogueItem
import com.openbank.rewards.application.ChangeRequest
import com.openbank.rewards.application.Fulfilment
import com.openbank.rewards.application.LiabilitySnapshot
import com.openbank.rewards.application.MissionView
import com.openbank.rewards.application.RateTerms
import com.openbank.rewards.application.ReferralReward
import com.openbank.rewards.application.SummaryView
import com.openbank.rewards.domain.BenefitParams
import com.openbank.rewards.domain.Coupon
import com.openbank.rewards.domain.CouponDef
import com.openbank.rewards.domain.LedgerEntry
import com.openbank.rewards.domain.Level
import com.openbank.rewards.domain.Market
import com.openbank.rewards.domain.MarketParams
import com.openbank.rewards.domain.Membership
import com.openbank.rewards.domain.Reward
import com.openbank.rewards.infrastructure.persistence.RewardsJson
import java.math.BigDecimal

/** Wire shapes of the customer API (contract: rewards-api-contract.md, Types). */
@Suppress("TooManyFunctions")
object Views {
    fun money(amount: BigDecimal?, currency: String?): Map<String, Any?>? = if (amount ==
        null
    ) {
        null
    } else {
        mapOf(
            "amount" to amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
            "currency" to currency,
        )
    }

    fun level(l: Level, lifetime: Int): Map<String, Any?> = mapOf(
        "code" to l.name,
        "index" to l.index,
        "lifetimeCharged" to lifetime,
        "nextCode" to l.next?.name,
        "nextAt" to l.next?.threshold,
    )

    fun membership(m: Membership): Map<String, Any?> = mapOf(
        "status" to if (m.status.name == "OPTED_OUT") {
            "OPTED_OUT"
        } else if (m.quietMode) {
            "QUIET"
        } else {
            "ACTIVE"
        },
        "termsVersion" to m.termsVersion,
        "joinedAt" to m.joinedAt,
    )

    fun summary(s: SummaryView): Map<String, Any?> = mapOf(
        "partyId" to s.membership.partyId,
        "market" to s.membership.market,
        "currency" to s.params.currency,
        "fluxValue" to money(s.params.fluxValue, s.params.currency),
        "balance" to s.balance,
        "chargedUnused" to s.chargedUnused,
        "level" to level(s.level, s.membership.lifetimeCharged),
        "chargingCoupon" to s.charging?.let { coupon(it.first, it.second, s.params) },
        "counts" to mapOf("claimable" to s.claimable, "ready" to s.ready),
        "membership" to membership(s.membership),
        "promotion" to
            s.promotion?.let {
                mapOf(
                    "promotionId" to it.promotionId,
                    "multiplier" to it.multiplier,
                    "endsAt" to it.endsAt,
                )
            },
        "nextExpiry" to
            s.nextExpiry?.let { mapOf("flux" to it.charged, "at" to it.expiresAt, "couponId" to it.couponId) },
    )

    fun reward(r: Reward): Map<String, Any?> = mapOf(
        "type" to if (r.couponDefId != null && r.flux == null) "COUPON" else "FLUX",
        "flux" to r.flux,
        "couponDefId" to r.couponDefId,
        "preChargePct" to r.preChargePct,
    )

    fun mission(v: MissionView): Map<String, Any?> = mapOf(
        "missionId" to v.def.missionId,
        "version" to v.def.version,
        "kind" to v.def.kind,
        "cadence" to v.def.cadence,
        "cycle" to v.instance?.cycle,
        "state" to v.state,
        "progress" to mapOf("current" to v.current, "target" to v.target),
        "reward" to reward(v.instance?.reward ?: v.def.reward),
        "windowEndsAt" to v.instance?.windowEndsAt,
        "completedAt" to v.instance?.completedAt,
        "claimedAt" to v.instance?.claimedAt,
        "autoClaimAt" to v.instance?.autoClaimAt,
        "handoff" to v.def.handoff,
    )

    /** Money inside benefit params is in the coupon's market currency (contract: Money always names it). */
    fun benefit(p: BenefitParams, currency: String): Map<String, Any?> = mapOf(
        "percent" to p.percent,
        "cap" to money(p.cap, currency),
        "deltaBps" to p.deltaBps,
        "durationDays" to p.durationDays,
        "balanceCap" to money(p.balanceCap, currency),
        "theme" to p.theme,
    ).filterValues { it != null }

    fun coupon(c: Coupon, d: CouponDef, params: MarketParams? = null): Map<String, Any?> = mapOf(
        "couponId" to c.couponId,
        "couponDefId" to c.couponDefId,
        "benefitType" to d.benefitType,
        "category" to d.category,
        "rarity" to d.rarity,
        "capacity" to c.capacity,
        "chargedOwn" to c.chargedOwn,
        "chargedBonus" to c.chargedBonus,
        "preCharge" to c.preCharge,
        "charged" to c.charged,
        "percent" to c.percent,
        "state" to c.state,
        "issuedAt" to c.issuedAt,
        "expiresAt" to c.expiresAt,
        "usedAt" to c.usedAt,
        "targetKind" to d.benefitType.target,
        "benefit" to benefit(d.params, params?.currency ?: d.market.currency),
        "outcome" to RewardsJson.outcome(c.outcome)?.let { o ->
            mapOf(
                "summary" to o.summary,
                "amount" to money(o.amount, o.currency),
                "rateDeltaBps" to o.rateDeltaBps,
                "until" to o.until,
                "reference" to o.reference,
            )
        },
    )

    fun catalogue(i: CatalogueItem, params: MarketParams?): Map<String, Any?> = mapOf(
        "couponDefId" to i.def.couponDefId, "benefitType" to i.def.benefitType, "category" to i.def.category,
        "rarity" to i.def.rarity,
        "capacity" to i.def.capacity, "validityDays" to i.def.validityDays, "levelGate" to i.def.levelGate,
        "locked" to i.locked,
        "benefit" to benefit(i.def.params, params?.currency ?: i.def.market.currency),
    )

    fun entry(e: LedgerEntry): Map<String, Any?> = mapOf(
        "entryId" to e.entryId,
        "type" to e.type,
        "source" to e.source.wire,
        "flux" to e.flux,
        "couponFlux" to e.couponFlux,
        "couponId" to e.couponId,
        "sourceRef" to e.sourceRef,
        "occurredAt" to e.occurredAt,
    )

    fun fulfilment(f: Fulfilment): Map<String, Any?> = mapOf(
        "fulfilmentId" to f.fulfilmentId,
        "state" to f.state,
        "outcome" to
            RewardsJson.outcome(f.outcome)?.let {
                mapOf(
                    "summary" to it.summary,
                    "amount" to money(it.amount, it.currency),
                    "rateDeltaBps" to it.rateDeltaBps,
                    "until" to it.until,
                )
            },
        "error" to f.lastError?.takeIf { f.state.name == "FAILED" },
    )

    fun terms(t: RateTerms?): Map<String, Any?>? = t?.let {
        mapOf(
            "nominalRatePct" to it.nominalRatePct,
            "aprcPct" to it.aprcPct,
            "instalment" to money(it.instalment, it.currency),
            "amount" to money(it.amount, it.currency),
            "until" to it.until?.toString(),
        ).filterValues { v -> v != null }
    }

    fun referral(r: ReferralReward): Map<String, Any?> = mapOf(
        "inviteId" to r.inviteId,
        "side" to r.side,
        "state" to r.state,
        "flux" to r.flux,
        "releaseBy" to r.releaseBy,
        "releasedAt" to r.releasedAt,
    )

    fun settings(m: Membership): Map<String, Any?> = mapOf(
        "participating" to (m.status.name == "ACTIVE"),
        "quietMode" to m.quietMode,
        "notifications" to
            mapOf("missions" to m.notifyMissions, "coupons" to m.notifyCoupons, "expiry" to m.notifyExpiry),
    )

    fun change(cr: ChangeRequest): Map<String, Any?> = mapOf(
        "requestId" to cr.requestId, "kind" to cr.kind, "payload" to RewardsJson.mapper.readTree(cr.payload),
        "reason" to cr.reason,
        "maker" to cr.maker, "status" to cr.status, "checker" to cr.checker, "result" to cr.result,
        "createdAt" to cr.createdAt, "decidedAt" to cr.decidedAt,
    )

    fun snapshot(s: LiabilitySnapshot): Map<String, Any?> = mapOf(
        "date" to s.date,
        "market" to s.market,
        "balanceFlux" to s.balanceFlux,
        "chargedFlux" to s.chargedFlux,
        "value" to money(s.value, s.currency),
        "postedDelta" to money(s.postedDelta, s.currency),
        "glRef" to s.glRef,
    )

    fun market(m: Market) = m.name
}
