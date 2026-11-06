// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Card designs the manufacturer can print (the app's `CardDesign` cases). */
object CardDesigns {
    val ALL = listOf("energoBlue", "midnight", "hydro", "copper")
    const val DEFAULT = "energoBlue"
}

/**
 * A card PIN the customer chooses: four digits, and not one a thief tries first — all the same digit,
 * a straight run up or down, or a year in the 1900s/2000s. A rule a customer can read, not a list.
 */
object PinPolicy {
    private const val LENGTH = 4
    private val YEAR = Regex("^(19|20)\\d\\d$")

    fun problem(pin: String): String? = when {
        pin.length != LENGTH || !pin.all(Char::isDigit) -> "A PIN has four digits."
        pin.toSet().size == 1 -> "Avoid the same digit four times."
        pin.zipWithNext().all { (a, b) -> b - a == 1 } || pin.zipWithNext().all { (a, b) -> a - b == 1 } ->
            "Avoid a run of digits like 1234."
        YEAR.matches(pin) -> "Avoid a year — it is the first thing a thief tries."
        else -> null
    }

    /** A first PIN for a new card: random, and acceptable under [problem]. */
    fun random(next: (Int) -> Int): String {
        while (true) {
            val pin = (1..LENGTH).joinToString("") { next(DIGITS).toString() }
            if (problem(pin) == null) return pin
        }
    }

    private const val DIGITS = 10
}

/** "I'm travelling to these countries on these dates." */
data class TravelWindow(val id: UUID, val countries: List<String>, val start: LocalDate, val end: LocalDate) {
    fun covers(country: String, day: LocalDate): Boolean =
        countries.any { it.equals(country, ignoreCase = true) } && !day.isBefore(start) && !day.isAfter(end)
}

data class CardNotificationSettings(
    val everyPayment: Boolean,
    val declinedPayments: Boolean,
    val paymentsAbroad: Boolean,
    val onlinePayments: Boolean,
    val weeklySummary: Boolean,
    val largePaymentThreshold: BigDecimal?,
    val currency: String,
) {
    companion object {
        /** Until the customer chooses: the notifications a bank sends by default. */
        fun defaults(currency: String) = CardNotificationSettings(true, true, true, false, true, null, currency)
    }
}

data class DeliveryAddress(
    val line1: String,
    val line2: String?,
    val city: String,
    val postalCode: String,
    val countryCode: String,
)

data class PhysicalCardOrder(
    val id: UUID,
    val partyId: UUID,
    val accountId: UUID,
    val cardId: UUID,
    val design: String,
    val nameOnCard: String,
    val address: DeliveryAddress,
    val factoryRef: String?,
    val activatedAt: Instant?,
    val createdAt: Instant,
)

/** The manufacturer's view of an order (from its own system). */
data class FactoryStatus(
    val status: String,
    val carrier: String?,
    val trackingNumber: String?,
    val expectedOn: String?,
    val events: List<FactoryStatusEvent>,
)

data class FactoryStatusEvent(val status: String, val at: String, val detail: String?)
