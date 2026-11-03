// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.AccountSanctionsScreeningPort
import com.openbank.account.application.port.out.SanctionsScreenResult
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.AccountType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class OnboardingStatusServiceTest {
    private val accounts = mockk<AccountRepository>()
    private val screening = mockk<AccountSanctionsScreeningPort>()
    private val service = OnboardingStatusService(accounts, screening)
    private val party = UUID.randomUUID()
    private val onboardingKey = "account-open:$party:onboarding-account-$party"

    @Test
    fun `an existing current account is OPEN without screening`(): Unit = runBlocking {
        val current = mockk<Account> { every { accountType } returns AccountType.CURRENT }
        coEvery { accounts.findByPartyId(party, any(), any()) } returns listOf(current)

        assertThat(service.state(party, "Jana Nováková")).isEqualTo(OnboardingAccountState.OPEN)
        coVerify(exactly = 0) { screening.screen(any(), any(), any()) }
    }

    @Test
    fun `the onboarding screening decides between IN_REVIEW and PENDING`(): Unit = runBlocking {
        coEvery { accounts.findByPartyId(party, any(), any()) } returns emptyList()
        coEvery { screening.screen("Michal Král", onboardingKey, party) } returns SanctionsScreenResult("HIT", 1.0, null)
        assertThat(service.state(party, "Michal Král")).isEqualTo(OnboardingAccountState.IN_REVIEW)

        coEvery { screening.screen("Michal Král", onboardingKey, party) } returns SanctionsScreenResult("CLEAR", 1.0, null)
        assertThat(service.state(party, "Michal Král")).isEqualTo(OnboardingAccountState.PENDING)
    }
}
