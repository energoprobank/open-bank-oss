// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import kotlin.random.Random

/**
 * Energo-Pro's billing system, the side a bank meets when its customer pays an electricity bill:
 * the supply point (where the electricity is delivered), monthly bills with Energo-Pro's collection
 * account and the payment reference, and the notice that a bill was paid.
 *
 * Bills are a pure function of the customer and the date: a bill per month for the last twelve months,
 * issued on the 5th of the following month and due on the 20th. Everything before the newest bill is
 * PAID (the customer paid it somewhere — Energo-Pro does not care where); the newest one is DUE until a
 * payment notice arrives, then PAID. A restart forgets notices, so the bank keeps its own record too.
 */
data class SupplyPoint(
    /** The EAN of the electricity meter point (CZ: 18 digits starting 859182; BG: 32Z…). */
    val id: String,
    val customerNumber: String,
    val street: String,
    val city: String,
    val postalCode: String,
    /** The distribution tariff, e.g. D02d (CZ) or "Двутарифно" (BG). */
    val tariff: String,
    val currency: String,
    /** The monthly advance (záloha) the customer pays towards the yearly settlement. */
    val monthlyAdvance: BigDecimal,
)

enum class BillStatus { DUE, PAID, OVERDUE }

data class EnergoProBill(
    val id: String,
    val customerNumber: String,
    val supplyPointId: String,
    /** `yyyy-MM`, the month the electricity was used in. */
    val period: String,
    val amount: BigDecimal,
    val currency: String,
    val issuedOn: LocalDate,
    val dueDate: LocalDate,
    val status: BillStatus,
    /** Where the money goes: Energo-Pro's collection account in that market. */
    val collectionIban: String,
    /** CZ: the variable symbol (10 digits); BG: the invoice number. The bank puts it on the payment. */
    val reference: String,
    val consumptionKwh: Int,
)

/** A bank's notice that its customer paid a bill (Energo-Pro matches it by [reference]). */
data class PaymentNotice(
    val reference: String,
    val amount: BigDecimal,
    val currency: String,
    val payerIban: String?,
    val bankReference: String?,
    val paidAt: java.time.Instant,
)

sealed interface NoticeOutcome {
    data class Accepted(val bill: EnergoProBill, val notice: PaymentNotice) : NoticeOutcome

    data class AmountMismatch(val bill: EnergoProBill) : NoticeOutcome

    data object UnknownReference : NoticeOutcome
}

/** Energo-Pro's own collection account per market (what a bank's biller registry holds). */
data class CollectionAccount(
    val market: EnergoProMarket,
    val name: String,
    val iban: String,
    val bic: String,
    val currency: String,
)

object EnergoProBilling {
    val COLLECTION = mapOf(
        EnergoProMarket.CZ to
            CollectionAccount(EnergoProMarket.CZ, "Energo-Pro, a.s.", "CZ1520100000002001234561", "FIOBCZPP", "CZK"),
        EnergoProMarket.BG to
            CollectionAccount(
                EnergoProMarket.BG,
                "Енерго-Про Продажби АД",
                "BG18RZBB91550123456789",
                "RZBBBGSF",
                "EUR",
            ),
    )

    private const val HISTORY_MONTHS = 12L
    private const val ISSUE_DAY = 5
    private const val DUE_DAY = 20
    private const val EAN_CZ_PREFIX = "859182"
    private const val EAN_CZ_DIGITS = 12
    private const val CZ_PRICE_PER_KWH = "5.20"
    private const val BG_PRICE_PER_KWH = "0.13"
    private const val MIN_KWH = 180
    private const val MAX_KWH = 420
    private const val WINTER_FACTOR = "1.35"
    private const val SUMMER_FACTOR = "0.80"
    private const val JITTER_PERCENT = 12
    private const val HUNDRED = 100
    private val WINTER = setOf(Month.NOVEMBER, Month.DECEMBER, Month.JANUARY, Month.FEBRUARY, Month.MARCH)
    private val SUMMER = setOf(Month.JUNE, Month.JULY, Month.AUGUST)
    private const val ROUND_TO_HUNDREDS = -2
    private const val MONEY_SCALE = 2
    private const val YEAR_DIGIT = 10

    private fun random(customerNumber: String) = Random(customerNumber.hashCode())

