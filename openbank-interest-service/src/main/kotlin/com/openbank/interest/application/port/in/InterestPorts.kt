// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.port.`in`

import com.openbank.interest.domain.model.*
import io.smallrye.mutiny.Uni
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

interface AccrueInterestUseCase {
    fun accrue(request: AccrualRequest): Uni<InterestAccrual>
    fun accrueAll(date: LocalDate): Uni<Int>
}

interface CapitalizeInterestUseCase {
    fun capitalize(accountId: UUID, productId: String, toDate: LocalDate): Uni<InterestCapitalization>
    fun capitalizeAll(toDate: LocalDate): Uni<Int>

    /**
     * Completes every outstanding capitalization claim at its own frozen period, starting no new
     * ones. Returns how many were completed.
     *
     * Exposed separately from [capitalizeAll] because the two run on very different clocks. A claim
     * stranded by a failed ledger post freezes its `(account, product)` pair until it is completed,
     * and [capitalizeAll] runs MONTHLY — so recovery riding along with it would leave a wedge in
     * place for up to a month, which is how this went unnoticed for seven weeks in sandbox (#10404).
     * This is safe to run often: it touches only sets a previous attempt already claimed, and
     * replaying one is idempotent by the ledger key.
     */
    fun recoverOutstandingClaims(): Uni<Int>
}

interface GetAccrualsUseCase {
    fun listAllAccruals(): Uni<List<InterestAccrual>>
    fun getAccruals(accountId: UUID, from: LocalDate?, to: LocalDate?): Uni<List<InterestAccrual>>
    fun getSummary(accountId: UUID, from: LocalDate, to: LocalDate): Uni<AccrualSummary>
    fun getCapitalizations(accountId: UUID): Uni<List<InterestCapitalization>>
}

interface ManageRateConfigUseCase {
    fun createConfig(config: InterestRateConfig): Uni<InterestRateConfig>
    fun getConfig(id: UUID): Uni<InterestRateConfig?>
    fun listConfigs(productId: String?): Uni<List<InterestRateConfig>>

    /** The rate that actually applies to [accountId] (account override, else product default), or
     *  null if the account earns no interest. Drives the app's per-account rate view. */
    fun effectiveRate(accountId: UUID, productId: String, date: LocalDate): Uni<InterestRateConfig?>
    fun deactivateConfig(id: UUID): Uni<InterestRateConfig>
}

/**
 * Additive, time-boxed rate adjustments (FLUX rewards "Deposit Rate Boost"). Separate from the
 * per-account override managed by [ManageRateConfigUseCase]: an override replaces the rate, an
 * adjustment adds a signed delta on top of it.
 */
interface ManageRateAdjustmentUseCase {
    /**
     * Records [adjustment] (validated by `RateAdjustmentPolicy`). Idempotent on `sourceRef`: a
     * replay with the same terms returns the existing row with `changed = false`; reusing the key
     * with different terms fails with `RateAdjustmentSourceRefConflictException`.
     */
    fun create(adjustment: RateAdjustment): Uni<RateAdjustmentResult>

    fun list(accountId: UUID): Uni<List<RateAdjustment>>

    /**
     * Cancels the adjustment (the compensation of [create]). Idempotent: cancelling an already
     * CANCELLED adjustment returns it unchanged. Forward-looking only — accruals already booked are
     * not recomputed.
     */
    fun cancel(accountId: UUID, adjustmentId: UUID, cancelledBy: String): Uni<RateAdjustmentResult>
}

/**
 * [changed] is true when this call created (create) or cancelled (cancel) the adjustment, false on
 * an idempotent replay. [effectiveAnnualRate] is the account's rate after every ACTIVE adjustment, as a decimal fraction,
 * on the date the use case resolved it for; null when no base rate resolves for the account (no
 * override and no accrual history from which to learn its product).
 */
data class RateAdjustmentResult(
    val adjustment: RateAdjustment,
    val changed: Boolean,
    val effectiveAnnualRate: BigDecimal?,
)
