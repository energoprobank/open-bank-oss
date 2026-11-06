// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/** One of the customer's accounts, as account-service lists it. */
data class AccountRef(val id: UUID, val iban: String, val currency: String, val type: String, val status: String)

/** A booked payment on one of the customer's accounts, seen from that account. */
data class Txn(
    val id: UUID,
    val accountId: UUID,
    /** Money leaving [accountId]. */
    val outgoing: Boolean,
    val amount: BigDecimal,
    val currency: String,
    val description: String,
    val date: LocalDate,
    val category: String?,
    val rail: String?,
    val instructionType: String?,
    /** The other account when it is also one of the customer's own (a transfer between them). */
    val ownCounterparty: Boolean,
)

// ---------------------------------------------------------------- categories

/** Spending categories: the transaction's own category when it has one, else the merchant's words. */
object Categories {
    private val RULES: List<Pair<String, List<String>>> = listOf(
        "GROCERIES" to
            listOf("albert", "lidl", "billa", "tesco", "kaufland", "penny", "rohlik", "globus", "fantastico", "лидл"),
        "DINING" to
            listOf(
                "costa", "kfc", "mcdonald", "starbucks", "restaurace", "bistro", "pizza", "kavarna", "wolt",
                "foodora",
            ),
        "TRANSPORT" to
            listOf(
                "benzina", "shell", "omv", "mol ", "orlen", "regiojet", "ceske drahy", "dpp", "uber", "bolt",
                "lukoil",
            ),
        "SHOPPING" to listOf("alza", "mall", "ikea", "datart", "zara", "h&m", "notino", "dm drogerie", "emag"),
        "UTILITIES" to listOf(
            "energo", "cez", "e.on", "innogy", "pre ", "prazska energetika", "vodafone", "o2", "t-mobile", "pvk",
            "sipo",
            "elektr", "plyn", "voda", "енерго",
        ),
        "HOUSING" to listOf("najem", "rent", "hypote", "svj", "nájem"),
        "SUBSCRIPTIONS" to
            listOf("netflix", "spotify", "apple.com", "google", "disney", "hbo", "youtube", "voyo", "audible"),
        "HEALTH" to listOf("lekarna", "dr.max", "benu", "pharmacy", "nemocnice", "zubni"),
        "CASH" to listOf("atm", "vyber", "bankomat"),
    )
    val NAMES = mapOf(
        "GROCERIES" to "Groceries", "DINING" to "Eating out", "TRANSPORT" to "Transport", "SHOPPING" to "Shopping",
        "UTILITIES" to "Energy and utilities", "HOUSING" to "Housing", "SUBSCRIPTIONS" to "Subscriptions",
        "HEALTH" to "Health", "CASH" to "Cash", "SALARY" to "Salary", "OTHER" to "Other",
    )

    fun of(t: Txn): String {
        t.category?.uppercase(Locale.ROOT)?.takeIf { it in NAMES }?.let { return it }
        val text = plain(t.description)
        return RULES.firstOrNull { (_, words) -> words.any { plain(it) in text } }?.first ?: "OTHER"
    }

    /** Lower case, accents removed: "Energo-Pro Elektřina" → "energo-pro elektrina". */
    fun plain(s: String): String = java.text.Normalizer.normalize(
        s,
        java.text.Normalizer.Form.NFD,
    ).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
}

// ---------------------------------------------------------------- subscriptions

data class DetectedSubscription(
    val id: String,
    val merchantName: String,
    val amount: BigDecimal,
    val currency: String,
    val frequency: String,
    val nextChargeDate: LocalDate,
    val paidBy: String,
    val lastChargeDate: LocalDate,
)

/**
 * A subscription is what the customer pays the same payee, about the same amount, at a steady rhythm:
 * at least three payments, a weekly, monthly or yearly interval, amounts within a fifth of each other,
 * and the last one recent enough that the next is still expected. Card payments, direct debits and
 * standing orders only — a one-off transfer that happens to repeat is the customer's own doing.
 */
object SubscriptionDetector {
    private const val MIN_PAYMENTS = 3
    private const val AMOUNT_TOLERANCE = "0.20"
    private const val OVERDUE_FACTOR = 1.6
    private const val WEEK = 7L
    private const val MONTH = 30L
    private const val YEAR = 365L
    private const val WEEK_SLACK = 1L
    private const val MONTH_SLACK = 5L
    private const val YEAR_SLACK = 15L
    private const val KEY_WORDS = 3
    private val RHYTHMS = listOf(
        Triple("WEEKLY", (WEEK - WEEK_SLACK)..(WEEK + WEEK_SLACK), WEEK),
        Triple("MONTHLY", (MONTH - MONTH_SLACK)..(MONTH + MONTH_SLACK), MONTH),
        Triple("YEARLY", (YEAR - YEAR_SLACK)..(YEAR + YEAR_SLACK), YEAR),
    )
    private const val ID_HEX = 16

    fun detect(party: UUID, payments: List<Txn>, today: LocalDate): List<DetectedSubscription> =
        payments.filter { it.outgoing && !it.ownCounterparty && paidBy(it) != null }
            .groupBy { key(it.description) }
            .filterKeys { it.isNotBlank() }
            .mapNotNull { (key, group) -> subscription(party, key, group.sortedBy { it.date }, today) }
            .sortedBy { it.nextChargeDate }

