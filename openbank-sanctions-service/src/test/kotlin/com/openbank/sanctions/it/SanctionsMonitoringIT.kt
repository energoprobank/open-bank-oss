// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.sanctions.application.port.`in`.MonitoredRescreenUseCase
import com.openbank.sanctions.application.port.`in`.ReviewCommand
import com.openbank.sanctions.application.port.`in`.SanctionsUseCase
import com.openbank.sanctions.application.port.out.MonitoredSubjectRepository
import com.openbank.sanctions.application.port.out.SanctionsRepository
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.MonitoredSubject
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import com.openbank.sanctions.domain.model.SanctionsEntry
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsEntryRepositoryImpl
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Real-Postgres cover for the potential-hit band and ongoing monitoring.
 *
 * The band defect lived in SQL: the search asked Postgres for `word_similarity >= 0.85`, so no
 * mocked repository could ever show that a 0.65–0.85 match never came back. Here the score is
 * measured by Postgres itself and the screening goes through the real endpoint.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SanctionsMonitoringIT {

    @Inject
    lateinit var entries: SanctionsEntryRepositoryImpl

    @Inject
    lateinit var rescreen: MonitoredRescreenUseCase

    @Inject
    lateinit var checks: SanctionsRepository

    @Inject
    lateinit var monitored: MonitoredSubjectRepository

    @Inject
    lateinit var useCase: SanctionsUseCase

    @Inject
    lateinit var pool: PgPool

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private val listed = "radomir kratochvil"
    private val candidates = listOf(
        "radomir kratochvilova",
        "radim kratochvil",
        "radek kratochvil",
        "radomir kratochvila",
        "radomil kratochvil",
    )

    @BeforeEach
    fun clean() {
        onEventLoop {
            pool.query("DELETE FROM sanctions_monitored_subjects").execute().awaitSuspending()
            pool.query("DELETE FROM sanctions_entries WHERE external_id LIKE 'mon-it-%'").execute().awaitSuspending()
        }
    }

    private fun seedListed() = onEventLoop {
        entries.upsertAll(
            listOf(
                SanctionsEntry(
                    listType = SanctionsListType.EU_CONSOLIDATED,
                    externalId = "mon-it-1",
                    entityType = EntityType.INDIVIDUAL,
                    primaryName = "Radomir Kratochvil",
                    searchText = listed,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            ),
        )
    }

    /** A subject name whose similarity Postgres itself puts inside the potential-hit band. */
    private fun nameInBand(): String {
        val scored = candidates.associateWith { q ->
            onEventLoop {
                pool.preparedQuery("SELECT word_similarity($1, $2) AS s").execute(Tuple.of(q, listed))
                    .awaitSuspending().first().getDouble("s")
            }
        }
        return scored.entries.firstOrNull { it.value >= 0.65 && it.value < 0.85 }?.key
            ?: error("no candidate scores in [0.65, 0.85): $scored")
    }

    private fun screen(name: String, partyId: String, monitor: Boolean): String = Given {
        contentType("application/json")
        body(
            """{"idempotencyKey":"${UUID.randomUUID()}","entityType":"INDIVIDUAL","name":"$name",""" +
                """"identifiers":{"partyId":"$partyId"},"listTypes":["EU_CONSOLIDATED"],"monitor":$monitor}""",
        )
    } When {
        post("/api/v1/sanctions/screen")
    } Then {
        statusCode(201)
    } Extract {
        path("status")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `a name in the 0_65 to 0_85 band screens POTENTIAL_HIT end to end`() {
        seedListed()
        assertThat(screen(nameInBand(), "party-band", monitor = false)).isEqualTo("POTENTIAL_HIT")
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `a monitored party that a list change starts matching lands in pending, once cleared it does not`() {
        val party = "party-${UUID.randomUUID()}"
        val name = nameInBand()
        // Screened and enrolled while the list does not carry the name yet.
        assertThat(screen(name, party, monitor = true)).isEqualTo("CLEAR")
        assertThat(onEventLoop { monitored.findByPartyId(party) }?.listTypes)
            .containsExactly(SanctionsListType.EU_CONSOLIDATED)

        seedListed() // the list change
        val first = onEventLoop {
            rescreen.rescreenAfterListChange(SanctionsListType.EU_CONSOLIDATED, "pub-a", false)
        }
        assertThat(first.checksCreated).isEqualTo(1)
        val created = onEventLoop { checks.findByIdempotencyKey("rescreen:pub-a:$party") }
        assertThat(created?.status).isEqualTo(SanctionsCheckStatus.POTENTIAL_HIT)
        val pendingKeys: List<String> = When { get("/api/v1/sanctions/pending") } Then {
            statusCode(200)
        } Extract { path("idempotencyKey") }
        assertThat(pendingKeys).contains("rescreen:pub-a:$party")

        // Compliance clears it as a false positive; the next change must not queue it again.
        onEventLoop {
            useCase.review(ReviewCommand(created!!.id, "officer", "false positive", SanctionsCheckStatus.CLEAR))
        }
        val second = onEventLoop {
            rescreen.rescreenAfterListChange(SanctionsListType.EU_CONSOLIDATED, "pub-b", false)
        }
        assertThat(second.coveredByClearance).isEqualTo(1)
        assertThat(second.checksCreated).isZero()
        assertThat(onEventLoop { checks.findByIdempotencyKey("rescreen:pub-b:$party") }).isNull()
    }

    @Test
    fun `a list the subject was not screened against does not re-screen it`() {
        onEventLoop {
            monitored.upsert(
                MonitoredSubject(
                    partyId = "party-ofac-only",
                    entityType = EntityType.INDIVIDUAL,
                    name = "Radomir Kratochvil",
                    aliases = emptyList(),
                    dateOfBirth = null,
                    nationality = null,
                    listTypes = listOf(SanctionsListType.OFAC_SDN),
                    lastScreenedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
        }
        seedListed()
        val summary = onEventLoop {
            rescreen.rescreenAfterListChange(SanctionsListType.EU_CONSOLIDATED, "pub-c", false)
        }
        assertThat(summary.screened).isZero()
    }
}
