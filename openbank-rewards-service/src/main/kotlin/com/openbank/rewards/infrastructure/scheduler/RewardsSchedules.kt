// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.infrastructure.scheduler

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.rewards.application.FulfilmentService
import com.openbank.rewards.application.Jobs
import com.openbank.rewards.application.LessonCatalog
import com.openbank.rewards.application.LessonView
import com.openbank.rewards.application.RewardsStore
import com.openbank.rewards.domain.RewardsNotFoundException
import com.openbank.rewards.infrastructure.persistence.RewardsJson
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.jboss.logging.Logger
import java.time.Clock

@ApplicationScoped
class ClockProducer {
    @Produces
    @Singleton
    fun clock(): Clock = Clock.systemUTC()
}

/**
 * Plain (non-suspend) @Scheduled methods run on a worker thread, which is where blocking JDBC
 * belongs. SKIP keeps one run at a time per replica; every job is idempotent across replicas.
 */
@ApplicationScoped
class RewardsSchedules(private val jobs: Jobs, private val fulfilment: FulfilmentService) {
    private val log = Logger.getLogger(RewardsSchedules::class.java)

    @Scheduled(
        cron = "\${rewards.jobs.daily-cron:0 10 2 * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "rewards-daily",
    )
    fun daily() {
        run("lapse missions") { jobs.lapseMissions() }
        run("lapse referrals") { jobs.lapseReferrals() }
        run("expire coupons") { jobs.expireCoupons() }
        run("expire inactive balances") { jobs.expireInactiveBalances() }
        run("tenure anniversaries") { jobs.tenureAnniversaries() }
        run("auto-claim") { jobs.autoClaim() }
        run("liability posting") { jobs.postLiability().size }
    }

    @Scheduled(
        every = "\${rewards.jobs.fulfilment-retry:60s}",
        delayed = "30s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "rewards-fulfilment-retry",
    )
    fun retryFulfilments() {
        run("fulfilment retry") {
            fulfilment.retryPending()
            0
        }
    }

    private fun run(name: String, block: () -> Int) {
        runCatching(block).onSuccess {
            if (it >
                0
            ) {
                log.infof("%s: %d", name, it)
            }
        }.onFailure { log.errorf(it, "%s failed", name) }
    }
}

/** Lessons from the `lesson` table: {"questions":[{"text":{lang:..},"options":{lang:[..]},"answer":n}]}. */
@ApplicationScoped
class StoredLessonCatalog(private val store: RewardsStore) : LessonCatalog {
    override fun all(language: String): List<LessonView> = store.tx { tx ->
        tx.lessons().map { (lessonId, missionId) ->
            val content = RewardsJson.mapper.readTree(tx.lessonContent(lessonId)!!.first)
            LessonView(
                lessonId,
                missionId,
                content["questions"].map { q ->
                    localized(q["text"], language).asText() to
                        localized(q["options"], language).map(JsonNode::asText)
                },
            )
        }
    }

    override fun score(lessonId: String, answers: List<Int>): Pair<Int, Boolean> {
        val (json, passPct) = store.tx { it.lessonContent(lessonId) }
            ?: throw RewardsNotFoundException("LESSON_UNKNOWN", lessonId)
        val questions = RewardsJson.mapper.readTree(json)["questions"].toList()
        val correct = questions.indices.count { i -> answers.getOrNull(i) == questions[i]["answer"].asInt() }
        val score = correct * PERCENT / questions.size.coerceAtLeast(1)
        return score to (score >= passPct)
    }

    override fun missionFor(lessonId: String): String =
        store.tx { tx -> tx.lessons().firstOrNull { it.first == lessonId }?.second } ?: "M-ONB-3"

    private fun localized(node: JsonNode, language: String): JsonNode = node[language.take(2).lowercase()] ?: node["en"]

    companion object {
        const val PERCENT = 100
    }
}
