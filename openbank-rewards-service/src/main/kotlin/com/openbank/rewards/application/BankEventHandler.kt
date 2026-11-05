// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.application

import com.openbank.rewards.domain.BehaviourFact
import com.openbank.rewards.domain.EntrySource
import com.openbank.rewards.domain.FactType
import com.openbank.rewards.domain.Market
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** A transaction as the engine needs it, assembled from TransactionInitiated once it completed. */
data class SettledTransaction(
    val transactionId: UUID,
    val type: String,
    val sourceAccountId: UUID?,
    val targetAccountId: UUID?,
    val amount: BigDecimal,
    val currency: String,
    val rail: String,
    val instructionType: String,
    val reversalOf: UUID?,
    val occurredAt: Instant,
)

/**
 * Normalises banking events (ingest module) into projections and [BehaviourFact]s. Natural keys
 * make every fact exactly-once whatever the delivery count. Nothing here can block banking: a
 * consumer failure lands in the DLQ, the producer never waits for the engine.
 */
@ApplicationScoped
@Suppress("TooManyFunctions")
class BankEventHandler(private val store: RewardsStore, private val engine: RewardsEngine) {
    private val log = Logger.getLogger(BankEventHandler::class.java)

    fun accountOpened(a: AccountRef) {
        store.tx { tx -> tx.upsertAccount(a) }
        if (a.accountType == "CURRENT" &&
            a.status == "ACTIVE"
        ) {
            activated(a.accountId, a.partyId, a.openedAt ?: engine.now())
        }
    }

    fun accountStatusChanged(accountId: UUID, partyId: UUID?, newStatus: String, at: Instant) {
        val account = store.tx { tx ->
            tx.account(accountId)?.copy(status = newStatus)?.also(tx::upsertAccount)
        }
        val owner = partyId ?: account?.partyId ?: return
        if (newStatus == "ACTIVE" &&
            (account == null || account.accountType == "CURRENT")
        ) {
            activated(accountId, owner, at)
        }
    }

    private fun activated(accountId: UUID, partyId: UUID, at: Instant) {
        engine.ingest(BehaviourFact("ACCOUNT_ACTIVATED:$accountId", partyId, FactType.ACCOUNT_ACTIVATED, at))
    }

    fun cardIssued(card: CardRef, at: Instant) {
        store.tx { tx -> tx.upsertCard(card) }
        // Demo cards are issued ACTIVE (no separate activation step in this bank yet).
        if (card.status == null ||
            card.status == "ACTIVE"
        ) {
            engine.ingest(BehaviourFact("CARD_ACTIVATED:${card.cardId}", card.partyId, FactType.CARD_ACTIVATED, at))
        }
    }

    fun cardStatusChanged(cardId: UUID, partyId: UUID?, newStatus: String, at: Instant) {
        val card = store.tx { tx -> tx.card(cardId)?.copy(status = newStatus)?.also(tx::upsertCard) }
        val owner = partyId ?: card?.partyId ?: return
        if (newStatus ==
            "ACTIVE"
        ) {
            engine.ingest(BehaviourFact("CARD_ACTIVATED:$cardId", owner, FactType.CARD_ACTIVATED, at))
        }
    }

    /** A settled card payment; [merchant] distinguishes merchants for Card Week (B08 anti-farming). */
    @Suppress("LongParameterList")
    fun cardPayment(
        authorizationId: String,
        cardId: UUID?,
        partyId: UUID?,
        amount: BigDecimal,
        currency: String,
        merchant: String?,
        at: Instant,
    ) {
        val owner = partyId ?: cardId?.let { id -> store.tx { tx -> tx.card(id)?.partyId } } ?: run {
            log.debugf("card payment %s: party unknown", authorizationId)
            return
        }
        engine.ingest(
            BehaviourFact(
                "CARD_PAYMENT:$authorizationId",
                owner,
                FactType.CARD_PAYMENT,
                at,
                amount,
                currency,
                merchant ?: "unknown:$authorizationId",
            ),
        )
    }

    @Suppress("CyclomaticComplexMethod") // one rule table; splitting hides it
    fun transactionSettled(t: SettledTransaction) {
        if (t.reversalOf != null ||
            t.type in setOf("FEE", "INTEREST", "REVERSAL") ||
            t.rail in setOf("FEE", "INTEREST", "CARD")
        ) {
            return
        }
        val (source, target) = store.tx { tx ->
            t.sourceAccountId?.let(tx::account) to
                t.targetAccountId?.let(tx::account)
        }
        // Bank-initiated credits (welcome bonus, reward cash credits, adjustments) are not the customer's funding.
        val systemCredit = t.instructionType == "SYSTEM" ||
            t.type == "ADJUSTMENT" ||
            (t.sourceAccountId == null && t.rail in setOf("INTERNAL", "UNKNOWN"))
        when {
            source != null && target != null && source.partyId == target.partyId && target.accountType == "SAVINGS" ->
                engine.ingest(
                    BehaviourFact(
                        "SAVINGS_TOPUP:${t.transactionId}",
                        target.partyId,
                        FactType.SAVINGS_TOPUP,
                        t.occurredAt,
                        t.amount,
                        t.currency,
                    ),
                )
            // own-account shuffles earn nothing (abuse rule)
            source != null && target != null && source.partyId == target.partyId -> Unit
            target != null && target.accountType == "CURRENT" && !systemCredit ->
                engine.ingest(
                    BehaviourFact(
                        "FUNDING_RECEIVED:${t.transactionId}",
                        target.partyId,
                        FactType.FUNDING_RECEIVED,
                        t.occurredAt,
                        t.amount,
                        t.currency,
                    ),
                )
            source != null && source.accountType == "CURRENT" ->
                engine.ingest(
                    BehaviourFact(
                        "TRANSFER_SENT:${t.transactionId}",
                        source.partyId,
                        FactType.TRANSFER_SENT,
                        t.occurredAt,
                        t.amount,
                        t.currency,
                    ),
                )
            else -> Unit
        }
    }

