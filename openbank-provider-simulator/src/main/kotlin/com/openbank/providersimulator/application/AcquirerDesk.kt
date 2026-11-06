// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.application

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A card-payment checkout an acquirer runs for a merchant (here: the bank's card top-up). */
data class Checkout(
    val id: String,
    val amount: BigDecimal,
    val currency: String,
    val merchantReference: String,
    val status: String,
    val createdAt: Instant,
    val cardLast4: String? = null,
)

/**
 * A card acquirer (NON-PRODUCTION): the bank opens a checkout, the customer enters a card on the
 * acquirer's own page (never in the bank's app), and the bank asks for the outcome. Test cards follow
 * the usual convention: any 16 digits authorise, except 4000 0000 0000 0002, which is declined. A
 * checkout nobody pays expires after 30 minutes.
 */
@ApplicationScoped
class AcquirerDesk @Inject constructor(
    private val clock: Clock,
    @ConfigProperty(name = "openbank.provider-simulator.public-url", defaultValue = "http://localhost:8163")
    private val publicUrl: String,
) {
    private val checkouts = ConcurrentHashMap<String, Checkout>()

    fun start(amount: BigDecimal, currency: String, merchantReference: String): Checkout {
        require(amount.signum() > 0) { "amount must be above zero" }
        require(currency in setOf("CZK", "EUR")) { "currency must be CZK or EUR" }
        require(merchantReference.isNotBlank() && merchantReference.length <= MAX_REFERENCE) {
            "merchant_reference is required"
        }
        if (checkouts.size >
            MAX_CHECKOUTS
        ) {
            checkouts.values.removeIf { it.createdAt.isBefore(clock.instant().minus(LIFE)) }
        }
        val c = Checkout(UUID.randomUUID().toString(), amount, currency, merchantReference, "CREATED", clock.instant())
        checkouts[c.id] = c
        return c
    }

    fun checkout(id: String): Checkout? = checkouts[id]?.let {
        if (it.status == "CREATED" &&
            it.createdAt.isBefore(clock.instant().minus(LIFE))
        ) {
            it.copy(status = "EXPIRED")
        } else {
            it
        }
    }

    /** The customer submitted a card on the checkout page. */
    fun pay(id: String, cardNumber: String): Checkout? {
        val c = checkout(id)?.takeIf { it.status == "CREATED" } ?: return checkout(id)
        val digits = cardNumber.filter(Char::isDigit)
        val outcome = when {
            digits.length != CARD_DIGITS -> return c
            digits == DECLINED_CARD -> "DECLINED"
            else -> "AUTHORISED"
        }
        return c.copy(status = outcome, cardLast4 = digits.takeLast(LAST4)).also { checkouts[id] = it }
    }

    fun pageUrl(c: Checkout) = "${publicUrl.trimEnd('/')}/acquirer/pay/${c.id}"

    private companion object {
        val LIFE: Duration = Duration.ofMinutes(30)
        const val MAX_REFERENCE = 64
        const val MAX_CHECKOUTS = 10_000
        const val CARD_DIGITS = 16
        const val LAST4 = 4
        const val DECLINED_CARD = "4000000000000002"
    }
}
