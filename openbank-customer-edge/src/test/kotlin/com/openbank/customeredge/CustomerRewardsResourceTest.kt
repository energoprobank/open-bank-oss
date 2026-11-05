// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import com.openbank.customeredge.infrastructure.rest.CustomerPartyResolver
import com.openbank.customeredge.infrastructure.rest.CustomerRewardsResource
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID

/**
 * FLUX rewards proxy. What matters: every engine path is the RESOLVED party's (never one the client
 * sent), writes carry the caller's Idempotency-Key unchanged, every engine failure the app cannot act
 * on is 503 REWARDS_UNAVAILABLE, the capability switch hides the whole surface, and a credit coupon
 * is never used without a spent SCA challenge.
 */
class CustomerRewardsResourceTest {
    private val caller: UUID = UUID.randomUUID()
    private val svc = "http://rewards-service.rewards.svc:8161"
    private val sca = "http://sca-service.sca.svc:8110"
    private val base = "$svc/api/v1/rewards/parties/$caller"
    private val mapper = ObjectMapper()
    private val upstream = mockk<UpstreamClient>()
    private val audit = mockk<EdgeAuditPublisher>(relaxed = true)

    private val parties = mockk<CustomerPartyResolver> { every { resolve(any()) } returns caller }

    private fun resource(
        enabled: Boolean = true,
        url: Optional<String> = Optional.of(svc),
        language: String? = "cs-CZ",
    ) = CustomerRewardsResource(upstream, parties, audit).apply {
        rewardsServiceUrl = url
        fluxEnabled = enabled
        scaServiceUrl = sca
        requestHeaders = mockk<HttpHeaders> {
            every { getHeaderString(any()) } returns null
            every { getHeaderString("Accept-Language") } returns language
        }
    }

    private fun json(response: Response): JsonNode = mapper.readTree(response.entity as String)

    private fun ok(body: String, status: Int = 200): Response = Response.status(status).entity(body).build()

    private fun allRoutes(r: CustomerRewardsResource): List<() -> Response> = listOf(
        { r.summary() },
        { r.missions() },
        { r.claimMission("M-CARD-3", null, "k") },
        { r.coupons() },
        { r.catalogue() },
        { r.issueCoupon("""{"couponDefId":"BILL_5"}""", "k") },
        { r.activateCoupon("c1", null, "k") },
        { r.chargeCoupon("c1", """{"flux":10}""", "k") },
        { r.previewCoupon("c1", "LOAN", "loan1") },
        { r.useCoupon("c1", """{"targetKind":"LOAN","targetId":"loan1"}""", "k", null) },
        { r.ledger(null, null) },
        { r.settings() },
        { r.updateSettings("""{"participating":true}""", "k") },
        { r.claimCode("""{"code":"FLUX10"}""", "k") },
        { r.lessons() },
        { r.submitQuiz("L1", """{"answers":[1]}""", "k") },
        { r.referrals() },
    )

