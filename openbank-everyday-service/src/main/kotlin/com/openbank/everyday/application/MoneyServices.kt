// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.application

import com.openbank.everyday.domain.AccountRef
import com.openbank.everyday.domain.CashFlowItem
import com.openbank.everyday.domain.DetectedSubscription
import com.openbank.everyday.domain.Insights
import com.openbank.everyday.domain.Spending
import com.openbank.everyday.domain.SubscriptionDetector
import com.openbank.everyday.domain.Txn
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

private val PRAGUE: ZoneId = ZoneId.of("Europe/Prague")

/** The customer's accounts and their recent payments, read once per request. */
@ApplicationScoped
class PaymentHistory(
    private val accounts: AccountsPort,
    private val transactions: TransactionsPort,
    private val clock: Clock,
) {
    fun today(): LocalDate = LocalDate.now(clock.withZone(PRAGUE))

    fun accounts(partyId: UUID): List<AccountRef> = accounts.accounts(partyId).filter { it.status == "ACTIVE" }

    /** The account, when it is the customer's own; otherwise a 404 (never someone else's data). */
    fun own(partyId: UUID, accountId: UUID): AccountRef = accounts(partyId).firstOrNull { it.id == accountId }
        ?: throw ResourceNotFoundException("No such account", "ACCOUNT_NOT_FOUND")

    fun payments(partyId: UUID, since: LocalDate, only: AccountRef? = null): List<Txn> {
        val all = accounts(partyId)
        val own = all.map { it.id }.toSet()
        return (
            only?.let {
                listOf(it)
            } ?: all.filter { it.type == "CURRENT" }
            ).flatMap { transactions.payments(it, since, own) }
    }
}

// ---------------------------------------------------------------- direct debits

/**
 * Direct-debit consents held at the debtor's bank: CZ inkaso (the creditor's account), SIPO (the
 * household's SIPO number) and SEPA Core (the creditor identifier, EUR only). A collection the bank
 * receives is checked against an ACTIVE consent, its limit and frequency.
 */
@ApplicationScoped
class DirectDebitService(private val store: MoneyStore, private val history: PaymentHistory, private val clock: Clock) {
    fun mandates(partyId: UUID): List<Mandate> = store.mandates(partyId)

    @Suppress("LongParameterList")
    fun create(
        partyId: UUID,
        scheme: String,
        creditorName: String,
        creditorReference: String,
        debtorAccountId: UUID,
        limit: BigDecimal?,
        frequency: String,
    ): Mandate {
        require(scheme in SCHEMES) { "scheme must be one of ${SCHEMES.joinToString()}" }
        require(frequency in FREQUENCIES) { "frequency must be one of ${FREQUENCIES.joinToString()}" }
        require(creditorName.isNotBlank() && creditorName.length <= MAX_NAME) { "creditor_name is required" }
        require(limit == null || limit.signum() > 0) { "limit must be above zero" }
        val reference = creditorReference.replace(" ", "").uppercase()
        require(REFERENCE.getValue(scheme).matches(reference)) { "creditor_reference is not a valid $scheme reference" }
        val account = history.own(partyId, debtorAccountId)
        require(scheme != "SEPA_CORE" || account.currency == "EUR") { "a SEPA Core mandate needs a EUR account" }
        require(scheme == "SEPA_CORE" || account.currency == "CZK") { "inkaso and SIPO need a CZK account" }
        if (store.mandates(partyId).any {
                it.status == "ACTIVE" &&
                    it.scheme == scheme &&
                    it.creditorReference == reference
            }
        ) {
            throw ResourceConflictException("You already allow this creditor to collect", "MANDATE_EXISTS")
        }
        val m = Mandate(
            UUID.randomUUID(), partyId, scheme, creditorName.trim(), reference, account.id, limit, account.currency,
            frequency, "ACTIVE", clock.instant(),
        )
        store.insertMandate(m)
        return m
    }

    fun cancel(partyId: UUID, id: UUID): Mandate = store.cancelMandate(partyId, id, clock.instant())
        ?: throw ResourceNotFoundException("No such direct debit", "MANDATE_NOT_FOUND")

