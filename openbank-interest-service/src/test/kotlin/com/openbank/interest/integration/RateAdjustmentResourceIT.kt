// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `/api/v1/interest/accounts/{accountId}/rate-adjustments` over real HTTP against the V18 schema:
 * create (201) → replay (200) → conflicting reuse of sourceRef (409) → list → cancel → cancel again.
 *
 * Authorization is NOT exercised here: the %test profile runs `authz.enforce=false` (no OPA sidecar
 * in CI), so @Authorize is advisory; the identity-scoped grant is covered by
 * interest_rest_ext_test.rego. @RolesAllowed IS enforced, hence ROLE_API.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_interest_it")],
)
class RateAdjustmentResourceIT {

    private fun body(deltaBps: Int, sourceRef: String, validTo: String = "2027-02-01") =
        """
        {"deltaBps": $deltaBps, "validFrom": "2026-11-01", "validTo": "$validTo",
         "source": "REWARDS", "sourceRef": "$sourceRef", "approvalRef": "approval-it"}
        """.trimIndent()

    private fun post(accountId: UUID, json: String) = RestAssured.given()
        .contentType("application/json")
        .body(json)
        .post("/api/v1/interest/accounts/$accountId/rate-adjustments")

    @TestSecurity(user = "service-account-openbank-rewards", roles = ["ROLE_API"])
    @Test
    fun `create, replay, conflict, list and cancel`() {
        val accountId = UUID.randomUUID()
        val sourceRef = "coupon-${UUID.randomUUID()}"

        val created = post(accountId, body(50, sourceRef))
        assertThat(created.statusCode).isEqualTo(201)
        val adjustmentId = created.jsonPath().getString("adjustmentId")
        assertThat(created.jsonPath().getString("status")).isEqualTo("ACTIVE")
        assertThat(created.jsonPath().getInt("deltaBps")).isEqualTo(50)
        assertThat(created.jsonPath().getString("createdBy")).isEqualTo("service-account-openbank-rewards")

        val replay = post(accountId, body(50, sourceRef))
        assertThat(replay.statusCode).isEqualTo(200)
        assertThat(replay.jsonPath().getString("adjustmentId")).isEqualTo(adjustmentId)

        val conflict = post(accountId, body(75, sourceRef))
        assertThat(conflict.statusCode).isEqualTo(409)

        val list = RestAssured.given().get("/api/v1/interest/accounts/$accountId/rate-adjustments")
        assertThat(list.statusCode).isEqualTo(200)
        assertThat(list.jsonPath().getList<String>("adjustmentId")).containsExactly(adjustmentId)

        val cancelled = RestAssured.given()
            .delete("/api/v1/interest/accounts/$accountId/rate-adjustments/$adjustmentId")
        assertThat(cancelled.statusCode).isEqualTo(200)
        assertThat(cancelled.jsonPath().getString("status")).isEqualTo("CANCELLED")

        val again = RestAssured.given()
            .delete("/api/v1/interest/accounts/$accountId/rate-adjustments/$adjustmentId")
        assertThat(again.statusCode).isEqualTo(200)
        assertThat(again.jsonPath().getString("status")).isEqualTo("CANCELLED")
    }

    @TestSecurity(user = "service-account-openbank-rewards", roles = ["ROLE_API"])
    @Test
    fun `guards answer 400`() {
        val accountId = UUID.randomUUID()
        assertThat(post(accountId, body(301, "g-${UUID.randomUUID()}")).statusCode).isEqualTo(400)
        assertThat(post(accountId, body(10, "g-${UUID.randomUUID()}", validTo = "2026-11-01")).statusCode)
            .isEqualTo(400)
        assertThat(post(accountId, body(10, "g-${UUID.randomUUID()}", validTo = "2027-12-31")).statusCode)
            .isEqualTo(400)
        assertThat(post(accountId, """{"deltaBps": 10}""").statusCode).isEqualTo(400)
    }

    @TestSecurity(user = "service-account-openbank-rewards", roles = ["ROLE_API"])
    @Test
    fun `cancel of an unknown adjustment answers 404`() {
        val status = RestAssured.given()
            .delete("/api/v1/interest/accounts/${UUID.randomUUID()}/rate-adjustments/${UUID.randomUUID()}")
            .statusCode
        assertThat(status).isEqualTo(404)
    }

    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    @Test
    fun `a viewer cannot create`() {
        assertThat(post(UUID.randomUUID(), body(10, "v-${UUID.randomUUID()}")).statusCode).isEqualTo(403)
    }
}
