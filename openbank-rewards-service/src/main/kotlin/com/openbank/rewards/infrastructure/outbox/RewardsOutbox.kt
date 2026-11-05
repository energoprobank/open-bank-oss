// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.agroal.api.AgroalDataSource
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.kafka.common.header.internals.RecordHeaders
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The rewards outbox on plain JDBC (the domain rows and the outbox row commit in one JDBC
 * transaction, see JdbcRewardsStore.enqueue). Claim = FOR UPDATE SKIP LOCKED, so replicas never
 * double-send; delivery stays at-least-once and consumers dedupe on `ce-id`.
 */
@ApplicationScoped
@Suppress("MagicNumber") // JDBC parameter indexes
class JdbcRewardsOutboxRepository(private val ds: AgroalDataSource) : OutboxRepository {

    override suspend fun listProcessable(limit: Int): List<OutboxEntry> = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            c.prepareStatement(
                "SELECT * FROM rewards_outbox WHERE status IN ('PENDING', 'FAILED') AND (next_attempt_at IS NULL OR next_attempt_at <= NOW()) ORDER BY created_at, id LIMIT ?",
            ).use { ps ->
                ps.setInt(1, limit)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.entry()) } }
            }
        }
    }

    override suspend fun claimProcessable(limit: Int, staleAfter: Duration): List<OutboxEntry> =
        withContext(Dispatchers.IO) {
            ds.connection.use { c ->
                c.prepareStatement(
                    """UPDATE rewards_outbox SET status = 'DISPATCHING', claimed_at = NOW(), updated_at = NOW()
                   WHERE id IN (SELECT id FROM rewards_outbox
                                WHERE (status IN ('PENDING', 'FAILED') AND (next_attempt_at IS NULL OR next_attempt_at <= NOW()))
                                   OR (status = 'DISPATCHING' AND claimed_at < ?)
                                ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED)
                   RETURNING *""",
                ).use { ps ->
                    ps.setTimestamp(1, Timestamp.from(Instant.now().minus(staleAfter)))
                    ps.setInt(2, limit)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.entry()) } }
                }
            }.sortedBy { it.createdAt }
        }

    override suspend fun countProcessable(): Long = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "SELECT COUNT(*) FROM rewards_outbox WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING')",
                ).use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
    }

    override suspend fun markSent(eventId: UUID, sentAt: Instant) {
        withContext(Dispatchers.IO) {
            ds.connection.use { c ->
                c.prepareStatement(
                    "UPDATE rewards_outbox SET status = 'SENT', sent_at = ?, updated_at = ? WHERE event_id = ?",
                ).use { ps ->
                    ps.setTimestamp(1, Timestamp.from(sentAt))
                    ps.setTimestamp(2, Timestamp.from(sentAt))
                    ps.setObject(3, eventId)
                    ps.executeUpdate()
                }
            }
        }
    }

    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant): OutboxStatus =
        withContext(Dispatchers.IO) {
            ds.connection.use { c ->
                c.prepareStatement(
                    """UPDATE rewards_outbox SET attempt_count = attempt_count + 1, last_error = ?, updated_at = ?,
                     status = CASE WHEN attempt_count + 1 >= ? THEN 'DEAD' ELSE 'FAILED' END,
                     next_attempt_at = ? + (INTERVAL '1 second' * POWER(2, LEAST(attempt_count, 8)))
                   WHERE event_id = ? RETURNING status""",
                ).use { ps ->
                    ps.setString(1, error.take(MAX_ERROR))
                    ps.setTimestamp(2, Timestamp.from(failedAt))
                    ps.setInt(3, MAX_ATTEMPTS)
                    ps.setTimestamp(4, Timestamp.from(failedAt))
                    ps.setObject(5, eventId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) OutboxStatus.valueOf(rs.getString(1)) else OutboxStatus.FAILED
                    }
                }
            }
        }

    private fun ResultSet.entry() = OutboxEntry(
        eventId = getObject("event_id", UUID::class.java),
        aggregateId = getObject("aggregate_id", UUID::class.java),
        eventType = getString("event_type"),
        payload = getString("payload"),
        status = OutboxStatus.valueOf(getString("status")),
        attemptCount = getInt("attempt_count"),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
        sentAt = getTimestamp("sent_at")?.toInstant(),
        lastError = getString("last_error"),
        synthetic = getBoolean("synthetic"),
        claimedAt = getTimestamp("claimed_at")?.toInstant(),
    )

    companion object {
        const val MAX_ATTEMPTS = 10
        const val MAX_ERROR = 2000
    }
}

/** Publishes to `openbank.rewards.events.v1`, keyed by party id (spec: partitioning by party). */
@ApplicationScoped
class KafkaRewardsOutboxEventPublisher(@Channel("rewards-outbox-out") private val emitter: MutinyEmitter<String>) :
    OutboxEventPublisher {
    override suspend fun publish(entry: OutboxEntry) {
        val headers = RecordHeaders()
        OutboxKafkaHeaders.headersFor(entry).forEach { (k, v) -> headers.add(k, v.toByteArray()) }
        val meta = OutgoingKafkaRecordMetadata.builder<String>().withKey(
            OutboxKafkaHeaders.partitionKey(entry),
        ).withHeaders(headers).build()
        emitter.sendMessage(Message.of(entry.payload).addMetadata(meta)).awaitSuspending()
    }
}

/**
 * Drains the outbox. A `suspend` @Scheduled method (never runBlocking, fleet rule); the JDBC work
 * inside the repository moves to the IO dispatcher. `openbank.outbox.dispatch-enabled` is true in
 * application.yaml — false by default in code, by house convention.
 */
@ApplicationScoped
class RewardsOutboxDispatcher(
    private val repo: JdbcRewardsOutboxRepository,
    private val publisher: KafkaRewardsOutboxEventPublisher,
    @ConfigProperty(name = "openbank.outbox.dispatch-enabled", defaultValue = "false") private val dispatchEnabled:
    Boolean,
    metrics: DomainMetrics,
) : AbstractOutboxDispatcher(metrics) {
    override val outboxRepository: OutboxRepository get() = repo
    override val outboxEventPublisher: OutboxEventPublisher get() = publisher

    @Scheduled(
        every = "\${openbank.outbox.poll-interval:5s}",
        delayed = "\${openbank.outbox.initial-delay:5s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "rewards-outbox-dispatcher",
    )
    suspend fun dispatch() {
        if (dispatchEnabled) dispatchScheduledBatch()
    }
}
