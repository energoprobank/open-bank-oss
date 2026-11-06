// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.openbank.cardissuance.application.usecase.CardSelfServiceService
import com.openbank.cardissuance.domain.model.CardNotificationSettings
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import java.util.UUID

@Path("/api/v1/card-notification-settings/{partyId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(SELF_SERVICE_CALLERS)
class CardNotificationResource(private val service: CardSelfServiceService) {
    @GET
    @Authorize(action = "card.notifications.read", resource = "#partyId")
    suspend fun get(@PathParam("partyId") partyId: UUID, @QueryParam("currency") currency: String?): NotificationView =
        NotificationView.of(service.notifications(partyId, currency ?: "CZK"))

    @PUT
    @Authorize(action = "card.notifications.update", resource = "#partyId")
    suspend fun put(@PathParam("partyId") partyId: UUID, body: NotificationView?): NotificationView {
        val r = requireNotNull(body) { "request body is required" }
        return NotificationView.of(
            service.saveNotifications(
                partyId,
                CardNotificationSettings(
                    r.everyPayment,
                    r.declinedPayments,
                    r.paymentsAbroad,
                    r.onlinePayments,
                    r.weeklySummary,
                    r.largePaymentThreshold,
                    r.currency.uppercase(),
                ),
            ),
        )
    }
}
