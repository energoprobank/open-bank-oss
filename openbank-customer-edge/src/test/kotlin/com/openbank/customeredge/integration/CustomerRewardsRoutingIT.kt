// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.integration

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The /rewards surface over HTTP: customer realm only, and — with capability `rewards.flux` at its
 * default (off) — a 404 before any upstream call, like every other feature-gated route.
 */
@QuarkusTest
class CustomerRewardsRoutingIT {
    private val routes = listOf(
        "GET" to "/customer/v1/rewards/summary",
        "GET" to "/customer/v1/rewards/missions",
        "POST" to "/customer/v1/rewards/missions/M-CARD-3/claim",
        "GET" to "/customer/v1/rewards/coupons",
        "POST" to "/customer/v1/rewards/coupons",
        "GET" to "/customer/v1/rewards/catalogue",
        "POST" to "/customer/v1/rewards/coupons/c1/activate",
        "POST" to "/customer/v1/rewards/coupons/c1/charge",
        "GET" to "/customer/v1/rewards/coupons/c1/preview?targetKind=LOAN&targetId=l1",
        "POST" to "/customer/v1/rewards/coupons/c1/use",
        "GET" to "/customer/v1/rewards/ledger",
        "GET" to "/customer/v1/rewards/settings",
        "PUT" to "/customer/v1/rewards/settings",
        "POST" to "/customer/v1/rewards/codes/claim",
        "GET" to "/customer/v1/rewards/lessons",
        "POST" to "/customer/v1/rewards/lessons/L1/quiz",
        "GET" to "/customer/v1/rewards/referrals",
    )

    private fun status(method: String, path: String): Int {
        val spec = given().header("Idempotency-Key", "routing-probe")
        if (method != "GET") spec.contentType("application/json").body("{}")
        return spec.request(method, path).then().extract().statusCode()
    }

    @Test
    fun `every rewards route rejects an unauthenticated caller`() {
        routes.forEach { (method, path) ->
            assertThat(status(method, path)).describedAs("$method $path").isEqualTo(401)
        }
    }

    @Test
    @TestSecurity(user = "customer:rewards-probe", roles = ["ROLE_CUSTOMER"])
    fun `with the capability off every rewards route is a 404`() {
        routes.forEach { (method, path) ->
            assertThat(status(method, path)).describedAs("$method $path").isEqualTo(404)
        }
    }

    @Test
    @TestSecurity(user = "customer:rewards-probe", roles = ["ROLE_CUSTOMER"])
    fun `the capability is published and off by default`() {
        val ids: List<String> = given().get("/customer/v1/capabilities").then().statusCode(200)
            .extract().path("capabilities.id")
        assertThat(ids).contains("rewards.flux")
    }
}
