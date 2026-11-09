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

    /**
     * The national number read with every rule the app applies (experience v2 step 9): structure,
     * checksum, the market's date encoding and — against [today] — no birthdate in the future.
     * Null when it is not a valid number for this market.
     */
    fun nationalId(raw: String, today: java.time.LocalDate): NationalId? = when (this) {
        CZ -> (RodneCislo.parse(raw, today) as? RodneCislo.Parsed)?.let {
            NationalId(it.canonical, it.birthdate, it.gender)
        }
        BG -> (Egn.parse(raw, today) as? Egn.Parsed)?.let { NationalId(it.canonical, it.birthdate, it.gender) }
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

/** A valid national number: canonical digits, the birthdate and sex it encodes. */
data class NationalId(val canonical: String, val birthdate: java.time.LocalDate, val gender: RodneCislo.Gender) {
    /** Completed years of age on [on]. */
    fun ageOn(on: java.time.LocalDate): Int = java.time.Period.between(birthdate, on).years

    /** "M" or "F", the form identity documents and providers use. */
    val sex: String get() = if (gender == RodneCislo.Gender.FEMALE) "F" else "M"
}
