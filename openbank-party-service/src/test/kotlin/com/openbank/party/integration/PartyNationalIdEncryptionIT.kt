// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Experience v2 step 9 over real HTTP and a real database: a person's national number never sits in
 * `tax_id` — the row holds only ciphertext and the blind index; the API shows it masked, the reveal
 * endpoint in clear; a second person with the same number is refused; nationality and the verified
 * identity are persisted.
 */
@QuarkusTest
@QuarkusTestResource(PartyNationalIdEncryptionIT.Profile::class)
@QuarkusTestResource(
    value = PostgresRedpandaTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_party_it")],
)
class PartyNationalIdEncryptionIT {

    class Profile : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "openbank.identity.rc-pepper" to "it-pepper",
        )
        override fun stop() = Unit
    }

    @Inject
    lateinit var dataSource: DataSource

    private fun createApplicant(): UUID {
        val id = given().contentType(ContentType.JSON).header("Idempotency-Key", UUID.randomUUID().toString())
            .body(
                """{"partyType":"INDIVIDUAL","legalName":"Petra Nováková",
                   "email":"enc-${UUID.randomUUID()}@example.cz","market":"CZ"}""",
            )
            .post("/api/v1/parties").then().statusCode(201).extract().jsonPath().getString("id")
        return UUID.fromString(id)
    }

    private fun details(id: UUID, rc: String) = given().contentType(ContentType.JSON)
        .body(
            """{"dateOfBirth":"1991-08-13","nationalId":"$rc","phone":"+420777123456","nationality":"CZ",
               "address":{"line1":"Dlouhá 1","city":"Praha","postalCode":"11000","countryCode":"CZ"}}""",
        )
        .put("/api/v1/parties/$id/onboarding-details")

    @Test
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR", "ROLE_API", "ROLE_KYC"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = EDGE),
        ],
    )
    fun `a national number is stored encrypted, shown masked, revealed on request and never reused`() {
        val rc = rcFor("910813")
        val id = createApplicant()
        details(id, rc).then().statusCode(200)
            .body("taxId", equalTo("910813/****")).body("nationality", equalTo("CZ"))
            .body("dateOfBirth", equalTo("1991-08-13")).body("nationalIdLast4", equalTo(rc.takeLast(4)))

        val row = row(id)
        assertThat(row["tax_id"]).isNull()
        assertThat(row["national_id_ct"]).startsWith("v1:").doesNotContain(rc)
        assertThat(row["rc_blind_index"]).hasSize(64)

        given().get("/api/v1/parties/$id/identity/national-id").then().statusCode(200).body("nationalId", equalTo(rc))
        given().get("/api/v1/parties/$id").then().statusCode(200).body("taxId", equalTo("910813/****"))

        // The same person signing up again: refused before anything is written.
        details(createApplicant(), rc).then().statusCode(409).body("code", equalTo("NATIONAL_ID_TAKEN"))

        given().contentType(ContentType.JSON)
            .body(
                """{"method":"DOCUMENT","documentType":"NATIONAL_ID","documentNumber":"204512345",
                   "issuingCountry":"CZ","expiryDate":"2031-03-01"}""",
            )
            .put(
                "/api/v1/parties/$id/identity/verified",
            ).then().statusCode(200).body("identityMethod", equalTo("DOCUMENT"))
        assertThat(row(id)["identity_method"]).isEqualTo("DOCUMENT")
    }

    private fun row(id: UUID): Map<String, String?> = dataSource.connection.use { c ->
        val ps = c.prepareStatement(
            "SELECT tax_id, national_id_ct, rc_blind_index, identity_method FROM parties WHERE party_id = ?",
        )
        ps.setObject(1, id)
        val rs = ps.executeQuery()
        check(rs.next()) { "no party row $id" }
        listOf("tax_id", "national_id_ct", "rc_blind_index", "identity_method").associateWith { rs.getString(it) }
    }

    private companion object {
        const val EDGE = "service-account-openbank-edge"

        /** A valid 10-digit RČ for the birth part: serial digits chosen so the number divides by 11. */
        fun rcFor(birth: String): String = "${birth}123".let { n9 ->
            n9 +
                (n9.toLong() % 11).let { if (it == 10L) 0 else it }
        }
    }
}
