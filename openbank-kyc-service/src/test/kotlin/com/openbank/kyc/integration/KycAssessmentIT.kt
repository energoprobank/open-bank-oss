// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.integration

import com.openbank.kyc.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Experience v2 step 9 through real HTTP and a real PostgreSQL (V13): an onboarding assessment is
 * stored with its evidence; a clean LOW-risk one is approved straight through and its event lands
 * in the same commit; anything else waits for a person, who must not be the assessor.
 */
@QuarkusTest
@QuarkusTestResource(KycAssessmentIT.Profile::class)
@QuarkusTestResource(PostgresTestResource::class)
class KycAssessmentIT {

    class Profile : QuarkusTestResourceLifecycleManager {
        // No OPA sidecar in a test JVM: the policy itself is covered by kyc_rest_ext_test.rego.
        override fun start(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "authz.enforce" to "false",
            "openbank.kyc.straight-through.enabled" to "true",
        )
        override fun stop() = Unit
    }

    @Inject
    lateinit var dataSource: DataSource

    private fun assess(partyId: UUID, risk: String, reasons: String = "") = given().contentType(ContentType.JSON)
        .body(
            """{"policyVersion":"onboarding-policy-v1","riskLevel":"$risk","dueDiligence":"CDD",
               "checks":{"IDENTITY":{"status":"PASSED","result":"Bank iD claims match"},
                         "ADDRESS":{"status":"PASSED","result":"Bank iD address"},
                         "PEP_SCREENING":{"status":"PASSED","result":"CLEAR"},
                         "SANCTIONS_SCREENING":{"status":"PASSED","result":"CLEAR"}},
               "reviewReasons":[$reasons],
               "evidence":{"method":"BANK_ID","risk":{"score":0,"rating":"$risk","factors":[]}}}""",
        )
        .post("/api/v1/kyc/cases/party/$partyId/assessment")

    @Test
    @TestSecurity(user = "service-account-openbank-onboarding", roles = ["ROLE_API"])
    fun `a clean LOW-risk assessment is approved straight through, with its evidence and event`() {
        val party = UUID.randomUUID()
        assess(party, "LOW").then().statusCode(200)
            .body("status", equalTo("APPROVED"))
            .body("reviewedBy", equalTo("straight-through:onboarding-policy-v1"))
            .body("evidence.method", equalTo("BANK_ID"))
            .body("policyVersion", equalTo("onboarding-policy-v1"))
        assertThat(column(party, "evidence_json")).contains("\"BANK_ID\"")
        assertThat(column(party, "next_review_date")).isNotNull()
        assertThat(outboxTypes(party)).contains("KYC_CASE_APPROVED")
        // A second submission does not re-open a decided case.
        assess(party, "HIGH").then().statusCode(200).body("status", equalTo("APPROVED"))
    }

    @Test
    @TestSecurity(user = "service-account-openbank-onboarding", roles = ["ROLE_API"])
    fun `a review reason sends the case to a person and an unknown check is a 400`() {
        val party = UUID.randomUUID()
        assess(party, "LOW", "\"FACE_MATCH_GREY\"").then().statusCode(200)
            .body("status", equalTo("UNDER_REVIEW")).body("reviewReasons[0]", equalTo("FACE_MATCH_GREY"))
        given().contentType(ContentType.JSON)
            .body("""{"policyVersion":"p","riskLevel":"LOW","checks":{"SELFIE":{"status":"PASSED"}}}""")
            .post("/api/v1/kyc/cases/party/$party/assessment").then().statusCode(400)
    }

    private fun column(party: UUID, name: String): String? =
        strings("SELECT $name FROM kyc_cases WHERE party_id = ? ORDER BY created_at DESC LIMIT 1", party).firstOrNull()

    private fun outboxTypes(party: UUID): List<String?> = strings(
        "SELECT o.event_type FROM kyc_outbox o JOIN kyc_cases k ON k.case_id = o.aggregate_id WHERE k.party_id = ?",
        party,
    )

    // conn.use closes the statement and result set with it — kept flat for detekt's NestedBlockDepth.
    private fun strings(sql: String, party: UUID): List<String?> = dataSource.connection.use { c ->
        val ps = c.prepareStatement(sql)
        ps.setObject(1, party)
        val rs = ps.executeQuery()
        buildList { while (rs.next()) add(rs.getString(1)) }
    }
}
