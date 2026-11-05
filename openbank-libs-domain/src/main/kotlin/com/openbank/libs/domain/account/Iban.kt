// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.account

data class Iban(val value: String) {
    init {
        require(isValid(value)) { "Invalid IBAN: $value" }
    }

    val countryCode: String get() = value.substring(0, 2)
    val checkDigits: String get() = value.substring(2, 4)
    val bban: String get() = value.substring(4)

    fun formatted(): String = value.chunked(4).joinToString(" ")

    override fun toString(): String = value

    companion object {
        // ISO 13616 / ISO 7064 mod-97-10: letters count as 10..35, check digits = 98 − (n mod 97).
        private const val ASCII_LIMIT = 128
        private const val LETTER_VALUE_OFFSET = 10
        private const val CHECK_BASE = 98
        private const val MOD_97 = 97

        fun of(raw: String): Iban = Iban(raw.replace(" ", "").uppercase())

        /** The IBAN for [bban] in [countryCode], with its ISO 13616 mod-97 check digits. */
        fun compose(countryCode: String, bban: String): Iban {
            val country = countryCode.uppercase()
            val body = bban.replace(" ", "").uppercase()
            require(country.length == 2 && country.all { it in 'A'..'Z' }) { "country code must be two letters" }
            require(
                body.isNotEmpty() &&
                    body.all {
                        it.isLetterOrDigit() && it.code < ASCII_LIMIT
                    },
            ) { "BBAN must be alphanumeric" }
            val numeric = (body + country + "00").map { c ->
                if (c.isDigit()) c.toString() else (c.code - 'A'.code + LETTER_VALUE_OFFSET).toString()
            }.joinToString("")
            val check = CHECK_BASE - numeric.toBigInteger().mod(MOD_97.toBigInteger()).toInt()
            return Iban(country + check.toString().padStart(2, '0') + body)
        }

        fun isValid(iban: String): Boolean {
            val cleaned = iban.replace(" ", "").uppercase()
            if (cleaned.length < 15 || cleaned.length > 34) return false
            if (!cleaned.substring(0, 2).all { it.isLetter() }) return false
            if (!cleaned.substring(2, 4).all { it.isDigit() }) return false
            val rearranged = cleaned.substring(4) + cleaned.substring(0, 4)
            val numeric = rearranged.map { c ->
                if (c.isDigit()) c.toString() else (c.code - 'A'.code + 10).toString()
            }.joinToString("")
            return numeric.toBigInteger().mod(97.toBigInteger()).toInt() == 1
        }
    }
}
