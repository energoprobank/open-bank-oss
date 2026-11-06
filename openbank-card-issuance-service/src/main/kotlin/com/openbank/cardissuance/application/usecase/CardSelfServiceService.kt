// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.usecase

import com.openbank.cardissuance.application.port.`in`.CardStatusCommand
import com.openbank.cardissuance.application.port.`in`.CardUseCase
import com.openbank.cardissuance.application.port.out.CardFactoryPort
import com.openbank.cardissuance.application.port.out.CardSecretCipher
import com.openbank.cardissuance.application.port.out.WalletProvisioning
import com.openbank.cardissuance.application.port.out.WalletTokenServicePort
import com.openbank.cardissuance.domain.model.Card
import com.openbank.cardissuance.domain.model.CardDesigns
import com.openbank.cardissuance.domain.model.CardNotificationSettings
import com.openbank.cardissuance.domain.model.CardStatus
import com.openbank.cardissuance.domain.model.CardType
import com.openbank.cardissuance.domain.model.DeliveryAddress
import com.openbank.cardissuance.domain.model.FactoryStatus
import com.openbank.cardissuance.domain.model.PhysicalCardOrder
import com.openbank.cardissuance.domain.model.PinPolicy
import com.openbank.cardissuance.domain.model.TravelWindow
import com.openbank.cardissuance.infrastructure.persistence.CardSelfServiceStore
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class PinStatus(val pinSet: Boolean, val lastChangedAt: java.time.Instant?, val attemptsLeft: Int?)

data class OrderView(val order: PhysicalCardOrder, val card: Card?, val factory: FactoryStatus?, val status: String)

/**
 * What a customer manages on their own card (experience v2 step 4): the PIN, the design, a plastic
 * card on its way, travel notices and card notifications. The caller (customer-edge) has already
 * established that the card is the customer's and, where the app asks for it, that SCA was passed.
 */
