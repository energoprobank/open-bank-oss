// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.application

import com.openbank.everyday.domain.Bill
import com.openbank.everyday.domain.BillPayment
import com.openbank.everyday.domain.BillReminders
import com.openbank.everyday.domain.Biller
import com.openbank.everyday.domain.ElectricityLink
import com.openbank.everyday.domain.NameMatch
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

// ---------------------------------------------------------------- ports

/** The Energo-Pro customer and billing system (locally: openbank-provider-simulator). */
interface EnergoProPort {
    /** The holder's name and market, or null when Energo-Pro has no such customer. */
    fun customer(customerNumber: String): EnergoProCustomer?

    /** Newest first; null when Energo-Pro has no such customer. */
    fun bills(customerNumber: String): List<Bill>?

    /** Tells Energo-Pro the bill with [reference] was paid. Throws when Energo-Pro cannot be reached. */
    fun notifyPaid(reference: String, amount: BigDecimal, currency: String, bankReference: String)
}

data class EnergoProCustomer(
    val customerNumber: String,
    val market: String,
    val firstName: String,
    val lastName: String,
)

/** party-service: the customer's name and market. */
interface PartyPort {
    fun party(partyId: UUID): PartyFacts?
}

data class PartyFacts(val partyId: UUID, val legalName: String, val market: String)

interface BillsStore {
    fun billers(market: String?): List<Biller>

    fun electricityLink(partyId: UUID): ElectricityLink?

    fun saveElectricityLink(link: ElectricityLink)

    fun deleteElectricityLink(partyId: UUID)

    /** The party linked to an Energo-Pro [customerNumber], if any. */
    fun partyLinkedTo(customerNumber: String): UUID?

    fun reminders(partyId: UUID): BillReminders?

    fun saveReminders(r: BillReminders, at: Instant)
}

/** Bill payments the bank made, and the notices to the biller. */
interface BillPaymentStore {
    /** False when the payment was already recorded (an event delivered twice). */
    fun insertBillPayment(p: BillPayment): Boolean

    /**
     * The bank's payments to [billerId] of these bill references. A biller's reference identifies one
     * bill of one linked customer number, so this does not depend on which party a payment event named.
     */
    fun billPayments(billerId: String, references: Collection<String>): List<BillPayment>

    fun pendingNotices(limit: Int): List<BillPayment>

    fun noticeSent(paymentId: UUID, at: Instant)

    fun noticeFailed(paymentId: UUID, error: String)
}

// ---------------------------------------------------------------- views

data class ElectricityView(
    val linked: Boolean,
    val customerNumber: String?,
    val supplierName: String?,
    val currentBill: BillWithStatus?,
    val nextEstimate: BigDecimal?,
)

/**
 * A bill with the status the customer should see: PAID as soon as the bank paid it, before Energo-Pro
 * hears of it. [paymentId] is the bank's payment of it — what a bill-cashback coupon is used on.
 */
data class BillWithStatus(val bill: Bill, val status: String, val paymentId: UUID? = null)

data class PaymentSeen(
    val paymentId: UUID,
    val partyId: UUID,
    val creditorAccount: String,
    val reference: String?,
    val amount: BigDecimal,
    val currency: String,
    val at: Instant,
)

/**
 * Bills and the Energo-Pro electricity account (experience v2 step 3). Energo-Pro (the distributor) and
 * the bank are independent companies: the bank shows Energo-Pro bills only to a customer who linked
 * their own Energo-Pro customer number, and pays them as an ordinary domestic payment the customer
 * signs; this service sees that payment, keeps its own record and tells Energo-Pro.
 */
