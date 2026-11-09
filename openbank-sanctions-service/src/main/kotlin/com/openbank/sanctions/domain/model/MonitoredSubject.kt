// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.domain.model

import java.time.Instant

/**
 * A party screened with `monitor = true`: kept so it can be screened again whenever a list it was
 * screened against changes. One row per party (`identifiers.partyId`); the latest monitored
 * screening's subject data wins.
 *
 * [listTypes] empty means "every list", mirroring an absent `listTypes` on the screen request.
 */
data class MonitoredSubject(
    val partyId: String,
    val entityType: EntityType,
    val name: String,
    val aliases: List<String>,
    val dateOfBirth: String?,
    val nationality: String?,
    val listTypes: List<SanctionsListType>,
    val lastScreenedAt: Instant,
) {
    fun watches(listType: SanctionsListType) = listTypes.isEmpty() || listType in listTypes
}
