// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.domain

import java.time.LocalDate
import java.util.Locale

/**
 * What a provider "finds" that differs from what the applicant typed — the only place the simulator
 * invents anything. With no scenario, every provider finds the applicant exactly as submitted; each
 * scenario makes one provider disagree the way a real one would. Every comparison is the bank's own.
 */
enum class IdentityScenario {
    /** The document's expiry date has passed. */
    EXPIRED_DOCUMENT,

    /** The document number is in the invalid-documents register (CZ MVČR: stolen; BG MVR: invalid). */
    INVALID_DOCUMENT,

    /** The chip's security object does not verify: passive authentication INVALID. */
    CHIP_FORGED,

    /** The selfie is not a live person. */
    LIVENESS_FAIL,

    /** The selfie is somebody else (face match 0.42). */
    FACE_MISMATCH,

    /** The selfie may or may not be the holder (face match 0.71, a reviewer's call). */
    FACE_GREY,

    /** The document is in another name than the applicant typed. */
    OCR_NAME_DIFFERS,

    /** Somebody else signed in at their bank: Bank iD returns another person's claims. */
    BANKID_OTHER_PERSON,

    /** An insolvency record (CZ ISIR, BG insolvency check). */
    INSOLVENT,

    /** The first payment arrives from an account in somebody else's name. */
    FIRST_PAYMENT_OTHER_NAME,

    /** The document shows the holder is sixteen. */
    UNDERAGE_DOCUMENT,

    /** BG GRAO (population register) holds a different name and date of birth for the EGN. */
    GRAO_MISMATCH,
    ;

    companion object {
        fun parse(code: String): IdentityScenario {
            val wanted = code.trim().uppercase(Locale.ROOT)
            return requireNotNull(entries.firstOrNull { it.name == wanted }) {
                "unknown scenario '$code' (known: ${entries.joinToString { it.name }})"
            }
        }
    }
}

/** Where a fixture came from: the scenarios file, or a runtime PUT (which overrides the file). */
enum class ScenarioSource { FILE, RUNTIME }

/** The scenarios of one person, keyed by canonical national id. */
data class ScenarioEntry(
    val nationalId: String,
    val scenarios: Set<IdentityScenario>,
    val note: String?,
    val source: ScenarioSource,
)

enum class Sex(val code: String, val oidc: String) {
    MALE("M", "male"),
    FEMALE("F", "female"),
}

/**
 * Czech birth numbers (rodné číslo) and Bulgarian EGNs. Both encode the date of birth and the sex:
 * a Czech woman's month is +50 (or +70 for the extended series), a Bulgarian EGN's ninth digit is odd
 * for a woman; an EGN month is +20 for the 1800s and +40 for the 2000s.
 */
object NationalIds {
    const val BG = "BG"
    const val CZ = "CZ"
    private const val MIN_DIGITS = 9
    private const val MAX_DIGITS = 10
    private const val FEMALE_OFFSET = 50
    private const val EXTENDED_FEMALE_OFFSET = 70
    private const val EXTENDED_MALE_OFFSET = 20
    private const val EGN_1800S = 20
    private const val EGN_2000S = 40
    private const val MONTHS = 12
    private const val CENTURY_19 = 1900
    private const val CENTURY_20 = 2000
    private const val CENTURY_18 = 1800
    private const val NINE_DIGIT_CUTOFF = 54
    private const val EGN_SEX_DIGIT = 8
    private const val MOD = 11
    private const val TEN = 10
    private const val HUNDRED = 100
    private const val MONTH_AT = 2
    private const val DAY_AT = 4
    private const val DATE_END = 6

    // the EGN check-digit weights, as the Bulgarian civil registration rules publish them
    private val EGN_WEIGHTS = "2,4,8,5,10,9,7,3,6".split(',').map(String::toInt)

    /** Digits only (`910813/1234` -> `9108131234`); null unless 9 or 10 digits. */
    fun canonical(raw: String?): String? = raw?.filter(Char::isDigit)?.takeIf { it.length in MIN_DIGITS..MAX_DIGITS }

    fun sexOf(id: String, country: String): Sex? {
        val digits = canonical(id) ?: return null
        return if (country.equals(BG, ignoreCase = true)) {
            if (digits.length != MAX_DIGITS) return null
            if (digits[EGN_SEX_DIGIT].digitToInt() % 2 == 1) Sex.FEMALE else Sex.MALE
        } else {
            if (digits.substring(MONTH_AT, DAY_AT).toInt() > FEMALE_OFFSET) Sex.FEMALE else Sex.MALE
        }
    }

    fun birthDateOf(id: String, country: String): LocalDate? {
        val digits = canonical(id) ?: return null
        val yy = digits.substring(0, MONTH_AT).toInt()
        val mm = digits.substring(MONTH_AT, DAY_AT).toInt()
        val dd = digits.substring(DAY_AT, DATE_END).toInt()
        val (year, month) = if (country.equals(BG, ignoreCase = true)) {
            when {
                mm > EGN_2000S -> CENTURY_20 + yy to mm - EGN_2000S
                mm > EGN_1800S + MONTHS -> return null
                mm > EGN_1800S -> CENTURY_18 + yy to mm - EGN_1800S
                else -> CENTURY_19 + yy to mm
            }
        } else {
            val month = when {
                mm > EXTENDED_FEMALE_OFFSET -> mm - EXTENDED_FEMALE_OFFSET
                mm > FEMALE_OFFSET -> mm - FEMALE_OFFSET
                mm > EXTENDED_MALE_OFFSET -> mm - EXTENDED_MALE_OFFSET
                else -> mm
            }
            val century = if (digits.length == MIN_DIGITS || yy >= NINE_DIGIT_CUTOFF) CENTURY_19 else CENTURY_20
            century + yy to month
        }
        return runCatching { LocalDate.of(year, month, dd) }.getOrNull()
    }

    /** A valid ten-digit Czech birth number (divisible by 11) for a person born from 1954 on. */
    fun czechBirthNumber(birthDate: LocalDate, sex: Sex, serial: Int): String {
        val month = birthDate.monthValue + if (sex == Sex.FEMALE) FEMALE_OFFSET else 0
        val first9 = "%02d%02d%02d%03d".format(
            Locale.ROOT,
            birthDate.year % HUNDRED,
            month,
            birthDate.dayOfMonth,
            serial,
        )
        return first9 + (first9.toLong() % MOD % TEN)
    }

    /** A valid Bulgarian EGN (weighted check digit) for a person born in the 1900s or 2000s. */
    fun bulgarianEgn(birthDate: LocalDate, sex: Sex, serial: Int): String {
        val month = birthDate.monthValue + if (birthDate.year >= CENTURY_20) EGN_2000S else 0
        val region = (serial % HUNDRED) * TEN
        val sexDigit = if (sex == Sex.FEMALE) 1 else 0
        val first9 = "%02d%02d%02d%03d".format(
            Locale.ROOT,
            birthDate.year % HUNDRED,
            month,
            birthDate.dayOfMonth,
            region + sexDigit,
        )
        val sum = first9.mapIndexed { i, c -> c.digitToInt() * EGN_WEIGHTS[i] }.sum()
        return first9 + (sum % MOD % TEN)
    }
}