    fun supplyPoint(c: EnergoProCustomer): SupplyPoint {
        val r = random(c.customerNumber)
        val baseKwh = r.nextInt(MIN_KWH, MAX_KWH)
        val digits = c.customerNumber.filter(Char::isDigit)
        return when (c.market) {
            EnergoProMarket.CZ -> SupplyPoint(
                id = EAN_CZ_PREFIX + digits.padStart(EAN_CZ_DIGITS, '0'),
                customerNumber = c.customerNumber,
                street = c.street,
                city = c.city,
                postalCode = c.postalCode,
                tariff = if (baseKwh > (MIN_KWH + MAX_KWH) / 2) "D25d" else "D02d",
                currency = "CZK",
                monthlyAdvance = price(
                    baseKwh,
                    CZ_PRICE_PER_KWH,
                ).setScale(ROUND_TO_HUNDREDS, RoundingMode.HALF_UP).setScale(MONEY_SCALE),
            )
            EnergoProMarket.BG -> SupplyPoint(
                id = "32Z" + digits.padStart(EAN_CZ_DIGITS, '0') + "B",
                customerNumber = c.customerNumber,
                street = c.street,
                city = c.city,
                postalCode = c.postalCode,
                tariff = "Двутарифно",
                currency = "EUR",
                monthlyAdvance = price(
                    baseKwh,
                    BG_PRICE_PER_KWH,
                ).setScale(0, RoundingMode.HALF_UP).setScale(MONEY_SCALE),
            )
        }
    }

    /** The customer's bills, newest first, as of [today]. [paidReferences] are payment notices received. */
    fun bills(c: EnergoProCustomer, today: LocalDate, paidReferences: Set<String>): List<EnergoProBill> {
        val point = supplyPoint(c)
        val account = COLLECTION.getValue(c.market)
        val newestIssued = YearMonth.from(today).let { if (today.dayOfMonth >= ISSUE_DAY) it else it.minusMonths(1) }
        val baseKwh = random(c.customerNumber).nextInt(MIN_KWH, MAX_KWH)
        return (0 until HISTORY_MONTHS).map { back ->
            val issueMonth = newestIssued.minusMonths(back)
            val period = issueMonth.minusMonths(1)
            val r = Random("${c.customerNumber}|$period".hashCode())
            val season = when (period.month) {
                in WINTER -> BigDecimal(WINTER_FACTOR)
                in SUMMER -> BigDecimal(SUMMER_FACTOR)
                else -> BigDecimal.ONE
            }
            val jitter = BigDecimal(HUNDRED + r.nextInt(-JITTER_PERCENT, JITTER_PERCENT)).divide(BigDecimal(HUNDRED))
            val kwh = BigDecimal(baseKwh).multiply(season).multiply(jitter).setScale(0, RoundingMode.HALF_UP).toInt()
            val perKwh = if (c.market == EnergoProMarket.CZ) CZ_PRICE_PER_KWH else BG_PRICE_PER_KWH
            val reference = reference(c, period)
            val dueDate = issueMonth.atDay(DUE_DAY)
            val status = when {
                back > 0 || reference in paidReferences -> BillStatus.PAID
                today.isAfter(dueDate) -> BillStatus.OVERDUE
                else -> BillStatus.DUE
            }
            EnergoProBill(
                id = "${c.customerNumber}-$period", customerNumber = c.customerNumber, supplyPointId = point.id,
                period = period.toString(), amount = price(kwh, perKwh), currency = point.currency,
                issuedOn = issueMonth.atDay(ISSUE_DAY), dueDate = dueDate, status = status,
                collectionIban = account.iban, reference = reference, consumptionKwh = kwh,
            )
        }
    }

    /** CZ: a 10-digit variable symbol (customer digits + month + year digit); BG: an invoice number. */
    fun reference(c: EnergoProCustomer, period: YearMonth): String {
        val digits = c.customerNumber.filter(Char::isDigit)
        return when (c.market) {
            EnergoProMarket.CZ -> digits + "%02d".format(period.monthValue) + (period.year % YEAR_DIGIT)
            EnergoProMarket.BG -> "EP${digits}${period.year}${"%02d".format(period.monthValue)}"
        }
    }

    private fun price(kwh: Int, perKwh: String): BigDecimal =
        BigDecimal(kwh).multiply(BigDecimal(perKwh)).setScale(2, RoundingMode.HALF_UP)
}
