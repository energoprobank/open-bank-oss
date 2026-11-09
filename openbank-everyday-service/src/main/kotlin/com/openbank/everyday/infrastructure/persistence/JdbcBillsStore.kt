// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.infrastructure.persistence

import com.openbank.everyday.application.BillPaymentStore
import com.openbank.everyday.application.BillsStore
import com.openbank.everyday.domain.BillPayment
import com.openbank.everyday.domain.BillReminders
import com.openbank.everyday.domain.Biller
import com.openbank.everyday.domain.ElectricityLink
import jakarta.inject.Singleton
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Singleton // no client proxy: a subclass of JdbcSupport has no no-args constructor to proxy
@Suppress("TooManyFunctions") // one table-per-concern store, each method one SQL statement
class JdbcBillsStore(dataSource: DataSource) :
    JdbcSupport(dataSource),
    BillsStore,
    BillPaymentStore {

    override fun billers(market: String?): List<Biller> = query(
        "SELECT * FROM billers WHERE active AND (CAST(? AS VARCHAR) IS NULL OR market = ?) ORDER BY sort_order, name",
        market,
        market,
    ) { rs ->
        Biller(
            rs.getString("biller_id"),
            rs.getString("market"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getString("iban"),
            rs.getString("account_number"),
            rs.getString("currency"),
            rs.getBoolean("connected"),
        )
    }

    override fun electricityLink(partyId: UUID): ElectricityLink? =
        queryOne("SELECT * FROM electricity_links WHERE party_id = ?", partyId) { rs ->
            ElectricityLink(
                rs.uuid("party_id"),
                rs.getString("customer_number"),
                rs.getString("market"),
                rs.instant("linked_at")!!,
            )
        }

    override fun saveElectricityLink(link: ElectricityLink) {
        update(
            """INSERT INTO electricity_links (party_id, customer_number, market, linked_at) VALUES (?, ?, ?, ?)
               ON CONFLICT (party_id) DO UPDATE SET customer_number = EXCLUDED.customer_number,
                 market = EXCLUDED.market, linked_at = EXCLUDED.linked_at""",
            link.partyId,
            link.customerNumber,
            link.market,
            link.linkedAt,
        )
    }

    override fun deleteElectricityLink(partyId: UUID) {
        update("DELETE FROM electricity_links WHERE party_id = ?", partyId)
    }

    override fun partyLinkedTo(customerNumber: String): UUID? =
        queryOne("SELECT party_id FROM electricity_links WHERE customer_number = ?", customerNumber) {
            it.uuid("party_id")
        }

    override fun insertBillPayment(p: BillPayment): Boolean = update(
        """INSERT INTO bill_payments (payment_id, party_id, biller_id, reference, amount, currency, paid_at)
           VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (payment_id) DO NOTHING""",
        p.paymentId,
        p.partyId,
        p.billerId,
        p.reference,
        p.amount,
        p.currency,
        p.paidAt,
    ) == 1

    override fun billPayments(billerId: String, references: Collection<String>): List<BillPayment> =
        if (references.isEmpty()) {
            emptyList()
        } else {
            // One text[] parameter (pgjdbc binds a String[] as text[]).
            query(
                "SELECT * FROM bill_payments WHERE biller_id = ? AND reference = ANY(?) ORDER BY paid_at, payment_id",
                billerId,
                references.toTypedArray(),
                map = ::payment,
            )
        }

    override fun pendingNotices(limit: Int): List<BillPayment> = query(
        """SELECT p.* FROM bill_payments p JOIN billers b ON b.biller_id = p.biller_id
           WHERE p.notice_sent_at IS NULL AND b.connected ORDER BY p.paid_at LIMIT ?""",
        limit,
        map = ::payment,
    )

    override fun noticeSent(paymentId: UUID, at: Instant) {
        update(
            "UPDATE bill_payments SET notice_sent_at = ?, notice_attempts = notice_attempts + 1, notice_error = NULL WHERE payment_id = ?",
            at,
            paymentId,
        )
    }

    override fun noticeFailed(paymentId: UUID, error: String) {
        update(
            "UPDATE bill_payments SET notice_attempts = notice_attempts + 1, notice_error = ? WHERE payment_id = ?",
            error,
            paymentId,
        )
    }

    override fun reminders(partyId: UUID): BillReminders? =
        queryOne("SELECT * FROM bill_reminders WHERE party_id = ?", partyId) { rs ->
            BillReminders(rs.uuid("party_id"), rs.getBoolean("enabled"), rs.getInt("days_before"))
        }

    override fun saveReminders(r: BillReminders, at: Instant) {
        update(
            """INSERT INTO bill_reminders (party_id, enabled, days_before, updated_at) VALUES (?, ?, ?, ?)
               ON CONFLICT (party_id) DO UPDATE SET enabled = EXCLUDED.enabled,
                 days_before = EXCLUDED.days_before, updated_at = EXCLUDED.updated_at""",
            r.partyId,
            r.enabled,
            r.daysBefore,
            at,
        )
    }

    private fun payment(rs: ResultSet) = BillPayment(
        rs.uuid("payment_id"), rs.uuid("party_id"), rs.getString("biller_id"), rs.getString("reference"),
        rs.getBigDecimal("amount"), rs.getString("currency"), rs.instant("paid_at")!!, rs.instant("notice_sent_at"),
        rs.getInt("notice_attempts"),
    )
}
