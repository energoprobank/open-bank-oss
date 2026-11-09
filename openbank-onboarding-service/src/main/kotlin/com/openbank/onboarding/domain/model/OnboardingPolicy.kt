// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.domain.model

import java.text.Normalizer
import java.time.LocalDate
import java.time.Period

// Experience v2 step 9: the bank's own rules for an account opening — how an identity is compared,
// how a risk score is computed and when a person must decide. Pure functions; every input comes from
// a provider or the applicant, every output is evidence the KYC case keeps.

/** The policy version every assessment names (kyc-service records it, the admin portal shows it). */
const val ONBOARDING_POLICY = "onboarding-policy-v1"

/** A check the bank ran on an identity: one row in the evidence. */
data class IdentityCheck(val type: String, val status: CheckOutcome, val detail: String)

enum class CheckOutcome { PASSED, FAILED, REVIEW }

/** Who a provider says the person is (document, chip, Bank iD, a register). */
data class Person(
    val givenName: String?,
    val familyName: String?,
    val dateOfBirth: String?,
    val nationalId: String?,
    val sex: String?,
)

object NameMatch {
    private val MARKS = Regex("\\p{M}+")
    private val SEPARATORS = Regex("[\\s\\-']+")

    /** Lower-case, accents removed, split into words. */
    fun tokens(vararg parts: String?): Set<String> = parts.filterNotNull()
        .flatMap { Normalizer.normalize(it, Normalizer.Form.NFD).replace(MARKS, "").lowercase().split(SEPARATORS) }
        .filter { it.isNotBlank() }
        .toSet()

    /**
     * The same person's name: accents, case and word order aside, and one side may carry an extra
     * given name (a document lists every given name, an applicant often types one) — but the family
     * name and at least one given name must agree. Nothing is assumed when a side is missing.
     */
    fun same(applicant: Set<String>, found: Set<String>): Boolean {
        if (applicant.isEmpty() || found.isEmpty()) return false
        val (shorter, longer) = if (applicant.size <= found.size) applicant to found else found to applicant
        return shorter.size >= 2 && longer.containsAll(shorter) || applicant == found
    }
}

/**
 * Compares what a provider found with the applicant. A null on either side is a FAILED check, never
 * a pass (step 8 counted a missing value as a match — that is how an echoing simulator passed).
 */
object IdentityComparison {
    fun compare(source: String, applicant: Person, found: Person): List<IdentityCheck> = listOf(
        check(
            "NAME",
            NameMatch.same(
                NameMatch.tokens(applicant.givenName, applicant.familyName),
                NameMatch.tokens(found.givenName, found.familyName),
            ),
            "$source name vs applicant",
        ),
        check(
            "BIRTHDATE",
            found.dateOfBirth != null && found.dateOfBirth == applicant.dateOfBirth,
            "$source date of birth",
        ),
        check(
            "NATIONAL_ID",
            found.nationalId != null && digits(found.nationalId) == digits(applicant.nationalId),
            "$source national number",
        ),
        check(
            "SEX",
            found.sex != null && found.sex.uppercase().take(1) == applicant.sex?.uppercase()?.take(1),
            "$source sex vs national number",
        ),
    )

    fun ageCheck(dateOfBirth: String?, today: LocalDate): IdentityCheck {
        val age = dateOfBirth?.let { runCatching { Period.between(LocalDate.parse(it), today).years }.getOrNull() }
        return IdentityCheck(
            "AGE",
            if (age != null && age >= ADULT) CheckOutcome.PASSED else CheckOutcome.FAILED,
            if (age == null) "no date of birth" else "age $age",
        )
    }

    private fun digits(s: String?) = s?.filter { it.isDigit() }

    private fun check(type: String, ok: Boolean, detail: String) =
        IdentityCheck(type, if (ok) CheckOutcome.PASSED else CheckOutcome.FAILED, detail)

    const val ADULT = 18
}

/** Face thresholds: liveness must pass; a face match in the grey zone goes to a person. */
object FaceThresholds {
    const val LIVENESS = 0.90
    const val MATCH_PASS = 0.80
    const val MATCH_FAIL = 0.60

    fun liveness(score: Double) = IdentityCheck(
        "LIVENESS",
        if (score >= LIVENESS) CheckOutcome.PASSED else CheckOutcome.FAILED,
        "liveness score %.2f (pass ≥ %.2f)".format(score, LIVENESS),
    )