    /** A domestic payment to a registered biller (spec: biller recognition, placeholder registry). */
    @Suppress("LongParameterList")
    fun domesticPayment(
        paymentId: UUID,
        partyId: UUID?,
        debtorAccountId: UUID?,
        creditorAccount: String,
        amount: BigDecimal,
        currency: String,
        at: Instant,
    ) {
        val owner = partyId ?: debtorAccountId?.let { id -> store.tx { tx -> tx.account(id)?.partyId } } ?: return
        val biller =
            store.tx { tx ->
                tx.billers(null).firstOrNull {
                    it.active &&
                        normalise(it.accountNumber) == normalise(creditorAccount)
                }
            }
                ?: return
        engine.ingest(
            BehaviourFact(
                "BILL_PAID:$paymentId",
                owner,
                FactType.BILL_PAID,
                at,
                amount,
                currency,
                paymentId.toString(),
            ),
            biller.billerId,
        )
    }

    fun savingsGoalSet(accountId: UUID, partyId: UUID?, at: Instant) {
        val owner = partyId ?: store.tx { tx -> tx.account(accountId)?.partyId } ?: return
        engine.ingest(BehaviourFact("SAVINGS_GOAL_SET:$accountId", owner, FactType.SAVINGS_GOAL_SET, at))
    }

    fun instalmentPaid(
        loanId: UUID,
        instalmentNumber: Int,
        partyId: UUID,
        onTime: Boolean,
        amount: BigDecimal?,
        currency: String?,
        at: Instant,
    ) {
        // CR-4: on-time repayment is behaviour, allowed; late or minimum payments earn nothing.
        if (!onTime) return
        engine.ingest(
            BehaviourFact(
                "INSTALMENT_PAID_ON_TIME:$loanId:$instalmentNumber",
                partyId,
                FactType.INSTALMENT_PAID_ON_TIME,
                at,
                amount,
                currency,
                loanId.toString(),
            ),
        )
    }

    /** E19 vest: both sides get a VESTED reward; the referee's first funding releases them (C4). */
    fun referralQualified(inviteId: UUID, programId: UUID?, referrer: UUID, referee: UUID, at: Instant) {
        if (referrer == referee) return // self-referral (abuse rule)
        engine.ensureMembership(referee)
        engine.ensureMembership(referrer)
        store.tx { tx ->
            val m = tx.membership(referee, forUpdate = true)!!
            val defs = tx.missionDefs(m.market)
            val referrerFlux = defs.firstOrNull { it.missionId == "M-REF-1" }?.reward?.flux ?: DEFAULT_REFERRER_FLUX
            val refereeFlux =
                defs.firstOrNull { it.missionId == "M-REF-1-REFEREE" }?.reward?.flux ?: DEFAULT_REFEREE_FLUX
            val releaseBy = at.plus(RewardsEngine.VESTING_WINDOW)
            listOf(
                ReferralReward(
                    inviteId,
                    ReferralSide.REFERRER,
                    referrer,
                    referee,
                    programId,
                    referrerFlux,
                    ReferralState.VESTED,
                    at,
                    releaseBy,
                    null,
                    null,
                ),
                ReferralReward(
                    inviteId,
                    ReferralSide.REFEREE,
                    referee,
                    referrer,
                    programId,
                    refereeFlux,
                    ReferralState.VESTED,
                    at,
                    releaseBy,
                    null,
                    null,
                ),
            ).forEach { r ->
                if (tx.insertReferralReward(r)) {
                    tx.enqueue(
                        OutboxEvent(
                            "referral_reward_vested",
                            r.partyId,
                            mapOf(
                                "inviteId" to inviteId,
                                "side" to r.side,
                                "flux" to r.flux,
                                "releaseBy" to releaseBy,
                            ),
                        ),
                        engine.now(),
                    )
                }
            }
        }
    }

    /** T01 via the event path (e.g. a code used when opening a term deposit): pay the offer's reward. */
    fun incentiveCommitted(partyId: UUID, offerId: UUID, reservationId: String) {
        val m0 = engine.ensureMembership(partyId)
        if (!m0.active) return
        store.tx { tx ->
            val m = tx.membership(partyId, forUpdate = true)!!
            val offer = tx.offerReward(offerId) ?: return@tx
            engine.payReward(
                tx,
                m,
                engine.codec.reward(offer.rewardJson),
                EntrySource.PROMO_CODE,
                "offer:$offerId",
                offer.budgetLineId,
            )
        }
        log.debugf("incentive commit %s for %s", reservationId, partyId)
    }

    fun marketOf(partyId: UUID): Market = store.tx { tx -> tx.membership(partyId)?.market } ?: Market.CZ

    companion object {
        const val DEFAULT_REFERRER_FLUX = 250
        const val DEFAULT_REFEREE_FLUX = 100

        fun normalise(account: String): String = account.replace(" ", "").uppercase().trimStart('0').replace("-0", "-")
    }
}
