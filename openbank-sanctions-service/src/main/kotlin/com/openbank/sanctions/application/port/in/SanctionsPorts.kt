// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.port.`in`

import com.openbank.sanctions.domain.model.*
import java.util.UUID

data class ScreenEntityCommand(
    val idempotencyKey: String,
    val entityType: EntityType,
    val name: String,
    // Nullable element ON PURPOSE (#7867): Jackson null-checks constructor parameters but not
    // collection elements, so `{"aliases": [null]}` arrives holding a null. Only a nullable
    // element type lets SanctionsService reject it with a 400 instead of an NPE-driven 500.
    val aliases: List<String?> = emptyList(),
    val dateOfBirth: String? = null,
    val nationality: String? = null,
    val identifiers: Map<String, String> = emptyMap(),
    /**
     * Restrict screening to these list types (null/empty = all enabled lists).
     * Nullable element for the same reason as [aliases] (#7867): `{"listTypes": [null]}`
     * arrives holding a null despite the Kotlin element type — fleet fuzz run 34017868446
     * turned exactly that into an NPE-driven 500.
     */
    val listTypes: List<String?>? = null,
    /**
     * Keep this party under ongoing monitoring: it is screened again whenever a list it was
     * screened against changes. Requires `identifiers.partyId` (the monitoring key).
     */
    val monitor: Boolean = false,
)

/** What screening a monitored subject again after a list change came to. */
enum class RescreenOutcome {
    /** No match at or above the potential-hit threshold. */
    CLEAR,

    /** It matched, but a person already cleared this party under this name — nothing to review. */
    COVERED_BY_CLEARANCE,

    /** A new HIT / POTENTIAL_HIT check was created and is waiting in the review queue. */
    CHECK_CREATED,

    /** The idempotency key already had a check (this change was re-screened before). */
    ALREADY_SCREENED,
}

/** Totals of one re-screen sweep, for the log line and the tests. */
data class RescreenSummary(
    val screened: Int = 0,
    val checksCreated: Int = 0,
    val coveredByClearance: Int = 0,
    val failed: Int = 0,
    val truncated: Boolean = false,
)

/** Screens monitored subjects again after a list they watch changed. Never throws. */
interface MonitoredRescreenUseCase {
    /**
     * @param changeId the publication id of the SANCTIONS_LIST_CHANGED event (or a stable id derived
     *   from the change-storm evidence); part of each check's idempotency key.
     */
    suspend fun rescreenAfterListChange(listType: SanctionsListType, changeId: String, storm: Boolean): RescreenSummary
}

data class ReviewCommand(
    val checkId: UUID,
    val reviewedBy: String,
    val note: String,
    val newStatus: SanctionsCheckStatus,
)

interface SanctionsUseCase {
    suspend fun screen(cmd: ScreenEntityCommand): SanctionsCheck
    suspend fun review(cmd: ReviewCommand): SanctionsCheck
    suspend fun getById(id: UUID): SanctionsCheck?
    suspend fun listHits(): List<SanctionsCheck>
    suspend fun listPending(): List<SanctionsCheck>
    suspend fun listChecks(): List<SanctionsCheck>
}
