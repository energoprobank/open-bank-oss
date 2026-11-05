// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.application

import com.openbank.rewards.domain.BehaviourFact
import com.openbank.rewards.domain.BudgetLine
import com.openbank.rewards.domain.Coupon
import com.openbank.rewards.domain.CouponDef
import com.openbank.rewards.domain.LedgerEntry
import com.openbank.rewards.domain.Lot
import com.openbank.rewards.domain.Market
import com.openbank.rewards.domain.MarketParams
import com.openbank.rewards.domain.Membership
import com.openbank.rewards.domain.MissionDef
import com.openbank.rewards.domain.MissionInstance
import com.openbank.rewards.domain.ProgrammeSwitch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One unit of work. Every customer command locks the party's membership row first, so a party's commands serialise. */
interface RewardsStore {
    fun <T> tx(block: (RewardsTx) -> T): T
}

data class AccountRef(
    val accountId: UUID,
    val partyId: UUID,
    val accountType: String,
    val currency: String,
    val status: String?,
    val openedAt: Instant?,
)

data class CardRef(val cardId: UUID, val partyId: UUID, val accountId: UUID?, val status: String?)

data class Promotion(
    val promotionId: UUID,
    val market: Market,
    val name: String,
    val multiplier: BigDecimal,
    val startsAt: Instant,
    val endsAt: Instant,
    val perPartyCap: Int,
    val budgetLineId: String,
    val active: Boolean,
)

enum class FulfilmentState { PENDING, APPLIED, FAILED }