    private companion object {
        val SCHEMES = setOf("INKASO", "SIPO", "SEPA_CORE")
        val FREQUENCIES = setOf("MONTHLY", "QUARTERLY", "YEARLY", "ANY")
        const val MAX_NAME = 120
        val REFERENCE = mapOf(
            // inkaso: the creditor's Czech account, prefix-number/bank
            "INKASO" to Regex("^(\\d{1,6}-)?\\d{2,10}/\\d{4}$"),
            // SIPO: the ten-digit SIPO connection number
            "SIPO" to Regex("^\\d{10}$"),
            // SEPA creditor identifier: CC + check digits + business code + national id
            "SEPA_CORE" to Regex("^[A-Z]{2}\\d{2}[A-Z0-9]{3}[A-Z0-9]{1,28}$"),
        )
    }
}

// ---------------------------------------------------------------- subscriptions

data class SubscriptionView(val detected: DetectedSubscription, val choice: SubscriptionChoice)

/** Subscriptions detected from the customer's card payments, direct debits and standing orders. */
@ApplicationScoped
class SubscriptionService(
    private val history: PaymentHistory,
    private val store: MoneyStore,
    private val clock: Clock,
) {
    fun subscriptions(partyId: UUID): List<SubscriptionView> {
        val today = history.today()
        val choices = store.subscriptionChoices(partyId)
        return SubscriptionDetector.detect(partyId, history.payments(partyId, today.minusDays(HISTORY_DAYS)), today)
            .map { SubscriptionView(it, choices[it.id] ?: SubscriptionChoice(false, false)) }
    }

    /**
     * The customer asks to stop a subscription. The bank records the request (CANCEL_REQUESTED) and
     * tells the customer what it can and cannot do: a card subscription is the merchant's to end.
     */
    fun cancel(partyId: UUID, id: String): SubscriptionView = change(partyId, id) { it.copy(cancelRequested = true) }

    fun reminder(partyId: UUID, id: String, enabled: Boolean): SubscriptionView = change(partyId, id) {
        it.copy(reminderEnabled = enabled)
    }

    private fun change(
        partyId: UUID,
        id: String,
        update: (SubscriptionChoice) -> SubscriptionChoice,
    ): SubscriptionView {
        val current = subscriptions(partyId).firstOrNull { it.detected.id == id }
            ?: throw ResourceNotFoundException("No such subscription", "SUBSCRIPTION_NOT_FOUND")
        val choice = update(current.choice)
        store.saveSubscriptionChoice(partyId, id, choice, clock.instant())
        return current.copy(choice = choice)
    }

    private companion object {
        const val HISTORY_DAYS = 400L
    }
}

// ---------------------------------------------------------------- insights

data class CashFlow(
    val currency: String,
    val balanceToday: BigDecimal,
    val balanceEndOfMonth: BigDecimal,
    val upcoming: List<CashFlowItem>,
)

data class EnergyTrend(val currency: String, val lastTwelveMonths: BigDecimal, val previousTwelveMonths: BigDecimal?)

@ApplicationScoped
class InsightsService(private val history: PaymentHistory, private val bills: BillsService) {
    fun spending(partyId: UUID, accountId: UUID, month: YearMonth): Spending {
        val account = history.own(partyId, accountId)
        val payments = history.payments(partyId, month.minusMonths(1).atDay(1), account)
        return Insights.spending(month, account.currency, payments)
    }

    /** Today's balance (from the edge, which reads it for the app) plus what is still expected this month. */
    fun cashFlow(partyId: UUID, accountId: UUID, balanceToday: BigDecimal): CashFlow {
        val account = history.own(partyId, accountId)
        val today = history.today()
        val payments = history.payments(partyId, today.minusDays(HISTORY_DAYS), account)
        val subscriptions = SubscriptionDetector.detect(partyId, payments, today).filter {
            it.currency ==
                account.currency
        }
        val income = Insights.recurringIncome(partyId, payments, today).filter { it.currency == account.currency }
        val dueBills = bills.bills(partyId).filter { it.status != "PAID" && it.bill.currency == account.currency }.map {
            CashFlowItem(it.bill.id, it.bill.dueDate, "Energo-Pro ${it.bill.period}", it.bill.amount.negate(), "BILL")
        }
        val upcoming = Insights.upcoming(today, subscriptions, income, dueBills)
        val end = upcoming.fold(balanceToday) { a, i -> a + i.amount }
        return CashFlow(account.currency, balanceToday, end, upcoming)
    }

