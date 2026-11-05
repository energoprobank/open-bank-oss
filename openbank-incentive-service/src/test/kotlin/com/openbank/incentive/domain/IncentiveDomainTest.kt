// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class IncentiveDomainTest {
    private val now = Instant.parse("2026-08-26T12:00:00Z")
    private val ref = OfferRef(UUID.randomUUID(), "summer-current-account", 1)

    @Test
    fun `publication requires an independent checker and freezes a versioned reference`() {
        val draft = offer()
        assertThatThrownBy { draft.submit("checker") }.isInstanceOf(IncentiveConflict::class.java)
        val submitted = draft.submit("maker")
        assertThatThrownBy { submitted.publish("maker") }.isInstanceOf(IncentiveConflict::class.java)

        val published = submitted.publish("checker")
        assertThat(published.status).isEqualTo(OfferStatus.PUBLISHED)
        assertThat(published.ref).isEqualTo(ref)
        assertThat(published.accepts("current-account", now)).isTrue()
        assertThat(published.accepts("loan", now)).isFalse()
    }

    @Test
    fun `commit and release are idempotent but mutually exclusive`() {
        val reservation = reservation()
        val committed = reservation.commit(now.plusSeconds(1))
        assertThat(committed.commit(now.plusSeconds(2))).isSameAs(committed)
        assertThatThrownBy { committed.release() }.isInstanceOf(IncentiveConflict::class.java)

        val released = reservation.release()
        assertThat(released.release()).isSameAs(released)
        assertThatThrownBy { released.commit(now.plusSeconds(2)) }.isInstanceOf(IncentiveConflict::class.java)
    }

    @Test
    fun `qualifying action cannot predate reservation`() {
        val reservation = reservation()
        assertThatThrownBy { reservation.commit(reservation.reservedAt.minusSeconds(1)) }
            .isInstanceOf(IncentiveConflict::class.java)
    }

    @Test
    fun `expiry closes inventory and cannot be converted into success`() {
        val reservation = reservation()
        assertThatThrownBy { reservation.expire(now) }.isInstanceOf(IncentiveConflict::class.java)
        val expired = reservation.expire(now.plus(16, ChronoUnit.MINUTES))
        assertThat(expired.status).isEqualTo(ReservationStatus.EXPIRED)
        assertThatThrownBy { expired.commit(now.plus(17, ChronoUnit.MINUTES)) }
            .isInstanceOf(IncentiveConflict::class.java)
    }

    @Test
    fun `code representation accepts only a one-way digest`() {
        assertThat(CodeDigest("a".repeat(64)).value).hasSize(64)
        assertThatThrownBy { CodeDigest("SAVE20") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `one-step redemption needs a live offer that is hub-claimable, unscoped or scoped to the context`() {
        val productBound = offer().submit("maker").publish("checker")
        assertThat(productBound.claimableIn(RedemptionContext.HUB, now)).isFalse()

        val hub = offer(claimableInHub = true).submit("maker").publish("checker")
        RedemptionContext.entries.forEach { assertThat(hub.claimableIn(it, now)).describedAs(it.name).isTrue() }
        assertThat(hub.claimableIn(RedemptionContext.HUB, now.plus(31, ChronoUnit.DAYS))).isFalse()
        assertThat(offer(claimableInHub = true).claimableIn(RedemptionContext.HUB, now)).isFalse()

        val unscoped = offer(scope = emptySet(), claimableInHub = true).submit("maker").publish("checker")
        assertThat(unscoped.productScope).isEmpty()
        assertThat(unscoped.claimableIn(RedemptionContext.ONBOARDING, now)).isTrue()

        val termDeposit = offer(scope = setOf("TERM_DEPOSIT")).submit("maker").publish("checker")
        assertThat(termDeposit.claimableIn(RedemptionContext.TERM_DEPOSIT, now)).isTrue()
        assertThat(termDeposit.claimableIn(RedemptionContext.HUB, now)).isFalse()
    }

    @Test
    fun `an empty product scope is refused unless the offer is hub-claimable`() {
        assertThatThrownBy { offer(scope = emptySet()) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an exclusive offer on either side refuses stacking`() {
        val stackable = offer(policy = StackingPolicy.STACKABLE)
        val exclusive = offer(policy = StackingPolicy.EXCLUSIVE)
        assertThat(exclusive.stacksWith(emptyList())).isTrue()
        assertThat(stackable.stacksWith(listOf(stackable))).isTrue()
        assertThat(stackable.stacksWith(listOf(exclusive))).isFalse()
        assertThat(exclusive.stacksWith(listOf(stackable))).isFalse()
    }

    private fun offer(
        scope: Set<String> = setOf("current-account"),
        claimableInHub: Boolean = false,
        policy: StackingPolicy = StackingPolicy.EXCLUSIVE,
    ) = IncentiveOffer(
        ref = ref,
        productScope = scope,
        effectiveFrom = now.minusSeconds(60),
        expiresAt = now.plus(30, ChronoUnit.DAYS),
        totalLimit = 1_000,
        perPartyLimit = 1,
        stackingPolicy = policy,
        status = OfferStatus.DRAFT,
        maker = "maker",
        claimableInHub = claimableInHub,
    )

    private fun reservation() = PromoReservation(
        id = UUID.randomUUID(),
        offerRef = ref,
        codeDigest = CodeDigest("b".repeat(64)),
        partyRef = "party-1",
        productRef = "current-account",
        idempotencyKey = "checkout-1",
        reservedAt = now,
        expiresAt = now.plus(15, ChronoUnit.MINUTES),
    )
}
