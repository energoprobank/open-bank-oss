// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.infrastructure.persistence

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.rewards.application.Codec
import com.openbank.rewards.domain.BenefitParams
import com.openbank.rewards.domain.FulfilmentOutcome
import com.openbank.rewards.domain.MissionRule
import com.openbank.rewards.domain.Progress
import com.openbank.rewards.domain.Reward
import jakarta.enterprise.context.ApplicationScoped

/** The service's one JSON mapper (stored rule/reward/progress columns, outbox payloads, staff payloads). */
object RewardsJson {
    val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    fun write(value: Any?): String = mapper.writeValueAsString(value)

    fun rule(json: String): MissionRule = mapper.readValue(json)

    fun reward(json: String): Reward = mapper.readValue(json)

    fun progress(json: String): Progress = mapper.readValue(json)

    fun params(json: String): BenefitParams = mapper.readValue(json)

    fun outcome(json: String?): FulfilmentOutcome? = json?.let { mapper.readValue(it) }
}

@ApplicationScoped
class JacksonCodec : Codec {
    override fun write(value: Any?): String = RewardsJson.write(value)

    override fun reward(json: String): Reward = RewardsJson.reward(json)
}
