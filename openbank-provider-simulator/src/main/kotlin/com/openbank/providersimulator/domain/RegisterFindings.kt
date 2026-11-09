// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.domain

import java.time.LocalDate

data class InsolvencyRecord(val caseNumber: String, val court: String, val state: String, val startedOn: LocalDate)

/** BG GRAO: the population register's record of an EGN. */
data class GraoRecord(
    val egn: String,
    val firstName: String,
    val surName: String,
    val familyName: String,
    val birthDate: LocalDate?,
    val sex: Sex?,
)

/** BG MVR: is this identity document valid, and until when. */
data class MvrDocumentStatus(val documentNumber: String, val valid: Boolean, val validTo: LocalDate?)

/** What the public registers hold for a person: pure, deterministic, driven by the person's scenarios. */
object RegisterFindings {
    private const val CASE_BASE = 1000
    private const val CASE_SPAN = 9000L
    private const val GRAO_SHIFT_DAYS = 10L
    private val INSOLVENCY_STARTED: LocalDate = LocalDate.parse("2025-03-01")

    /** CZ ISIR: the insolvency register's records for a birth number. */
    fun isir(nationalId: String, scenarios: Set<IdentityScenario>): List<InsolvencyRecord> =
        if (IdentityScenario.INSOLVENT in scenarios) {
            listOf(
                InsolvencyRecord(
                    "KSPH 38 INS ${caseNumber("isir|$nationalId")}/2025",
                    "Krajský soud v Praze",
                    "KONKURS",
                    INSOLVENCY_STARTED,
                ),
            )
        } else {
            emptyList()
        }

    /** BG insolvency proceedings for an EGN (the Commercial Register's insolvency part). */
    fun bgInsolvency(egn: String, scenarios: Set<IdentityScenario>): List<InsolvencyRecord> =
        if (IdentityScenario.INSOLVENT in scenarios) {
            val case = "${caseNumber("bg|$egn")}/2025"
            listOf(InsolvencyRecord(case, "Софийски градски съд", "OPEN", INSOLVENCY_STARTED))
        } else {
            emptyList()
        }

    /** BG GRAO for a known applicant: their record, or under GRAO_MISMATCH another name and birth date. */
    fun grao(egn: String, applicant: Applicant, scenarios: Set<IdentityScenario>): GraoRecord {
        val country = NationalIds.BG
        val sex = NationalIds.sexOf(egn, country)
        val given = applicant.givenName.trim().split(Regex("\\s+"))
        val middle = given.drop(1).joinToString(" ")
        val born = NationalIds.birthDateOf(egn, country) ?: People.dateOfBirth(applicant, country)
        if (IdentityScenario.GRAO_MISMATCH in scenarios) {
            val (first, family) = People.otherPerson(applicant, country, sex)
            val otherBorn = (born ?: People.OTHER_PERSON_BORN).minusYears(1).plusDays(GRAO_SHIFT_DAYS)
            return GraoRecord(egn, first, middle, family, otherBorn, sex)
        }
        return GraoRecord(egn, given.first(), middle, applicant.familyName.trim(), born, sex)
    }

    /** BG MVR: the document is valid when it is the EGN's own and neither listed invalid nor expired. */
    fun mvr(
        egn: String,
        documentNumber: String,
        scenarios: Set<IdentityScenario>,
        today: LocalDate,
    ): MvrDocumentStatus {
        val number = DocumentFindings.normaliseDocumentNumber(documentNumber)
        val own = DocumentType.entries.any { DocumentFindings.documentNumber(egn, it, NationalIds.BG) == number }
        val valid = own &&
            IdentityScenario.INVALID_DOCUMENT !in scenarios &&
            IdentityScenario.EXPIRED_DOCUMENT !in scenarios
        return MvrDocumentStatus(number, valid, if (own) DocumentFindings.expiry(scenarios, today) else null)
    }

    /** CZ MVČR invalid documents: is [documentNumber] a document of anyone listed under INVALID_DOCUMENT. */
    fun listedAsInvalid(documentNumber: String, invalidHolders: Collection<String>): Boolean {
        val number = DocumentFindings.normaliseDocumentNumber(documentNumber)
        return invalidHolders.any { id ->
            DocumentType.entries.any { DocumentFindings.documentNumber(id, it, NationalIds.CZ) == number }
        }
    }

    private fun caseNumber(seed: String): Int = CASE_BASE + (StableHash.of(seed) % CASE_SPAN).toInt()
}
