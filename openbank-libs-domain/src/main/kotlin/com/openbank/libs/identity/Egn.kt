// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.identity

import java.time.LocalDate
import java.time.YearMonth

/**
 * Bulgarian personal number (ЕГН, единен граждански номер) parser and validator — the Bulgarian
 * counterpart of [RodneCislo], for a party in the BG market.
 *
 * Form: 10 digits `YYMMDDRRGC` — birthdate, region + sequence (the ninth digit is even for men and
 * odd for women) and a check digit. The month is as is for 1900–1999, +20 for 1800–1899 and +40 for
 * 2000–2099. Check digit = Σ digit[i] × (2, 4, 8, 5, 10, 9, 7, 3, 6) mod 11, where 10 becomes 0.
 *
 * Deterministic and clock-free like [RodneCislo]: the caller cross-checks [Parsed.birthdate] against
 * the declared date of birth.
 */
// The digit positions, month offsets and the statutory weight vector ARE the specification
// (Закон за гражданската регистрация); naming each of them would only restate the KDoc above.
@Suppress("MagicNumber")
object Egn {
    sealed interface Result

    data class Parsed(val canonical: String, val birthdate: LocalDate, val gender: RodneCislo.Gender) : Result

    data class Invalid(val reason: String) : Result

    private val WEIGHTS = intArrayOf(2, 4, 8, 5, 10, 9, 7, 3, 6)

    /** [today], when given, rejects a birthdate after it. */
    fun parse(raw: String, today: LocalDate? = null): Result {
        val result = parseStructure(raw)
        return if (result is Parsed && today != null && result.birthdate.isAfter(today)) {
            Invalid("birthdate in the future")
        } else {
            result
        }
    }

    @Suppress("ReturnCount")
    private fun parseStructure(raw: String): Result {
        val cleaned = raw.trim().replace(" ", "")
        if (cleaned.isEmpty()) return Invalid("empty")
        if (!cleaned.all { it.isDigit() }) return Invalid("non-digit characters")
        if (cleaned.length != 10) return Invalid("length must be 10 digits")
        val digits = cleaned.map { it - '0' }
        val yy = digits[0] * 10 + digits[1]
        val encodedMonth = digits[2] * 10 + digits[3]
        val day = digits[4] * 10 + digits[5]
        val (year, month) = when (encodedMonth) {
            in 1..12 -> 1900 + yy to encodedMonth
            in 21..32 -> 1800 + yy to encodedMonth - 20
            in 41..52 -> 2000 + yy to encodedMonth - 40
            else -> return Invalid("invalid month component")
        }
        if (day !in 1..YearMonth.of(year, month).lengthOfMonth()) return Invalid("invalid date component")
        val birthdate = LocalDate.of(year, month, day)
        val check = WEIGHTS.indices.sumOf { digits[it] * WEIGHTS[it] } % 11 % 10
        if (check != digits[9]) return Invalid("checksum mismatch")
        val gender = if (digits[8] % 2 == 0) RodneCislo.Gender.MALE else RodneCislo.Gender.FEMALE
        return Parsed(cleaned, birthdate, gender)
    }

    fun isValid(raw: String): Boolean = parse(raw) is Parsed
}