@ApplicationScoped
class BillsService(
    private val store: BillsStore,
    private val payments: BillPaymentStore,
    private val energoPro: EnergoProPort,
    private val parties: PartyPort,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(BillsService::class.java)

    fun billers(partyId: UUID): List<Biller> = store.billers(parties.party(partyId)?.market ?: "CZ")

    fun electricity(partyId: UUID): ElectricityView {
        val link = store.electricityLink(partyId) ?: return ElectricityView(false, null, null, null, null)
        val bills = billsWithStatus(link)
        val supplier = store.billers(link.market).firstOrNull { it.connected }?.name
        val current = bills.firstOrNull()
        // Next month's bill: the average of the last three, as Energo-Pro's advance would be.
        val estimate = bills.take(ESTIMATE_MONTHS).map { it.bill.amount }.takeIf { it.isNotEmpty() }
            ?.let {
                it.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(it.size), 0, java.math.RoundingMode.HALF_UP)
            }
        return ElectricityView(true, link.customerNumber, supplier, current, estimate)
    }

    fun bills(partyId: UUID): List<BillWithStatus> {
        val link = store.electricityLink(partyId) ?: return emptyList()
        return billsWithStatus(link)
    }

    /**
     * Links an Energo-Pro customer number to the customer. Energo-Pro must know the number and its record
     * must carry the customer's own name; the customer must agree to the data sharing.
     */
    fun link(partyId: UUID, customerNumber: String, consent: Boolean): ElectricityView {
        require(consent) { "data_sharing_consent must be true: Energo-Pro shares your bills only with your consent" }
        val number = customerNumber.trim().uppercase()
        require(NUMBER.matches(number)) { "customer_number must look like EP-1234567" }
        val record = ownRecord(partyId, number)
        store.partyLinkedTo(number)?.takeIf { it != partyId }?.let {
            throw ResourceConflictException("This Energo-Pro account is linked to another customer", "ALREADY_LINKED")
        }
        store.saveElectricityLink(ElectricityLink(partyId, number, record.market, clock.instant()))
        return electricity(partyId)
    }

    fun unlink(partyId: UUID) = store.deleteElectricityLink(partyId)

    /** Energo-Pro's record for [number], only when it carries the customer's own name. */
    private fun ownRecord(partyId: UUID, number: String): EnergoProCustomer {
        val party = parties.party(partyId)
        val record = energoPro.customer(number)
        // One answer for "no such number", "not yours" and "no such customer": no oracle for whose number exists.
        return record?.takeIf { party != null && NameMatch.same("${it.firstName} ${it.lastName}", party.legalName) }
            ?: throw ResourceNotFoundException("Energo-Pro has no customer with this number and your name", "NO_MATCH")
    }

    /**
     * A domestic payment the bank executed. When its creditor is a biller and its reference is a bill
     * reference, it is a bill payment: recorded, and Energo-Pro is told (now, or by the retry job).
     * Returns true when it was a bill payment.
     */
    fun paymentSeen(p: PaymentSeen): Boolean {
        val reference = p.reference?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val biller = store.billers(null).firstOrNull { it.receives(p.creditorAccount) } ?: return false
        val payment = BillPayment(p.paymentId, p.partyId, biller.id, reference, p.amount, p.currency, p.at, null, 0)
        if (!payments.insertBillPayment(payment)) return true
        log.infof(
            "Bill payment %s to %s (reference %s) recorded for party %s",
            p.paymentId,
            biller.id,
            reference,
            p.partyId,
        )
        if (biller.connected) sendNotice(payment)
        return true
    }

    /** Retries notices Energo-Pro has not acknowledged yet. Returns how many went through. */
    fun retryNotices(): Int = payments.pendingNotices(RETRY_BATCH).count { sendNotice(it) }

    private fun sendNotice(p: BillPayment): Boolean = try {
        energoPro.notifyPaid(p.reference, p.amount, p.currency, p.paymentId.toString())
        payments.noticeSent(p.paymentId, clock.instant())
        true
    } catch (
        @Suppress("TooGenericExceptionCaught") e: RuntimeException,
    ) {
        // Energo-Pro unreachable or refusing: keep the payment, retry later. The customer's bill shows
        // PAID from the bank's own record either way.
        log.warnf("Energo-Pro notice for payment %s failed: %s", p.paymentId, e.message)
        payments.noticeFailed(p.paymentId, (e.message ?: e.javaClass.simpleName).take(MAX_ERROR))
        false
    }

    private fun billsWithStatus(link: ElectricityLink): List<BillWithStatus> {
        val bills = energoPro.bills(link.customerNumber) ?: return emptyList()
        val biller =
            store.billers(link.market).firstOrNull { it.connected }
                ?: return bills.map { BillWithStatus(it, it.status) }
        val paid = payments.billPayments(biller.id, bills.map { it.reference }).associateBy { it.reference }
        return bills.map { bill ->
            val payment = paid[bill.reference]
            BillWithStatus(bill, if (payment != null) "PAID" else bill.status, payment?.paymentId)
        }
    }

    companion object {
        val NUMBER = Regex("^EP-\\d{7}$")
        private const val ESTIMATE_MONTHS = 3
        private const val RETRY_BATCH = 50
        private const val MAX_ERROR = 200
    }
}

/** Bill reminders: on or off, and how many days before the due date. */
@ApplicationScoped
class BillRemindersService(private val store: BillsStore, private val clock: Clock) {
    fun reminders(partyId: UUID): BillReminders =
        store.reminders(partyId) ?: BillReminders(partyId, false, DEFAULT_DAYS)

    fun save(partyId: UUID, enabled: Boolean, daysBefore: Int): BillReminders {
        require(daysBefore in 0..MAX_DAYS_BEFORE) { "days_before must be between 0 and $MAX_DAYS_BEFORE" }
        return BillReminders(partyId, enabled, daysBefore).also { store.saveReminders(it, clock.instant()) }
    }

    private companion object {
        const val DEFAULT_DAYS = 3
        const val MAX_DAYS_BEFORE = 14
    }
}
