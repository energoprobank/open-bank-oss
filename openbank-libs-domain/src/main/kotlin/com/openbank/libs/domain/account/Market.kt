// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.account

import com.openbank.libs.identity.Egn
import com.openbank.libs.identity.RodneCislo

/**
 * The commercial market a retail customer banks in: the country of the account, its currency, the
 * national identification number the bank records, and the deposit-guarantee scheme. Czechia is the
 * default (the market every party had before markets existed); Bulgaria uses the euro.
 */
enum class Market(val currency: String, val nationalIdName: String) {
    CZ("CZK", "rodné číslo"),
    BG("EUR", "ЕГН"),
    ;

    val countryCode: String get() = name

    /** The national number in canonical (digits-only) form, or null when it is not valid for this market. */
    fun canonicalNationalId(raw: String): String? = when (this) {
        CZ -> (RodneCislo.parse(raw) as? RodneCislo.Parsed)?.canonical
        BG -> (Egn.parse(raw) as? Egn.Parsed)?.canonical
    }

    /** The birthdate the national number encodes, for the cross-check with the declared date of birth. */
    fun birthdateOf(raw: String): java.time.LocalDate? = when (this) {
        CZ -> (RodneCislo.parse(raw) as? RodneCislo.Parsed)?.birthdate
        BG -> (Egn.parse(raw) as? Egn.Parsed)?.birthdate
    }

    companion object {
        val DEFAULT = CZ

        /** CZ, BG (any case); null for anything else. */
        fun of(raw: String?): Market? =
            raw?.trim()?.uppercase()?.let { code -> entries.firstOrNull { it.name == code } }

        fun forCurrency(currency: String): Market? = entries.firstOrNull { it.currency == currency.uppercase() }
    }
}
