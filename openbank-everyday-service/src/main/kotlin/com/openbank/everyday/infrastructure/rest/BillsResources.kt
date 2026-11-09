// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.everyday.application.BillRemindersService
import com.openbank.everyday.application.BillWithStatus
import com.openbank.everyday.application.BillsService
import com.openbank.everyday.application.ElectricityView
import com.openbank.everyday.domain.BillReminders
import com.openbank.everyday.domain.Biller
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.math.BigDecimal
import java.util.UUID

// Machine callers only: customer-edge, which takes the party id from the customer's token (never from
// the app) and calls these with its M2M token. Wire names are snake_case, the same as the app's contract.

internal const val MACHINE = "ROLE_API"

@Path("/api/v1/everyday/parties/{partyId}/bills")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(MACHINE)
@Blocking
class BillsResource(private val service: BillsService, private val reminders: BillRemindersService) {

    @GET
    @Path("/billers")
    @Authorize(action = "everyday.bills.read", resource = "#partyId")
    @Operation(summary = "Billers in the customer's market")
    fun billers(@PathParam("partyId") partyId: UUID): List<BillerView> = service.billers(partyId).map(BillerView::of)

    @GET
    @Path("/energo-pro")
    @Authorize(action = "everyday.bills.read", resource = "#partyId")
    @Operation(summary = "The linked Energo-Pro electricity account and its current bill (linked=false when none)")
    fun electricity(@PathParam("partyId") partyId: UUID): ElectricityAccountView =
        ElectricityAccountView.of(service.electricity(partyId))

    @GET
    @Path("/energo-pro/bills")
    @Authorize(action = "everyday.bills.read", resource = "#partyId")
    @Operation(summary = "The linked account's bills, newest first")
    fun bills(@PathParam("partyId") partyId: UUID): List<BillView> = service.bills(partyId).map(BillView::of)

    @POST
    @Path("/energo-pro/link")
    @Authorize(action = "everyday.bills.update", resource = "#partyId")
    @Operation(summary = "Link the customer's own Energo-Pro customer number (with their data-sharing consent)")
    fun link(@PathParam("partyId") partyId: UUID, body: LinkRequest?): ElectricityAccountView {
        val req = requireNotNull(body) { "request body is required" }
        val number = requireNotNull(req.customerNumber) { "customer_number is required" }
        return ElectricityAccountView.of(service.link(partyId, number, req.dataSharingConsent == true))
    }

    @DELETE
    @Path("/energo-pro/link")
    @Authorize(action = "everyday.bills.update", resource = "#partyId")
    @Operation(summary = "Unlink the Energo-Pro account")
    fun unlink(@PathParam("partyId") partyId: UUID): Response {
        service.unlink(partyId)
        return Response.noContent().build()
    }

    @GET
    @Path("/reminders")
    @Authorize(action = "everyday.bills.read", resource = "#partyId")
    @Operation(summary = "Bill reminders and the bills they will remind about")
    fun reminders(@PathParam("partyId") partyId: UUID): RemindersView =
        remindersView(partyId, reminders.reminders(partyId))

    @PUT
    @Path("/reminders")
    @Authorize(action = "everyday.bills.update", resource = "#partyId")
    @Operation(summary = "Turn bill reminders on or off and choose how many days before the due date")
    fun saveReminders(@PathParam("partyId") partyId: UUID, body: RemindersRequest?): RemindersView {
        val req = requireNotNull(body) { "request body is required" }
        val saved = reminders.save(
            partyId,
            requireNotNull(req.enabled) { "enabled is required" },
            req.daysBefore ?: DEFAULT_DAYS,
        )
        return remindersView(partyId, saved)
    }

    private fun remindersView(partyId: UUID, r: BillReminders): RemindersView {
        val upcoming = service.bills(partyId).filter { it.status == "DUE" || it.status == "OVERDUE" }.map(BillView::of)
        return RemindersView(r.enabled, r.daysBefore, upcoming)
    }

    private companion object {
        const val DEFAULT_DAYS = 3
    }
}

// ---------------------------------------------------------------- wire types

data class BillerView(val id: String, val name: String, val category: String, val iban: String) {
    companion object {
        fun of(b: Biller) = BillerView(b.id, b.name, b.category, b.iban)
    }
}

data class BillView(
    val id: String,
    val period: String,
    val amount: BigDecimal,
    val currency: String,
    @JsonProperty("due_date") val dueDate: String,
    val status: String,
    @JsonProperty("collection_iban") val collectionIban: String,
    val reference: String,
    /** The bank's payment of this bill, once paid: the target of a bill-cashback coupon. */
    @JsonProperty("payment_id") val paymentId: String? = null,
) {
    companion object {
        fun of(b: BillWithStatus) = BillView(
            b.bill.id,
            b.bill.period,
            b.bill.amount,
            b.bill.currency,
            b.bill.dueDate.toString(),
            b.status,
            b.bill.collectionIban,
            b.bill.reference,
            b.paymentId?.toString(),
        )
    }
}

data class ElectricityAccountView(
    val linked: Boolean,
    @JsonProperty("customer_number") val customerNumber: String?,
    @JsonProperty("supplier_name") val supplierName: String?,
    @JsonProperty("current_bill") val currentBill: BillView?,
    @JsonProperty("next_estimate") val nextEstimate: BigDecimal?,
) {
    companion object {
        fun of(v: ElectricityView) = ElectricityAccountView(
            v.linked,
            v.customerNumber,
            v.supplierName,
            v.currentBill?.let(BillView::of),
            v.nextEstimate,
        )
    }
}

data class LinkRequest(
    @JsonProperty("customer_number") val customerNumber: String? = null,
    @JsonProperty("data_sharing_consent") val dataSharingConsent: Boolean? = null,
)

data class RemindersRequest(val enabled: Boolean? = null, @JsonProperty("days_before") val daysBefore: Int? = null)

data class RemindersView(
    val enabled: Boolean,
    @JsonProperty("days_before") val daysBefore: Int,
    val upcoming: List<BillView>,
)
