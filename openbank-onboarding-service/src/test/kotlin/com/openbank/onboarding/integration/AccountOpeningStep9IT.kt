// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.integration

import com.openbank.onboarding.application.port.out.PidDecision
import com.openbank.onboarding.application.port.out.PidResolution
import com.openbank.onboarding.domain.model.Person
import com.openbank.onboarding.it.Applicants
import com.openbank.onboarding.it.FakeBankIdOidc
import com.openbank.onboarding.it.FakeDocuments
import com.openbank.onboarding.it.FakeKyc
import com.openbank.onboarding.it.FakePid
import com.openbank.onboarding.it.OnboardingPostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Experience v2 step 9 through real HTTP and PostgreSQL (V6): the orchestrated account opening —
 * identity resolution that fails closed and refuses an existing customer, Bank iD whose claims must
 * be the applicant's, the document + chip + selfie chain with a grey face going to a person, the
 * first payment, and the assessment kyc-service decides on.
 */
@QuarkusTest
@QuarkusTestResource(OnboardingPostgresTestResource::class)
@QuarkusTestResource(AccountOpeningStep9IT.Sandbox::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
class AccountOpeningStep9IT {

    class Sandbox : QuarkusTestResourceLifecycleManager {
        override fun start() = mapOf("openbank.onboarding.first-payment.simulate-enabled" to "true")
        override fun stop() = Unit
    }

    @Inject lateinit var pid: FakePid

    @Inject lateinit var bankId: FakeBankIdOidc

    @Inject lateinit var documents: FakeDocuments

    @Inject lateinit var kyc: FakeKyc

    private fun base(party: UUID) = "/api/v1/onboarding/account-opening/$party"

    private fun details(party: UUID, dob: String) = given().contentType(ContentType.JSON)
        .body(
            """{"date_of_birth":"$dob","birth_number":"${Applicants.numbers.getValue(party)}","phone":"+420777000111",
               "nationality":"cz","address":{"street":"Dlouhá 1","city":"Praha","postal_code":"110 00","country":"CZ"}}""",
        )
        .put("/api/v1/onboarding/applications/$party/personal-details")

    @Test
    fun `a Bank iD applicant whose claims match is approved straight through at LOW risk`() {
        val party = Applicants.BANK_ID
        pid.next = PidResolution(PidDecision.NO_MATCH, null, null)
        details(party, "1985-03-21").then().statusCode(204)
        assertThat(pid.registered).containsKey(party)

        val start = given().post("${base(party)}/identity/bank-id").then().statusCode(201).extract()
        val redirect: String = start.path("redirect_url")
        val state = redirect.substringAfter("state=").substringBefore('&')
        given().contentType(ContentType.JSON).body("""{"code":"$state","state":"$state"}""")
            .post("/api/v1/onboarding/bank-id/callback").then().statusCode(200)
            .body("status", equalTo("VERIFIED")).body("checks.type", hasItem("NATIONAL_ID"))

        given().post("${base(party)}/submit").then().statusCode(200).body("status", equalTo("APPROVED"))
        val body = kyc.assessments.getValue(party)
        assertThat(body["riskLevel"]).isEqualTo("LOW")
        assertThat(body["reviewReasons"] as List<*>).isEmpty()
        @Suppress("UNCHECKED_CAST")
        val evidence = body["evidence"] as Map<String, Any?>
        assertThat(evidence["method"]).isEqualTo("BANK_ID")
    }

    @Test
    fun `an existing customer is refused, and Bank iD for another person fails the attempt`() {
        val party = Applicants.OTHER
        pid.next = PidResolution(PidDecision.MATCH_EXISTING, UUID.randomUUID(), null)
        details(party, "1979-01-01").then().statusCode(409).body("code", equalTo("ALREADY_CUSTOMER"))

        pid.next = PidResolution(PidDecision.NO_MATCH, null, null)
        details(party, "1979-01-01").then().statusCode(204)
        bankId.otherPerson = Person("Jan", "Novák", "1960-01-01", "6001011234", "M")
        try {
            val redirect: String = given().post("${base(party)}/identity/bank-id").then().statusCode(201)
                .extract().path("redirect_url")
            val state = redirect.substringAfter("state=").substringBefore('&')
            given().contentType(ContentType.JSON).body("""{"code":"$state","state":"$state"}""")
                .post("/api/v1/onboarding/bank-id/callback").then().statusCode(200)
                .body("status", equalTo("FAILED"))
        } finally {
            bankId.otherPerson = null
        }
        given().get(base(party)).then().statusCode(200).body("identity_attempts_left", equalTo(2))
        given().post("${base(party)}/submit").then().statusCode(409).body("code", equalTo("IDENTITY_REQUIRED"))
    }

    @Test
    fun `the document path needs chip, selfie and a first payment, and is CDD at least`() {
        val party = Applicants.DOCUMENT
        details(party, "1988-06-15").then().statusCode(204)
        val id: String = given().contentType(ContentType.JSON).body("""{"document_type":"ID_CARD"}""")
            .post("${base(party)}/identity/documents").then().statusCode(201).extract().path("id")
        given().post("${base(party)}/identity/$id/selfie").then().statusCode(409)
        given().post("${base(party)}/identity/$id/document").then().statusCode(200)
            .body("status", equalTo("IN_PROGRESS")).body("document.numberLast4", equalTo("2345"))
        given().post("${base(party)}/identity/$id/chip").then().statusCode(200).body("chip_read", equalTo(true))
        given().post("${base(party)}/identity/$id/selfie").then().statusCode(200).body("status", equalTo("VERIFIED"))

        given().post("${base(party)}/submit").then().statusCode(409).body("code", equalTo("AWAITING_FIRST_PAYMENT"))
        given().post("${base(party)}/first-payment/simulate").then().statusCode(200).body("received", equalTo(true))
            .body("payment.nameMatch", equalTo(true))
        // The document channel alone scores 10: still LOW, so straight through.
        given().post("${base(party)}/submit").then().statusCode(200).body("status", equalTo("APPROVED"))
        @Suppress("UNCHECKED_CAST")
        val evidence = kyc.assessments.getValue(party)["evidence"] as Map<String, Any?>
        assertThat(evidence["method"]).isEqualTo("DOCUMENT")
        assertThat(evidence["firstPayment"]).isNotNull()
    }

    @Test
    fun `a face match in the grey zone verifies the identity but sends the case to a person`() {
        val party = Applicants.GREY
        details(party, "1992-08-07").then().statusCode(204)
        documents.faceMatch = 0.71
        try {
            val id: String = given().contentType(ContentType.JSON).body("{}")
                .post("${base(party)}/identity/documents").then().statusCode(201).extract().path("id")
            given().post("${base(party)}/identity/$id/document").then().statusCode(200)
            given().post("${base(party)}/identity/$id/chip").then().statusCode(200)
            given().post("${base(party)}/identity/$id/selfie").then().statusCode(200)
                .body("status", equalTo("VERIFIED")).body("needs_review", equalTo(true))
        } finally {
            documents.faceMatch = 0.93
        }
        given().post("${base(party)}/first-payment/simulate").then().statusCode(200)
        given().post("${base(party)}/submit").then().statusCode(200)
            .body("status", equalTo("IN_REVIEW")).body("review_reasons", hasItem("FACE_MATCH_GREY"))
    }
}