    private fun subscription(party: UUID, key: String, sorted: List<Txn>, today: LocalDate): DetectedSubscription? {
        if (sorted.size < MIN_PAYMENTS) return null
        val gaps = sorted.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }.sorted()
        val median = gaps[gaps.size / 2]
        val (frequency, _, step) = RHYTHMS.firstOrNull { median in it.second } ?: return null
        val amounts = sorted.map { it.amount }.sorted()
        val typical = amounts[amounts.size / 2]
        val tolerance = typical.multiply(BigDecimal(AMOUNT_TOLERANCE))
        if (sorted.takeLast(MIN_PAYMENTS).any { (it.amount - typical).abs() > tolerance }) return null
        val last = sorted.last()
        if (ChronoUnit.DAYS.between(last.date, today) > step * OVERDUE_FACTOR) return null
        var next = last.date.plusDays(step)
        if (frequency == "MONTHLY") next = last.date.plusMonths(1)
        if (frequency == "YEARLY") next = last.date.plusYears(1)
        while (next.isBefore(today)) next = next.plusDays(step)
        return DetectedSubscription(
            id = id(party, key),
            merchantName = last.description.trim(),
            amount = last.amount,
            currency = last.currency,
            frequency = frequency,
            nextChargeDate = next,
            paidBy = paidBy(last)!!,
            lastChargeDate = last.date,
        )
    }

    fun paidBy(t: Txn): String? = when {
        t.instructionType == "STANDING_ORDER" -> "STANDING_ORDER"
        t.instructionType == "DIRECT_DEBIT" || t.rail == "SDD" -> "DIRECT_DEBIT"
        t.rail == "CARD" -> "CARD"
        else -> null
    }

    /** The payee, without the numbers that change from one payment to the next (dates, invoice numbers). */
    fun key(description: String): String =
        Categories.plain(description).replace(Regex("[0-9#/.*:-]+"), " ").split(Regex("\\s+"))
            .filter { it.length > 1 }.take(KEY_WORDS).joinToString(" ")

    fun id(party: UUID, key: String): String = MessageDigest.getInstance("SHA-256").digest("$party|$key".toByteArray())
        .joinToString("") { "%02x".format(it) }.take(ID_HEX)
}

// ---------------------------------------------------------------- insights

data class CategoryTotal(
    val id: String,
    val name: String,
    val amount: BigDecimal,
    val share: Double,
    val changeFromLastMonth: BigDecimal?,
)

data class Spending(
    val month: YearMonth,
    val currency: String,
    val totalOut: BigDecimal,
    val totalIn: BigDecimal,
    val categories: List<CategoryTotal>,
)

data class CashFlowItem(
    val id: String,
    val date: LocalDate,
    val title: String,
    val amount: BigDecimal,
    val source: String,
)

object Insights {
    /** A month's money out by category (transfers between own accounts are not spending) and money in. */
    fun spending(month: YearMonth, currency: String, payments: List<Txn>): Spending {
        val real = payments.filter { !it.ownCounterparty && it.currency == currency }
        val thisMonth = real.filter { YearMonth.from(it.date) == month }
        val lastMonth = real.filter { YearMonth.from(it.date) == month.minusMonths(1) }
        val out = thisMonth.filter { it.outgoing }.groupBy(Categories::of).mapValues { (_, v) -> sum(v) }
        val before = lastMonth.filter { it.outgoing }.groupBy(Categories::of).mapValues { (_, v) -> sum(v) }
        val totalOut = out.values.fold(BigDecimal.ZERO, BigDecimal::add)
        val categories = out.entries.sortedByDescending { it.value }.map { (id, amount) ->
            CategoryTotal(
                id,
                Categories.NAMES[id] ?: id,
                amount,
                if (totalOut.signum() == 0) 0.0 else amount.toDouble() / totalOut.toDouble(),
                before[id]?.let { amount - it },
            )
        }
        return Spending(month, currency, totalOut, sum(thisMonth.filter { !it.outgoing }), categories)
    }

    /**
     * What is still expected this month: detected subscriptions and standing orders, recurring income
     * (three or more monthly credits from the same payer), and known bills. Signed amounts.
     */
    fun upcoming(
        today: LocalDate,
        subscriptions: List<DetectedSubscription>,
        income: List<DetectedSubscription>,
        bills: List<CashFlowItem>,
    ): List<CashFlowItem> {
        val end = YearMonth.from(today).atEndOfMonth()
        val inMonth = { d: LocalDate -> !d.isBefore(today) && !d.isAfter(end) }
        val out = subscriptions.filter { inMonth(it.nextChargeDate) }.map {
            CashFlowItem(
                it.id,
                it.nextChargeDate,
                it.merchantName,
                it.amount.negate(),
                if (it.paidBy ==
                    "STANDING_ORDER"
                ) {
                    "STANDING_ORDER"
                } else if (it.paidBy ==
                    "DIRECT_DEBIT"
                ) {
                    "DIRECT_DEBIT"
                } else {
                    "SUBSCRIPTION"
                },
            )
        }
        val incoming = income.filter { inMonth(it.nextChargeDate) }.map {
            CashFlowItem(it.id, it.nextChargeDate, it.merchantName, it.amount, "SALARY")
        }
        return (out + incoming + bills.filter { inMonth(it.date) }).sortedBy { it.date }
    }

    /** Recurring income: credits from the same payer at a monthly rhythm (salary, pension). */
    fun recurringIncome(party: UUID, payments: List<Txn>, today: LocalDate): List<DetectedSubscription> =
        SubscriptionDetector.detect(
            party,
            payments.filter { !it.outgoing && !it.ownCounterparty }.map { it.copy(outgoing = true, rail = "CARD") },
            today,
        ).filter { it.frequency == "MONTHLY" }

    private fun sum(ts: List<Txn>) = ts.fold(BigDecimal.ZERO) { a, t -> a + t.amount }.setScale(2, RoundingMode.HALF_UP)
}
