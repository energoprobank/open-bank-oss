// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator

import com.openbank.providersimulator.domain.Pkce
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder

/** The step-9 faces over real HTTP: Bank iD's OIDC flow end to end, the registers, the other bank. */
@QuarkusTest
class OnboardingProvidersApiIT {
    private val redirect = "https://bank.example/onboarding/bankid/callback"
    private val verifier = "M25iVXpKU3puUjFaYWg3T1NDTDQtcW1ROUY5YXlwalNoc0hhakxifmZHag"

    private fun applicant(nationalId: String, given: String = "Jana", family: String = "Nováková") =
        """{"given_name":"$given","family_name":"$family","date_of_birth":"1990-05-01","national_id":"$nationalId"}"""

    private fun expectLogin(state: String, nationalId: String) {
        given().contentType(ContentType.JSON).body("""{"state":"$state","applicant":${applicant(nationalId)}}""")
            .post("/api/v1/bankid/oidc/expected-logins").then().statusCode(201).body("state", equalTo(state))
    }

    private fun authPage(state: String, challenge: String = Pkce.s256(verifier)): Response = given()
        .queryParam("response_type", "code")
        .queryParam("client_id", "openbank-onboarding")
        .queryParam("redirect_uri", redirect)
        .queryParam("scope", "openid profile.name profile.birthdate")
        .queryParam("state", state)
        .queryParam("nonce", "n-$state")
        .queryParam("code_challenge", challenge)
        .queryParam("code_challenge_method", "S256")
        .get("/bankid/auth")

