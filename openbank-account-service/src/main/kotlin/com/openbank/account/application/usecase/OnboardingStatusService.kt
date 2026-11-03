// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.AccountSanctionsScreeningPort
import com.openbank.account.domain.model.AccountType
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** Where a new customer's onboarding account opening stands, as the customer may see it. */
enum class OnboardingAccountState {
    /** The current account exists. */
    OPEN,

    /** A sanctions/PEP screening hit holds the opening until a bank employee reviews it. */
    IN_REVIEW,

    /** Not opened yet, nothing holding it (the PARTY_CREATED projection or a resume is on its way). */
    PENDING,
}

/**
 * Answers "is my account coming?" for the customer app without saying why not: the state, never
 * the matched name or list (which would be a sanctions-list oracle, #8512).
 *
 * The hold is read from the onboarding account-opening screening itself — the same idempotency key
 * AccountService uses — so asking repeats nothing: sanctions-service answers the stored check (with
 * its review applied) and creates no new record. Only before that opening ran does the call screen
 * for the first time, and the opening then reuses that very check.
 */
@ApplicationScoped
class OnboardingStatusService(
    private val accountRepository: AccountRepository,
    private val sanctionsScreening: AccountSanctionsScreeningPort,
) {
    suspend fun state(partyId: UUID, legalName: String): OnboardingAccountState {
        val accounts = accountRepository.findByPartyId(partyId, 50, null)
        if (accounts.any { it.accountType == AccountType.CURRENT }) return OnboardingAccountState.OPEN
        val screening = sanctionsScreening.screen(
            name = legalName,
            idempotencyKey = "account-open:$partyId:onboarding-account-$partyId",
            partyId = partyId,
        )
        return if (screening.permitsOpening) OnboardingAccountState.PENDING else OnboardingAccountState.IN_REVIEW
    }
}
