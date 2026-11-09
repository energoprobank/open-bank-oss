// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.domain

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/** The applicant as submitted, read the way a provider reads them; and "somebody else" when a scenario needs one. */
object People {
    val OTHER_PERSON_BORN: LocalDate = LocalDate.parse("1984-03-15")
    private const val SERIAL_SPAN = 1000L

    /** Another real-looking person of the same market and sex, never with the same family name. */
    private val OTHER_PEOPLE = mapOf(
        (NationalIds.CZ to Sex.MALE) to listOf("Petr" to "Svoboda", "Tomáš" to "Dvořák"),
        (NationalIds.CZ to Sex.FEMALE) to listOf("Lucie" to "Svobodová", "Eva" to "Dvořáková"),
        (NationalIds.BG to Sex.MALE) to listOf("Georgi" to "Ivanov", "Dimitar" to "Petrov"),
        (NationalIds.BG to Sex.FEMALE) to listOf("Maria" to "Ivanova", "Elena" to "Petrova"),
    )

    fun dateOfBirth(applicant: Applicant, country: String): LocalDate? =
        applicant.dateOfBirth?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
            ?: applicant.nationalId?.let { NationalIds.birthDateOf(it, country) }

    fun sexOf(applicant: Applicant, country: String): Sex? =
        applicant.nationalId?.let { NationalIds.sexOf(it, country) }

    /** (given name, family name) of somebody else of the applicant's market and sex. */
    fun otherPerson(applicant: Applicant, country: String, sex: Sex?): Pair<String, String> {
        val market = if (country.equals(NationalIds.BG, ignoreCase = true)) NationalIds.BG else NationalIds.CZ
        val people = OTHER_PEOPLE.getValue(market to (sex ?: Sex.MALE))
        return people.first { !it.second.equals(applicant.familyName.trim(), ignoreCase = true) }
    }

    /** The name an account holder other than [name] would have (FIRST_PAYMENT_OTHER_NAME). */
    fun otherPayerName(name: String, nationalId: String?): String {
        val applicant = Applicant("", name.trim().substringAfterLast(' '), null, nationalId)
        val (given, family) = otherPerson(applicant, NationalIds.CZ, sexOf(applicant, NationalIds.CZ))
        return "$given $family"
    }

    /** Who Bank iD says signed in: the applicant, or under BANKID_OTHER_PERSON somebody else entirely. */
    fun bankId(applicant: Applicant, scenarios: Set<IdentityScenario>): BankIdIdentity {
        val country = NationalIds.CZ
        val sex = sexOf(applicant, country)
        if (IdentityScenario.BANKID_OTHER_PERSON in scenarios) {
            val (given, family) = otherPerson(applicant, country, sex)
            val otherSex = sex ?: Sex.MALE
            val serial = (StableHash.of("serial|${applicant.nationalId}|${applicant.familyName}") % SERIAL_SPAN).toInt()
            val number = NationalIds.czechBirthNumber(OTHER_PERSON_BORN, otherSex, serial)
            return BankIdIdentity(
                subjectOf(number),
                given,
                family,
                OTHER_PERSON_BORN,
                otherSex,
                number,
                listOf(country),
            )
        }
        val number = NationalIds.canonical(applicant.nationalId)
        val sub = subjectOf(number ?: "${applicant.givenName}|${applicant.familyName}|${applicant.dateOfBirth}")
        return BankIdIdentity(
            sub,
            applicant.givenName.trim(),
            applicant.familyName.trim(),
            dateOfBirth(applicant, country),
            sex,
            number,
            listOf(country),
        )
    }

    private fun subjectOf(key: String): String = UUID.nameUUIDFromBytes("bankid|$key".toByteArray()).toString()
}

/** A hash that is the same in every JVM and every run (String.hashCode is too, but collides far too easily). */
object StableHash {
    private const val TEN = 10L

    fun of(seed: String): Long {
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(bytes).long and Long.MAX_VALUE
    }

    /** [count] digits derived from [seed], never starting with 0 (a printed number does not). */
    fun digits(seed: String, count: Int): String {
        var span = 1L
        repeat(count - 1) { span *= TEN }
        return (span + of(seed) % (span * (TEN - 1))).toString()
    }

    fun lowercaseKey(vararg parts: String): String = parts.joinToString("|") { it.trim().lowercase(Locale.ROOT) }
}
