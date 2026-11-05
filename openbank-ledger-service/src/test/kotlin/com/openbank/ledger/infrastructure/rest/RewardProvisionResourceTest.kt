// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.port.`in`.PostRewardProvisionCommand
import com.openbank.ledger.application.port.`in`.RewardProvisionResult
import com.openbank.ledger.application.port.`in`.RewardProvisionStatus
import com.openbank.ledger.application.port.`in`.RewardProvisionUseCase
import com.openbank.ledger.domain.model.RewardMarket
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class RewardProvisionResourceTest {

    private class FakeUseCase(private val status: RewardProvisionStatus) : RewardProvisionUseCase {
        var seen: PostRewardProvisionCommand? = null

        override suspend fun post(command: PostRewardProvisionCommand): RewardProvisionResult {
            seen = command
            return RewardProvisionResult(
                status = status,
                journalId = JOURNAL,
                entryNumber = 7L,
                postingDate = command.postingDate,
                market = command.market,
                currency = command.currency,
                deltaAmount = command.deltaAmount,
                sourceRef = command.sourceRef,
            )
        }
    }

    private fun request(
        postingDate: String? = "2026-11-04",
        market: String? = "BG",
        currency: String? = "EUR",
        deltaAmount: BigDecimal? = BigDecimal("-12.30"),
        sourceRef: String? = "bg-2026-11-04",
    ) = RewardProvisionRequest(postingDate, market, currency, deltaAmount, sourceRef)

    @Test
    fun `a new posting answers 201 with the journal and the parsed command`(): Unit = runBlocking {
        val useCase = FakeUseCase(RewardProvisionStatus.POSTED)

        val response = RewardProvisionResource(useCase).post(request())

        assertThat(response.status).isEqualTo(201)
        assertThat(response.location.toString()).isEqualTo("/api/v1/journals/$JOURNAL")
        assertThat(response.getHeaderString(IDEMPOTENT_REPLAYED_HEADER)).isEqualTo("false")
        assertThat(useCase.seen).isEqualTo(
            PostRewardProvisionCommand(
                LocalDate.of(2026, 11, 4),
                RewardMarket.BG,
                "EUR",
                BigDecimal("-12.30"),
                "bg-2026-11-04",
            ),
        )
        val body = response.entity as RewardProvisionResponse
        assertThat(body.status).isEqualTo("POSTED")
        assertThat(body.journalId).isEqualTo(JOURNAL)
    }

    @Test
    fun `a replay answers 200 and says so in the header`(): Unit = runBlocking {
        val response = RewardProvisionResource(FakeUseCase(RewardProvisionStatus.REPLAYED)).post(request())

        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeaderString(IDEMPOTENT_REPLAYED_HEADER)).isEqualTo("true")
        assertThat((response.entity as RewardProvisionResponse).status).isEqualTo("REPLAYED")
    }

    @Test
    fun `missing or malformed fields are 400s (IllegalArgumentException), never 500s`() {
        val resource = RewardProvisionResource(FakeUseCase(RewardProvisionStatus.POSTED))
        listOf(
            null,
            request(postingDate = null),
            request(postingDate = "04.11.2026"),
            request(market = null),
            request(market = "SK"),
            request(currency = null),
            request(deltaAmount = null),
            request(sourceRef = null),
        ).forEach { body ->
            assertThatThrownBy { runBlocking { resource.post(body) } }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `only ROLE_API reaches it, under its own non-four-eyes action`() {
        val method = RewardProvisionResource::class.java.declaredMethods.single { it.name == "post" }
        assertThat(method.getAnnotation(RolesAllowed::class.java).value).containsExactly("ROLE_API")
        val action = method.getAnnotation(Authorize::class.java).action
        assertThat(action).isEqualTo("ledger.rewardProvision")
        // rules.yaml four_eyes.verbs includes `post` and `credit`: an automated daily call must not
        // end in one, or enforcement would pause it for a second approver.
        assertThat(action.substringAfterLast('.')).isNotIn("post", "credit", "create")
    }

    private companion object {
        val JOURNAL: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
    }
}
