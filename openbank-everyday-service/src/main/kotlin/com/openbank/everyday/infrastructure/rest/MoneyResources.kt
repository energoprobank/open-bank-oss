// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonProperty
import com.openbank.everyday.application.CashFlow
import com.openbank.everyday.application.DirectDebitService
import com.openbank.everyday.application.EnergyTrend
import com.openbank.everyday.application.ExportService
import com.openbank.everyday.application.InsightsService
import com.openbank.everyday.application.Mandate
import com.openbank.everyday.application.PaymentExport
import com.openbank.everyday.application.Protection
import com.openbank.everyday.application.ProtectionService
import com.openbank.everyday.application.RecallCase
import com.openbank.everyday.application.RecallService
import com.openbank.everyday.application.SubscriptionService
import com.openbank.everyday.application.SubscriptionView
import com.openbank.everyday.application.TopUp
import com.openbank.everyday.application.TopUpService
import com.openbank.everyday.domain.Spending
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@Path("/api/v1/everyday/parties/{partyId}/direct-debits/mandates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(MACHINE)
@Blocking
class DirectDebitResource(private val service: DirectDebitService) {
    @GET
    @Authorize(action = "everyday.money.read", resource = "#partyId")
    @Operation(summary = "The customer's direct-debit consents")
    fun list(@PathParam("partyId") partyId: UUID): List<MandateView> = service.mandates(partyId).map(MandateView::of)

    @POST
    @Authorize(action = "everyday.money.update", resource = "#partyId")
    @Operation(summary = "Allow a creditor to collect (INKASO, SIPO or SEPA_CORE)")
    fun create(@PathParam("partyId") partyId: UUID, body: NewMandateRequest?): Response {
        val r = requireNotNull(body) { "request body is required" }
        val m = service.create(
            partyId,
            requireNotNull(r.scheme) { "scheme is required" },
            requireNotNull(r.creditorName) { "creditor_name is required" },
            requireNotNull(r.creditorReference) { "creditor_reference is required" },
            UUID.fromString(requireNotNull(r.debtorAccountId) { "debtor_account_id is required" }),
            r.limit,
            r.frequency ?: "ANY",
        )
        return Response.status(Response.Status.CREATED).entity(MandateView.of(m)).build()
    }

    @DELETE
    @Path("/{id}")
    @Authorize(action = "everyday.money.update", resource = "#partyId")
    @Operation(summary = "Withdraw a direct-debit consent")
    fun cancel(@PathParam("partyId") partyId: UUID, @PathParam("id") id: UUID): MandateView =
        MandateView.of(service.cancel(partyId, id))
}

@Path("/api/v1/everyday/parties/{partyId}/subscriptions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(MACHINE)
@Blocking
class SubscriptionResource(private val service: SubscriptionService) {
    @GET
    @Authorize(action = "everyday.money.read", resource = "#partyId")
    @Operation(summary = "Subscriptions detected from the customer's payments")
    fun list(@PathParam("partyId") partyId: UUID): List<SubscriptionDto> =
        service.subscriptions(partyId).map(SubscriptionDto::of)

    @POST
    @Path("/{id}/cancel")
    @Authorize(action = "everyday.money.update", resource = "#partyId")
    @Operation(summary = "Ask to stop a subscription")
    fun cancel(@PathParam("partyId") partyId: UUID, @PathParam("id") id: String): SubscriptionDto =
        SubscriptionDto.of(service.cancel(partyId, id))

    @PUT
    @Path("/{id}/reminder")
    @Authorize(action = "everyday.money.update", resource = "#partyId")
    @Operation(summary = "Remind before the next charge")
    fun reminder(
        @PathParam("partyId") partyId: UUID,
        @PathParam("id") id: String,
        body: ReminderRequest?,
    ): SubscriptionDto =
        SubscriptionDto.of(service.reminder(partyId, id, requireNotNull(body?.enabled) { "enabled is required" }))
}

@Path("/api/v1/everyday/parties/{partyId}/accounts/{accountId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(MACHINE)
@Blocking
class AccountInsightsResource(private val insights: InsightsService, private val exports: ExportService) {
    @GET
    @Path("/insights/spending")
    @Authorize(action = "everyday.money.read", resource = "#partyId")
    @Operation(summary = "A month's spending by category (month = yyyy-MM)")
    fun spending(
        @PathParam("partyId") partyId: UUID,
        @PathParam("accountId") accountId: UUID,
        @QueryParam("month") month: String?,
    ): SpendingView {
        val m = requireNotNull(month) { "query parameter 'month' is required" }
        val parsed =
            runCatching { YearMonth.parse(m) }.getOrNull() ?: throw IllegalArgumentException("month must be yyyy-MM")
        return SpendingView.of(insights.spending(partyId, accountId, parsed))
    }

    @GET
    @Path("/insights/cash-flow")
    @Authorize(action = "everyday.money.read", resource = "#partyId")
    @Operation(summary = "The balance expected at the end of the month (balance = today's, from the caller)")
    fun cashFlow(
        @PathParam("partyId") partyId: UUID,
        @PathParam("accountId") accountId: UUID,
        @QueryParam("balance") balance: BigDecimal?,
    ): CashFlowView = CashFlowView.of(
        insights.cashFlow(
            partyId,
            accountId,
            requireNotNull(balance) {
                "query parameter 'balance' is required"
            },
        ),
    )

    @POST
    @Path("/exports")
    @Authorize(action = "everyday.money.update", resource = "#partyId")
    @Operation(summary = "Export the account's payments for a period (CSV or PDF)")
    fun export(
        @PathParam("partyId") partyId: UUID,
        @PathParam("accountId") accountId: UUID,
        body: ExportRequest?,
    ): Response {
        val r = requireNotNull(body) { "request body is required" }
        val e = exports.create(
            partyId,
            accountId,
            (r.format ?: "CSV").uppercase(),
            LocalDate.parse(requireNotNull(r.from) { "from is required" }),
            LocalDate.parse(requireNotNull(r.to) { "to is required" }),
            (r.direction ?: "ALL").uppercase(),
        )
        return Response.status(Response.Status.CREATED).entity(ExportView.of(e)).build()
    }

    @GET
    @Path("/exports/{exportId}")
    @Authorize(action = "everyday.money.read", resource = "#partyId")
    @Operation(summary = "An export's state and download token")
    fun exportStatus(
        @PathParam("partyId") partyId: UUID,
        @PathParam("accountId") accountId: UUID,
        @PathParam("exportId") exportId: UUID,
    ): ExportView = ExportView.of(
        exports.export(partyId, exportId).also {
            if (it.accountId !=
                accountId
            ) {
                throw NotFoundException()
            }
        },
    )
}

/** The file behind a download token: the token is the capability; the edge serves it to whoever holds the link. */
@Path("/api/v1/everyday/export-files")
@RolesAllowed(MACHINE)
@Blocking
class ExportFileResource(private val exports: ExportService) {
    @GET
    @Path("/{token}")
    @Authorize(action = "everyday.exports.download")
    @Operation(summary = "An export file by its download token (404 when unknown or expired)")
    fun file(@PathParam("token") token: String): Response {
        require(token.length <= MAX_TOKEN) { "token is too long" }
        val e = exports.download(token) ?: throw NotFoundException()
        return Response.ok(e.content, e.contentType)
            .header("Content-Disposition", "attachment; filename=\"${e.fileName}\"")
            .header("Cache-Control", "no-store").build()
    }

    private companion object {
        const val MAX_TOKEN = 64
    }
}

// Its own path segment: a class whose path were a prefix of another's would capture that class's routes.
@Path("/api/v1/everyday/parties/{partyId}/money")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(MACHINE)
@Blocking
class CasesResource(
    private val recalls: RecallService,
    private val topUps: TopUpService,
    private val protection: ProtectionService,
    private val insights: InsightsService,
) {
    @POST
    @Path("/transactions/{transactionId}/recall")
    @Authorize(action = "everyday.money.update", resource = "#partyId")
    @Operation(summary = "Ask the recipient's bank to return a transfer")
    fun recall(
        @PathParam("partyId") partyId: UUID,
        @PathParam("transactionId") transactionId: UUID,
        body: RecallRequest?,
    ): Response {
        val r = requireNotNull(body) { "request body is required" }
        val c = recalls.recall(partyId, transactionId, requireNotNull(r.reason) { "reason is required" }, r.note)
        return Response.status(Response.Status.CREATED).entity(RecallView.of(c)).build()
    }

    @POST
    @Path("/top-ups")
    @Authorize(action = "everyday.money.update", resource = "#partyId")
    @Operation(summary = "Start a card top-up; the card is entered on the acquirer's checkout page")
    fun topUp(@PathParam("partyId") partyId: UUID, body: TopUpRequest?): Response {
        val r = requireNotNull(body) { "request body is required" }
        val t = topUps.create(
            partyId,
            UUID.fromString(requireNotNull(r.accountId) { "account_id is required" }),
            requireNotNull(r.amount) { "amount is required" },
            requireNotNull(r.currency) { "currency is required" },
        )
        return Response.status(Response.Status.CREATED).entity(TopUpView.of(t)).build()
    }

    @GET
    @Path("/top-ups/{id}")
    @Authorize(action = "everyday.money.read", resource = "#partyId")
    @Operation(summary = "A top-up's state (credits the account once the acquirer authorised)")
    fun topUpStatus(@PathParam("partyId") partyId: UUID, @PathParam("id") id: UUID): TopUpView =
        TopUpView.of(topUps.status(partyId, id))

    @GET
    @Path("/payment-protection")
    @Authorize(action = "everyday.money.read", resource = "#partyId")
    @Operation(summary = "Payment protection settings")
    fun protection(@PathParam("partyId") partyId: UUID): ProtectionView =
        ProtectionView.of(protection.protection(partyId))

    @PUT
    @Path("/payment-protection")
    @Authorize(action = "everyday.money.update", resource = "#partyId")
    @Operation(summary = "Change payment protection")
    fun saveProtection(@PathParam("partyId") partyId: UUID, body: ProtectionView?): ProtectionView {
        val r = requireNotNull(body) { "request body is required" }
        return ProtectionView.of(
            protection.save(
                partyId,
                Protection(
                    r.holdUnusual,
                    r.streetMode,
                    r.streetModeLimit,
                    r.trustedApproverName?.takeIf {
                        it.isNotBlank()
                    },
                    r.trustedApproverPhone?.filter { it.isDigit() || it == '+' }?.takeIf { it.isNotBlank() },
                ),
            ),
        )
    }

    @GET
    @Path("/energy-trend")
    @Authorize(action = "everyday.money.read", resource = "#partyId")
    @Operation(summary = "The linked Energo-Pro account's cost over twelve months (404 when not linked)")
    fun energyTrend(@PathParam("partyId") partyId: UUID): EnergyTrendView =
        EnergyTrendView.of(insights.energyTrend(partyId) ?: throw NotFoundException())
}

// ---------------------------------------------------------------- wire types (the app's contract, snake_case)

data class NewMandateRequest(
    val scheme: String? = null,
    @JsonProperty("creditor_name") val creditorName: String? = null,
    @JsonProperty("creditor_reference") val creditorReference: String? = null,
    @JsonProperty("debtor_account_id") val debtorAccountId: String? = null,
    val limit: BigDecimal? = null,
    val currency: String? = null,
    val frequency: String? = null,
)

data class MandateView(
    val id: String,
    @JsonProperty("creditor_name") val creditorName: String,
    @JsonProperty("creditor_reference") val creditorReference: String,
    val scheme: String,
    @JsonProperty("debtor_account_id") val debtorAccountId: String,
    val limit: BigDecimal?,
    val currency: String,
    val frequency: String,
    val status: String,
    @JsonProperty("last_collection") val lastCollection: BigDecimal? = null,
    @JsonProperty("last_collection_date") val lastCollectionDate: String? = null,
) {
    companion object {
        fun of(m: Mandate) = MandateView(
            m.id.toString(), m.creditorName, m.creditorReference, m.scheme, m.debtorAccountId.toString(), m.limit,
            m.currency,
            m.frequency, m.status,
        )
    }
}

data class ReminderRequest(val enabled: Boolean? = null)

data class SubscriptionDto(
    val id: String,
    @JsonProperty("merchant_name") val merchantName: String,
    val amount: BigDecimal,
    val currency: String,
    val frequency: String,
    @JsonProperty("next_charge_date") val nextChargeDate: String,
    @JsonProperty("paid_by") val paidBy: String,
    @JsonProperty("reminder_enabled") val reminderEnabled: Boolean,
    val status: String,
) {
    companion object {
        fun of(v: SubscriptionView) = SubscriptionDto(
            v.detected.id, v.detected.merchantName, v.detected.amount, v.detected.currency, v.detected.frequency,
            v.detected.nextChargeDate.toString(), v.detected.paidBy, v.choice.reminderEnabled,
            if (v.choice.cancelRequested) "CANCEL_REQUESTED" else "ACTIVE",
        )
    }
}

data class CategoryView(
    val id: String,
    val name: String,
    val amount: BigDecimal,
    val share: Double,
    @JsonProperty("change_from_last_month") val changeFromLastMonth: BigDecimal?,
)

data class SpendingView(
    val month: String,
    val currency: String,
    @JsonProperty("total_out") val totalOut: BigDecimal,
    @JsonProperty("total_in") val totalIn: BigDecimal,
    val categories: List<CategoryView>,
) {
    companion object {
        fun of(s: Spending) = SpendingView(
            s.month.toString(),
            s.currency,
            s.totalOut,
            s.totalIn,
            s.categories.map { CategoryView(it.id, it.name, it.amount, it.share, it.changeFromLastMonth) },
        )
    }
}

data class CashFlowItemView(
    val id: String,
    val date: String,
    val title: String,
    val amount: BigDecimal,
    val source: String,
)

data class CashFlowView(
    val currency: String,
    @JsonProperty("balance_today") val balanceToday: BigDecimal,
    @JsonProperty("balance_end_of_month") val balanceEndOfMonth: BigDecimal,
    val upcoming: List<CashFlowItemView>,
) {
    companion object {
        fun of(c: CashFlow) = CashFlowView(
            c.currency,
            c.balanceToday,
            c.balanceEndOfMonth,
            c.upcoming.map { CashFlowItemView(it.id, it.date.toString(), it.title, it.amount, it.source) },
        )
    }
}

data class ExportRequest(
    val format: String? = null,
    val from: String? = null,
    val to: String? = null,
    val direction: String? = null,
)

data class ExportView(
    val id: String,
    val format: String,
    val status: String,
    @JsonProperty("download_token") val downloadToken: String?,
    @JsonProperty("file_name") val fileName: String?,
    @JsonProperty("expires_at") val expiresAt: String,
) {
    companion object {
        fun of(e: PaymentExport) =
            ExportView(e.id.toString(), e.format, e.status, e.token, e.fileName, e.expiresAt.toString())
    }
}

data class RecallRequest(val reason: String? = null, val note: String? = null)

data class RecallView(
    val id: String,
    val reference: String,
    val status: String,
    @JsonProperty("answer_by") val answerBy: String,
) {
    companion object {
        fun of(c: RecallCase) = RecallView(c.id.toString(), c.reference, c.status, c.answerBy.toString())
    }
}

data class TopUpRequest(
    @JsonProperty("account_id") val accountId: String? = null,
    val amount: BigDecimal? = null,
    val currency: String? = null,
)

data class TopUpView(
    val id: String,
    @JsonProperty("checkout_url") val checkoutUrl: String?,
    val status: String,
    val fee: BigDecimal,
) {
    companion object {
        fun of(t: TopUp) = TopUpView(t.id.toString(), t.checkoutUrl.takeIf { t.status == "CREATED" }, t.status, t.fee)
    }
}

data class ProtectionView(
    @JsonProperty("hold_unusual") val holdUnusual: Boolean = false,
    @JsonProperty("street_mode") val streetMode: Boolean = false,
    @JsonProperty("street_mode_limit") val streetModeLimit: BigDecimal = BigDecimal("2000"),
    @JsonProperty("trusted_approver_name") val trustedApproverName: String? = null,
    @JsonProperty("trusted_approver_phone") val trustedApproverPhone: String? = null,
) {
    companion object {
        fun of(p: Protection) = ProtectionView(
            p.holdUnusual,
            p.streetMode,
            p.streetModeLimit,
            p.trustedApproverName,
            p.trustedApproverPhone,
        )
    }
}

data class EnergyTrendView(
    val currency: String,
    @JsonProperty("last_twelve_months") val lastTwelveMonths: BigDecimal,
    @JsonProperty("previous_twelve_months") val previousTwelveMonths: BigDecimal?,
) {
    companion object {
        fun of(e: EnergyTrend) = EnergyTrendView(e.currency, e.lastTwelveMonths, e.previousTwelveMonths)
    }
}
