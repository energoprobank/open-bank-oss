// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.persistence

import com.openbank.cardissuance.domain.model.CardNotificationSettings
import com.openbank.cardissuance.domain.model.DeliveryAddress
import com.openbank.cardissuance.domain.model.PhysicalCardOrder
import com.openbank.cardissuance.domain.model.TravelWindow
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A stored PIN: encrypted, with the wrong-entry counter. */
data class StoredPin(val cipher: String, val setAt: Instant, val attemptsLeft: Int)

/**
 * The experience v2 step-4 tables (V10) over the reactive Postgres pool, in plain SQL: small rows,
 * one statement each, called from `suspend` resources that already run on a Vert.x context.
 */
@ApplicationScoped
@Suppress("TooManyFunctions") // one table per concern, each method one SQL statement
class CardSelfServiceStore(private val pool: Pool) {

    private suspend fun rows(sql: String, vararg args: Any?): List<Row> =
        pool.preparedQuery(sql).execute(Tuple.from(args.toList())).awaitSuspending().toList()

    private suspend fun exec(sql: String, vararg args: Any?): Int =
        pool.preparedQuery(sql).execute(Tuple.from(args.toList())).awaitSuspending().rowCount()

    private fun Instant.odt(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    // ---------------------------------------------------------------- PIN

    suspend fun pin(cardId: UUID): StoredPin? =
        rows("SELECT * FROM card_pins WHERE card_id = $1", cardId).firstOrNull()?.let {
            StoredPin(
                it.getString("pin_cipher"),
                it.getOffsetDateTime("set_at").toInstant(),
                it.getInteger("attempts_left"),
            )
        }

    suspend fun savePin(cardId: UUID, cipher: String, at: Instant) {
        exec(
            """INSERT INTO card_pins (card_id, pin_cipher, set_at, attempts_left) VALUES ($1, $2, $3, 3)
               ON CONFLICT (card_id) DO UPDATE SET pin_cipher = EXCLUDED.pin_cipher, set_at = EXCLUDED.set_at, attempts_left = 3""",
            cardId,
            cipher,
            at.odt(),
        )
    }

    /** Stores [cipher] only when the card has no PIN yet (a concurrent first view must not make two). */
    suspend fun insertPinIfAbsent(cardId: UUID, cipher: String, at: Instant) {
        exec(
            "INSERT INTO card_pins (card_id, pin_cipher, set_at, attempts_left) VALUES ($1, $2, $3, 3) ON CONFLICT (card_id) DO NOTHING",
            cardId,
            cipher,
            at.odt(),
        )
    }

    // ---------------------------------------------------------------- design

    suspend fun design(cardId: UUID): String? =
        rows("SELECT design FROM card_designs WHERE card_id = $1", cardId).firstOrNull()?.getString("design")

    suspend fun saveDesign(cardId: UUID, design: String, at: Instant) {
        exec(
            """INSERT INTO card_designs (card_id, design, updated_at) VALUES ($1, $2, $3)
               ON CONFLICT (card_id) DO UPDATE SET design = EXCLUDED.design, updated_at = EXCLUDED.updated_at""",
            cardId,
            design,
            at.odt(),
        )
    }

    // ---------------------------------------------------------------- plastic card orders

    suspend fun insertOrder(o: PhysicalCardOrder) {
        exec(
            """INSERT INTO physical_card_orders (order_id, party_id, account_id, card_id, design, name_on_card, line1, line2,
                 city, postal_code, country_code, factory_ref, created_at)
               VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)""",
            o.id, o.partyId, o.accountId, o.cardId, o.design, o.nameOnCard, o.address.line1, o.address.line2,
            o.address.city, o.address.postalCode, o.address.countryCode, o.factoryRef, o.createdAt.odt(),
        )
    }

    suspend fun orders(partyId: UUID): List<PhysicalCardOrder> =
        rows("SELECT * FROM physical_card_orders WHERE party_id = $1 ORDER BY created_at DESC", partyId).map(::order)

    suspend fun order(partyId: UUID, id: UUID): PhysicalCardOrder? = rows(
        "SELECT * FROM physical_card_orders WHERE order_id = $1 AND party_id = $2",
        id,
        partyId,
    ).firstOrNull()?.let(::order)

    suspend fun orderForCard(cardId: UUID): PhysicalCardOrder? =
        rows("SELECT * FROM physical_card_orders WHERE card_id = $1", cardId).firstOrNull()?.let(::order)

    suspend fun markActivated(orderId: UUID, at: Instant) {
        exec(
            "UPDATE physical_card_orders SET activated_at = $1 WHERE order_id = $2 AND activated_at IS NULL",
            at.odt(),
            orderId,
        )
    }

    private fun order(r: Row) = PhysicalCardOrder(
        r.getUUID(
            "order_id",
        ),
        r.getUUID("party_id"), r.getUUID("account_id"), r.getUUID("card_id"), r.getString("design"),
        r.getString("name_on_card"),
        DeliveryAddress(
            r.getString("line1"),
            r.getString("line2"),
            r.getString("city"),
            r.getString("postal_code"),
            r.getString("country_code"),
        ),
        r.getString(
            "factory_ref",
        ),
        r.getOffsetDateTime("activated_at")?.toInstant(), r.getOffsetDateTime("created_at").toInstant(),
    )

    // ---------------------------------------------------------------- travel notices

    suspend fun travelWindows(cardId: UUID): List<TravelWindow> = rows(
        "SELECT * FROM card_travel_notices WHERE card_id = $1 AND removed_at IS NULL ORDER BY start_date",
        cardId,
    ).map {
        TravelWindow(
            it.getUUID("notice_id"),
            it.getString("countries").split(","),
            it.getLocalDate("start_date"),
            it.getLocalDate("end_date"),
        )
    }

    suspend fun insertTravel(cardId: UUID, w: TravelWindow, at: Instant) {
        exec(
            "INSERT INTO card_travel_notices (notice_id, card_id, countries, start_date, end_date, created_at) VALUES ($1,$2,$3,$4,$5,$6)",
            w.id,
            cardId,
            w.countries.joinToString(","),
            w.start,
            w.end,
            at.odt(),
        )
    }

    suspend fun removeTravel(cardId: UUID, noticeId: UUID, at: Instant): Boolean = exec(
        "UPDATE card_travel_notices SET removed_at = $1 WHERE notice_id = $2 AND card_id = $3 AND removed_at IS NULL",
        at.odt(),
        noticeId,
        cardId,
    ) == 1

    // ---------------------------------------------------------------- notification settings

    suspend fun notificationSettings(partyId: UUID): CardNotificationSettings? =
        rows("SELECT * FROM card_notification_settings WHERE party_id = $1", partyId).firstOrNull()?.let {
            CardNotificationSettings(
                it.getBoolean("every_payment"),
                it.getBoolean("declined_payments"),
                it.getBoolean("payments_abroad"),
                it.getBoolean("online_payments"),
                it.getBoolean("weekly_summary"),
                it.getBigDecimal("large_payment_threshold"),
                it.getString("currency"),
            )
        }

    suspend fun saveNotificationSettings(partyId: UUID, s: CardNotificationSettings, at: Instant) {
        exec(
            """INSERT INTO card_notification_settings (party_id, every_payment, declined_payments, payments_abroad, online_payments,
                 weekly_summary, large_payment_threshold, currency, updated_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
               ON CONFLICT (party_id) DO UPDATE SET every_payment = EXCLUDED.every_payment,
                 declined_payments = EXCLUDED.declined_payments, payments_abroad = EXCLUDED.payments_abroad,
                 online_payments = EXCLUDED.online_payments, weekly_summary = EXCLUDED.weekly_summary,
                 large_payment_threshold = EXCLUDED.large_payment_threshold, currency = EXCLUDED.currency,
                 updated_at = EXCLUDED.updated_at""",
            partyId, s.everyPayment, s.declinedPayments, s.paymentsAbroad, s.onlinePayments, s.weeklySummary,
            s.largePaymentThreshold, s.currency, at.odt(),
        )
    }
}
