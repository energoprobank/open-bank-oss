// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.port.out

import com.openbank.sanctions.domain.model.MonitoredSubject
import com.openbank.sanctions.domain.model.SanctionsListType
import java.time.Instant

/** Outbound persistence port for parties under ongoing sanctions monitoring. */
interface MonitoredSubjectRepository {

    /** Insert or replace the party's monitored subject data (keyed by partyId). */
    suspend fun upsert(subject: MonitoredSubject)

    /**
     * One page of the subjects that watch [listType] (an empty list-type set watches every list),
     * ordered by partyId and starting strictly after [afterPartyId] — keyset paging, so a large
     * population is walked in bounded pages without OFFSET.
     */
    suspend fun findWatching(listType: SanctionsListType, afterPartyId: String?, limit: Int): List<MonitoredSubject>

    suspend fun markScreened(partyId: String, at: Instant)

    suspend fun findByPartyId(partyId: String): MonitoredSubject?
}
