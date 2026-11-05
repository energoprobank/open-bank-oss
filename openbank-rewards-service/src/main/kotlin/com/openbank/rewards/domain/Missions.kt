// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.domain

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * Normalised behaviour the engine rewards. The credit facts at the bottom exist ONLY so the
 * guardrail can name them: [CreditGuardrail] refuses them before they are stored, whatever a
 * mission definition says (G-CR-01, a code-level deny-list, not configuration).
 */
enum class FactType {
    ACCOUNT_ACTIVATED,
    CARD_ACTIVATED,
    FUNDING_RECEIVED,
    QUIZ_PASSED,
    TRANSFER_SENT,
    CARD_PAYMENT,
    BILL_PAID,
    SAVINGS_GOAL_SET,
    SAVINGS_GOAL_REACHED,
    SAVINGS_TOPUP,
    REFERRAL_RELEASED_REFERRER,
    REFERRAL_RELEASED_REFEREE,
    INSTALMENT_PAID_ON_TIME,
    TENURE_ANNIVERSARY,
    SALARY_INFLOW,
    UTILITY_MANDATE_ACTIVE,
    REACTIVATED,
    APP_DAY,

    // --- deny-listed credit facts (G-CR-01): never stored, never rewarded ---
    LOAN_APPLIED,
    LOAN_APPROVED,
    LOAN_DISBURSED,
    CREDIT_LIMIT_INCREASED,
    CREDIT_UTILISED,
    MINIMUM_PAYMENT_MADE,
}

object CreditGuardrail {
    /** G-CR-01: applying, approval, drawdown, limit increase, utilisation and minimum payments earn nothing. */
    val DENIED: Set<FactType> = setOf(
        FactType.LOAN_APPLIED,
        FactType.LOAN_APPROVED,
        FactType.LOAN_DISBURSED,
        FactType.CREDIT_LIMIT_INCREASED,
        FactType.CREDIT_UTILISED,
        FactType.MINIMUM_PAYMENT_MADE,
    )

    fun admissible(type: FactType): Boolean = type !in DENIED
}

data class BehaviourFact(
    val factId: String,
    val partyId: UUID,
    val type: FactType,
    val occurredAt: Instant,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    /** Free reference: merchant for card payments, lesson id for quizzes, payment id for bills. */
    val ref: String? = null,
    val sourceEventId: String? = null,
)

enum class MissionKind { ONE_TIME, RECURRING }

enum class Cadence { ONCE, WEEKLY, MONTHLY, YEARLY, ROLLING }

enum class MinAmount { FUNDING, CARD }

data class MissionRule(
    val fact: FactType,
    val count: Int = 1,
    val minAmount: MinAmount? = null,
    val distinctMerchants: Int? = null,
    val consecutiveMonths: Int? = null,
    val ref: String? = null,
    val hidden: Boolean = false,
) {
    val target: Int get() = consecutiveMonths ?: count
}

data class Reward(val flux: Int? = null, val couponDefId: String? = null, val preChargePct: Int? = null) {
    init {
        require(flux != null || couponDefId != null) { "a reward pays FLUX, a coupon, or both" }
        require(flux == null || flux > 0) { "reward FLUX must be positive" }
        require(preChargePct == null || preChargePct in 0..PERCENT) { "pre-charge is a percentage" }
    }
}

data class MissionDef(
    val missionId: String,
    val version: Int,
    val market: Market,
    val kind: MissionKind,
    val cadence: Cadence,
    val rule: MissionRule,
    val reward: Reward,
    val handoff: String?,
    val sortOrder: Int,
    val budgetLineId: String,
    val active: Boolean,
)

enum class MissionState { NOT_STARTED, IN_PROGRESS, CLAIM_PENDING, CLAIMED, LAPSED }

data class Progress(val count: Int = 0, val merchants: Set<String> = emptySet(), val months: Set<String> = emptySet())

data class MissionInstance(
    val partyId: UUID,
    val missionId: String,
    val version: Int,
    val cycle: String,
    val state: MissionState,
    val progress: Progress,
    val reward: Reward,
    val startedAt: Instant,
    val completedAt: Instant?,
    val claimedAt: Instant?,
    val windowEndsAt: Instant?,
) {
    val autoClaimAt: Instant? get() = completedAt?.takeIf {
        state == MissionState.CLAIM_PENDING
    }?.plus(AUTO_CLAIM_AFTER)

    companion object {
        /** Spec B7: auto-claim after 30 days, no "stop accruing". */
        val AUTO_CLAIM_AFTER: Duration = Duration.ofDays(30)
    }
}

object Cycles {
    const val ONCE = "once"
    const val ROLLING = "rolling"

