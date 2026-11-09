// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.scheduler

import com.openbank.party.application.port.out.PartyRepository
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Step 9 backfill: national numbers written in clear before the encryption key existed are moved to
 * `national_id_ct` (and a missing date of birth is derived from them), in bounded batches. A no-op
 * once nothing is left, or without a key. `suspend` so the reactive repository has a Vert.x context
 * (a plain @Scheduled method has none — rules.yaml: scheduled_methods).
 */
@ApplicationScoped
class NationalIdEncryptionBackfill {

    @Inject lateinit var parties: PartyRepository

    @ConfigProperty(name = "openbank.party.national-id.backfill-enabled", defaultValue = "true")
    var enabled: Boolean = true

    private val log = Logger.getLogger(NationalIdEncryptionBackfill::class.java)

    @Scheduled(every = "10m", delayed = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun run() {
        if (!enabled) return
        var total = 0
        repeat(MAX_BATCHES) {
            val n = runCatching { parties.encryptClearNationalIds(BATCH) }
                .onFailure { log.warnf(it, "National-number encryption backfill failed after %d rows", total) }
                .getOrDefault(0)
            total += n
            if (n < BATCH) {
                if (total > 0) log.infof("Encrypted %d national numbers stored in clear", total)
                return
            }
        }
    }

    private companion object {
        const val BATCH = 100
        const val MAX_BATCHES = 50
    }
}