    fun faceMatch(score: Double) = IdentityCheck(
        "FACE_MATCH",
        when {
            score >= MATCH_PASS -> CheckOutcome.PASSED
            score >= MATCH_FAIL -> CheckOutcome.REVIEW
            else -> CheckOutcome.FAILED
        },
        "face match to the chip photo %.2f (pass ≥ %.2f, review ≥ %.2f)".format(score, MATCH_PASS, MATCH_FAIL),
    )
}

/** What the applicant declared about their money (party-service's personal AML profile). */
data class FinancialProfile(
    val purposes: Set<String>,
    val incomeSources: Set<String>,
    val occupation: String,
    val expectedMonthlyTurnover: String,
    val cashIntensive: Boolean,
    val pep: Boolean,
    val taxResidencies: List<String>,
    val usPerson: Boolean,
)

data class RiskFactor(val code: String, val points: Int, val label: String)

data class RiskScore(val score: Int, val rating: String, val factors: List<RiskFactor>) {
    val dueDiligence: String get() = if (rating == "HIGH" || rating == "VERY_HIGH") "EDD" else "CDD"
}

/**
 * onboarding-policy-v1 risk score (STEP9-design.md §2). Points add up; LOW < 20, MEDIUM 20–39,
 * HIGH 40–59, VERY_HIGH ≥ 60. HIGH and above need enhanced due diligence.
 */
object RiskModel {
    /** FATF "black and grey" lists as of 2026 (call for action + increased monitoring), ISO-2. */
    val HIGH_RISK_COUNTRIES = setOf(
        "KP", "IR", "MM", "DZ", "AO", "BO", "CM", "CI", "CD", "HT", "KE", "LA", "LB", "MC",
        "MZ", "NA", "NP", "NG", "SS", "SY", "VE", "VN", "YE",
    )

    val EEA = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT",
        "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "IS", "LI", "NO",
    )

    @Suppress("CyclomaticComplexMethod", "MagicNumber") // one line, one point value per factor of the policy
    fun score(
        profile: FinancialProfile,
        nationality: String?,
        pepScreened: Boolean,
        documentChannel: Boolean,
    ): RiskScore {
        val countries = (listOfNotNull(nationality) + profile.taxResidencies).map { it.uppercase() }.toSet()
        val f = buildList {
            if (countries.any { it in HIGH_RISK_COUNTRIES }) {
                add(RiskFactor("HIGH_RISK_COUNTRY", 40, "Nationality or tax residency in a FATF high-risk country"))
            }
            if (countries.any { it !in EEA }) {
                add(RiskFactor("OUTSIDE_EEA", 15, "Nationality or tax residency outside the EU/EEA"))
            }
            if (profile.pep || pepScreened) {
                add(RiskFactor("PEP", 40, "Politically exposed person (declared or screened)"))
            }
            if (profile.occupation in setOf("UNEMPLOYED", "OTHER")) {
                add(RiskFactor("OCCUPATION", 10, "Occupation: ${profile.occupation.lowercase()}"))
            }
            if (profile.cashIntensive) add(RiskFactor("CASH", 20, "Cash-intensive activity"))
            when (profile.expectedMonthlyTurnover) {
                "UP_TO_500K" -> add(RiskFactor("TURNOVER", 10, "Expected monthly turnover up to 500 000"))
                "OVER_500K" -> add(RiskFactor("TURNOVER", 25, "Expected monthly turnover over 500 000"))
            }
            if (profile.incomeSources.any { it in setOf("INHERITANCE_GIFT", "OTHER") }) {
                add(RiskFactor("SOURCE_OF_FUNDS", 10, "Source of funds: inheritance, gift or other"))
            } else if ("INVESTMENTS" in profile.incomeSources) {
                add(RiskFactor("SOURCE_OF_FUNDS", 5, "Source of funds: investments"))
            }
            if (documentChannel) {
                add(RiskFactor("CHANNEL", 10, "Non-face-to-face: identity from a document, not a bank"))
            }
            if (profile.usPerson) add(RiskFactor("US_PERSON", 5, "US person (FATCA)"))
        }
        val score = f.sumOf { it.points }
        val rating = when {
            score >= 60 -> "VERY_HIGH"
            score >= 40 -> "HIGH"
            score >= 20 -> "MEDIUM"
            else -> "LOW"
        }
        return RiskScore(score, rating, f)
    }
}
