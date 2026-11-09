// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.out.MonitoredSubjectRepository
import com.openbank.sanctions.application.port.out.SanctionsEntryRepository
import com.openbank.sanctions.application.port.out.SanctionsRepository
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.MonitoredSubject
import com.openbank.sanctions.domain.model.SanctionsCheck
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Re-screening on list change, over the REAL [SanctionsService] (so the check it creates takes the
 * ordinary screen path) with the persistence ports mocked.
 */
class MonitoredSubjectRescreenServiceTest {

    private val repo = mockk<SanctionsRepository>()
    private val entryRepo = mockk<SanctionsEntryRepository>()
    private val importer = mockk<SanctionsImportService>()
    private val subjects = mockk<MonitoredSubjectRepository>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC)
    private val sanctions = SanctionsService(repo, entryRepo, importer, clock, subjects)
    private val service = MonitoredSubjectRescreenService(subjects, sanctions, clock)

    private fun subject(partyId: String, name: String = "Ivan Petrov") = MonitoredSubject(
        partyId = partyId,
        entityType = EntityType.INDIVIDUAL,
        name = name,
        aliases = emptyList(),
        dateOfBirth = null,
        nationality = null,
        listTypes = emptyList(),
        lastScreenedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private val listed = SanctionsEntry(
        listType = SanctionsListType.EU_CONSOLIDATED,
        externalId = "eu-7",
        entityType = EntityType.INDIVIDUAL,
        primaryName = "Ivan Petrov",
        searchText = "ivan petrov",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @BeforeEach
    fun stubs() {
        every { importer.normalizeForSearch(any()) } answers { firstArg<String>().lowercase() }
        coEvery { entryRepo.search(any(), any(), any(), any()) } returns emptyList()
        coEvery { entryRepo.search("ivan petrov", any(), any(), any()) } returns
            listOf(SanctionsEntryMatch(listed, "Ivan Petrov", 0.72))
        coEvery { repo.findByIdempotencyKey(any()) } returns null
        coEvery { repo.findLatestReviewedForParty(any()) } returns null
        coEvery { repo.saveWithEvent(any(), any()) } answers { firstArg() }
    }

    private fun page(vararg s: MonitoredSubject) {
        coEvery { subjects.findWatching(SanctionsListType.EU_CONSOLIDATED, null, any()) } returns s.toList()
        coEvery {
            subjects.findWatching(SanctionsListType.EU_CONSOLIDATED, s.last().partyId, any())
        } returns emptyList()
    }

    @Test
    fun `a monitored subject matching the changed list becomes a new pending check`(): Unit = runBlocking {
        page(subject("party-1"))
        val saved = slot<SanctionsCheck>()
        coEvery { repo.saveWithEvent(capture(saved), any()) } answers { firstArg() }

        val summary = service.rescreenAfterListChange(SanctionsListType.EU_CONSOLIDATED, "pub-1", storm = false)

        assertThat(summary.checksCreated).isEqualTo(1)
        assertThat(saved.captured.status).isEqualTo(SanctionsCheckStatus.POTENTIAL_HIT)
        assertThat(saved.captured.idempotencyKey).isEqualTo("rescreen:pub-1:party-1")
        assertThat(saved.captured.checkedLists).containsExactly(SanctionsListType.EU_CONSOLIDATED)
        assertThat(saved.captured.identifiers).containsEntry("partyId", "party-1")
        coVerify { repo.saveWithEvent(any(), SanctionsService.EVENT_SCREENED) }
        coVerify { subjects.markScreened("party-1", Instant.now(clock)) }
    }

    @Test
    fun `a party compliance already cleared under this name is covered, not queued again`(): Unit = runBlocking {
        page(subject("party-1"))
        coEvery { repo.findLatestReviewedForParty("party-1") } returns cleared("Ivan Petrov")

        val summary = service.rescreenAfterListChange(SanctionsListType.EU_CONSOLIDATED, "pub-1", storm = false)

        assertThat(summary.coveredByClearance).isEqualTo(1)
        assertThat(summary.checksCreated).isZero()
        coVerify(exactly = 0) { repo.saveWithEvent(any(), any()) }
    }

    @Test
    fun `a clear re-screen records nothing`(): Unit = runBlocking {
        page(subject("party-2", name = "Jana Novakova"))
        val summary = service.rescreenAfterListChange(SanctionsListType.EU_CONSOLIDATED, "pub-1", storm = true)
        assertThat(summary.screened).isEqualTo(1)
        coVerify(exactly = 0) { repo.saveWithEvent(any(), any()) }
    }

    @Test
    fun `one failing subject does not stop the others, and nothing is thrown`(): Unit = runBlocking {
        page(subject("party-a"), subject("party-b"))
        coEvery { repo.findByIdempotencyKey("rescreen:pub-1:party-a") } throws IllegalStateException("db hiccup")

        val summary = service.rescreenAfterListChange(SanctionsListType.EU_CONSOLIDATED, "pub-1", storm = false)

        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.checksCreated).isEqualTo(1)
        coVerify { repo.saveWithEvent(match { it.idempotencyKey == "rescreen:pub-1:party-b" }, any()) }
    }

    @Test
    fun `a failing subject query is swallowed, never rethrown into the refresh`(): Unit = runBlocking {
        coEvery { subjects.findWatching(any(), any(), any()) } throws IllegalStateException("table gone")
        val summary = service.rescreenAfterListChange(SanctionsListType.EU_CONSOLIDATED, "pub-1", storm = false)
        assertThat(summary.checksCreated).isZero()
    }

    @Test
    fun `a change already re-screened is not screened twice`(): Unit = runBlocking {
        page(subject("party-1"))
        coEvery { repo.findByIdempotencyKey("rescreen:pub-1:party-1") } returns cleared("Ivan Petrov")
        service.rescreenAfterListChange(SanctionsListType.EU_CONSOLIDATED, "pub-1", storm = false)
        coVerify(exactly = 0) { repo.saveWithEvent(any(), any()) }
    }

    private fun cleared(name: String) = SanctionsCheck(
        id = UUID.randomUUID(), idempotencyKey = "earlier", entityType = EntityType.INDIVIDUAL,
        name = name, aliases = emptyList(), dateOfBirth = null, nationality = null,
        identifiers = mapOf("partyId" to "party-1"), status = SanctionsCheckStatus.CLEAR,
        matches = emptyList(), overallScore = 0.0, checkedLists = SanctionsListType.entries,
        reviewedBy = "officer@bank", reviewNote = "false positive",
        checkedAt = Instant.parse("2026-01-01T00:00:00Z"), reviewedAt = Instant.parse("2026-01-02T00:00:00Z"),
    )
}
