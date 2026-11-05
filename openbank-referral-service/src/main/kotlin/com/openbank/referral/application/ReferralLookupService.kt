// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.referral.application

import com.openbank.referral.application.port.out.ReferralInviteRepository
import com.openbank.referral.application.port.out.ReferralRefereeLookup
import com.openbank.referral.domain.InviteStatus
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.util.UUID

/**
 * Read-only lookups for account opening (experience v2 step 8): who sent the invite a new applicant
 * opened, and later, who referred a customer. Both answer with the referrer's party id only; the edge
 * turns it into a first name. Nothing is changed and nothing is audited — the attribution itself
 * stays the one write.
 */
@ApplicationScoped
class ReferralLookupService(
    private val invites: ReferralInviteRepository,
    private val referees: ReferralRefereeLookup,
    private val clock: Clock,
) {
    /** The referrer of an invite that can still be used (ISSUED, not expired), else null. */
    suspend fun previewReferrer(token: String): UUID? {
        if (!TOKEN.matches(token)) return null
        val invite = invites.findByToken(ReferralService.hash(token)) ?: return null
        return invite.referrerPartyId.takeIf {
            invite.status == InviteStatus.ISSUED &&
                invite.expiresAt.isAfter(clock.instant())
        }
    }

    /** Who referred [refereePartyId] (their attributed invite), else null. */
    suspend fun referrerOf(refereePartyId: UUID): UUID? =
        referees.attributedInvites(refereePartyId).maxByOrNull { it.attributedAt ?: it.expiresAt }?.referrerPartyId

    private companion object {
        val TOKEN = Regex("^[A-Za-z0-9_-]{16,128}$")
    }
}
