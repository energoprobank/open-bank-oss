// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.sanctions.application.port.`in`.MonitoredRescreenUseCase
import com.openbank.sanctions.application.port.`in`.RescreenOutcome
import com.openbank.sanctions.application.port.`in`.RescreenSummary
import com.openbank.sanctions.application.port.`in`.ScreenEntityCommand
import com.openbank.sanctions.application.port.out.MonitoredSubjectRepository
import com.openbank.sanctions.domain.model.MonitoredSubject
import com.openbank.sanctions.domain.model.SanctionsListType
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant

/**
 * Ongoing monitoring: when a list publishes a change, every monitored subject that watches that
 * list is screened again against it. A HIT / POTENTIAL_HIT that no prior clearance covers becomes an
 * ordinary check (idempotency key `rescreen:<changeId>:<partyId>`), so it reaches the same /pending
 * review queue and emits the same `SanctionChecked` event as a screen request.
 *
 * Runs on the refresh path, after the publication committed. It therefore must never throw back
 * into the refresh, and one subject's failure must not stop the others. Monitored subjects are
 * expected to be few (customers who opted into, or were enrolled in, monitoring), but the sweep is
 * still bounded: keyset pages of [PAGE_SIZE], at most [MAX_SUBJECTS] per change, with counts logged
 * and truncation reported loudly rather than silently.
 */
@ApplicationScoped
class MonitoredSubjectRescreenService(
    private val subjects: MonitoredSubjectRepository,
    private val sanctions: SanctionsService,
    private val clock: Clock,
) : MonitoredRescreenUseCase {

    @Suppress("TooGenericExceptionCaught") // a sweep failure is logged; the refresh must still complete
    override suspend fun rescreenAfterListChange(
        listType: SanctionsListType,
        changeId: String,
        storm: Boolean,
    ): RescreenSummary {
        var summary = RescreenSummary()
        try {
            summary = sweep(listType, changeId)
        } catch (ex: Exception) {
            Log.errorf(
                "Monitored re-screen for %s change %s aborted (%s: %s)",
                listType,
                changeId,
                ex.javaClass.simpleName,
                ex.message,
            )
        }
        Log.infof(
            "Monitored re-screen for %s %s %s: screened=%d checksCreated=%d coveredByClearance=%d " +
                "failed=%d truncated=%s",
            listType,
            if (storm) "change storm" else "publication",
            changeId,
            summary.screened,
            summary.checksCreated,
            summary.coveredByClearance,
            summary.failed,
            summary.truncated,
        )
        return summary
    }

    private suspend fun sweep(listType: SanctionsListType, changeId: String): RescreenSummary {
        var screened = 0
        var created = 0
        var covered = 0
        var failed = 0
        var after: String? = null
        while (screened + failed < MAX_SUBJECTS) {
            val page = subjects.findWatching(listType, after, minOf(PAGE_SIZE, MAX_SUBJECTS - screened - failed))
            if (page.isEmpty()) break
            for (subject in page) {
                when (rescreenOne(subject, listType, changeId)) {
                    null -> failed++
                    RescreenOutcome.CHECK_CREATED -> {
                        screened++
                        created++
                    }
                    RescreenOutcome.COVERED_BY_CLEARANCE -> {
                        screened++
                        covered++
                    }
                    else -> screened++
                }
            }
            after = page.last().partyId
        }
        val truncated = screened + failed >= MAX_SUBJECTS &&
            subjects.findWatching(listType, after, 1).isNotEmpty()
        if (truncated) {
            Log.errorf(
                "Monitored re-screen for %s change %s stopped at the cap of %d subjects; the rest were NOT re-screened",
                listType,
                changeId,
                MAX_SUBJECTS,
            )
        }
        return RescreenSummary(screened, created, covered, failed, truncated)
    }

    /** Null when this subject failed; the failure is logged and the sweep continues. */
    @Suppress("TooGenericExceptionCaught") // one subject's failure must not stop the others
    private suspend fun rescreenOne(
        subject: MonitoredSubject,
        listType: SanctionsListType,
        changeId: String,
    ): RescreenOutcome? = try {
        val outcome = sanctions.rescreen(
            ScreenEntityCommand(
                idempotencyKey = "rescreen:$changeId:${subject.partyId}",
                entityType = subject.entityType,
                name = subject.name,
                aliases = subject.aliases,
                dateOfBirth = subject.dateOfBirth,
                nationality = subject.nationality,
                identifiers = mapOf("partyId" to subject.partyId),
                listTypes = listOf(listType.name),
            ),
        )
        subjects.markScreened(subject.partyId, Instant.now(clock))
        outcome
    } catch (ex: Exception) {
        Log.warnf(
            "Monitored re-screen of party %s against %s failed (%s: %s) — continuing with the others",
            subject.partyId,
            listType,
            ex.javaClass.simpleName,
            ex.message,
        )
        null
    }

    companion object {
        const val PAGE_SIZE = 100
        const val MAX_SUBJECTS = 5_000
    }
}
