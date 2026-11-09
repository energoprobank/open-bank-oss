// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.`in`.ScreenEntityCommand
import com.openbank.sanctions.application.port.out.MonitoredSubjectRepository
import com.openbank.sanctions.application.port.out.SanctionsEntryRepository
import com.openbank.sanctions.application.port.out.SanctionsRepository
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.MatchScoring
import com.openbank.sanctions.domain.model.MonitoredSubject
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import com.openbank.sanctions.domain.model.SanctionsEntry
import com.openbank.sanctions.domain.model.SanctionsEntryMatch
import com.openbank.sanctions.domain.model.SanctionsListType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The potential-hit band, date-of-birth / nationality weighting, and `monitor` enrolment. */
class SanctionsMatchingTest {

    private val repo = mockk<SanctionsRepository>()
    private val entryRepo = mockk<SanctionsEntryRepository>()
    private val importer = mockk<SanctionsImportService>()
    private val monitored = mockk<MonitoredSubjectRepository>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC)
    private val service = SanctionsService(repo, entryRepo, importer, clock, monitored)

    @BeforeEach
    fun stubs() {
        every { importer.normalizeForSearch(any()) } answers { firstArg<String>().lowercase() }
        coEvery { entryRepo.search(any(), any(), any(), any()) } returns emptyList()
        coEvery { repo.findByIdempotencyKey(any()) } returns null
        coEvery { repo.saveWithEvent(any(), any()) } answers { firstArg() }
    }

    private fun entry(dob: String? = null, nationalities: List<String> = emptyList()) = SanctionsEntry(
        id = UUID.randomUUID(),
        listType = SanctionsListType.EU_CONSOLIDATED,
        externalId = "eu-1",
        entityType = EntityType.INDIVIDUAL,
        primaryName = "Ivan Petrov",
        dateOfBirth = dob,
        nationalities = nationalities,
        searchText = "ivan petrov",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun stubMatch(score: Double, dob: String? = null, nationalities: List<String> = emptyList()) {
        coEvery { entryRepo.search("ivan petrov", any(), any(), any()) } returns listOf(
            SanctionsEntryMatch(entry(dob, nationalities), "Ivan Petrov", score),
        )
    }

    private fun cmd(dob: String? = null, nationality: String? = null) = ScreenEntityCommand(
        idempotencyKey = "k-${UUID.randomUUID()}",
        entityType = EntityType.INDIVIDUAL,
        name = "Ivan Petrov",
        dateOfBirth = dob,
        nationality = nationality,
    )

    @Test
    fun `the database is asked for the potential-hit band, not only hits`(): Unit = runBlocking {
        service.screen(cmd())
        coVerify { entryRepo.search("ivan petrov", any(), 0.65, any()) }
    }

    @Test
    fun `a 0_70 name match is a POTENTIAL_HIT`(): Unit = runBlocking {
        stubMatch(0.70)
        val result = service.screen(cmd())
        assertThat(result.status).isEqualTo(SanctionsCheckStatus.POTENTIAL_HIT)
        assertThat(result.overallScore).isEqualTo(0.70)
    }

    @Test
    fun `a different birth year demotes a strong name match and says why`(): Unit = runBlocking {
        stubMatch(0.90, dob = "1961-03-04")
        val result = service.screen(cmd(dob = "1990-03-04"))
        val match = result.matches.single()
        assertThat(match.nameScore).isEqualTo(0.90)
        assertThat(match.matchScore).isCloseTo(0.54, within(1e-9))
        assertThat(match.scoreAdjustments).containsExactly(MatchScoring.DOB_MISMATCH)
        assertThat(result.status).isEqualTo(SanctionsCheckStatus.CLEAR)
    }

    @Test
    fun `same birth year but another day is a softer demotion`(): Unit = runBlocking {
        stubMatch(0.90, dob = "1961-03-04")
        val result = service.screen(cmd(dob = "1961-11-20"))
        assertThat(result.matches.single().matchScore).isCloseTo(0.765, within(1e-9))
        assertThat(result.matches.single().scoreAdjustments).containsExactly(MatchScoring.DOB_PARTIAL_MISMATCH)
        assertThat(result.status).isEqualTo(SanctionsCheckStatus.POTENTIAL_HIT)
    }

    @Test
    fun `the same date of birth keeps the name score`(): Unit = runBlocking {
        stubMatch(0.90, dob = "4 Mar 1961")
        val result = service.screen(cmd(dob = "1961-03-04"))
        assertThat(result.matches.single().matchScore).isEqualTo(0.90)
        assertThat(result.matches.single().scoreAdjustments).containsExactly(MatchScoring.DOB_MATCH)
        assertThat(result.status).isEqualTo(SanctionsCheckStatus.HIT)
    }

    @Test
    fun `an entry without a date of birth keeps a strong name match a HIT`(): Unit = runBlocking {
        stubMatch(0.90)
        val result = service.screen(cmd(dob = "1990-01-01", nationality = "CZ"))
        assertThat(result.status).isEqualTo(SanctionsCheckStatus.HIT)
        assertThat(result.matches.single().scoreAdjustments).isEmpty()
    }

    @Test
    fun `a nationality the entry does not list costs five points`(): Unit = runBlocking {
        stubMatch(0.88, nationalities = listOf("ru", "BY"))
        val result = service.screen(cmd(nationality = "CZ"))
        assertThat(result.matches.single().matchScore).isCloseTo(0.83, within(1e-9))
        assertThat(result.matches.single().scoreAdjustments).containsExactly(MatchScoring.NATIONALITY_MISMATCH)
        assertThat(result.status).isEqualTo(SanctionsCheckStatus.POTENTIAL_HIT)

        stubMatch(0.88, nationalities = listOf("ru"))
        assertThat(service.screen(cmd(nationality = "RU")).matches.single().matchScore).isEqualTo(0.88)
    }

    @Test
    fun `monitor without a party id is a 400, with one the party is enrolled`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.screen(cmd().copy(monitor = true)) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("partyId")

        val subject = slot<MonitoredSubject>()
        coEvery { monitored.upsert(capture(subject)) } returns Unit
        service.screen(
            cmd(dob = "1990-01-01", nationality = "CZ").copy(
                monitor = true,
                identifiers = mapOf("partyId" to "party-9"),
                listTypes = listOf("EU_CONSOLIDATED"),
            ),
        )
        assertThat(subject.captured.partyId).isEqualTo("party-9")
        assertThat(subject.captured.dateOfBirth).isEqualTo("1990-01-01")
        assertThat(subject.captured.listTypes).containsExactly(SanctionsListType.EU_CONSOLIDATED)
        assertThat(subject.captured.lastScreenedAt).isEqualTo(Instant.now(clock))
    }

    @Test
    fun `screening without monitor never enrols`(): Unit = runBlocking {
        service.screen(cmd().copy(identifiers = mapOf("partyId" to "party-9")))
        coVerify(exactly = 0) { monitored.upsert(any()) }
    }

    @Test
    fun `entry dates are read in the lists' own shapes`() {
        val ofac = MatchScoring.parseDates("12 May 1960; circa 1962")
        assertThat(ofac.dates.map { it.toString() }).containsExactly("1960-05-12")
        assertThat(ofac.years).containsExactly(1962)
        assertThat(MatchScoring.parseDates("1958 to 1960").years).containsExactlyInAnyOrder(1958, 1959, 1960)
        assertThat(MatchScoring.parseDates("unknown").isEmpty()).isTrue()
        // An unparseable entry date is absent, never a mismatch.
        assertThat(MatchScoring.score(0.9, "1990-01-01", null, "unknown", emptyList()).score).isEqualTo(0.9)
        // A year-only entry value the subject's year matches keeps the score.
        val yearOnly = MatchScoring.score(0.9, "1962-07-01", null, "1962", emptyList())
        assertThat(yearOnly.score).isEqualTo(0.9)
        assertThat(yearOnly.adjustments).containsExactly(MatchScoring.DOB_YEAR_MATCH)
    }
}
