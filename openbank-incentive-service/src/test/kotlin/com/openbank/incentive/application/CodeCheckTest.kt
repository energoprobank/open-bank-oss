// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.incentive.application

import com.openbank.incentive.domain.CodeDigest
import com.openbank.incentive.domain.IncentiveOffer
import com.openbank.incentive.domain.OfferRef
import com.openbank.incentive.domain.OfferStatus
import com.openbank.incentive.domain.StackingPolicy
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID

/** Experience v2 step 8: the read-only promo-code check at account opening. */
class CodeCheckTest {
    private val now = Instant.parse("2026-11-05T10:00:00Z")

    /** What the inventory answers, and the digests it was asked for (CodeDigest is a value class: no mockk matchers). */
    private var answer: Pair<IncentiveOffer, String>? = null
    private val asked = mutableListOf<CodeDigest>()
    private val store = object : IncentiveStore by mockk<IncentiveStore>() {
        override suspend fun findCode(digest: CodeDigest): Pair<IncentiveOffer, String>? {
            asked += digest
            return answer
        }
    }
    private val app =
        IncentiveApplication(store, mockk(relaxed = true), Optional.of("p".repeat(32)), Duration.ofMinutes(30))

    private fun offer(status: OfferStatus = OfferStatus.PUBLISHED, scope: Set<String> = setOf("ONBOARDING")) =
        IncentiveOffer(
            ref = OfferRef(UUID.randomUUID(), "Welcome 500", 1),
            productScope = scope,
            effectiveFrom = now.minusSeconds(3600),
            expiresAt = now.plusSeconds(3600),
            totalLimit = 100,
            perPartyLimit = 1,
            stackingPolicy = StackingPolicy.STACKABLE,
            status = status,
            maker = "m",
        )

    @Test
    fun `an available code on a published onboarding offer is valid, case and spaces aside`(): Unit = runBlocking {
        answer = offer() to "AVAILABLE"
        assertThat(app.checkCode(" energo500 ", now)?.ref?.name).isEqualTo("Welcome 500")
        app.checkCode("ENERGO500", now)
        assertThat(asked[1]).isEqualTo(asked[0])
    }

    @Test
    fun `used codes, draft offers, other products and unknown codes are not valid`(): Unit = runBlocking {
        answer = offer() to "REDEEMED"
        assertThat(app.checkCode("ENERGO500", now)).isNull()
        answer = offer(OfferStatus.DRAFT) to "AVAILABLE"
        assertThat(app.checkCode("ENERGO500", now)).isNull()
        answer = offer(scope = setOf("TERM_DEPOSIT")) to "AVAILABLE"
        assertThat(app.checkCode("ENERGO500", now)).isNull()
        answer = null
        assertThat(app.checkCode("NOSUCHCODE", now)).isNull()
        // Too short to exist in the inventory at all: no lookup, just "not valid".
        assertThat(app.checkCode("ABC", now)).isNull()
    }
}