    /** What the linked Energo-Pro account cost over the last twelve months (Energo-Pro keeps twelve). */
    fun energyTrend(partyId: UUID): EnergyTrend? {
        val all = bills.bills(partyId).takeIf { it.isNotEmpty() } ?: return null
        val last = all.take(MONTHS).fold(BigDecimal.ZERO) { a, b -> a + b.bill.amount }
        val previous = all.drop(MONTHS).take(MONTHS).takeIf { it.size == MONTHS }?.fold(BigDecimal.ZERO) { a, b ->
            a +
                b.bill.amount
        }
        return EnergyTrend(all.first().bill.currency, last, previous)
    }

    private companion object {
        const val HISTORY_DAYS = 200L
        const val MONTHS = 12
    }
}

// ---------------------------------------------------------------- export

/** Payments of one account for a period, as CSV or PDF, downloadable through a short-lived token. */
@ApplicationScoped
class ExportService(
    private val history: PaymentHistory,
    private val store: MoneyStore,
    private val renderer: ExportRenderer,
    private val clock: Clock,
) {
    @Suppress("LongParameterList")
    fun create(
        partyId: UUID,
        accountId: UUID,
        format: String,
        from: LocalDate,
        to: LocalDate,
        direction: String,
    ): PaymentExport {
        require(format in setOf("CSV", "PDF")) { "format must be CSV or PDF" }
        require(direction in setOf("ALL", "INCOMING", "OUTGOING")) { "direction must be ALL, INCOMING or OUTGOING" }
        require(!to.isBefore(from)) { "to must not be before from" }
        require(!from.isBefore(to.minusYears(MAX_YEARS))) { "an export covers at most $MAX_YEARS years" }
        val account = history.own(partyId, accountId)
        val rows = history.payments(partyId, from, account)
            .filter { !it.date.isAfter(to) && !it.date.isBefore(from) }
            .filter { direction == "ALL" || (direction == "OUTGOING") == it.outgoing }
            .sortedBy { it.date }
        val name = "payments-${account.iban.takeLast(IBAN_TAIL)}-$from-$to.${format.lowercase()}"
        val content = if (format == "CSV") renderer.csv(rows) else renderer.pdf(account, from, to, rows)
        val export = PaymentExport(
            UUID.randomUUID(), partyId, account.id, format, direction, from, to, "READY", name,
            if (format == "CSV") "text/csv; charset=utf-8" else "application/pdf", content, token(),
            clock.instant().plus(TOKEN_LIFE),
        )
        store.insertExport(export, clock.instant())
        return export
    }

    fun export(partyId: UUID, id: UUID): PaymentExport =
        store.export(partyId, id) ?: throw ResourceNotFoundException("No such export", "EXPORT_NOT_FOUND")

    /** The file behind a download link, while the link is still valid. */
    fun download(token: String): PaymentExport? =
        store.exportByToken(token)?.takeIf { it.status == "READY" && it.expiresAt.isAfter(clock.instant()) }

    private fun token(): String = ByteArray(TOKEN_BYTES).also { RANDOM.nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private companion object {
        val TOKEN_LIFE: Duration = Duration.ofMinutes(30)
        const val TOKEN_BYTES = 32
        const val MAX_YEARS = 2L
        const val IBAN_TAIL = 4
        val RANDOM = SecureRandom()
    }
}

/** Renders payments to a file. */
interface ExportRenderer {
    fun csv(rows: List<Txn>): ByteArray

    fun pdf(account: AccountRef, from: LocalDate, to: LocalDate, rows: List<Txn>): ByteArray
}
