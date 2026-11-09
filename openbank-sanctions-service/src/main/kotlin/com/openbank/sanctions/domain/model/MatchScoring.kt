// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.domain.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** A name score after the subject's date of birth and nationality have been weighed against one entry. */
data class ScoredMatch(val score: Double, val adjustments: List<String>)

/**
 * Moves a pure name-similarity score using the two identifying attributes a screening subject and a
 * list entry can share: date of birth and nationality.
 *
 * Only DISAGREEMENT lowers a score, and only when BOTH sides carry the value. An entry with no date
 * of birth (most organisations, many PEP rows, every seed entry) keeps its name score untouched — a
 * strong name match on such an entry stays a HIT or POTENTIAL_HIT, because nothing proves it is a
 * different person and a person must look. Agreement never raises a score above the name score:
 * the name is what the lists are about, and a shared birthday is not a sanctions signal.
 *
 *  - same full date of birth → score stands (`dob_match`)
 *  - same birth year, entry knows only the year → score stands (`dob_year_match`)
 *  - same birth year, both full dates known and different → score × 0.85 (`dob_partial_mismatch`)
 *  - birth year differs → score × 0.6 (`dob_mismatch`)
 *  - entry lists nationality/citizenship countries and the subject's is not among them → −0.05
 *    (`nationality_mismatch`)
 *
 * Entry dates come in the source lists' own shapes — ISO `1960-05-12` (EU FSF, OpenSanctions),
 * `12 May 1960` or a bare `1960` (OFAC), several values joined by `;`, or a `1960 to 1962` range.
 * A value that yields no year at all is treated as absent, never as a mismatch.
 */
object MatchScoring {
    const val DOB_YEAR_MISMATCH_FACTOR = 0.6
    const val DOB_DATE_MISMATCH_FACTOR = 0.85
    const val NATIONALITY_MISMATCH_PENALTY = 0.05

    const val DOB_MATCH = "dob_match"
    const val DOB_YEAR_MATCH = "dob_year_match"
    const val DOB_PARTIAL_MISMATCH = "dob_partial_mismatch"
    const val DOB_MISMATCH = "dob_mismatch"
    const val NATIONALITY_MISMATCH = "nationality_mismatch"

    fun score(
        nameScore: Double,
        subjectDob: String?,
        subjectNationality: String?,
        entryDob: String?,
        entryNationalities: List<String>,
    ): ScoredMatch {
        var score = nameScore
        val adjustments = mutableListOf<String>()

        val subject = subjectDob?.let { parseDates(it) }?.takeIf { !it.isEmpty() }
        val entry = entryDob?.let { parseDates(it) }?.takeIf { !it.isEmpty() }
        if (subject != null && entry != null) {
            val verdict = compareDob(subject, entry)
            if (verdict == DOB_MISMATCH) score *= DOB_YEAR_MISMATCH_FACTOR
            if (verdict == DOB_PARTIAL_MISMATCH) score *= DOB_DATE_MISMATCH_FACTOR
            adjustments += verdict
        }

        val nationality = subjectNationality?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val entryCountries = entryNationalities.map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        if (nationality != null && entryCountries.isNotEmpty() && nationality !in entryCountries) {
            score -= NATIONALITY_MISMATCH_PENALTY
            adjustments += NATIONALITY_MISMATCH
        }
        return ScoredMatch(score.coerceIn(0.0, 1.0), adjustments)
    }

    private fun compareDob(subject: Dates, entry: Dates): String {
        val subjectDate = subject.dates.firstOrNull()
        if (subjectDate != null && subjectDate in entry.dates) return DOB_MATCH
        val subjectYears = subject.allYears()
        val sharedYears = subjectYears intersect entry.allYears()
        if (sharedYears.isEmpty()) return DOB_MISMATCH
        // The year agrees. A full date on both sides that still differs is a softer disagreement —
        // unless the entry also carries a year-only value for that year, which the subject matches.
        val entryKnowsOnlyYear = sharedYears.any { it in entry.years }
        return if (subjectDate != null && !entryKnowsOnlyYear && entry.dates.any { it.year in sharedYears }) {
            DOB_PARTIAL_MISMATCH
        } else {
            DOB_YEAR_MATCH
        }
    }

    /** Full dates and year-only values found in a raw date-of-birth string. */
    internal data class Dates(val dates: Set<LocalDate>, val years: Set<Int>) {
        fun isEmpty() = dates.isEmpty() && years.isEmpty()
        fun allYears() = years + dates.map { it.year }
    }

    private val ISO = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val YEAR = Regex("""\b(1[89]\d{2}|20\d{2})\b""")
    private val RANGE = Regex(
        """\b(1[89]\d{2}|20\d{2})\s*(?:to|-|–)\s*(1[89]\d{2}|20\d{2})\b""",
        RegexOption.IGNORE_CASE,
    )
    private const val MAX_RANGE_YEARS = 20
    private val TEXT_DATE: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("d MMM yyyy")
        .toFormatter(Locale.ENGLISH)

    internal fun parseDates(raw: String): Dates {
        val dates = mutableSetOf<LocalDate>()
        val years = mutableSetOf<Int>()
        for (token in raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val date = parseFullDate(token)
            if (date != null) dates += date else years += parseYears(token)
        }
        return Dates(dates, years)
    }

    private fun parseFullDate(token: String): LocalDate? =
        ISO.find(token)?.let { runCatching { LocalDate.parse(it.value) }.getOrNull() }
            ?: runCatching { LocalDate.parse(token, TEXT_DATE) }.getOrNull()

    private fun parseYears(token: String): Set<Int> {
        val range = RANGE.find(token) ?: return YEAR.findAll(token).map { it.value.toInt() }.toSet()
        val (from, to) = range.destructured
        val span = from.toInt()..to.toInt()
        return if (span.count() in 1..MAX_RANGE_YEARS) span.toSet() else emptySet()
    }
}