    /** Opens the consent page, presses the button, and returns the redirect's query parameters. */
    private fun signIn(state: String, nationalId: String, action: String = "confirm"): Map<String, String> {
        expectLogin(state, nationalId)
        val page = authPage(state).then().statusCode(200)
            .body(containsString("id=\"bankid-confirm\""))
            .body(containsString("Potvrdit / Confirm"))
            .extract().asString()
        val requestId = Regex("name=\"request_id\" value=\"([^\"]+)\"").find(page)!!.groupValues[1]
        val location = given().redirects().follow(false).contentType(ContentType.URLENC)
            .formParam("request_id", requestId).formParam("bank", "0800").formParam("action", action)
            .post("/bankid/auth/confirm").then().statusCode(302).extract().header("Location")
        assertThat(location).startsWith("$redirect?")
        // the consent was single use
        given().redirects().follow(false).contentType(ContentType.URLENC).formParam("request_id", requestId)
            .formParam("action", "confirm").post("/bankid/auth/confirm").then().statusCode(400)
        return URI(location).rawQuery.split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }
    }

    private fun token(code: String, codeVerifier: String = verifier, secret: String = "secret") =
        given().contentType(ContentType.URLENC)
            .formParam("grant_type", "authorization_code")
            .formParam("code", code)
            .formParam("redirect_uri", redirect)
            .formParam("code_verifier", codeVerifier)
            .formParam("client_id", "openbank-onboarding")
            .formParam("client_secret", secret)
            .post("/bankid/token")

    @Test
    @TestSecurity(user = "service-account-openbank-onboarding", roles = ["ROLE_API"])
    fun `Bank iD - auth, confirm, token with PKCE, userinfo - and a code works once`() {
        given().get("/bankid/.well-known/openid-configuration").then().statusCode(200)
            .body("issuer", equalTo("http://localhost:8163/bankid"))
            .body("code_challenge_methods_supported", hasSize<Any>(1))
        given().get("/bankid/assets/bankid.css").then().statusCode(200)

        val wrong = signIn("st-wrong", "9055011234")
        assertThat(wrong["state"]).isEqualTo("st-wrong")
        token(wrong.getValue("code"), codeVerifier = "x".repeat(43)).then().statusCode(400)
            .body("error", equalTo("invalid_grant"))
        // a refused redemption burns the code too
        token(wrong.getValue("code")).then().statusCode(400).body("error", equalTo("invalid_grant"))

        val params = signIn("st-ok", "9055011234")
        token(params.getValue("code"), secret = " ").then().statusCode(401).body("error", equalTo("invalid_client"))
        val tokens = token(params.getValue("code")).then().statusCode(200)
            .body("token_type", equalTo("Bearer")).body("expires_in", equalTo(300))
            .extract().jsonPath()
        val access = tokens.getString("access_token")
        assertThat(tokens.getString("id_token").split('.')).hasSize(3)
        given().header("Authorization", "Bearer $access").get("/bankid/userinfo").then().statusCode(200)
            .body("given_name", equalTo("Jana")).body("family_name", equalTo("Nováková"))
            .body("birthdate", equalTo("1990-05-01")).body("gender", equalTo("female"))
            .body("birthnumber", equalTo("9055011234")).body("nationalities[0]", equalTo("CZ"))
        given().header("Authorization", "Bearer nope").get("/bankid/userinfo").then().statusCode(401)

        // the same code again: refused, and what it issued is revoked
        token(params.getValue("code")).then().statusCode(400).body("error", equalTo("invalid_grant"))
        given().header("Authorization", "Bearer $access").get("/bankid/userinfo").then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-onboarding", roles = ["ROLE_API"])
    fun `Bank iD - another person signs in, a cancel is access_denied, an unknown state is an error page`() {
        given().contentType(ContentType.JSON).body("""{"scenarios":["BANKID_OTHER_PERSON"]}""")
            .put("/api/v1/sim/scenarios/9055021234").then().statusCode(200).body("source", equalTo("RUNTIME"))
        val code = signIn("st-other", "9055021234").getValue("code")
        val access = token(code).then().statusCode(200).extract().path<String>("access_token")
        given().header("Authorization", "Bearer $access").get("/bankid/userinfo").then().statusCode(200)
            .body("family_name", not(equalTo("Nováková"))).body("birthnumber", not(equalTo("9055021234")))

        val cancelled = signIn("st-cancel", "9055011234", action = "cancel")
        assertThat(cancelled).containsEntry("error", "access_denied").containsEntry("state", "st-cancel")

        authPage("st-unknown").then().statusCode(400).body(containsString("id=\"bankid-error\""))
        expectLogin("st-plain", "9055011234")
        authPage("st-plain", challenge = "").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-onboarding", roles = ["ROLE_API"])
    fun `a stolen document, an insolvency, and a first payment from somebody else's account`() {
        val id = "8501011236"
        given().contentType(ContentType.JSON)
            .body(
                """{"scenarios":["INVALID_DOCUMENT","INSOLVENT","FIRST_PAYMENT_OTHER_NAME",""" +
                    """"CHIP_FORGED","FACE_GREY"]}""",
            )
            .put("/api/v1/sim/scenarios/$id").then().statusCode(200)
        given().get("/api/v1/sim/scenarios").then().statusCode(200)
            .body("find { it.national_id == '$id' }.scenarios", hasSize<Any>(5))

        val doc = given().contentType(ContentType.JSON)
            .body(
                """{"reference":"app-9","document_type":"ID_CARD","issuing_country":"CZ",""" +
                    """"applicant":${applicant(id)}}""",
            )
            .post("/api/v1/idv/documents").then().statusCode(201)
            .body("ocr.national_id", equalTo(id)).body("mrz.check_digits_valid", equalTo(true))
            .body("chip_present", equalTo(true))
            .extract().jsonPath()
        val docId = doc.getString("id")
        val number = doc.getString("ocr.document_number")
        given().contentType(ContentType.JSON).body("{}").post("/api/v1/idv/documents/$docId/chip")
            .then().statusCode(200)
            .body("passive_authentication.status", equalTo("INVALID")).body("dg1.document_number", equalTo(number))
            .body("photo_ref", equalTo("chip-photo:$docId"))
        given().contentType(ContentType.JSON).body("""{"reference":"app-9","document_id":"$docId"}""")
            .post("/api/v1/idv/selfies").then().statusCode(201)
            .body("face_match.score", equalTo(0.71f)).body("face_match.against", equalTo("CHIP_PHOTO"))
            .body("liveness.passed", equalTo(true))
        given().contentType(ContentType.JSON).body("""{"reference":"app-9","document_id":"nope"}""")
            .post("/api/v1/idv/selfies").then().statusCode(404)

        given().queryParam("doklad", number).get("/api/v1/mvcr/neplatne-doklady").then().statusCode(200)
            .body("evidovan", equalTo(true)).body("duvod", equalTo("ODCIZEN"))
        given().queryParam("doklad", "123456789").get("/api/v1/mvcr/neplatne-doklady").then()
            .body("evidovan", equalTo(false))
        given().queryParam("rc", "850101/1236").get("/api/v1/isir/osoby").then().statusCode(200)
            .body("rc", equalTo(id)).body("zaznamy", hasSize<Any>(1)).body("zaznamy[0].stav", equalTo("KONKURS"))
        given().get("/api/v1/isir/osoby").then().statusCode(400)

        val payment = """{"payer_name":"Jana Nováková","payer_account":"19-2000145399","payer_bank_code":"0800",""" +
            """"amount":"1.00","currency":"CZK","creditor_account":"2900000001","creditor_bank_code":"2010",""" +
            """"variable_symbol":"8501011236","national_id":"$id"}"""
        given().contentType(ContentType.JSON).body(payment).post("/api/v1/other-bank/payments")
            .then().statusCode(201)
            .body("status", equalTo("SENT"))
        given().queryParam("creditor_account", "2900000001").queryParam("variable_symbol", "8501011236")
            .get("/api/v1/other-bank/incoming").then().statusCode(200)
            .body("", hasSize<Any>(1))
            .body("[0].payer_name", not(equalTo("Jana Nováková")))
            .body("[0].payer_bank_name", equalTo("Česká spořitelna"))
            .body("[0].amount", equalTo("1.00"))

        given().delete("/api/v1/sim/scenarios/$id").then().statusCode(204)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-onboarding", roles = ["ROLE_API"])
    fun `RegiX answers GRAO, MVR and insolvency for a person the IDV vendor met`() {
        val egn = "8802145663"
        given().contentType(ContentType.JSON).body("""{"egn":"$egn"}""")
            .post("/api/v1/regix/operations/GRAO.PersonDataSearch").then().statusCode(404)
        val number: String = given().contentType(ContentType.JSON)
            .body(
                """{"reference":"bg-1","document_type":"ID_CARD","issuing_country":"BG","applicant":""" +
                    applicant(egn, given = "Ivan Petrov", family = "Georgiev") + "}",
            )
            .post("/api/v1/idv/documents").then().statusCode(201).body("ocr.sex", equalTo("M"))
            .body("ocr.nationality", equalTo("BG")).extract().path("ocr.document_number")
        given().contentType(ContentType.JSON).body("""{"egn":"$egn"}""")
            .post("/api/v1/regix/operations/GRAO.PersonDataSearch").then().statusCode(200)
            .body("first_name", equalTo("Ivan")).body("sur_name", equalTo("Petrov"))
            .body("family_name", equalTo("Georgiev")).body("birth_date", equalTo("1988-02-14"))
            .body("gender", equalTo("M"))
        given().contentType(ContentType.JSON).body("""{"egn":"$egn","document_number":"$number"}""")
            .post("/api/v1/regix/operations/MVR.GetPersonalIdentityV2").then().statusCode(200)
            .body("status", equalTo("VALID"))
        given().contentType(ContentType.JSON).body("""{"egn":"$egn"}""")
            .post("/api/v1/regix/operations/BG.InsolvencyCheck").then().statusCode(200)
            .body("proceedings", hasSize<Any>(0))
        given().contentType(ContentType.JSON).body("""{"egn":"$egn"}""")
            .post("/api/v1/regix/operations/Nope").then().statusCode(404)
        given().contentType(ContentType.JSON).body("""{"egn":"12"}""")
            .post("/api/v1/regix/operations/BG.InsolvencyCheck").then().statusCode(400)
    }

    @Test
    fun `the browser faces are anonymous, the bank's views are not`() {
        given().get("/bankid/.well-known/openid-configuration").then().statusCode(200)
        given().get("/bankid/.well-known/jwks.json").then().statusCode(200).body("keys", hasSize<Any>(0))
        given().get("/api/v1/other-bank/incoming?creditor_account=1").then().statusCode(401)
        given().get("/api/v1/sim/scenarios").then().statusCode(401)
        given().contentType(ContentType.JSON).body("""{"state":"s","applicant":${applicant("9055011234")}}""")
            .post("/api/v1/bankid/oidc/expected-logins").then().statusCode(401)
        given().contentType(ContentType.JSON)
            .body(
                """{"payer_name":"Jan Novák","payer_account":"123456789","payer_bank_code":"0300",""" +
                    """"amount":"1","currency":"EUR","creditor_account":"2900000002"}""",
            )
            .post("/api/v1/other-bank/payments").then().statusCode(201)
    }
}
