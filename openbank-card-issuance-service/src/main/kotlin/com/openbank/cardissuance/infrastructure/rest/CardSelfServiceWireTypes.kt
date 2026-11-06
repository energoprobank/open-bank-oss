// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.cardissuance.application.port.out.WalletProvisioning
import com.openbank.cardissuance.application.usecase.OrderView
import com.openbank.cardissuance.domain.model.CardNotificationSettings
import com.openbank.cardissuance.domain.model.TravelWindow
import java.math.BigDecimal

// Experience v2 step 4. Machine callers only (customer-edge), after it established the card is the
// customer's and, for PIN and ordering, that SCA passed. Wire names are snake_case, like the app's.
// Every class carries its full path: a prefix class would capture the routes of CardResource.

internal const val SELF_SERVICE_CALLERS = "ROLE_API"

data class PinStatusView(
    @JsonProperty("pin_set") val pinSet: Boolean,
    @JsonProperty("last_changed_at") val lastChangedAt: String?,
    @JsonProperty("attempts_left") val attemptsLeft: Int?,
)

data class PinView(val pin: String, @JsonProperty("visible_seconds") val visibleSeconds: Int)

data class NewPinRequest(@JsonProperty("new_pin") val newPin: String? = null)

data class DesignView(val design: String? = null)

data class ActivationRequest(val last4: String? = null)

data class ActivationView(@JsonProperty("card_id") val cardId: String, val status: String)

data class WalletRequest(
    val certificates: List<String>? = null,
    val nonce: String? = null,
    @JsonProperty("nonce_signature") val nonceSignature: String? = null,
)

/** PassKit's fields for in-app provisioning, plus the token the bank registers with card-processing. */
data class WalletView(
    @JsonProperty("activation_data") val activationData: String,
    @JsonProperty("encrypted_pass_data") val encryptedPassData: String,
    @JsonProperty("ephemeral_public_key") val ephemeralPublicKey: String,
    @JsonProperty("token_reference") val tokenReference: String,
    @JsonProperty("token_last4") val tokenLast4: String,
) {
    companion object {
        fun of(w: WalletProvisioning) =
            WalletView(w.activationData, w.encryptedPassData, w.ephemeralPublicKey, w.tokenReference, w.tokenLast4)
    }
}

data class TravelRequest(
    val countries: List<String>? = null,
    @JsonProperty("start_date") val startDate: String? = null,
    @JsonProperty("end_date") val endDate: String? = null,
)

data class TravelView(
    val id: String,
    val countries: List<String>,
    @JsonProperty("start_date") val startDate: String,
    @JsonProperty("end_date") val endDate: String,
) {
    companion object {
        fun of(w: TravelWindow) = TravelView(w.id.toString(), w.countries, w.start.toString(), w.end.toString())
    }
}

data class AddressDto(
    val line1: String? = null,
    val line2: String? = null,
    val city: String? = null,
    @JsonProperty("postal_code") val postalCode: String? = null,
    @JsonProperty("country_code") val countryCode: String? = null,
)

data class OrderRequest(
    @JsonProperty("party_id") val partyId: String? = null,
    @JsonProperty("account_id") val accountId: String? = null,
    @JsonProperty("card_id") val cardId: String? = null,
    val design: String? = null,
    @JsonProperty("name_on_card") val nameOnCard: String? = null,
    @JsonProperty("delivery_address") val deliveryAddress: AddressDto? = null,
)

data class OrderEventDto(val status: String, val at: String, val detail: String?)

data class OrderDto(
    val id: String,
    @JsonProperty("card_id") val cardId: String,
    val last4: String?,
    val design: String,
    val status: String,
    val carrier: String?,
    @JsonProperty("tracking_number") val trackingNumber: String?,
    @JsonProperty("tracking_url") val trackingUrl: String?,
    @JsonProperty("expected_on") val expectedOn: String?,
    @JsonProperty("delivery_address") val deliveryAddress: AddressDto,
    val events: List<OrderEventDto>,
) {
    companion object {
        fun of(v: OrderView): OrderDto {
            val a = v.order.address
            val tracking = v.factory?.trackingNumber
            return OrderDto(
                v.order.id.toString(), v.order.cardId.toString(),
                v.card?.maskedPan?.takeLast(
                    LAST4,
                ),
                v.order.design, v.status,
                v.factory?.carrier, tracking,
                tracking?.let {
                    if (a.countryCode ==
                        "BG"
                    ) {
                        "https://www.econt.com/services/track-shipment/$it"
                    } else {
                        "https://www.postaonline.cz/trackandtrace/-/zasilka/cislo?parcelNumbers=$it"
                    }
                },
                v.factory?.expectedOn, AddressDto(a.line1, a.line2, a.city, a.postalCode, a.countryCode),
                v.factory?.events.orEmpty().map { OrderEventDto(it.status, it.at, it.detail) } +
                    (
                        v.order.activatedAt?.let {
                            listOf(OrderEventDto("ACTIVATED", it.toString(), null))
                        } ?: emptyList()
                        ),
            )
        }

        private const val LAST4 = 4
    }
}

data class NotificationView(
    @JsonProperty("every_payment") val everyPayment: Boolean = true,
    @JsonProperty("declined_payments") val declinedPayments: Boolean = true,
    @JsonProperty("payments_abroad") val paymentsAbroad: Boolean = true,
    @JsonProperty("online_payments") val onlinePayments: Boolean = false,
    @JsonProperty("weekly_summary") val weeklySummary: Boolean = true,
    @JsonProperty("large_payment_threshold") val largePaymentThreshold: BigDecimal? = null,
    val currency: String = "CZK",
) {
    companion object {
        fun of(s: CardNotificationSettings) = NotificationView(
            s.everyPayment,
            s.declinedPayments,
            s.paymentsAbroad,
            s.onlinePayments,
            s.weeklySummary,
            s.largePaymentThreshold,
            s.currency,
        )
    }
}