    fun keyFor(cadence: Cadence, at: Instant, zone: ZoneId): String {
        val date = at.atZone(zone).toLocalDate()
        return when (cadence) {
            Cadence.ONCE -> ONCE
            Cadence.ROLLING -> ROLLING
            Cadence.WEEKLY -> "%d-W%02d".format(
                date.get(IsoFields.WEEK_BASED_YEAR),
                date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
            )
            Cadence.MONTHLY -> YearMonth.from(date).toString()
            Cadence.YEARLY -> date.year.toString()
        }
    }

    fun windowEnd(cadence: Cadence, at: Instant, zone: ZoneId): Instant? {
        val date = at.atZone(zone).toLocalDate()
        val end: LocalDate = when (cadence) {
            Cadence.ONCE, Cadence.ROLLING -> return null
            Cadence.WEEKLY -> date.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            Cadence.MONTHLY -> date.with(TemporalAdjusters.firstDayOfNextMonth())
            Cadence.YEARLY -> date.with(TemporalAdjusters.firstDayOfNextYear())
        }
        return end.atStartOfDay(zone).toInstant()
    }
}

/** Pure mission evaluation: no IO, deterministic, so every rule is unit-testable. */
object MissionEngine {

    /** True when [fact] is the kind of behaviour [def] counts (before progress or state). */
    fun matches(def: MissionDef, fact: BehaviourFact, params: MarketParams): Boolean {
        val rule = def.rule
        if (fact.type != rule.fact) return false
        if (rule.ref != null && fact.ref != rule.ref) return false
        val minimum = when (rule.minAmount) {
            MinAmount.FUNDING -> params.fundingMinimum
            MinAmount.CARD -> params.cardMinimum
            null -> null
        }
        if (minimum != null && (fact.amount == null || fact.amount < minimum)) return false
        return true
    }

    fun newInstance(def: MissionDef, partyId: UUID, at: Instant, zone: ZoneId): MissionInstance = MissionInstance(
        partyId = partyId,
        missionId = def.missionId,
        version = def.version,
        cycle = Cycles.keyFor(def.cadence, at, zone),
        state = MissionState.IN_PROGRESS,
        progress = Progress(),
        reward = def.reward,
        startedAt = at,
        completedAt = null,
        claimedAt = null,
        windowEndsAt = Cycles.windowEnd(def.cadence, at, zone),
    )

    /** Applies one matching fact. Returns the instance unchanged when it is no longer in progress. */
    fun apply(def: MissionDef, instance: MissionInstance, fact: BehaviourFact, zone: ZoneId): MissionInstance {
        if (instance.state != MissionState.IN_PROGRESS) return instance
        val rule = def.rule
        val month = YearMonth.from(fact.occurredAt.atZone(zone)).toString()
        val progress = instance.progress.let { p ->
            p.copy(
                count = p.count + 1,
                merchants = if (rule.distinctMerchants != null &&
                    fact.ref != null
                ) {
                    p.merchants + fact.ref
                } else {
                    p.merchants
                },
                months = if (rule.consecutiveMonths != null) p.months + month else p.months,
            )
        }
        val done = isComplete(rule, progress)
        return instance.copy(
            progress = progress,
            state = if (done) MissionState.CLAIM_PENDING else MissionState.IN_PROGRESS,
            completedAt = if (done) fact.occurredAt.coerceAtLeast(instance.startedAt) else null,
        )
    }

    fun current(rule: MissionRule, progress: Progress): Int =
        if (rule.consecutiveMonths != null) longestRun(progress.months) else minOf(progress.count, rule.count)

    fun isComplete(rule: MissionRule, progress: Progress): Boolean = when {
        rule.consecutiveMonths != null -> longestRun(progress.months) >= rule.consecutiveMonths
        progress.count < rule.count -> false
        rule.distinctMerchants != null -> progress.merchants.size >= rule.distinctMerchants
        else -> true
    }

    /** Length of the longest run of consecutive calendar months in [months] ("yyyy-MM"). */
    fun longestRun(months: Set<String>): Int {
        if (months.isEmpty()) return 0
        val sorted = months.map(YearMonth::parse).sorted()
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i - 1].plusMonths(1) == sorted[i]) run + 1 else 1
            best = maxOf(best, run)
        }
        return best
    }

    fun claim(instance: MissionInstance, at: Instant): MissionInstance {
        if (instance.state == MissionState.CLAIMED) return instance
        if (instance.state != MissionState.CLAIM_PENDING) {
            throw RewardsRuleException("MISSION_NOT_CLAIMABLE", "mission ${instance.missionId} is ${instance.state}")
        }
        return instance.copy(state = MissionState.CLAIMED, claimedAt = at)
    }
}
