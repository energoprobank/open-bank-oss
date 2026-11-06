// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.domain

import java.math.BigDecimal
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/** Someone a customer can pay as a bill. [connected]: the bank can show this biller's bills. */
data class Biller(
    val id: String,
    val market: String,
    val name: String,
    val category: String,
    val iban: String,
    val accountNumber: String?,
    val currency: String,
    val connected: Boolean,
) {
    /** True when a payment to [creditorAccount] (number/bank or IBAN, any spacing) is a payment to this biller. */
    fun receives(creditorAccount: String): Boolean {
        val wanted = normaliseAccount(creditorAccount)
        return wanted == normaliseAccount(iban) || (accountNumber != null && wanted == normaliseAccount(accountNumber))
    }

    companion object {
        /** Spaces out, upper case, leading zeros of a domestic account number out (`000123/0100` = `123/0100`). */
        fun normaliseAccount(raw: String): String {
            val compact = raw.replace(" ", "").uppercase(Locale.ROOT)
            if (!compact.contains('/')) return compact
            val (number, bank) = compact.split('/', limit = 2)
            val (prefix, base) = if (number.contains('-')) number.split('-', limit = 2) else listOf("", number)
            val p = prefix.trimStart('0')
            return (if (p.isEmpty()) "" else "$p-") + base.trimStart('0') + "/" + bank
        }
    }
}

/** A customer's statement that an Energo-Pro customer number is theirs. */
data class ElectricityLink(val partyId: UUID, val customerNumber: String, val market: String, val linkedAt: Instant)

/** A bill as Energo-Pro's billing system issued it (its own status, before the bank's record). */
data class Bill(
    val id: String,
    val period: String,
    val amount: BigDecimal,
    val currency: String,
    val dueDate: LocalDate,
    val status: String,
    val collectionIban: String,
    val reference: String,
)

/** A bill payment the bank made for its customer, and whether Energo-Pro has been told. */
data class BillPayment(
    val paymentId: UUID,
    val partyId: UUID,
    val billerId: String,
    val reference: String,
    val amount: BigDecimal,
    val currency: String,
    val paidAt: Instant,
    val noticeSentAt: Instant?,
    val noticeAttempts: Int,
)

data class BillReminders(val partyId: UUID, val enabled: Boolean, val daysBefore: Int)

/**
 * Energo-Pro releases a customer's bills to the bank only for its own customer: the name on the
 * Energo-Pro record must be the name the bank holds (accents, case and order of words aside).
 */
object NameMatch {
    fun same(a: String, b: String): Boolean = words(a) == words(b)

    private fun words(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }.sorted()
}