data class Fulfilment(
    val fulfilmentId: UUID,
    val couponId: UUID,
    val partyId: UUID,
    val benefitType: String,
    val state: FulfilmentState,
    val attempts: Int,
    val commandRef: String?,
    val outcome: String?,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class ReferralSide { REFERRER, REFEREE }

enum class ReferralState { VESTED, RELEASED, LAPSED }

data class ReferralReward(
    val inviteId: UUID,
    val side: ReferralSide,
    val partyId: UUID,
    val otherParty: UUID,
    val programId: UUID?,
    val flux: Int,
    val state: ReferralState,
    val vestedAt: Instant,
    val releaseBy: Instant,
    val releasedAt: Instant?,
    val note: String?,
)

data class OfferReward(val offerId: UUID, val market: Market, val rewardJson: String, val budgetLineId: String)

data class Biller(
    val billerId: String,
    val market: Market,
    val name: String,
    val accountNumber: String,
    val placeholder: Boolean,
    val active: Boolean,
)

data class ChangeRequest(
    val requestId: UUID,
    val kind: String,
    val payload: String,
    val reason: String,
    val maker: String,
    val status: String,
    val checker: String?,
    val result: String?,
    val createdAt: Instant,
    val decidedAt: Instant?,
)

data class LiabilitySnapshot(
    val date: LocalDate,
    val market: Market,
    val balanceFlux: Long,
    val chargedFlux: Long,
    val value: BigDecimal,
    val currency: String,
    val postedDelta: BigDecimal?,
    val glRef: String?,
)

data class StoredResponse(val statusCode: Int, val body: String)

data class OutboxEvent(val eventType: String, val partyId: UUID, val payload: Map<String, Any?>)

@Suppress("TooManyFunctions") // one transactional port over the service's own tables, by design
interface RewardsTx {
    // markets, switches, budgets
    fun marketParams(market: Market): MarketParams
    fun allMarketParams(): List<MarketParams>
    fun upsertMarketParams(params: MarketParams)
    fun switchEnabled(switch: ProgrammeSwitch): Boolean
    fun setSwitch(switch: ProgrammeSwitch, enabled: Boolean, by: String, at: Instant)
    fun budgetLine(id: String, forUpdate: Boolean = false): BudgetLine?
    fun budgetLines(): List<BudgetLine>
    fun upsertBudgetLine(line: BudgetLine)
    fun consumeBudget(id: String, value: BigDecimal)

    // membership and projections
    fun membership(partyId: UUID, forUpdate: Boolean = false): Membership?
    fun insertMembership(m: Membership)
    fun updateMembership(m: Membership)
    fun memberships(market: Market?, limit: Int): List<Membership>
    fun upsertAccount(a: AccountRef)
    fun account(accountId: UUID): AccountRef?
    fun accountsOf(partyId: UUID): List<AccountRef>
    fun accountsOpenedOn(monthDay: String): List<AccountRef>
    fun upsertCard(c: CardRef)
    fun card(cardId: UUID): CardRef?
    fun putPendingTransaction(transactionId: UUID, payload: String, at: Instant)
    fun takePendingTransaction(transactionId: UUID): String?
    fun peekPendingTransaction(transactionId: UUID): String?

    // facts and missions
    fun insertFact(fact: BehaviourFact, payload: String?, at: Instant): Boolean
    fun fact(factId: String): BehaviourFact?
    fun factsOf(partyId: UUID, type: com.openbank.rewards.domain.FactType, limit: Int): List<BehaviourFact>
    fun missionDefs(market: Market, includeInactive: Boolean = false): List<MissionDef>
    fun upsertMissionDef(def: MissionDef, approvalRef: String)
    fun instance(partyId: UUID, missionId: String, cycle: String, forUpdate: Boolean = false): MissionInstance?
    fun upsertInstance(instance: MissionInstance)
    fun archiveRollingInstance(partyId: UUID, missionId: String, newCycle: String)
    fun instancesOf(partyId: UUID): List<MissionInstance>
    fun claimPendingBefore(before: Instant, limit: Int): List<MissionInstance>
    fun inProgressEndedBefore(before: Instant, limit: Int): List<MissionInstance>

    // ledger
    fun insertEntry(entry: LedgerEntry): Boolean
    fun entries(partyId: UUID, page: Int, size: Int): List<LedgerEntry>
    fun countEntries(partyId: UUID): Long
    fun openLots(partyId: UUID): List<Lot>
    fun insertLot(lot: Lot)
    fun debitLot(lotId: UUID, amount: Int)
    fun balance(partyId: UUID): Int
    fun liabilityFlux(market: Market): Pair<Long, Long>

    // coupons
    fun couponDefs(market: Market, includeInactive: Boolean = false): List<CouponDef>
    fun couponDef(market: Market, couponDefId: String): CouponDef?
    fun upsertCouponDef(def: CouponDef, approvalRef: String)
    fun coupons(partyId: UUID): List<Coupon>
    fun coupon(couponId: UUID, forUpdate: Boolean = false): Coupon?
    fun couponBySource(partyId: UUID, sourceRef: String): Coupon?
    fun insertCoupon(coupon: Coupon)
    fun updateCoupon(coupon: Coupon)
    fun expiringCoupons(before: Instant, limit: Int): List<Coupon>

    // promotions
    fun activePromotion(market: Market, at: Instant): Promotion?
    fun promotions(market: Market?): List<Promotion>
    fun insertPromotion(p: Promotion, approvalRef: String)
    fun stopPromotion(id: UUID)
    fun bonusReceived(partyId: UUID, promotionId: UUID): Int

    // fulfilment
    fun insertFulfilment(f: Fulfilment)
    fun updateFulfilment(f: Fulfilment)
    fun fulfilmentForCoupon(couponId: UUID): Fulfilment?
    fun fulfilment(fulfilmentId: UUID): Fulfilment?
    fun pendingFulfilments(limit: Int): List<Fulfilment>

    // referral, offers, billers, lessons
    fun insertReferralReward(r: ReferralReward): Boolean
    fun updateReferralReward(r: ReferralReward)
    fun referralRewardsOf(partyId: UUID): List<ReferralReward>
    fun vestedReferralsForReferee(refereePartyId: UUID): List<ReferralReward>
    fun referralRewardsForInvite(inviteId: UUID): List<ReferralReward>
    fun vestedReferralsDueBefore(before: Instant, limit: Int): List<ReferralReward>
    fun offerReward(offerId: UUID): OfferReward?
    fun upsertOfferReward(o: OfferReward, approvalRef: String)
    fun billers(market: Market?): List<Biller>
    fun upsertBiller(b: Biller)
    fun lessonContent(lessonId: String): Pair<String, Int>?
    fun lessons(): List<Pair<String, String>>

    // idempotency, maker-checker, liability, outbox
    fun storedResponse(partyId: UUID, key: String, operation: String): StoredResponse?
    fun storeResponse(partyId: UUID, key: String, operation: String, response: StoredResponse, at: Instant)
    fun insertChangeRequest(cr: ChangeRequest)
    fun changeRequest(id: UUID, forUpdate: Boolean = false): ChangeRequest?
    fun changeRequests(status: String?, limit: Int): List<ChangeRequest>
    fun decideChangeRequest(id: UUID, status: String, checker: String, result: String?, at: Instant)
    fun lastSnapshot(market: Market): LiabilitySnapshot?
    fun upsertSnapshot(s: LiabilitySnapshot, at: Instant)
    fun snapshots(limit: Int): List<LiabilitySnapshot>
    fun enqueue(event: OutboxEvent, at: Instant)
}

/** A downstream service refused the command definitively (4xx): compensate, do not retry. */
class DownstreamRejected(val code: String, message: String) : RuntimeException(message)

/** A downstream service could not be reached or failed transiently (5xx, timeout): retry later. */
class DownstreamUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class RateTerms(
    val nominalRatePct: BigDecimal?,
    val aprcPct: BigDecimal?,
    val instalment: BigDecimal?,
    val currency: String?,
    val amount: BigDecimal? = null,
)

data class LoanRef(
    val loanId: UUID,
    val partyId: UUID?,
    val currency: String,
    val nextInstalment: BigDecimal?,
    val nextDueDate: LocalDate?,
    val daysPastDue: Int,
    val status: String?,
)

/** Reads the bank's own records about a party (backfill of the projections). */
interface BankDirectoryPort {
    fun accountsOf(partyId: UUID): List<AccountRef>
    fun cardsOf(partyId: UUID): List<CardRef>

    /**
     * The party's market (CZ / BG) as party-service holds it (experience v2 step 8), or null when it
     * cannot be asked. Authoritative over the account currency: a customer joins the programme during
     * account opening, before any account exists.
     */
    fun marketOf(partyId: UUID): String? = null
}

interface InterestPort {
    fun addRateAdjustment(
        accountId: UUID,
        deltaBps: Int,
        validFrom: LocalDate,
        validTo: LocalDate,
        sourceRef: String,
        approvalRef: String,
    ): String
    fun cancelRateAdjustment(accountId: UUID, adjustmentId: String)
}

interface LendingPort {
    /** The party's loan, read from the party's own loan list (ownership is the lookup). */
    fun loan(partyId: UUID, loanId: UUID): LoanRef
    fun preview(loanId: UUID, deltaBps: Int, months: Int): Pair<RateTerms, RateTerms>
    fun addRateAdjustment(
        loanId: UUID,
        deltaBps: Int,
        validFrom: LocalDate,
        validTo: LocalDate,
        sourceRef: String,
        approvalRef: String,
    ): String
}

interface CashCreditPort {
    /** Pays [amount] from the FLUX provision to the party's own account. Idempotent on [sourceRef]. */
    fun credit(
        partyId: UUID,
        accountId: UUID,
        amount: BigDecimal,
        currency: String,
        reason: String,
        sourceRef: String,
    ): String
}

interface GlPostingPort {
    /** Posts the day's change of the FLUX liability: positive = Dr FLUX reward expense / Cr FLUX provision. */
    fun postLiabilityDelta(
        date: LocalDate,
        market: Market,
        currency: String,
        delta: BigDecimal,
        sourceRef: String,
    ): String
}

interface IncentivePort {
    /** Reserves and commits [code] for [partyId]; returns the offer id. */
    fun redeem(partyId: UUID, code: String, context: String): UUID
}
