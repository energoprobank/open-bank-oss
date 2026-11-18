// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.application

import com.openbank.rewards.domain.BenefitType
import com.openbank.rewards.domain.Coupon
import com.openbank.rewards.domain.CouponDef
import com.openbank.rewards.domain.CouponState
import com.openbank.rewards.domain.EntrySource
import com.openbank.rewards.domain.EntryType
import com.openbank.rewards.domain.FulfilmentOutcome
import com.openbank.rewards.domain.LedgerEntry
import com.openbank.rewards.domain.ProgrammeSwitch
import com.openbank.rewards.domain.RewardsRuleException
import com.openbank.rewards.domain.TargetKind
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

data class UseResult(val coupon: Pair<Coupon, CouponDef>, val fulfilment: Fulfilment)

/**
 * Both sides are always present: the customer-edge contract (`RewardsPreview`) requires `before`
 * and `after`, and the app decodes them as objects. A side with nothing to compare is an empty
 * [RateTerms], never null — a null `before` made every savings preview fail to decode in the app.
 */
data class Preview(val before: RateTerms, val after: RateTerms, val notes: List<String>)

/** What one use will do, computed before any money moves (so it can also be previewed). */
private data class Command(val outcome: FulfilmentOutcome, val run: () -> String, val creditCost: BigDecimal)

/**
 * Using a coupon is a saga (spec: Benefit sagas): 1) reserve the coupon (USING) and record a
 * PENDING fulfilment, 2) issue the command to the owning service, 3) commit (USED + BURN) or
 * compensate (back to READY). A definitive downstream refusal compensates at once; a transient
 * failure leaves the fulfilment PENDING for [retryPending]. The saga state lives in the
 * `fulfilment` table, so a restart resumes it — the engine does not need Temporal for a
 * single-step command, and this keeps the local stack free of a Temporal worker for rewards.
 */