@ApplicationScoped
@Suppress("TooManyFunctions") // one small method per customer action
class CardSelfServiceService(
    private val cards: CardUseCase,
    private val store: CardSelfServiceStore,
    private val cipher: CardSecretCipher,
    private val factory: CardFactoryPort,
    private val wallet: WalletTokenServicePort,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(CardSelfServiceService::class.java)
    private val random = SecureRandom()

    private suspend fun card(cardId: UUID): Card =
        cards.getCard(cardId) ?: throw ResourceNotFoundException("No such card", "CARD_NOT_FOUND")

    // ---------------------------------------------------------------- PIN

    /** A card with a chip has a PIN from issue; a card issued before PINs were kept gets one now. */
    suspend fun pinStatus(cardId: UUID): PinStatus {
        val c = card(cardId)
        if (!hasPin(c)) return PinStatus(false, null, null)
        val pin = store.pin(cardId) ?: run {
            store.insertPinIfAbsent(cardId, cipher.encrypt(PinPolicy.random(random::nextInt)), clock.instant())
            store.pin(cardId)!!
        }
        return PinStatus(true, pin.setAt, pin.attemptsLeft)
    }

    suspend fun viewPin(cardId: UUID): String {
        if (!pinStatus(cardId).pinSet) throw ResourceConflictException("This card has no PIN", "NO_PIN")
        return cipher.decrypt(store.pin(cardId)!!.cipher)
    }

    suspend fun changePin(cardId: UUID, newPin: String) {
        if (!hasPin(card(cardId))) throw ResourceConflictException("This card has no PIN", "NO_PIN")
        PinPolicy.problem(newPin)?.let { throw IllegalArgumentException(it) }
        store.savePin(cardId, cipher.encrypt(newPin), clock.instant())
    }

    private fun hasPin(c: Card) = c.cardType != CardType.VIRTUAL && c.cardType != CardType.SINGLE_USE

    // ---------------------------------------------------------------- design

    suspend fun design(cardId: UUID): String {
        card(cardId)
        return store.design(cardId) ?: CardDesigns.DEFAULT
    }

    suspend fun setDesign(cardId: UUID, design: String): String {
        require(design in CardDesigns.ALL) { "design must be one of ${CardDesigns.ALL.joinToString()}" }
        val c = card(cardId)
        // A plastic card already printed keeps its face; a virtual card (or one not yet made) can change.
        val order = store.orderForCard(cardId)
        if (c.cardType != CardType.VIRTUAL && order != null && refresh(order).status != "ORDERED") {
            throw ResourceConflictException(
                "This card is already printed; order a new plastic card for a new design",
                "ALREADY_PRINTED",
            )
        }
        store.saveDesign(cardId, design, clock.instant())
        return design
    }

    // ---------------------------------------------------------------- plastic card

    /** Registers the plastic card order for [cardId] (issued PENDING by the caller) and sends it to the manufacturer. */
    @Suppress("LongParameterList")
    suspend fun order(
        partyId: UUID,
        accountId: UUID,
        cardId: UUID,
        design: String,
        nameOnCard: String,
        address: DeliveryAddress,
    ): OrderView {
        require(design in CardDesigns.ALL) { "design must be one of ${CardDesigns.ALL.joinToString()}" }
        require(nameOnCard.isNotBlank() && nameOnCard.length <= MAX_NAME) {
            "name_on_card must be 1..$MAX_NAME characters"
        }
        require(address.line1.isNotBlank() && address.city.isNotBlank() && address.postalCode.isNotBlank()) {
            "delivery_address is incomplete"
        }
        require(address.countryCode in setOf("CZ", "BG")) { "we deliver cards to the Czech Republic and Bulgaria" }
        val c = card(cardId)
        if (c.partyId != partyId ||
            c.accountId != accountId
        ) {
            throw ResourceNotFoundException("No such card", "CARD_NOT_FOUND")
        }
        store.orderForCard(cardId)?.let { return refresh(it) }
        val factoryRef = factory.place(
            cardId.toString(),
            design,
            nameOnCard.uppercase(),
            address.city,
            address.countryCode,
        )
        val o = PhysicalCardOrder(
            UUID.randomUUID(), partyId, accountId, cardId, design, nameOnCard.uppercase(), address, factoryRef, null,
            clock.instant(),
        )
        store.insertOrder(o)
        store.saveDesign(cardId, design, clock.instant())
        store.insertPinIfAbsent(cardId, cipher.encrypt(PinPolicy.random(random::nextInt)), clock.instant())
        log.infof("Plastic card %s ordered (factory %s)", cardId, factoryRef)
        return refresh(o)
    }

    suspend fun orders(partyId: UUID): List<OrderView> = store.orders(partyId).map { refresh(it) }

    suspend fun order(partyId: UUID, id: UUID): OrderView =
        refresh(store.order(partyId, id) ?: throw ResourceNotFoundException("No such card order", "ORDER_NOT_FOUND"))

    /** Activates a delivered plastic card with the last four digits printed on it. */
    suspend fun activate(cardId: UUID, last4: String, by: String): Card {
        val c = card(cardId)
        if (c.status == CardStatus.ACTIVE) return c
        val order = store.orderForCard(cardId)
        refusal(
            order?.let {
                refresh(it).status
            },
        )?.let { (message, code) -> throw ResourceConflictException(message, code) }
        require(last4.length == LAST4 && c.maskedPan.endsWith(last4)) {
            "Those are not the last four digits on this card"
        }
        val active = cards.activateCard(CardStatusCommand(cardId, "activated by the cardholder", by))
        store.markActivated(order!!.id, clock.instant())
        return active
    }

    /** Why a plastic card cannot be activated yet (message, code), from its order's status; null when it can. */
    private fun refusal(orderStatus: String?): Pair<String, String>? = when (orderStatus) {
        null -> "This card is not a plastic card on its way" to "NOT_ORDERED"
        "DELIVERED" -> null
        else -> "The card has not been delivered yet" to "NOT_DELIVERED"
    }

    private suspend fun refresh(o: PhysicalCardOrder): OrderView {
        val card = cards.getCard(o.cardId)
        val f = o.factoryRef?.let { ref ->
            runCatching { factory.status(ref) }
                .onFailure { log.warnf("Card factory unreachable for %s: %s", ref, it.message) }.getOrNull()
        }
        val status = when {
            o.activatedAt != null || card?.status == CardStatus.ACTIVE -> "ACTIVATED"
            card?.status == CardStatus.CANCELLED || card?.status == CardStatus.BLOCKED -> "RETURNED"
            else -> f?.status ?: "ORDERED"
        }
        return OrderView(o, card, f, status)
    }

    // ---------------------------------------------------------------- travel

    suspend fun travel(cardId: UUID): List<TravelWindow> {
        card(cardId)
        return store.travelWindows(cardId).filter { !it.end.isBefore(today()) }
    }

    suspend fun addTravel(cardId: UUID, countries: List<String>, start: LocalDate, end: LocalDate): TravelWindow {
        card(cardId)
        val codes = countries.map { it.trim().uppercase() }.distinct()
        require(codes.isNotEmpty() && codes.size <= MAX_COUNTRIES && codes.all { ISO2.matches(it) }) {
            "countries must be 1..$MAX_COUNTRIES ISO country codes"
        }
        require(!end.isBefore(start)) { "end_date must not be before start_date" }
        require(!end.isBefore(today()) && !start.isAfter(today().plusYears(1))) {
            "a travel notice covers dates from today, up to a year ahead"
        }
        require(!end.isAfter(start.plusDays(MAX_DAYS))) { "a travel notice covers at most $MAX_DAYS days" }
        return TravelWindow(UUID.randomUUID(), codes, start, end).also {
            store.insertTravel(cardId, it, clock.instant())
        }
    }

    suspend fun removeTravel(cardId: UUID, noticeId: UUID) {
        if (!store.removeTravel(
                cardId,
                noticeId,
                clock.instant(),
            )
        ) {
            throw ResourceNotFoundException("No such travel notice", "NOTICE_NOT_FOUND")
        }
    }

    // ---------------------------------------------------------------- notifications

    suspend fun notifications(partyId: UUID, currency: String): CardNotificationSettings =
        store.notificationSettings(partyId) ?: CardNotificationSettings.defaults(currency)

    suspend fun saveNotifications(partyId: UUID, s: CardNotificationSettings): CardNotificationSettings {
        require(s.largePaymentThreshold == null || s.largePaymentThreshold.signum() > 0) {
            "large_payment_threshold must be above zero"
        }
        require(s.currency in setOf("CZK", "EUR")) { "currency must be CZK or EUR" }
        store.saveNotificationSettings(partyId, s, clock.instant())
        return s
    }

    // ---------------------------------------------------------------- wallet

    /** In-app provisioning data from the wallet's token service, for the card's own wallet pass. */
    suspend fun walletProvisioning(
        cardId: UUID,
        certificates: List<String>,
        nonce: String,
        nonceSignature: String,
    ): WalletProvisioning {
        val c = card(cardId)
        if (c.status !=
            CardStatus.ACTIVE
        ) {
            throw ResourceConflictException("Only an active card can go into Apple Wallet", "CARD_NOT_ACTIVE")
        }
        return wallet.provision(cardId.toString(), certificates, nonce, nonceSignature)
    }

    private fun today(): LocalDate = LocalDate.now(clock.withZone(PRAGUE))

    private companion object {
        val PRAGUE: ZoneId = ZoneId.of("Europe/Prague")
        val ISO2 = Regex("^[A-Z]{2}$")
        const val MAX_NAME = 26
        const val LAST4 = 4
        const val MAX_COUNTRIES = 10
        const val MAX_DAYS = 180L
    }
}
