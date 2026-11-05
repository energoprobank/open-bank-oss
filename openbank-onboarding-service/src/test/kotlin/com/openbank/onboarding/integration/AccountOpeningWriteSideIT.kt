// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.integration

import com.openbank.onboarding.it.Applicants
import com.openbank.onboarding.it.FakeContactCodeDelivery
import com.openbank.onboarding.it.FakePartyMasterData
import com.openbank.onboarding.it.OnboardingPostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.http.ContentType
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The account-opening write side (experience v2 step 8) through real HTTP and a real PostgreSQL:
 * every row here is written by `JdbcApplicationStore` against the V5 schema and read back through
 * the next request, so a column the store names wrongly, or a state the next step cannot read, fails
 * here where a mocked store would pass. Providers and party-service are test doubles (`FakeProviders`).
 */
@QuarkusTest
@QuarkusTestResource(OnboardingPostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
class AccountOpeningWriteSideIT {

    @Inject
    lateinit var delivery: FakeContactCodeDelivery

    @Inject
    lateinit var party: FakePartyMasterData

    private fun json(body: String) = Given {
        contentType(ContentType.JSON)
        body(body)
    }

    @Test
    fun `contact codes are checked one by one, and only a confirmed contact releases the Energo-Pro record`() {
        val email = "jana.testova@example.com"
        val phone = "+420777000111"
        val id: String = json("""{"email":"$email","phone":"$phone"}""") When {
            post("/api/v1/onboarding/contact-verifications")
        } Then {
            statusCode(201)
            body("demo_email_code", nullValue()) // never revealed outside the sandbox
        } Extract { path("id") }

        val prefill = """{"customer_number":"EP-4100999","contact_verification_id":"$id"}"""
        json(prefill) When { post("/api/v1/onboarding/energo-pro/prefill") } Then {
            statusCode(409)
            body("code", equalTo("CONTACT_NOT_CONFIRMED"))
        }

        json("""{"email_code":"000000","phone_code":"${delivery.lastCode.getValue(phone)}"}""") When {
            post("/api/v1/onboarding/contact-verifications/$id/confirm")
        } Then {
            statusCode(200)
            body("email_verified", equalTo(false))
            body("phone_verified", equalTo(true))
        }

        json(prefill) When { post("/api/v1/onboarding/energo-pro/prefill") } Then {
            statusCode(200)
            body("street", equalTo("Na Bělidle 1/2"))
            body("postal_code", equalTo("150 00"))
        }
        json("""{"customer_number":"EP-0000000","contact_verification_id":"$id"}""") When {
            post("/api/v1/onboarding/energo-pro/prefill")
        } Then {
            statusCode(404)
            body("code", equalTo("NO_MATCH"))
        }
    }

    @Test
    fun `an unknown verification and a malformed contact are refused`() {
        json("""{"email_code":"123456"}""") When {
            post("/api/v1/onboarding/contact-verifications/${UUID.randomUUID()}/confirm")
        } Then { statusCode(404) }
        json("""{"email":"not-an-email","phone":"+420777000111"}""") When {
            post("/api/v1/onboarding/contact-verifications")
        } Then { statusCode(400) }
    }

    @Test
    fun `the application collects details, tax residency, package and a verified identity`() {
        val cz = Applicants.CZ
        val base = "/api/v1/onboarding/applications/$cz"
        json(
            """{"date_of_birth":"1990-04-12","birth_number":"9004120004","phone":"+420777000111",
               "address":{"street":"Na Bělidle 1/2","city":"Praha 5","postal_code":"150 00","country":"CZ"}}""",
        ) When { put("$base/personal-details") } Then { statusCode(204) }
        assertThat(party.personalDetails.getValue(cz).city).isEqualTo("Praha 5")

        json("""{"us_person":false,"residencies":[{"country":"CZ"}]}""") When {
            put("$base/tax-residency")
        } Then { statusCode(204) }
        assertThat(party.taxResidencies.getValue(cz).map { it.country }).containsExactly("CZ")

        json("""{"package_code":"NOPE"}""") When { put("$base/package") } Then { statusCode(400) }
        json("""{"package_code":"PLUS"}""") When { put("$base/package") } Then { statusCode(204) }

        val identity: String = json("""{"method":"BANK_ID"}""") When {
            post("$base/identity-verifications")
        } Then {
            statusCode(201)
            body("status", equalTo("VERIFIED"))
        } Extract { path("id") }
        When { get("$base/identity-verifications/$identity") } Then {
            statusCode(200)
            body("checks[0].type", equalTo("BANK_ID"))
        }

        When { get(base) } Then {
            statusCode(200)
            body("package_code", equalTo("PLUS"))
            body("personal_details_at", notNullValue())
            body("tax_residency_at", notNullValue())
            body("identity_method", equalTo("BANK_ID"))
        }
    }

    @Test
    fun `Bank iD is Czech only, and a document scan verifies a Bulgarian applicant`() {
        val base = "/api/v1/onboarding/applications/${Applicants.BG}"
        json("""{"method":"BANK_ID"}""") When { post("$base/identity-verifications") } Then {
            statusCode(409)
            body("code", equalTo("METHOD_NOT_AVAILABLE"))
        }
        json("""{"method":"DOCUMENT_SCAN","document_type":"PASSPORT"}""") When {
            post("$base/identity-verifications")
        } Then {
            statusCode(201)
            body("status", equalTo("VERIFIED"))
            body("checks.type", equalTo(listOf("DOCUMENT", "CHIP", "LIVENESS", "FACE_MATCH")))
        }
        When { get("$base/identity-verifications/${UUID.randomUUID()}") } Then { statusCode(404) }
    }

    @Test
    fun `the face-check limit changes only with a fresh face check, and a payment session is spent once`() {
        val base = "/api/v1/security/liveness/parties/${Applicants.BG}"
        When { get("$base/policy") } Then {
            statusCode(200)
            body("enabled", equalTo(false))
            body("currency", equalTo("EUR"))
        }

        val change: String = json("""{"purpose":"POLICY_CHANGE"}""") When { post("$base/sessions") } Then {
            statusCode(201)
        } Extract { path("id") }
        When { get("$base/sessions/$change") } Then { body("status", equalTo("PASSED")) }

        val update = """{"enabled":true,"threshold":500,"session_id":"$change"}"""
        json(update) When { put("$base/policy") } Then {
            statusCode(200)
            body("enabled", equalTo(true))
            body("last_check_at", notNullValue())
        }
        json(update) When { put("$base/policy") } Then {
            statusCode(409)
            body("code", equalTo("LIVENESS_REQUIRED"))
        }

        val payment: String = json("""{"purpose":"PAYMENT","payment_reference":"inv-1"}""") When {
            post("$base/sessions")
        } Then { statusCode(201) } Extract { path("id") }
        json("{}") When { post("$base/sessions/$payment/consume") } Then { statusCode(200) }
        json("{}") When { post("$base/sessions/$payment/consume") } Then { statusCode(409) }

        When { get("/api/v1/security/liveness/parties/${Applicants.CZ}/sessions/$payment") } Then { statusCode(404) }
    }
}
