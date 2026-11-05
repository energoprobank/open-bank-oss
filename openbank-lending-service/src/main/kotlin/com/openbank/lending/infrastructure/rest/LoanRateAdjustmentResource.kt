// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.`in`.RateAdjustmentUseCase
import com.openbank.lending.domain.model.LoanTermsView
import com.openbank.lending.domain.model.RateAdjustment
import com.openbank.lending.domain.model.RateAdjustmentBlockedException
import com.openbank.lending.domain.model.RateAdjustmentNotFoundException
import com.openbank.lending.domain.model.RateAdjustmentPreview
import com.openbank.lending.domain.model.RateAdjustmentRequest
import com.openbank.lending.domain.model.RateAdjustmentSource
import com.openbank.lending.domain.model.RateAdjustmentSourceRefConflictException
import com.openbank.lending.infrastructure.rewards.RateAdjustmentConfig
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.libs.domain.identifiers.LoanId
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Additive loan rate adjustments for the FLUX rewards engine (CR-1 loan rate coupon).
 *
 * ## Who may call
 *
 * Machines only, and only named ones. RBAC admits ROLE_API (the rewards service-account's realm
 * role) and, for the read-only preview, ROLE_OPERATOR (customer-edge's). Neither role is the
 * control — staff carry ROLE_OPERATOR too — so every handler also checks the VERIFIED
 * service-account name against [RateAdjustmentConfig] (`ServiceAccountIdentity`), the same
 * belt-and-braces stance as `CustomerQuoteResource`. `lending_rest_ext.rego` states the same intent
 * per action for the day `AUTHZ_ENFORCE` flips.
 *
 * ## Errors
 *
 * `{code, message}` as the rewards contract fixes it: 409 `CREDIT_COUPON_BLOCKED` (with `reason`)
 * when a guardrail refuses, 409 `SOURCE_REF_CONFLICT` when a sourceRef is replayed with different
 * terms, 404 `NOT_FOUND`, 400 `INVALID_REQUEST`, 403 `FORBIDDEN`.
 */
@Path("/api/v1/loans")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Lending", description = "Additive loan rate adjustments (rewards)")
@RolesAllowed("ROLE_API")
class LoanRateAdjustmentResource(
    private val useCase: RateAdjustmentUseCase,
    private val config: RateAdjustmentConfig,
    private val identity: SecurityIdentity,
) {
    @GET
    @Path("/{loanId}/rate-adjustments/preview")
    @Operation(summary = "Preview a reward rate adjustment: before/after rate, APRC and instalment; never writes")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR")
    @Authorize(action = "lending.rateAdjustment.preview", resource = "#loanId")
    fun preview(
        @PathParam("loanId") loanId: UUID,
        @QueryParam("deltaBps") deltaBps: Int?,
        @QueryParam("months") @DefaultValue("12") months: Int,
    ): Uni<Response> {
        if (!ServiceAccountIdentity.isOneOf(identity, config.previewPrincipals)) return forbidden()
        val delta =
            deltaBps
                ?: return Uni.createFrom().item(
                    error(HTTP_BAD_REQUEST, INVALID, "query parameter 'deltaBps' is required"),
                )
        return guarded { useCase.preview(LoanId(loanId), delta, months).map { Response.ok(it.toDto()).build() } }
    }

    @POST
    @Path("/{loanId}/rate-adjustments")
    @Operation(summary = "Apply a reward rate adjustment (idempotent on sourceRef)")
    @Authorize(action = "lending.rateAdjustment.create", resource = "#loanId")
    fun create(@PathParam("loanId") loanId: UUID, body: RateAdjustmentBody?): Uni<Response> {
        if (!ServiceAccountIdentity.isOneOf(identity, config.writerPrincipals)) return forbidden()
        return guarded {
            val request = requireNotNull(body) { "request body is required" }.toRequest()
            useCase.apply(LoanId(loanId), request, actor()).map { outcome ->
                Response.status(HTTP_CREATED).entity(outcome.adjustment.toDto(outcome.replayed)).build()
            }
        }
    }

    @DELETE
    @Path("/{loanId}/rate-adjustments/{adjustmentId}")
    @Operation(summary = "Cancel a reward rate adjustment (compensation); re-amortises the remaining schedule")
    @Authorize(action = "lending.rateAdjustment.cancel", resource = "#loanId")
    fun cancel(@PathParam("loanId") loanId: UUID, @PathParam("adjustmentId") adjustmentId: UUID): Uni<Response> {
        if (!ServiceAccountIdentity.isOneOf(identity, config.writerPrincipals)) return forbidden()
        return guarded {
            useCase.cancel(LoanId(loanId), adjustmentId, actor()).map { Response.ok(it.toDto(false)).build() }
        }
    }

    private fun actor(): String = ServiceAccountIdentity.verifiedPrincipalName(identity).orEmpty()

    /** One mapping for every refusal, synchronous (argument validation) or from the use case. */
    private fun guarded(block: () -> Uni<Response>): Uni<Response> {
        val started = try {
            block()
        } catch (e: IllegalArgumentException) {
            return Uni.createFrom().item(error(HTTP_BAD_REQUEST, INVALID, e.message))
        }
        return started.onFailure().recoverWithItem { e -> mapFailure(e) }
    }

    private fun mapFailure(e: Throwable): Response = when {
        e is RateAdjustmentBlockedException -> Response.status(HTTP_CONFLICT)
            .entity(mapOf("code" to BLOCKED, "message" to e.message, "reason" to e.reason.name)).build()
        e is RateAdjustmentSourceRefConflictException -> error(HTTP_CONFLICT, SOURCE_REF_CONFLICT, e.message)
        // Two loans racing the same sourceRef: the second loses on UNIQUE(source_ref) at commit.
        generateSequence(e) { it.cause }.any { it is org.hibernate.exception.ConstraintViolationException } ->
            error(HTTP_CONFLICT, SOURCE_REF_CONFLICT, "sourceRef already used")
        e is RateAdjustmentNotFoundException -> error(HTTP_NOT_FOUND, "NOT_FOUND", e.message)
        e is IllegalArgumentException -> error(HTTP_BAD_REQUEST, INVALID, e.message)
        e is IllegalStateException -> error(HTTP_CONFLICT, "CONFLICT", e.message)
        else -> throw e
    }

    private fun forbidden(): Uni<Response> =
        Uni.createFrom().item(error(HTTP_FORBIDDEN, "FORBIDDEN", "caller is not a permitted rate-adjustment principal"))

    private fun error(status: Int, code: String, message: String?): Response =
        Response.status(status).entity(mapOf("code" to code, "message" to message)).build()

    private companion object {
        const val HTTP_CREATED = 201
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val BLOCKED = "CREDIT_COUPON_BLOCKED"
        const val SOURCE_REF_CONFLICT = "SOURCE_REF_CONFLICT"
        const val INVALID = "INVALID_REQUEST"
    }
}

/** Wire body of POST …/rate-adjustments. Nullable so a missing field is a 400, not a 500. */
data class RateAdjustmentBody(
    val deltaBps: Int? = null,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val source: String? = null,
    val sourceRef: String? = null,
    val approvalRef: String? = null,
) {
    fun toRequest() = RateAdjustmentRequest(
        deltaBps = requireNotNull(deltaBps) { "deltaBps is required" },
        validFrom = requireNotNull(validFrom) { "validFrom is required" },
        validTo = requireNotNull(validTo) { "validTo is required" },
        source = requireNotNull(source) { "source is required" }.let { s ->
            requireNotNull(RateAdjustmentSource.entries.firstOrNull { it.name == s }) { "unknown source: $s" }
        },
        sourceRef = requireNotNull(sourceRef) { "sourceRef is required" },
        approvalRef = requireNotNull(approvalRef) { "approvalRef is required" },
    )
}

data class MoneyDto(val amount: String, val currency: String)

data class LoanTermsDto(val nominalRatePct: BigDecimal, val aprcPct: BigDecimal?, val instalment: MoneyDto)

data class RateAdjustmentPreviewDto(
    val before: LoanTermsDto?,
    val after: LoanTermsDto?,
    val eligible: Boolean,
    val reason: String?,
    val effectiveFrom: LocalDate?,
)

data class RateAdjustmentDto(
    val adjustmentId: UUID,
    val loanId: UUID,
    val deltaBps: Int,
    val validFrom: LocalDate,
    val validTo: LocalDate,
    val source: String,
    val sourceRef: String,
    val status: String,
    val before: LoanTermsDto?,
    val after: LoanTermsDto?,
    /** Due date of the first instalment computed at the adjusted rate. */
    val effectiveFrom: LocalDate?,
    val replayed: Boolean,
)

internal fun LoanTermsView.toDto() = LoanTermsDto(
    nominalRatePct = nominalRatePct,
    aprcPct = aprcPct,
    instalment = MoneyDto(instalment.amount.toPlainString(), instalment.currency.code),
)

internal fun RateAdjustmentPreview.toDto() =
    RateAdjustmentPreviewDto(before?.toDto(), after?.toDto(), eligible, reason?.name, effectiveFrom)

internal fun RateAdjustment.toDto(replayed: Boolean) = RateAdjustmentDto(
    adjustmentId = adjustmentId,
    loanId = loanId.value,
    deltaBps = deltaBps,
    validFrom = validFrom,
    validTo = validTo,
    source = source.name,
    sourceRef = sourceRef,
    status = status.name,
    before = before?.toDto(),
    after = after?.toDto(),
    effectiveFrom = effectiveFrom,
    replayed = replayed,
)
