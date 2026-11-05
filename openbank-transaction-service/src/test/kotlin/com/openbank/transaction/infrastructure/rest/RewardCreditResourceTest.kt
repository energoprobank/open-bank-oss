// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.transaction.application.port.`in`.RewardCreditCommand
import com.openbank.transaction.application.port.`in`.RewardCreditResult
import com.openbank.transaction.application.port.`in`.RewardCreditUseCase
import com.openbank.transaction.application.usecase.RewardCreditConflictException
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.reward.RewardCreditRefusal
import com.openbank.transaction.domain.reward.RewardCreditRefusedException
import com.openbank.transaction.domain.reward.RewardReason
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class RewardCreditResourceTest {

    private class FakeUseCase(private val replayed: Boolean) : RewardCreditUseCase {
        var seen: RewardCreditCommand? = null

        override suspend fun credit(command: RewardCreditCommand): RewardCreditResult {
            seen = command
            return RewardCreditResult(CREDIT, TX, TransactionStatus.COMPLETED, replayed)
        }
    }

    private val party = UUID.randomUUID()
    private val account = UUID.randomUUID()

    private fun request(
        partyId: UUID? = party,
        accountId: UUID? = account,
        amount: BigDecimal? = BigDecimal("150.00"),
        currency: String? = "CZK",
        reason: String? = "INSTALMENT_RELIEF",
        sourceRef: String? = "relief-7",
    ) = RewardCreditRequest(partyId, accountId, amount, currency, reason, sourceRef)

    @Test
    fun `a new credit answers 201 with creditId, transactionId and status`(): Unit = runBlocking {
        val useCase = FakeUseCase(replayed = false)

        val response = RewardCreditResource(useCase).credit(request())

        assertThat(response.status).isEqualTo(201)
        assertThat(response.location.toString()).isEqualTo("/api/v1/transactions/$TX")
        assertThat(response.getHeaderString(RewardCreditResource.IDEMPOTENT_REPLAYED_HEADER)).isEqualTo("false")
        assertThat(response.entity).isEqualTo(RewardCreditResponse(CREDIT, TX, "COMPLETED"))
        assertThat(useCase.seen).isEqualTo(
            RewardCreditCommand(
                partyId = party,
                accountId = account,
                amount = BigDecimal("150.00"),
                currency = "CZK",
                reason = RewardReason.INSTALMENT_RELIEF,
                sourceRef = "relief-7",
            ),
        )
    }

    @Test
    fun `a replay answers 200 and says so`(): Unit = runBlocking {
        val response = RewardCreditResource(FakeUseCase(replayed = true)).credit(request())

        assertThat(response.status).isEqualTo(200)
        assertThat(response.getHeaderString(RewardCreditResource.IDEMPOTENT_REPLAYED_HEADER)).isEqualTo("true")
    }

    @Test
    fun `missing fields and an unknown reason are 400s (IllegalArgumentException)`() {
        val resource = RewardCreditResource(FakeUseCase(replayed = false))
        listOf(
            null,
            request(partyId = null),
            request(accountId = null),
            request(amount = null),
            request(currency = null),
            request(reason = null),
            request(reason = "WELCOME_BONUS"),
            request(sourceRef = null),
        ).forEach { body ->
            assertThatThrownBy { runBlocking { resource.credit(body) } }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `only ROLE_API reaches it, under its own action that is not a four-eyes verb`() {
        val method = RewardCreditResource::class.java.declaredMethods.single { it.name == "credit" }
        assertThat(method.getAnnotation(PermitAll::class.java)).isNull()
        assertThat(method.getAnnotation(RolesAllowed::class.java).value).containsExactly("ROLE_API")
        val action = method.getAnnotation(Authorize::class.java).action
        assertThat(action).isEqualTo("transaction.rewardCredit")
        // rules.yaml four_eyes.verbs has `credit`: the action must not END in that verb.
        assertThat(action.substringAfterLast('.')).isNotIn("credit", "create", "post")
    }

    @Test
    fun `refusals map to 422 with a machine-readable code, a reused sourceRef to 409`() {
        val refused = RewardCreditRefusedExceptionMapper().toResponse(
            RewardCreditRefusedException(RewardCreditRefusal.ACCOUNT_NOT_CURRENT, "CURRENT only"),
        )
        assertThat(refused.status).isEqualTo(422)
        assertThat(refused.entity).isEqualTo(mapOf("code" to "ACCOUNT_NOT_CURRENT", "message" to "CURRENT only"))

        val conflict = RewardCreditConflictExceptionMapper().toResponse(RewardCreditConflictException("reused"))
        assertThat(conflict.status).isEqualTo(409)
        assertThat((conflict.entity as Map<*, *>)["code"]).isEqualTo("SOURCE_REF_REUSED")
    }

    private companion object {
        val CREDIT: UUID = UUID.fromString("c0000000-0000-0000-0000-000000000001")
        val TX: UUID = UUID.fromString("70000000-0000-0000-0000-000000000001")
    }
}
