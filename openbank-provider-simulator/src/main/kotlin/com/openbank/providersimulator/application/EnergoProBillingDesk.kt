// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.application

import com.openbank.providersimulator.domain.EnergoProBill
import com.openbank.providersimulator.domain.EnergoProBilling
import com.openbank.providersimulator.domain.EnergoProCustomer
import com.openbank.providersimulator.domain.NoticeOutcome
import com.openbank.providersimulator.domain.PaymentNotice
import com.openbank.providersimulator.domain.SupplyPoint
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/** Energo-Pro's billing system (NON-PRODUCTION): supply points, bills, and the bank's payment notices. */
@ApplicationScoped
class EnergoProBillingDesk @Inject constructor(private val simulator: ProviderSimulator, private val clock: Clock) {
    private val log = Logger.getLogger(EnergoProBillingDesk::class.java)

    /** Payment notices received (reference -> notice); a restart forgets them, as the bank's own record does not. */
    private val paymentNotices = ConcurrentHashMap<String, PaymentNotice>()

    fun supplyPoints(customerNumber: String): List<SupplyPoint>? =
        simulator.directory.find(customerNumber)?.let { listOf(EnergoProBilling.supplyPoint(it)) }

    fun bills(customerNumber: String): List<EnergoProBill>? =
        simulator.directory.find(customerNumber)?.let { EnergoProBilling.bills(it, today(), paymentNotices.keys) }

    /**
     * A bank tells Energo-Pro that its customer paid a bill. Matched by reference; an amount below the
     * bill's is refused (a partial payment is a matter for Energo-Pro's collections, not this notice).
     * The same reference twice answers the first notice again.
     */
    fun notePayment(notice: PaymentNotice): NoticeOutcome {
        paymentNotices[notice.reference]?.let { return NoticeOutcome.Accepted(billFor(notice.reference)!!, it) }
        val bill = billFor(notice.reference) ?: return NoticeOutcome.UnknownReference
        if (notice.currency != bill.currency || notice.amount < bill.amount) return NoticeOutcome.AmountMismatch(bill)
        paymentNotices[notice.reference] = notice
        log.infof("Energo-Pro: bill %s paid (%s %s)", bill.id, notice.amount, notice.currency)
        return NoticeOutcome.Accepted(billFor(notice.reference)!!, notice)
    }

    private fun billFor(reference: String): EnergoProBill? {
        val digits = reference.removePrefix("EP").take(EnergoProCustomer.NUMBER_DIGITS)
        val customer = simulator.directory.find("EP-$digits") ?: return null
        return EnergoProBilling.bills(customer, today(), paymentNotices.keys).firstOrNull { it.reference == reference }
    }

    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
}