@ApplicationScoped
@Suppress("TooManyFunctions")
class FulfilmentService(
    private val store: RewardsStore,
    private val engine: RewardsEngine,
    private val customers: CustomerService,
    private val interest: InterestPort,
    private val lending: LendingPort,
    private val cash: CashCreditPort,
) {
    private val log = Logger.getLogger(FulfilmentService::class.java)

    fun use(partyId: UUID, couponId: UUID, targetKind: TargetKind, targetId: String?): UseResult {
        engine.ensureMembership(partyId)
        val prepared = reserve(partyId, couponId, targetKind, targetId)
        if (prepared.fulfilment.state != FulfilmentState.PENDING ||
            prepared.coupon.first.state != CouponState.USING
        ) {
            return prepared
        }
        return execute(prepared.fulfilment.fulfilmentId)
    }

    private fun reserve(partyId: UUID, couponId: UUID, targetKind: TargetKind, targetId: String?): UseResult =
        store.tx { tx ->
            val m = tx.membership(partyId, forUpdate = true)!!
            engine.requireActive(m)
            engine.requireSwitch(tx, ProgrammeSwitch.USE)
            val coupon = customers.owned(tx, partyId, couponId, forUpdate = true)
            val def = tx.couponDef(m.market, coupon.couponDefId)!!
            // A retried request while the first is still in flight answers with the same saga.
            tx.fulfilmentForCoupon(couponId)?.takeIf {
                coupon.state == CouponState.USING || coupon.state == CouponState.USED
            }?.let {
                return@tx UseResult(coupon to def, it)
            }
            if (coupon.state !=
                CouponState.READY
            ) {
                throw RewardsRuleException("COUPON_NOT_READY", "the coupon is ${coupon.state}")
            }
            if (def.benefitType.target != targetKind) {
                throw RewardsRuleException(
                    "TARGET_KIND_MISMATCH",
                    "${def.benefitType} applies to ${def.benefitType.target}",
                )
            }
            validateTarget(tx, partyId, def, targetId)
            val now = engine.now()
            val using = coupon.copy(state = CouponState.USING, targetKind = targetKind, targetId = targetId)
            tx.updateCoupon(using)
            val f =
                Fulfilment(
                    UUID.randomUUID(),
                    couponId,
                    partyId,
                    def.benefitType.name,
                    FulfilmentState.PENDING,
                    0,
                    null,
                    null,
                    null,
                    now,
                    now,
                )
            tx.insertFulfilment(f)
            UseResult(using to def, f)
        }

    @Suppress("CyclomaticComplexMethod", "ThrowsCount") // one rule table; splitting hides it
    private fun validateTarget(tx: RewardsTx, partyId: UUID, def: CouponDef, targetId: String?) {
        when (def.benefitType.target) {
            TargetKind.NONE -> Unit
            TargetKind.SAVINGS_ACCOUNT -> {
                val account = targetId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let(tx::account)
                if (account == null || account.partyId != partyId || account.accountType != "SAVINGS") {
                    throw RewardsRuleException("TARGET_INVALID", "choose one of your savings accounts")
                }
            }
            TargetKind.BILL_PAYMENT -> {
                val fact = targetId?.let { tx.fact("BILL_PAID:$it") }
                if (fact == null ||
                    fact.partyId != partyId
                ) {
                    throw RewardsRuleException("TARGET_INVALID", "choose an Energo-Pro bill paid from your account")
                }
                if (fact.occurredAt.isBefore(engine.now().minus(Duration.ofDays(BILL_WINDOW_DAYS)))) {
                    throw RewardsRuleException(
                        "TARGET_INVALID",
                        "the bill was paid more than $BILL_WINDOW_DAYS days ago",
                    )
                }
                if (tx.coupons(partyId).any {
                        it.targetId == targetId &&
                            it.state in setOf(CouponState.USING, CouponState.USED)
                    }
                ) {
                    throw RewardsRuleException("TARGET_ALREADY_REWARDED", "this bill already received cashback")
                }
            }
            TargetKind.LOAN -> if (targetId ==
                null
            ) {
                throw RewardsRuleException("TARGET_INVALID", "choose one of your loans")
            }
        }
    }

    /** Step 2 and 3 of the saga. Safe to call again for the same fulfilment (commands are idempotent on the coupon id). */
    fun execute(fulfilmentId: UUID): UseResult {
        val loaded = store.tx { tx ->
            val f = tx.fulfilment(fulfilmentId) ?: error("unknown fulfilment $fulfilmentId")
            val c = tx.coupon(f.couponId)!!
            Triple(c, tx.couponDef(c.market, c.couponDefId)!!, f)
        }
        val (coupon, def, f) = loaded
        if (f.state != FulfilmentState.PENDING) return UseResult(coupon to def, f)
        return try {
            val command = command(coupon, def, f.fulfilmentId)
            val ref = command.run()
            commit(f, coupon, def, command, ref)
        } catch (e: DownstreamRejected) {
            compensate(f, e.code, e.message ?: e.code)
            throw RewardsRuleException(e.code, e.message ?: "the benefit could not be applied", e)
        } catch (e: RewardsRuleException) {
            compensate(f, e.code, e.message ?: e.code)
            throw e
        } catch (e: DownstreamUnavailable) {
            log.warnf("fulfilment %s pending: %s", fulfilmentId, e.message)
            store.tx { tx ->
                val updated = f.copy(attempts = f.attempts + 1, lastError = e.message, updatedAt = engine.now())
                tx.updateFulfilment(updated)
                UseResult(tx.coupon(coupon.couponId)!! to def, updated)
            }
        }
    }

    private fun commit(f: Fulfilment, coupon: Coupon, def: CouponDef, command: Command, ref: String): UseResult =
        store.tx { tx ->
            val now = engine.now()
            val c = tx.coupon(coupon.couponId, forUpdate = true)!!
            val outcomeJson = engine.codec.write(command.outcome.copy(reference = ref))
            val used = c.copy(state = CouponState.USED, usedAt = now, outcome = outcomeJson)
            tx.updateCoupon(used)
            tx.insertEntry(
                LedgerEntry(
                    UUID.randomUUID(),
                    c.partyId,
                    EntryType.BURN,
                    EntrySource.REDEMPTION,
                    0,
                    -c.charged,
                    c.couponId,
                    "burn:${c.couponId}",
                    RewardsEngine.RULE_VERSION,
                    def.budgetLineId,
                    c.market,
                    now,
                ),
            )
            if (def.benefitType.credit &&
                command.creditCost > BigDecimal.ZERO
            ) {
                tx.consumeBudget(def.budgetLineId, command.creditCost)
            }
            val applied = f.copy(
                state = FulfilmentState.APPLIED,
                attempts = f.attempts + 1,
                commandRef = ref,
                outcome = outcomeJson,
                updatedAt = now,
            )
            tx.updateFulfilment(applied)
            tx.enqueue(
                OutboxEvent(
                    "coupon_used",
                    c.partyId,
                    engine.couponEvent(used) + mapOf("benefitType" to def.benefitType, "reference" to ref),
                ),
                now,
            )
            tx.enqueue(OutboxEvent("flux_burned", c.partyId, mapOf("couponId" to c.couponId, "flux" to c.charged)), now)
            UseResult(used to def, applied)
        }

    private fun compensate(f: Fulfilment, code: String, message: String) = store.tx { tx ->
        val c = tx.coupon(f.couponId, forUpdate = true)!!
        if (c.state ==
            CouponState.USING
        ) {
            tx.updateCoupon(c.copy(state = CouponState.READY, targetKind = null, targetId = null))
        }
        tx.updateFulfilment(
            f.copy(
                state = FulfilmentState.FAILED,
                attempts = f.attempts + 1,
                lastError = "$code: $message",
                updatedAt = engine.now(),
            ),
        )
    }

    /** Retries PENDING fulfilments; after [MAX_ATTEMPTS] the coupon goes back to READY. */
    fun retryPending() {
        val due = store.tx { tx ->
            tx.pendingFulfilments(RETRY_BATCH)
        }.filter { it.updatedAt.isBefore(engine.now().minus(RETRY_AFTER)) }
        for (f in due) {
            if (f.attempts >= MAX_ATTEMPTS) {
                compensate(f, "FULFILMENT_TIMEOUT", "the benefit could not be applied after ${f.attempts} attempts")
                continue
            }
            runCatching {
                execute(f.fulfilmentId)
            }.onFailure { log.warnf("retry of fulfilment %s: %s", f.fulfilmentId, it.message) }
        }
    }

    fun preview(partyId: UUID, couponId: UUID, targetKind: TargetKind, targetId: String?): Preview {
        engine.ensureMembership(partyId)
        val (coupon, def) = store.tx { tx ->
            val c = customers.owned(tx, partyId, couponId)
            c to tx.couponDef(c.market, c.couponDefId)!!
        }
        if (def.benefitType.target !=
            targetKind
        ) {
            throw RewardsRuleException(
                "TARGET_KIND_MISMATCH",
                "${def.benefitType} applies to ${def.benefitType.target}",
            )
        }
        val withTarget = coupon.copy(targetKind = targetKind, targetId = targetId)
        return when (def.benefitType) {
            BenefitType.LOAN_RATE_REDUCTION -> {
                val (before, after) = lending.preview(
                    lending.loan(partyId, loanId(targetId)).loanId,
                    def.params.deltaBps ?: 0,
                    months(def),
                )
                Preview(before, after, listOf("RATE_FOR_GUARANTEED_PERIOD_ONLY", "NEW_SCHEDULE_BEFORE_EFFECT"))
            }
            BenefitType.BILL_CASHBACK -> {
                val bill = store.tx { tx -> targetId?.let { tx.fact("BILL_PAID:$it") } }
                val outcome = store.tx { tx -> command(withTarget, def, tx).outcome }
                Preview(
                    RateTerms(null, null, null, bill?.currency, amount = bill?.amount),
                    RateTerms(null, null, null, outcome.currency, amount = outcome.amount),
                    listOf("CASHBACK_TO_CURRENT_ACCOUNT"),
                )
            }
            BenefitType.INSTALMENT_RELIEF -> {
                val loan = lending.loan(partyId, loanId(targetId))
                val outcome = store.tx { tx -> command(withTarget, def, tx).outcome }
                val after = loan.nextInstalment?.let { it - (outcome.amount ?: BigDecimal.ZERO) }
                Preview(
                    RateTerms(null, null, loan.nextInstalment, loan.currency),
                    RateTerms(null, null, after, loan.currency),
                    listOf("CREDIT_BEFORE_NEXT_INSTALMENT"),
                )
            }
            BenefitType.DEPOSIT_RATE_BOOST -> depositBoostPreview(coupon, def)
            else -> Preview(
                RateTerms.NONE,
                RateTerms.NONE,
                listOf("RATE_DELTA_BPS:${def.params.deltaBps ?: 0}", "DURATION_DAYS:${def.params.durationDays ?: 0}"),
            )
        }
    }

    /**
     * The current savings rate is the interest service's to tell, and this service has no read grant
     * there, so `before` stays empty; `after` says until when the boost runs, the same date the use
     * itself will book (see command()).
     */
    private fun depositBoostPreview(coupon: Coupon, def: CouponDef): Preview {
        val days = def.params.durationDays ?: DEFAULT_DAYS
        val until = LocalDate.now(engine.clock.withZone(coupon.market.zone)).plusDays(days.toLong())
        return Preview(
            RateTerms.NONE,
            RateTerms.NONE.copy(until = until),
            listOf("RATE_DELTA_BPS:${def.params.deltaBps ?: 0}", "DURATION_DAYS:$days"),
        )
    }

    private fun command(coupon: Coupon, def: CouponDef, fulfilmentId: UUID): Command = store.tx { tx ->
        command(coupon, def, tx, fulfilmentId)
    }

    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "LongMethod")
    private fun command(coupon: Coupon, def: CouponDef, tx: RewardsTx, fulfilmentId: UUID? = null): Command {
        val p = def.params
        val ref = "coupon:${coupon.couponId}"
        // transaction-service keeps a FAILED credit FAILED for its sourceRef, so each saga run pays under its own.
        val cashRef = "rc:${fulfilmentId ?: coupon.couponId}"
        val today = LocalDate.now(engine.clock.withZone(coupon.market.zone))
        return when (def.benefitType) {
            BenefitType.BILL_CASHBACK -> {
                val bill =
                    tx.fact("BILL_PAID:${coupon.targetId}")
                        ?: throw RewardsRuleException("TARGET_INVALID", "bill not found")
                val pct = BigDecimal(p.percent ?: 0).divide(BigDecimal(PERCENT))
                val amount = bill.amount!!.multiply(pct).min(p.cap ?: bill.amount).setScale(2, RoundingMode.DOWN)
                val currency = bill.currency ?: coupon.market.currency
                val account = currentAccount(tx, coupon.partyId, currency)
                Command(FulfilmentOutcome("BILL_CASHBACK", amount, currency), {
                    cash.credit(coupon.partyId, account, amount, currency, "BILL_CASHBACK", cashRef)
                }, BigDecimal.ZERO)
            }
            BenefitType.DEPOSIT_RATE_BOOST -> {
                val until = today.plusDays((p.durationDays ?: DEFAULT_DAYS).toLong())
                val accountId = UUID.fromString(coupon.targetId)
                Command(
                    FulfilmentOutcome(
                        "DEPOSIT_RATE_BOOST",
                        rateDeltaBps = p.deltaBps,
                        until = until.atStartOfDay(coupon.market.zone).toInstant(),
                    ),
                    { interest.addRateAdjustment(accountId, p.deltaBps ?: 0, today, until, ref, APPROVAL_REF) },
                    BigDecimal.ZERO,
                )
            }
            BenefitType.LOAN_RATE_REDUCTION -> {
                val loanId = lending.loan(coupon.partyId, loanId(coupon.targetId)).loanId // ownership
                val until = today.plusDays((p.durationDays ?: YEAR_DAYS).toLong())
                val cost = runCatching {
                    val (before, after) = lending.preview(loanId, p.deltaBps ?: 0, months(def))
                    ((before.instalment ?: BigDecimal.ZERO) - (after.instalment ?: BigDecimal.ZERO)).multiply(
                        BigDecimal(months(def)),
                    ).max(BigDecimal.ZERO)
                }.getOrDefault(BigDecimal.ZERO)
                Command(
                    FulfilmentOutcome(
                        "LOAN_RATE_REDUCTION",
                        rateDeltaBps = p.deltaBps,
                        until = until.atStartOfDay(coupon.market.zone).toInstant(),
                    ),
                    { lending.addRateAdjustment(loanId, p.deltaBps ?: 0, today, until, ref, APPROVAL_REF) },
                    cost,
                )
            }
            BenefitType.INSTALMENT_RELIEF -> {
                val loan = lending.loan(coupon.partyId, loanId(coupon.targetId))
                if (loan.partyId != null &&
                    loan.partyId != coupon.partyId
                ) {
                    throw RewardsRuleException("TARGET_INVALID", "choose one of your loans")
                }
                val instalment =
                    loan.nextInstalment
                        ?: throw RewardsRuleException("TARGET_INVALID", "the loan has no upcoming instalment")
                val pct = BigDecimal(p.percent ?: 0).divide(BigDecimal(PERCENT))
                val amount = instalment.multiply(pct).min(p.cap ?: instalment).setScale(2, RoundingMode.DOWN)
                val account = currentAccount(tx, coupon.partyId, loan.currency)
                Command(FulfilmentOutcome("INSTALMENT_RELIEF", amount, loan.currency), {
                    cash.credit(coupon.partyId, account, amount, loan.currency, "INSTALMENT_RELIEF", cashRef)
                }, amount)
            }
            BenefitType.COSMETIC -> Command(FulfilmentOutcome("THEME_UNLOCKED:${p.theme}"), {
                "theme:${p.theme}"
            }, BigDecimal.ZERO)
            BenefitType.FEE_WAIVER, BenefitType.CASH_CREDIT -> throw RewardsRuleException(
                "NOT_SUPPORTED",
                "${def.benefitType} is not available yet",
            )
        }
    }

    private fun currentAccount(tx: RewardsTx, partyId: UUID, currency: String): UUID =
        tx.accountsOf(partyId).firstOrNull {
            it.accountType == "CURRENT" &&
                it.currency == currency &&
                (it.status == null || it.status == "ACTIVE")
        }?.accountId
            ?: throw RewardsRuleException("NO_CURRENT_ACCOUNT", "no active current account in $currency")

    private fun loanId(targetId: String?): UUID = targetId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw RewardsRuleException("TARGET_INVALID", "choose one of your loans")

    private fun months(def: CouponDef): Int = ((def.params.durationDays ?: YEAR_DAYS) / DAYS_PER_MONTH).coerceAtLeast(1)

    companion object {
        const val APPROVAL_REF = "SPEC-2026-11-05"
        const val BILL_WINDOW_DAYS = 90L
        const val MAX_ATTEMPTS = 6
        const val RETRY_BATCH = 50
        const val PERCENT = 100
        const val DEFAULT_DAYS = 90
        const val YEAR_DAYS = 365
        const val DAYS_PER_MONTH = 30
        val RETRY_AFTER: Duration = Duration.ofMinutes(1)
    }
}
