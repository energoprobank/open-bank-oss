// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.port.out

import com.openbank.transaction.domain.reward.RewardTargetAccount
import java.util.UUID

/**
 * Reads the owner, type, currency and status of one account from account-service, the owner of that
 * data (ADR-0344 D3: a reward is paid only to the party's own ACTIVE CURRENT account). `null` means
 * account-service does not know the account; any other failure propagates and nothing is paid.
 */
interface AccountLookupPort {
    suspend fun findAccount(accountId: UUID): RewardTargetAccount?
}
