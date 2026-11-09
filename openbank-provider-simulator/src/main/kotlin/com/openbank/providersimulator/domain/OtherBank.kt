// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Locale

/** A payment the customer orders at "their other bank" (the first payment of a document sign-up). */
data class OtherBankOrder(
    val payerName: String,
    val payerAccount: String,
    val payerBankCode: String,
    val amount: BigDecimal,
    val currency: String,
    val creditorAccount: String,
    val creditorBankCode: String?,
    val variableSymbol: String?,
    val nationalId: String?,
)

/** The payment as it reaches the creditor's bank: whose account it came from, and when. */
data class IncomingPayment(
    val id: String,
    val payerName: String,
    val payerAccount: String,
    val payerBankCode: String,
    val payerBankName: String,
    val amount: BigDecimal,
    val currency: String,
    val creditorAccount: String,
    val variableSymbol: String?,
    val receivedAt: Instant,
)

/**
 * The other bank: sends what the customer ordered, except that under FIRST_PAYMENT_OTHER_NAME the
 * account the money comes from is held by somebody else — the case the bank's name match exists for.
 */
object OtherBank {
    private const val MAX_NAME = 140
    private const val MAX_ACCOUNT = 34
    private const val MAX_VS = 10
    private val CURRENCIES = setOf("CZK", "EUR", "BGN")
    private val ACCOUNT = Regex("[0-9A-Z-]{2,34}")
    private val BANK_CODE = Regex("[0-9A-Z]{4}")

    /** Czech bank codes (four digits) and Bulgarian BIC prefixes, as a payment carries them. */
    val BANKS = mapOf(
        "0100" to "Komerční banka",
        "0300" to "ČSOB",
        "0600" to "MONETA Money Bank",
        "0800" to "Česká spořitelna",
        "2010" to "Fio banka",
        "3030" to "Air Bank",
        "5500" to "Raiffeisenbank",
        "6210" to "mBank",
        "UNCR" to "UniCredit Bulbank",
        "STSA" to "DSK Bank",
        "FINV" to "Fibank",
        "UBBS" to "UBB",
    )

    fun validate(order: OtherBankOrder) {
        require(order.payerName.isNotBlank() && order.payerName.length <= MAX_NAME) { "payer_name is required" }
        require(ACCOUNT.matches(normaliseAccount(order.payerAccount))) { "payer_account is not an account number" }
        require(BANK_CODE.matches(order.payerBankCode.trim().uppercase(Locale.ROOT))) {
            "payer_bank_code is a 4-character bank code"
        }
        require(order.amount.signum() > 0) { "amount must be above zero" }
        require(order.currency.uppercase(Locale.ROOT) in CURRENCIES) { "currency must be one of $CURRENCIES" }
        require(normaliseAccount(order.creditorAccount).length in 2..MAX_ACCOUNT) { "creditor_account is required" }
        order.variableSymbol?.let {
            require(it.length <= MAX_VS && it.all(Char::isDigit)) { "variable_symbol is up to 10 digits" }
        }
    }

    fun receive(id: String, order: OtherBankOrder, scenarios: Set<IdentityScenario>, at: Instant): IncomingPayment {
        val code = order.payerBankCode.trim().uppercase(Locale.ROOT)
        val payer = if (IdentityScenario.FIRST_PAYMENT_OTHER_NAME in scenarios) {
            People.otherPayerName(order.payerName, order.nationalId)
        } else {
            order.payerName.trim()
        }
        return IncomingPayment(
            id = id,
            payerName = payer,
            payerAccount = normaliseAccount(order.payerAccount),
            payerBankCode = code,
            payerBankName = BANKS[code] ?: "Bank $code",
            amount = order.amount.setScale(2, RoundingMode.HALF_UP),
            currency = order.currency.uppercase(Locale.ROOT),
            creditorAccount = normaliseAccount(order.creditorAccount),
            variableSymbol = order.variableSymbol?.trim()?.takeIf(String::isNotEmpty),
            receivedAt = at,
        )
    }

    fun matches(payment: IncomingPayment, creditorAccount: String, variableSymbol: String?): Boolean =
        payment.creditorAccount == normaliseAccount(creditorAccount) &&
            (variableSymbol.isNullOrBlank() || payment.variableSymbol == variableSymbol.trim())

    fun normaliseAccount(raw: String): String = raw.filterNot(Char::isWhitespace).uppercase(Locale.ROOT)
}