    @Test
    fun `capability off makes every route a 404 with no upstream call`() {
        val r = resource(enabled = false)
        allRoutes(r).forEach { call -> assertThatThrownBy { call() }.isInstanceOf(NotFoundException::class.java) }
        verify(exactly = 0) { upstream.getWithHeaders(any(), any(), any()) }
        verify(exactly = 0) { upstream.post(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { upstream.put(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a blank engine URL is an explicit 503 REWARDS_UNAVAILABLE on every route`() {
        listOf(Optional.empty(), Optional.of(" ")).forEach { url ->
            allRoutes(resource(url = url)).forEach { call ->
                val response = call()
                assertThat(response.status).isEqualTo(503)
                assertThat(json(response)["code"].asText()).isEqualTo("REWARDS_UNAVAILABLE")
            }
        }
        verify(exactly = 0) { upstream.getWithHeaders(any(), any(), any()) }
    }

    @Test
    fun `reads are scoped to the resolved party, forward Accept-Language and relay the body verbatim`() {
        val url = slot<String>()
        val party = slot<String>()
        val headers = slot<Map<String, String>>()
        val body = """{"partyId":"$caller","market":"CZ","balance":120,"level":{"code":"SPARK","index":1}}"""
        every { upstream.getWithHeaders(capture(url), capture(party), capture(headers)) } returns ok(body)

        val response = resource().summary()

        assertThat(url.captured).isEqualTo("$base/summary")
        assertThat(party.captured).isEqualTo(caller.toString())
        assertThat(headers.captured).containsEntry("Accept-Language", "cs-CZ")
        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity as String).isEqualTo(body)
    }

    @Test
    fun `a malformed Accept-Language is not forwarded`() {
        val headers = slot<Map<String, String>>()
        every { upstream.getWithHeaders(any(), any(), capture(headers)) } returns ok("""{"items":[]}""")

        resource(language = "cs\r\nX-Evil: 1").lessons()

        assertThat(headers.captured).isEmpty()
    }

    @Test
    fun `ledger and preview build only validated query strings`() {
        val urls = mutableListOf<String>()
        every { upstream.getWithHeaders(capture(urls), any(), any()) } returns ok("{}")

        resource().ledger(2, 50)
        resource().previewCoupon("c-1", "SAVINGS_ACCOUNT", "acc_9")

        assertThat(urls).containsExactly(
            "$base/ledger?page=2&size=50",
            "$base/coupons/c-1/preview?targetKind=SAVINGS_ACCOUNT&targetId=acc_9",
        )
        assertThat(resource().ledger(0, 500).status).isEqualTo(400)
        assertThat(resource().previewCoupon("c-1", "CARD", "x").status).isEqualTo(400)
        assertThat(resource().previewCoupon("c-1", "LOAN", "../x").status).isEqualTo(400)
    }

    @Test
    fun `a path id that could escape the party path never goes upstream`() {
        listOf("../../other", "a/b", "x.y", "").forEach { id ->
            assertThat(resource().claimMission(id, null, "k").status).isEqualTo(404)
            assertThat(resource().activateCoupon(id, null, "k").status).isEqualTo(404)
        }
        verify(exactly = 0) { upstream.post(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `writes forward the Idempotency-Key unchanged, drop a body partyId and audit the outcome`() {
        val url = slot<String>()
        val body = slot<String>()
        val key = slot<String>()
        every { upstream.post(capture(url), caller.toString(), capture(body), capture(key), any()) } returns
            ok("""{"couponId":"c-9","state":"ISSUED"}""", 201)

        val request = """{"couponDefId":"BILL_5","partyId":"${UUID.randomUUID()}"}"""

        val response = resource().issueCoupon(request, " key-1 ")

        assertThat(url.captured).isEqualTo("$base/coupons")
        assertThat(key.captured).isEqualTo("key-1")
        assertThat(mapper.readTree(body.captured)).isEqualTo(mapper.readTree("""{"couponDefId":"BILL_5"}"""))
        assertThat(response.status).isEqualTo(201)
        verify {
            audit.emit(
                eventType = "CUSTOMER_REWARDS_COUPONS_ISSUE",
                partyId = caller.toString(),
                operation = "rewards.coupons.issue",
                result = "SUCCESS",
                resourceId = "c-9",
                details = any(),
            )
        }
    }

    @Test
    fun `mission claim posts to the party's mission path with an empty object when there is no body`() {
        val body = slot<String>()
        every { upstream.post("$base/missions/M-CARD-3/claim", any(), capture(body), "k", any()) } returns
            ok("""{"balance":10}""")

        assertThat(resource().claimMission("M-CARD-3", null, "k").status).isEqualTo(200)
        assertThat(body.captured).isEqualTo("{}")
    }

    @Test
    fun `settings are replaced with PUT and the Idempotency-Key`() {
        every { upstream.put("$base/settings", caller.toString(), any(), "k2", any()) } returns
            ok("""{"participating":false}""")

        assertThat(resource().updateSettings("""{"participating":false}""", "k2").status).isEqualTo(200)
    }

    @Test
    fun `a write without an Idempotency-Key never reaches the engine`() {
        listOf(null, "", "   ", "x".repeat(256)).forEach { k ->
            val response = resource().claimCode("""{"code":"FLUX10"}""", k)
            assertThat(response.status).isEqualTo(400)
            assertThat(json(response)["code"].asText()).startsWith("IDEMPOTENCY_KEY_")
        }
        verify(exactly = 0) { upstream.post(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the replay marker of an idempotent retry is passed through`() {
        every { upstream.post(any(), any(), any(), any(), any()) } returns Response.ok("""{"status":"REDEEMED"}""")
            .header(UpstreamClient.IDEMPOTENCY_REPLAY_HEADER, "true").build()

        val response = resource().claimCode("""{"code":"FLUX10"}""", "k")

        assertThat(response.getHeaderString(UpstreamClient.IDEMPOTENCY_REPLAY_HEADER)).isEqualTo("true")
    }

    @Test
    fun `an engine 4xx keeps its status and only code and message`() {
        every { upstream.post(any(), any(), any(), any(), any()) } returns ok(
            """{"code":"INSUFFICIENT_FLUX","message":"not enough FLUX","internal":"secret"}""",
            409,
        )

        val response = resource().chargeCoupon("c1", """{"flux":999}""", "k")

        assertThat(response.status).isEqualTo(409)
        assertThat(json(response)["code"].asText()).isEqualTo("INSUFFICIENT_FLUX")
        assertThat(json(response)["message"].asText()).isEqualTo("not enough FLUX")
        assertThat(response.entity as String).doesNotContain("secret")
    }

    @Test
    fun `an engine 404 code such as CODE_UNKNOWN is relayed`() {
        every { upstream.post(any(), any(), any(), any(), any()) } returns
            ok("""{"code":"CODE_UNKNOWN","message":"no such code"}""", 404)

        val response = resource().claimCode("""{"code":"NOPE"}""", "k")

        assertThat(response.status).isEqualTo(404)
        assertThat(json(response)["code"].asText()).isEqualTo("CODE_UNKNOWN")
    }

    @Test
    fun `an unavailable or misbehaving engine is 503 REWARDS_UNAVAILABLE without its body`() {
        listOf(
            ok("""{"error":"upstream unavailable"}""", 502),
            ok("""{"secret":"stack"}""", 503),
            ok("""{"secret":"stack"}""", 500),
            ok("""{"error":"forbidden"}""", 403),
            ok("not json", 200),
        ).forEach { upstreamResponse ->
            every { upstream.getWithHeaders(any(), any(), any()) } returns upstreamResponse

            val response = resource().summary()

            assertThat(response.status).isEqualTo(503)
            assertThat(json(response)["code"].asText()).isEqualTo("REWARDS_UNAVAILABLE")
            assertThat(response.entity as String).doesNotContain("secret")
        }
    }

    // ---------------------------------------------------------------- SCA for credit coupons

    private val loanUse = """{"targetKind":"LOAN","targetId":"loan-1"}"""

    private fun wallet(vararg coupons: Pair<String, String>) {
        val items = coupons.joinToString(",") { (id, category) ->
            """{"couponId":"$id","category":"$category","benefitType":"X","state":"READY"}"""
        }
        every { upstream.getWithHeaders("$base/coupons", caller.toString(), any()) } returns
            ok("""{"items":[$items]}""")
    }

    @Test
    fun `using a LOANS coupon without a challenge is 403 SCA_REQUIRED with the linking to sign`() {
        wallet("c-loan" to "LOANS")

        val response = resource().useCoupon("c-loan", loanUse, "k", null)

        assertThat(response.status).isEqualTo(403)
        val body = json(response)
        assertThat(body["code"].asText()).isEqualTo("SCA_REQUIRED")
        assertThat(body["scaLinking"]["purpose"].asText()).isEqualTo("APPROVAL")
        assertThat(body["scaLinking"]["approvalRequestId"].asText()).isEqualTo("rewards.coupon.use:c-loan")
        assertThat(body["scaLinking"]["payloadSha256"].asText()).matches("^[0-9a-f]{64}$")
        verify(exactly = 0) { upstream.post(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
    }

    @Test
    fun `a LOANS coupon use spends the challenge against the same linking and forwards it to the engine`() {
        wallet("c-loan" to "LOANS")
        val linking = json(resource().useCoupon("c-loan", loanUse, "k", null))["scaLinking"]
        val challenge = UUID.randomUUID()
        val consumeBody = slot<String>()
        every {
            upstream.post(
                "$sca/api/v1/sca/challenges/$challenge/consume",
                caller.toString(),
                capture(consumeBody),
                any(),
            )
        } returns ok("""{"status":"CONSUMED"}""")
        val extra = slot<Map<String, String>>()
        every { upstream.post("$base/coupons/c-loan/use", caller.toString(), any(), "k", capture(extra)) } returns
            ok("""{"coupon":{"couponId":"c-loan"},"fulfilment":{"fulfilmentId":"f1","state":"PENDING"}}""")

        val response = resource().useCoupon("c-loan", loanUse, "k", challenge.toString())

        assertThat(response.status).isEqualTo(200)
        val consumed = mapper.readTree(consumeBody.captured)
        assertThat(consumed["partyId"].asText()).isEqualTo(caller.toString())
        assertThat(consumed["approvalRequestId"].asText()).isEqualTo(linking["approvalRequestId"].asText())
        assertThat(consumed["payloadSha256"].asText()).isEqualTo(linking["payloadSha256"].asText())
        assertThat(extra.captured).containsEntry("X-SCA-Challenge-Id", challenge.toString())
    }

    @Test
    fun `a rejected challenge is 403 SCA_REJECTED and the coupon is not used`() {
        wallet("c-loan" to "LOANS")
        every { upstream.post(match { it.endsWith("/consume") }, any(), any(), any()) } returns ok("{}", 409)

        val response = resource().useCoupon("c-loan", loanUse, "k", UUID.randomUUID().toString())

        assertThat(response.status).isEqualTo(403)
        assertThat(json(response)["code"].asText()).isEqualTo("SCA_REJECTED")
        verify(exactly = 0) { upstream.post(any(), any(), any(), any(), any()) }
        verify { audit.emit(any(), any(), "rewards.coupons.use", "FAILURE", "c-loan", any()) }
    }

    @Test
    fun `the payload hash binds the target, so a challenge for one loan does not fit another`() {
        wallet("c-loan" to "LOANS")
        val a = json(resource().useCoupon("c-loan", loanUse, "k", null))["scaLinking"]["payloadSha256"].asText()
        val b = json(resource().useCoupon("c-loan", """{"targetKind":"LOAN","targetId":"loan-2"}""", "k", null))
        assertThat(b["scaLinking"]["payloadSha256"].asText()).isNotEqualTo(a)
    }

    @Test
    fun `a non-credit coupon is used without SCA`() {
        wallet("c-bill" to "BILLS")
        every { upstream.post("$base/coupons/c-bill/use", any(), any(), "k", any()) } returns
            ok("""{"coupon":{},"fulfilment":{"fulfilmentId":"f","state":"APPLIED"}}""")

        val response = resource().useCoupon("c-bill", """{"targetKind":"BILL_PAYMENT","targetId":"b1"}""", "k", null)

        assertThat(response.status).isEqualTo(200)
        verify(exactly = 0) { upstream.post(match { it.endsWith("/consume") }, any(), any(), any()) }
    }

    @Test
    fun `a coupon the wallet does not list is a 404 and never used without SCA`() {
        wallet("c-other" to "BILLS")

        val response = resource().useCoupon("c-loan", loanUse, "k", null)

        assertThat(response.status).isEqualTo(404)
        assertThat(json(response)["code"].asText()).isEqualTo("COUPON_NOT_FOUND")
        verify(exactly = 0) { upstream.post(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an unreachable wallet lookup is 503 REWARDS_UNAVAILABLE`() {
        every { upstream.getWithHeaders(any(), any(), any()) } returns ok("""{"error":"upstream unavailable"}""", 502)

        val response = resource().useCoupon("c-loan", loanUse, "k", null)

        assertThat(response.status).isEqualTo(503)
        assertThat(json(response)["code"].asText()).isEqualTo("REWARDS_UNAVAILABLE")
    }
}
