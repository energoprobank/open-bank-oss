// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The /rewards routes the edge serves are the ones its spec publishes, and the spec is self-consistent. */
class CustomerRewardsOpenApiTest {
    private val raw = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
    private val spec: JsonNode = ObjectMapper(YAMLFactory()).readTree(raw)

    private val operations = listOf(
        "/rewards/summary" to "get",
        "/rewards/missions" to "get",
        "/rewards/missions/{missionId}/claim" to "post",
        "/rewards/coupons" to "get",
        "/rewards/coupons" to "post",
        "/rewards/catalogue" to "get",
        "/rewards/coupons/{couponId}/activate" to "post",
        "/rewards/coupons/{couponId}/charge" to "post",
        "/rewards/coupons/{couponId}/preview" to "get",
        "/rewards/coupons/{couponId}/use" to "post",
        "/rewards/ledger" to "get",
        "/rewards/settings" to "get",
        "/rewards/settings" to "put",
        "/rewards/codes/claim" to "post",
        "/rewards/lessons" to "get",
        "/rewards/lessons/{lessonId}/quiz" to "post",
        "/rewards/referrals" to "get",
    )

    @Test
    fun `every rewards route is published with a 503 REWARDS_UNAVAILABLE answer`() {
        operations.forEach { (path, method) ->
            val op = spec.path("paths").path(path).path(method)
            assertThat(op.isObject).describedAs("$method $path").isTrue()
            assertThat(op.path("responses").path("503").path("\$ref").asText())
                .isEqualTo("#/components/responses/RewardsUnavailable")
        }
        val published = spec.path("paths").fieldNames().asSequence().filter { it.startsWith("/rewards") }.toSet()
        assertThat(published).isEqualTo(operations.map { it.first }.toSet())
    }

    @Test
    fun `every rewards write requires an Idempotency-Key`() {
        operations.filter { it.second != "get" }.forEach { (path, method) ->
            val refs = spec.path("paths").path(path).path(method).path("parameters").map { it.path("\$ref").asText() }
            assertThat(refs).describedAs("$method $path").contains("#/components/parameters/RewardsIdempotencyKey")
        }
    }

    @Test
    fun `coupon use documents the SCA header and the SCA_REQUIRED answer`() {
        val op = spec.path("paths").path("/rewards/coupons/{couponId}/use").path("post")
        assertThat(op.path("parameters").any { it.path("name").asText() == "X-SCA-Challenge-Id" }).isTrue()
        assertThat(op.path("responses").path("403").path("\$ref").asText())
            .isEqualTo("#/components/responses/RewardsScaRequired")
    }

    @Test
    fun `the loyalty routes are deprecated in favour of rewards`() {
        listOf("/loyalty", "/loyalty/benefits", "/loyalty/earn-sources", "/loyalty/grants").forEach { path ->
            assertThat(spec.path("paths").path(path).path("get").path("deprecated").asBoolean()).isTrue()
        }
        assertThat(spec.path("paths").path("/loyalty/redemptions").path("post").path("deprecated").asBoolean())
            .isTrue()
    }

    @Test
    fun `every component a rewards route references exists`() {
        val refs = Regex("#/components/(\\w+)/(Rewards\\w+)").findAll(raw)
            .map { it.groupValues[1] to it.groupValues[2] }
        refs.toSet().forEach { (kind, name) ->
            assertThat(spec.path("components").path(kind).has(name)).describedAs("$kind/$name").isTrue()
        }
    }
}
