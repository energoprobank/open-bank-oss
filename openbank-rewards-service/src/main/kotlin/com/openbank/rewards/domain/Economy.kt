// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * FLUX: the programme's currency, an untranslated brand name in every language. A closed-loop
 * engagement unit — never bought, never paid out, never transferred between parties. It has a
 * notional value per market only so Finance can book the liability (spec: Budget and liability).
 */
enum class Market(val currency: String, val zone: ZoneId) {
    CZ("CZK", ZoneId.of("Europe/Prague")),
    BG("EUR", ZoneId.of("Europe/Sofia")),
    ;

    companion object {
        /** A party's market follows the currency of its first current account; CZ when unknown (demo). */
        fun forCurrency(currency: String?): Market = if (currency.equals("EUR", ignoreCase = true)) BG else CZ
    }
}

data class MarketParams(
    val market: Market,
    val currency: String,
    val fluxValue: BigDecimal,
    val fundingMinimum: BigDecimal,
    val cardMinimum: BigDecimal,
    val envelopePerYear: BigDecimal,
    val locale: String,
) {
    fun valueOf(flux: Long): BigDecimal = fluxValue.multiply(BigDecimal.valueOf(flux)).setScale(2, RoundingMode.HALF_UP)
}

/** Six levels on lifetime OWN charged FLUX (promotion bonus never counts). Never decreases. */
@Suppress("MagicNumber") // the level ladder is spec data
enum class Level(val index: Int, val threshold: Int) {
    SPARK(1, 0),
    CELL(2, 100),
    BATTERY(3, 250),
    TURBINE(4, 500),
    REACTOR(5, 900),
    POWER_PLANT(6, 1500),
    ;

    val next: Level? get() = entries.firstOrNull { it.index == index + 1 }

    companion object {
        fun forLifetime(lifetimeCharged: Int): Level = entries.last { lifetimeCharged >= it.threshold }
    }
}

enum class MembershipStatus { ACTIVE, OPTED_OUT }

data class Membership(
    val partyId: UUID,
    val market: Market,
    val status: MembershipStatus,
    val quietMode: Boolean,
    val notifyMissions: Boolean,
    val notifyCoupons: Boolean,
    val notifyExpiry: Boolean,
    val termsVersion: String,
    val joinedAt: Instant,
    val lifetimeCharged: Int,
    val lastActivityAt: Instant,
    val frozenReason: String?,
) {
    val level: Level get() = Level.forLifetime(lifetimeCharged)
    val active: Boolean get() = status == MembershipStatus.ACTIVE && frozenReason == null

    companion object {
        const val TERMS_VERSION = "2026-11"

        fun join(partyId: UUID, market: Market, at: Instant) = Membership(
            partyId = partyId, market = market, status = MembershipStatus.ACTIVE, quietMode = false,
            notifyMissions = true, notifyCoupons = true, notifyExpiry = true, termsVersion = TERMS_VERSION,
            joinedAt = at, lifetimeCharged = 0, lastActivityAt = at, frozenReason = null,
        )
    }
}

/** Kill-switch scopes (spec: stop earning, charging or using, programme-wide). */
enum class ProgrammeSwitch { EARN, CHARGE, USE }

enum class BudgetKind { ENGAGEMENT, ACQUISITION, OFFER, MIGRATION, CREDIT }

data class BudgetLine(
    val budgetLineId: String,
    val kind: BudgetKind,
    val market: Market,
    val amount: BigDecimal,
    val consumed: BigDecimal,
    val description: String,
) {
    val remaining: BigDecimal get() = amount.subtract(consumed)

    fun canConsume(value: BigDecimal): Boolean = remaining >= value
}

/** A business rule refused the command; the REST layer maps [code] to 409 with this code. */
class RewardsRuleException(val code: String, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class RewardsNotFoundException(val code: String, message: String) : RuntimeException(message)
