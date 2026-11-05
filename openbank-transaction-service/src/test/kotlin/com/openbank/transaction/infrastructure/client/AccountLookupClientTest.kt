// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.client

import com.openbank.transaction.domain.reward.RewardTargetAccount
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** Unit coverage for [AccountLookupClient]: mapping account-service's answer for the reward checks. */
class AccountLookupClientTest {

    private val restClient: AccountLookupRestClient = mockk()
    private val client = AccountLookupClient(restClient)
    private val accountId = UUID.randomUUID()
    private val party = UUID.randomUUID()

    @Test
    fun `a found account maps owner, type, currency and status`(): Unit = runBlocking {
        every { restClient.getAccount(accountId) } returns Uni.createFrom().item(
            AccountLookupResponse(accountId, "CURRENT", party, "CZK", "ACTIVE"),
        )

        assertThat(client.findAccount(accountId)).isEqualTo(RewardTargetAccount(party, "CURRENT", "CZK", "ACTIVE"))
    }

    @Test
    fun `a 404 is an unknown account, not an error`(): Unit = runBlocking {
        every { restClient.getAccount(accountId) } returns Uni.createFrom().failure(WebApplicationException(404))

        assertThat(client.findAccount(accountId)).isNull()
    }

    @Test
    fun `any other upstream failure propagates - nothing is paid on a guess`() {
        every { restClient.getAccount(accountId) } returns Uni.createFrom().failure(WebApplicationException(403))

        assertThatThrownBy { runBlocking { client.findAccount(accountId) } }
            .isInstanceOf(WebApplicationException::class.java)
    }

    @Test
    fun `a 200 without a field the checks need is a contract break, never a payable account`() {
        every { restClient.getAccount(accountId) } returns Uni.createFrom().item(
            AccountLookupResponse(accountId, "CURRENT", null, "CZK", "ACTIVE"),
        )

        assertThatThrownBy { runBlocking { client.findAccount(accountId) } }
            .isInstanceOf(AccountLookupContractException::class.java)
            .hasMessageContaining("partyId")
    }
}
