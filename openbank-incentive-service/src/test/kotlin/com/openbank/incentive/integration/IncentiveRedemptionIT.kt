// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.integration

import com.openbank.incentive.it.IncentivePostgresTestResource
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.quarkus.test.security.oidc.OidcTestSecurityIdentityAugmentor
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID
import javax.sql.DataSource

/**
 * FLUX rewards: `POST /api/v1/incentives/redemptions` reserves AND commits in one step for the
 * rewards engine only, and every commit emits `incentive.reservation.committed_for_party.v1`.
 */
@QuarkusTest
@QuarkusTestResource(IncentivePostgresTestResource::class)
@QuarkusTestResource(IncentiveRestContractIT.InMemoryKafkaResource::class)
@TestSecurity(user = "maker@openbank.test", roles = ["ROLE_OPERATOR", "ROLE_API"])
@OidcSecurity(
    claims = [
        Claim(key = "sub", value = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"),
        Claim(key = "azp", value = "openbank-edge"),
        Claim(key = "preferred_username", value = "service-account-openbank-edge"),
    ],
)
class IncentiveRedemptionIT {
    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var testIdentity: TestIdentityAssociation

    private val offers = mutableListOf<String>()

    /**
     * The outbox is one table shared by every @QuarkusTest in this JVM, and
     * IncentiveRestContractIT dispatches the WHOLE of it in one batch. Drop this class's evidence
     * (outbox + its audit twin, keeping the 1:1 invariant) so its rows never crowd that batch.
     */
    @AfterEach
    fun dropEvidence() {
        if (offers.isEmpty()) return
        val ids = offers.joinToString(",") { "'$it'" }
        val scope = "aggregate_id in ($ids) or " +
            "aggregate_id in (select id from promo_reservation where offer_id in ($ids))"
        execute("delete from incentive_outbox where $scope")
        execute("delete from incentive_audit_event where $scope")
        offers.clear()
    }

    @Suppress("LongMethod")
    @Test
    fun `the rewards engine redeems a hub code once, replays it, and is held to limits`() {
        val tag = UUID.randomUUID().toString().take(8).uppercase()
        val codes = (1..4).map { "HUB-$tag-000$it" }
        val offerId = publishedOffer(
            "hub-$tag",
            scope = "[]",
            claimableInHub = true,
            totalLimit = 3,
            perPartyLimit = 1,
            codes = codes,
        )
        val party1 = UUID.randomUUID()
        val party2 = UUID.randomUUID()
        val party3 = UUID.randomUUID()
        val party4 = UUID.randomUUID()

        useRewardsIdentity()
        val reservationId = redeem(party1, codes[0], "HUB", "flux-$tag-1") Then {
            statusCode(200)
            body("status", equalTo("REDEEMED"))
            body("offerId", equalTo(offerId))
        } Extract { path<String>("reservationId") }
        // Same Idempotency-Key replays the committed reservation.
        redeem(party1, codes[0], "HUB", "flux-$tag-1") Then {
            statusCode(200)
            body("reservationId", equalTo(reservationId))
        }
        redeem(party2, codes[0], "HUB", "flux-$tag-2") Then {
            statusCode(409)
            body("code", equalTo("CODE_USED"))
        }
        redeem(party1, codes[1], "HUB", "flux-$tag-3") Then {
            statusCode(409)
            body("code", equalTo("LIMIT_REACHED"))
        }
        redeem(party1, "NOPE-$tag-UNKNOWN", "HUB", null) Then {
            statusCode(404)
            body("code", equalTo("CODE_UNKNOWN"))
        }
        redeem(party1, "short", "HUB", null) Then {
            statusCode(404)
            body("code", equalTo("CODE_UNKNOWN"))
        }
        redeem(party2, codes[1], "ONBOARDING", null) Then { statusCode(200) }
        redeem(party3, codes[2], "TERM_DEPOSIT", null) Then { statusCode(200) }
        redeem(party4, codes[3], "HUB", null) Then {
            statusCode(409)
            body("code", equalTo("LIMIT_REACHED"))
        }

        assertThat(string("select status from promo_reservation where id = '$reservationId'")).isEqualTo("COMMITTED")
        assertThat(string("select product_ref from promo_reservation where id = '$reservationId'")).isEqualTo("HUB")
        assertThat(
            string(
                """select c.status from promo_code_inventory c
                    join promo_reservation r on r.code_digest = c.digest where r.id = '$reservationId'
                """.trimIndent(),
            ),
        ).isEqualTo("REDEEMED")
        assertThat(eventTypes(reservationId)).containsExactlyInAnyOrder(
            "incentive.reservation.created.v1",
            "incentive.reservation.committed.v1",
            "incentive.reservation.committed_for_party.v1",
        )
        val payload = partyCommitPayload(reservationId)
        assertThat(payload).contains("\"partyId\":\"$party1\"")
        assertThat(payload).contains("\"offerId\":\"$offerId\"")
        assertThat(payload).contains("\"reservationId\":\"$reservationId\"")
        assertThat(payload).contains("\"source\":\"DIRECT_REDEMPTION\"")
        assertThat(payload).contains("\"productRef\":\"HUB\"")
        assertThat(payload).doesNotContain(codes[0])
        // Existing lifecycle events still carry no party identifier.
        assertThat(
            count(
                """select count(*) from incentive_outbox where aggregate_id = '$reservationId'
                    and event_type <> 'incentive.reservation.committed_for_party.v1'
                    and payload like '%$party1%'
                """.trimIndent(),
            ),
        ).isZero()
    }

    @Test
    fun `an exclusive offer does not stack on the party's committed benefit in the same context`() {
        val tag = UUID.randomUUID().toString().take(8).uppercase()
        val stackable =
            publishedOffer("stack-$tag", scope = "[]", claimableInHub = true, codes = listOf("STK-$tag-0001"))
        val exclusive = publishedOffer(
            "excl-$tag",
            scope = "[]",
            claimableInHub = true,
            policy = "EXCLUSIVE",
            codes = listOf("EXC-$tag-0001"),
        )
        val party = UUID.randomUUID()
        useRewardsIdentity()
        redeem(party, "STK-$tag-0001", "HUB", null) Then {
            statusCode(200)
            body("offerId", equalTo(stackable))
        }
        redeem(party, "EXC-$tag-0001", "HUB", null) Then {
            statusCode(409)
            body("code", equalTo("NOT_STACKABLE"))
        }
        // A different context is a different benefit slot.
        redeem(party, "EXC-$tag-0001", "ONBOARDING", null) Then {
            statusCode(200)
            body("offerId", equalTo(exclusive))
        }
    }

    @Test
    fun `a product-bound offer is not claimable in the hub, and only the rewards engine may redeem`() {
        val tag = UUID.randomUUID().toString().take(8).uppercase()
        publishedOffer("bound-$tag", scope = "[\"current-account\"]", codes = listOf("BND-$tag-0001"))
        useRewardsIdentity()
        redeem(UUID.randomUUID(), "BND-$tag-0001", "HUB", null) Then {
            statusCode(409)
            body("code", equalTo("CODE_NOT_CLAIMABLE"))
        }
        Given {
            contentType("application/json")
            body("""{"partyId":"${UUID.randomUUID()}","code":"BND-$tag-0001"}""")
        } When { post("/api/v1/incentives/redemptions") } Then { statusCode(400) }

        useIdentity("openbank-edge", "service-account-openbank-edge", "ROLE_API")
        redeem(UUID.randomUUID(), "BND-$tag-0001", "HUB", null) Then { statusCode(403) }
        useIdentity("openbank-admin-ui", "checker@openbank.test", "ROLE_OPERATOR")
        redeem(UUID.randomUUID(), "BND-$tag-0001", "HUB", null) Then { statusCode(403) }
    }

    @Test
    fun `an operator commit also names the party for the rewards engine`() {
        val tag = UUID.randomUUID().toString().take(8).uppercase()
        val offerId = publishedOffer("op-$tag", scope = "[\"current-account\"]", codes = listOf("OPR-$tag-0001"))
        useIdentity("openbank-admin-ui", "checker@openbank.test", "ROLE_OPERATOR")
        val reservationId = Given {
            contentType("application/json")
            header("Idempotency-Key", "op-$tag")
            body("""{"code":"OPR-$tag-0001","partyRef":"party-$tag","productRef":"current-account"}""")
        } When { post("/api/v1/incentives/offers/$offerId/reservations") } Then {
            statusCode(201)
        } Extract { path<String>("id") }
        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/reservations/$reservationId/commit") }
            .Then { statusCode(200) }
        val payload = partyCommitPayload(reservationId)
        assertThat(payload).contains("\"partyId\":\"party-$tag\"")
        assertThat(payload).contains("\"source\":\"RESERVATION_COMMIT\"")
        assertThat(payload).contains("\"eventType\":\"incentive.reservation.committed_for_party.v1\"")
    }

    @Suppress("LongParameterList")
    private fun publishedOffer(
        name: String,
        scope: String,
        claimableInHub: Boolean = false,
        totalLimit: Int = 10,
        perPartyLimit: Int = 1,
        policy: String = "STACKABLE",
        codes: List<String>,
    ): String {
        useIdentity("openbank-admin-ui", "maker@openbank.test", "ROLE_OPERATOR")
        val offerId = Given {
            contentType("application/json")
            body(
                """{
                    "name":"$name","version":1,"productScope":$scope,
                    "effectiveFrom":"${Instant.now().minusSeconds(60)}",
                    "expiresAt":"${Instant.now().plusSeconds(86_400)}",
                    "totalLimit":$totalLimit,"perPartyLimit":$perPartyLimit,
                    "stackingPolicy":"$policy","claimableInHub":$claimableInHub
                }
                """.trimIndent(),
            )
        } When { post("/api/v1/incentives/offers") } Then {
            statusCode(201)
            body("claimableInHub", equalTo(claimableInHub))
        } Extract { path<String>("ref.id") }
        offers += offerId
        Given {
            contentType("application/json")
            body("""{"codes":[${codes.joinToString(",") { "\"$it\"" }}]}""")
        } When { post("/api/v1/incentives/offers/$offerId/codes") } Then { statusCode(201) }
        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/offers/$offerId/submit") }
            .Then { statusCode(200) }
        useIdentity("openbank-admin-ui", "checker@openbank.test", "ROLE_OPERATOR")
        Given { contentType("application/json") }
            .When { post("/api/v1/incentives/offers/$offerId/publish") }
            .Then { statusCode(200) }
        return offerId
    }

    private fun redeem(party: UUID, code: String, context: String, key: String?): Response = Given {
        contentType("application/json")
        if (key != null) header("Idempotency-Key", key)
        body("""{"partyId":"$party","code":"$code","context":"$context"}""")
    } When { post("/api/v1/incentives/redemptions") }

    private fun useRewardsIdentity() = useIdentity("openbank-rewards", "service-account-openbank-rewards", "ROLE_API")

    private fun useIdentity(clientId: String, username: String, role: String) {
        val base = QuarkusSecurityIdentity.builder().setPrincipal { username }.addRole(role).build()
        val annotation = OidcSecurity(
            claims = arrayOf(
                Claim(key = "azp", value = clientId),
                Claim(key = "sub", value = username),
                Claim(key = "preferred_username", value = username),
            ),
        )
        val identity = OidcTestSecurityIdentityAugmentor(Optional.empty()).augment(base, arrayOf(annotation))
        TestJsonWebToken.actor = username
        testIdentity.setTestIdentity(identity)
    }

    private fun partyCommitPayload(reservationId: String): String = string(
        """select payload from incentive_outbox where aggregate_id = '$reservationId'
            and event_type = 'incentive.reservation.committed_for_party.v1'
        """.trimIndent(),
    )

    private fun eventTypes(aggregateId: String): List<String> = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("select event_type from incentive_outbox where aggregate_id = '$aggregateId'")
                .use { result -> generateSequence { if (result.next()) result.getString(1) else null }.toList() }
        }
    }

    private fun execute(sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.executeUpdate(sql) } }
    }

    private fun count(sql: String): Long = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                result.next()
                result.getLong(1)
            }
        }
    }

    private fun string(sql: String): String = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                result.next()
                result.getString(1)
            }
        }
    }
}
