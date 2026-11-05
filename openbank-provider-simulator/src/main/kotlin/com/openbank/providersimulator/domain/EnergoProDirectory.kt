// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.domain

import java.util.Locale
import kotlin.random.Random

/** CZ (Czech Republic, CZK) or BG (Bulgaria, EUR): where an Energo-Pro supply point is. */
enum class EnergoProMarket { CZ, BG }

/**
 * One customer of Energo-Pro's electricity business, as Energo-Pro's own customer system holds it.
 * Energo-Pro (the distributor) and Energo-Pro Bank are independent companies: most of these people
 * never bank with the bank, and the bank learns about a record only when its own customer names it.
 */
data class EnergoProCustomer(
    val customerNumber: String,
    val market: EnergoProMarket,
    val firstName: String,
    val lastName: String,
    val street: String,
    val city: String,
    val postalCode: String,
    val email: String,
    val phone: String,
) {
    /** True when [email] or [phone] is the contact this record holds (case- and format-insensitive). */
    fun hasContact(email: String?, phone: String?): Boolean =
        (email != null && normaliseEmail(email) == normaliseEmail(this.email)) ||
            (phone != null && normalisePhone(phone) == normalisePhone(this.phone))

    companion object {
        /** `EP-` and seven digits, as printed on an invoice. */
        const val NUMBER_DIGITS = 7
        val NUMBER = Regex("^EP-\\d{$NUMBER_DIGITS}$")

        fun normaliseNumber(raw: String): String {
            val trimmed = raw.trim().uppercase(Locale.ROOT).replace(" ", "")
            val digits = trimmed.removePrefix("EP-").removePrefix("EP")
            return if (digits.length == NUMBER_DIGITS && digits.all(Char::isDigit)) "EP-$digits" else trimmed
        }

        fun normaliseEmail(email: String): String = email.trim().lowercase(Locale.ROOT)

        fun normalisePhone(phone: String): String = phone.filter { it.isDigit() || it == '+' }
    }
}

/**
 * Energo-Pro's customer base: a deterministic synthetic population (Energo-Pro-only customers) plus
 * the records a local seed adds (demo personas who are also Energo-Pro customers). A seed record
 * replaces a generated one with the same number.
 */
class EnergoProDirectory(generated: List<EnergoProCustomer>, seeded: List<EnergoProCustomer>) {
    private val byNumber: Map<String, EnergoProCustomer> =
        (generated + seeded).associateBy { it.customerNumber }

    val size: Int get() = byNumber.size

    fun find(customerNumber: String): EnergoProCustomer? = byNumber[EnergoProCustomer.normaliseNumber(customerNumber)]

    companion object {
        private const val DEFAULT_SEED = 20261105
        private const val FIRST_NUMBER = 1_000_000
        private const val ONE_IN_BULGARIA = 4
        private const val MAX_HOUSE_BG = 120
        private const val MAX_HOUSE_CZ = 2000
        private const val BG_MOBILE_FROM = 7 // +359 87x / 88x / 89x
        private const val BG_MOBILE_UNTIL = 10
        private const val SEVEN_DIGITS_FROM = 1_000_000
        private const val SEVEN_DIGITS_UNTIL = 10_000_000
        private const val EIGHT_DIGITS_FROM = 10_000_000
        private const val EIGHT_DIGITS_UNTIL = 100_000_000
        private val CZ_FIRST_M = listOf("Jan", "Petr", "Martin", "Tomáš", "Pavel")
        private val CZ_FIRST_F = listOf("Lucie", "Jana", "Eva", "Kateřina", "Lenka")
        private val CZ_LAST_M = listOf("Novák", "Svoboda", "Dvořák", "Černý", "Procházka", "Kučera", "Veselý", "Horák")
        private val CZ_LAST_F =
            listOf("Nováková", "Svobodová", "Dvořáková", "Černá", "Procházková", "Kučerová", "Veselá", "Horáková")
        private val CZ_STREETS = listOf("Nádražní", "Školní", "Husova", "Palackého", "Komenského", "Masarykova")
        private val CZ_CITIES = listOf(
            "Praha" to "110 00",
            "Brno" to "602 00",
            "Plzeň" to "301 00",
            "Olomouc" to "779 00",
        )
        private val BG_FIRST_M = listOf("Георги", "Иван", "Димитър", "Николай", "Петър")
        private val BG_FIRST_F = listOf("Мария", "Елена", "Десислава", "Иванка", "Гергана")
        private val BG_LAST_M = listOf("Иванов", "Георгиев", "Димитров", "Петров", "Николов")
        private val BG_LAST_F = listOf("Иванова", "Георгиева", "Димитрова", "Петрова", "Николова")
        private val BG_STREETS =
            listOf("ул. Шипка", "бул. България", "ул. Раковски", "бул. Витоша", "ул. Граф Игнатиев")
        private val BG_CITIES = listOf("София" to "1000", "Пловдив" to "4000", "Варна" to "9000", "Бургас" to "8000")

        /**
         * [count] customers, numbered `EP-1000000` upward, about one in four in Bulgaria. Pure function
         * of [seed], so every start of the simulator holds the same population.
         */
        fun generate(count: Int, seed: Int = DEFAULT_SEED): List<EnergoProCustomer> {
            val random = Random(seed)
            return (0 until count).map { index ->
                val number = "EP-${FIRST_NUMBER + index}"
                val female = random.nextBoolean()
                if (random.nextInt(ONE_IN_BULGARIA) == 0) {
                    val (city, postal) = BG_CITIES[random.nextInt(BG_CITIES.size)]
                    EnergoProCustomer(
                        customerNumber = number, market = EnergoProMarket.BG,
                        firstName = (if (female) BG_FIRST_F else BG_FIRST_M).random(random),
                        lastName = (if (female) BG_LAST_F else BG_LAST_M).random(random),
                        street = "${BG_STREETS.random(
                            random,
                        )} ${random.nextInt(1, MAX_HOUSE_BG)}",
                        city = city, postalCode = postal,
                        email = "ep.$index@example.bg",
                        phone = "+3598${random.nextInt(
                            BG_MOBILE_FROM,
                            BG_MOBILE_UNTIL,
                        )}${random.nextInt(SEVEN_DIGITS_FROM, SEVEN_DIGITS_UNTIL)}",
                    )
                } else {
                    val (city, postal) = CZ_CITIES[random.nextInt(CZ_CITIES.size)]
                    EnergoProCustomer(
                        customerNumber = number, market = EnergoProMarket.CZ,
                        firstName = (if (female) CZ_FIRST_F else CZ_FIRST_M).random(random),
                        lastName = (if (female) CZ_LAST_F else CZ_LAST_M).random(random),
                        street = "${CZ_STREETS.random(
                            random,
                        )} ${random.nextInt(1, MAX_HOUSE_CZ)}",
                        city = city, postalCode = postal,
                        email = "ep.$index@example.cz",
                        phone = "+4206${random.nextInt(
                            EIGHT_DIGITS_FROM,
                            EIGHT_DIGITS_UNTIL,
                        )}",
                    )
                }
            }
        }
    }
}
