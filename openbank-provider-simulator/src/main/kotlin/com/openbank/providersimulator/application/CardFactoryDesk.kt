// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.application

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One stage of a plastic card's way to the customer. */
enum class FactoryStage { ORDERED, IN_PRODUCTION, SHIPPED, DELIVERED }

data class FactoryEvent(val stage: FactoryStage, val at: Instant, val detail: String)

data class FactoryOrder(
    val id: String,
    val reference: String,
    val design: String,
    val nameOnCard: String,
    val city: String,
    val country: String,
    val trackingNumber: String,
    val orderedAt: Instant,
    val stage: FactoryStage,
    val events: List<FactoryEvent>,
    val expectedOn: LocalDate,
)

/**
 * The card manufacturer and its courier (NON-PRODUCTION): a bank orders a plastic card; the card is
 * produced, shipped and delivered, one stage every `stage-seconds` (a real factory takes days; a demo
 * minutes). The order's state is a pure function of when it was placed, so a restart loses only
 * orders, never their timing. Česká pošta in CZ, Econt in BG, as carriers there would be.
 */
@ApplicationScoped
class CardFactoryDesk @Inject constructor(
    private val clock: Clock,
    @ConfigProperty(name = "openbank.provider-simulator.card-factory.stage-seconds", defaultValue = "120")
    private val stageSeconds: Long,
) {
    private val orders = ConcurrentHashMap<String, Placed>()

    private data class Placed(
        val id: String,
        val reference: String,
        val design: String,
        val nameOnCard: String,
        val city: String,
        val country: String,
        val trackingNumber: String,
        val orderedAt: Instant,
    )

    fun place(reference: String, design: String, nameOnCard: String, city: String, country: String): FactoryOrder {
        require(reference.isNotBlank() && reference.length <= MAX_TEXT) { "reference is required" }
        require(nameOnCard.isNotBlank() && nameOnCard.length <= MAX_NAME) {
            "name_on_card must be 1..$MAX_NAME characters"
        }
        require(country in setOf("CZ", "BG")) { "country must be CZ or BG" }
        orders.values.firstOrNull { it.reference == reference }?.let { return view(it) }
        val prefix = if (country == "BG") "EC" else "RR"
        val tracking = prefix + UUID.randomUUID().toString().filter(Char::isDigit).padEnd(TRACKING_DIGITS, '7')
            .take(TRACKING_DIGITS) + country
        val p =
            Placed(
                UUID.randomUUID().toString(),
                reference,
                design,
                nameOnCard.trim(),
                city,
                country,
                tracking,
                clock.instant(),
            )
        orders[p.id] = p
        return view(p)
    }

    fun order(id: String): FactoryOrder? = orders[id]?.let(::view)

    private fun view(p: Placed): FactoryOrder {
        val step = Duration.ofSeconds(stageSeconds.coerceAtLeast(0))
        val reached = FactoryStage.entries.filter { s ->
            !clock.instant().isBefore(p.orderedAt.plus(step.multipliedBy(s.ordinal.toLong())))
        }
        val carrier = if (p.country == "BG") "Econt" else "Česká pošta"
        val events = reached.map { s ->
            FactoryEvent(
                s,
                p.orderedAt.plus(step.multipliedBy(s.ordinal.toLong())),
                when (s) {
                    FactoryStage.ORDERED -> "Order received"
                    FactoryStage.IN_PRODUCTION -> "Card embossed and personalised"
                    FactoryStage.SHIPPED -> "Handed to $carrier, ${p.trackingNumber}"
                    FactoryStage.DELIVERED -> "Delivered in ${p.city}"
                },
            )
        }
        return FactoryOrder(
            p.id, p.reference, p.design, p.nameOnCard, p.city, p.country, p.trackingNumber, p.orderedAt,
            reached.last(), events, LocalDate.ofInstant(p.orderedAt, ZoneOffset.UTC).plusDays(DELIVERY_DAYS),
        )
    }

    fun carrierOf(o: FactoryOrder) = if (o.country == "BG") "Econt" else "Česká pošta"

    private companion object {
        const val MAX_TEXT = 64
        const val MAX_NAME = 26
        const val TRACKING_DIGITS = 9
        const val DELIVERY_DAYS = 5L
    }
}
