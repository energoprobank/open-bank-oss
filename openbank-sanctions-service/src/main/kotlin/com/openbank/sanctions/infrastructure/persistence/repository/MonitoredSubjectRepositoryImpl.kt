// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.sanctions.application.port.out.MonitoredSubjectRepository
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.MonitoredSubject
import com.openbank.sanctions.domain.model.SanctionsListType
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import io.vertx.sqlclient.Tuple as CoreTuple

/**
 * PgPool-based store of monitored subjects. PgPool rather than Panache so the re-screen sweep —
 * which runs from the list-refresh path, outside any request — needs no Hibernate session.
 */
@ApplicationScoped
class MonitoredSubjectRepositoryImpl(private val pool: PgPool) : MonitoredSubjectRepository {

    private val mapper = jacksonObjectMapper()

    override suspend fun upsert(subject: MonitoredSubject) {
        val args = CoreTuple.wrap(
            listOf(
                subject.partyId,
                subject.entityType.name,
                subject.name,
                mapper.writeValueAsString(subject.aliases),
                subject.dateOfBirth,
                subject.nationality,
                mapper.writeValueAsString(subject.listTypes.map { it.name }),
                OffsetDateTime.ofInstant(subject.lastScreenedAt, ZoneOffset.UTC),
            ),
        )
        pool.preparedQuery(UPSERT_SQL).execute(Tuple.newInstance(args)).awaitSuspending()
    }

    override suspend fun findWatching(
        listType: SanctionsListType,
        afterPartyId: String?,
        limit: Int,
    ): List<MonitoredSubject> {
        val rows = pool.preparedQuery(FIND_WATCHING_SQL)
            .execute(Tuple.of(listType.name, afterPartyId ?: "", limit))
            .awaitSuspending()
        return rows.map { toDomain(it) }
    }

    override suspend fun markScreened(partyId: String, at: Instant) {
        pool.preparedQuery(
            "UPDATE sanctions_monitored_subjects SET last_screened_at = $2, updated_at = NOW() WHERE party_id = $1",
        ).execute(Tuple.of(partyId, OffsetDateTime.ofInstant(at, ZoneOffset.UTC))).awaitSuspending()
    }

    override suspend fun findByPartyId(partyId: String): MonitoredSubject? =
        pool.preparedQuery("SELECT $COLUMNS FROM sanctions_monitored_subjects WHERE party_id = $1")
            .execute(Tuple.of(partyId))
            .awaitSuspending()
            .firstOrNull()
            ?.let { toDomain(it) }

    private fun toDomain(row: Row) = MonitoredSubject(
        partyId = row.getString("party_id"),
        entityType = EntityType.valueOf(row.getString("entity_type")),
        name = row.getString("name"),
        aliases = mapper.readValue(row.getString("aliases")),
        dateOfBirth = row.getString("date_of_birth"),
        nationality = row.getString("nationality"),
        listTypes = mapper.readValue<List<String>>(row.getString("list_types"))
            .mapNotNull { name -> SanctionsListType.entries.firstOrNull { it.name == name } },
        lastScreenedAt = row.getOffsetDateTime("last_screened_at").toInstant(),
    )

    private companion object {
        const val COLUMNS = "party_id, entity_type, name, aliases::text AS aliases, date_of_birth, nationality, " +
            "list_types::text AS list_types, last_screened_at"

        // `$4::text::jsonb`, not `$4::jsonb`: with a jsonb-typed parameter the Vert.x client encodes a
        // Kotlin String as a JSON STRING, so `[]` would be stored as the scalar "[]" — readable by
        // nothing, and invisible to the `list_types ? $1` filter. Binding as text parses the array.
        val UPSERT_SQL = """
            INSERT INTO sanctions_monitored_subjects
                (party_id, entity_type, name, aliases, date_of_birth, nationality, list_types, last_screened_at)
            VALUES ($1, $2, $3, $4::text::jsonb, $5, $6, $7::text::jsonb, $8)
            ON CONFLICT (party_id) DO UPDATE SET
                entity_type      = EXCLUDED.entity_type,
                name             = EXCLUDED.name,
                aliases          = EXCLUDED.aliases,
                date_of_birth    = EXCLUDED.date_of_birth,
                nationality      = EXCLUDED.nationality,
                list_types       = EXCLUDED.list_types,
                last_screened_at = GREATEST(sanctions_monitored_subjects.last_screened_at, EXCLUDED.last_screened_at),
                updated_at       = NOW()
        """.trimIndent()

        // `list_types = '[]'` is "every list"; `?` is jsonb's top-level element test.
        val FIND_WATCHING_SQL = """
            SELECT $COLUMNS FROM sanctions_monitored_subjects
            WHERE (list_types = '[]'::jsonb OR list_types ? $1)
              AND party_id > $2
            ORDER BY party_id
            LIMIT $3
        """.trimIndent()
    }
}
