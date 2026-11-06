// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.infrastructure.persistence

import com.openbank.everyday.application.CaseStore
import com.openbank.everyday.application.Mandate
import com.openbank.everyday.application.MoneyStore
import com.openbank.everyday.application.PaymentExport
import com.openbank.everyday.application.Protection
import com.openbank.everyday.application.RecallCase
import com.openbank.everyday.application.SubscriptionChoice
import com.openbank.everyday.application.TopUp
import jakarta.inject.Singleton
import java.sql.Date
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Singleton // no client proxy: a subclass of JdbcSupport has no no-args constructor to proxy
class JdbcMoneyStore(dataSource: DataSource) :
    JdbcSupport(dataSource),
    MoneyStore {

    override fun mandates(partyId: UUID): List<Mandate> = query(
        "SELECT * FROM direct_debit_mandates WHERE party_id = ? ORDER BY created_at DESC",
        partyId,
        map = ::mandate,
    )

    override fun insertMandate(m: Mandate) {
        update(
            """INSERT INTO direct_debit_mandates (mandate_id, party_id, scheme, creditor_name, creditor_reference,
                 debtor_account_id, limit_amount, currency, frequency, status, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            m.id, m.partyId, m.scheme, m.creditorName, m.creditorReference, m.debtorAccountId, m.limit, m.currency,
            m.frequency, m.status, m.createdAt,
        )
    }

    override fun cancelMandate(partyId: UUID, id: UUID, at: Instant): Mandate? {
        update(
            "UPDATE direct_debit_mandates SET status = 'CANCELLED', cancelled_at = ? WHERE mandate_id = ? AND party_id = ? AND status = 'ACTIVE'",
            at,
            id,
            partyId,
        )
        return queryOne(
            "SELECT * FROM direct_debit_mandates WHERE mandate_id = ? AND party_id = ?",
            id,
            partyId,
            map = ::mandate,
        )
    }

    override fun subscriptionChoices(partyId: UUID): Map<String, SubscriptionChoice> =
        query("SELECT * FROM subscription_choices WHERE party_id = ?", partyId) { rs ->
            rs.getString("subscription_id") to
                SubscriptionChoice(rs.getBoolean("cancel_requested"), rs.getBoolean("reminder_enabled"))
        }.toMap()

    override fun saveSubscriptionChoice(
        partyId: UUID,
        subscriptionId: String,
        choice: SubscriptionChoice,
        at: Instant,
    ) {
        update(
            """INSERT INTO subscription_choices (party_id, subscription_id, cancel_requested, reminder_enabled, updated_at)
               VALUES (?,?,?,?,?) ON CONFLICT (party_id, subscription_id) DO UPDATE SET
               cancel_requested = EXCLUDED.cancel_requested, reminder_enabled = EXCLUDED.reminder_enabled, updated_at = EXCLUDED.updated_at""",
            partyId,
            subscriptionId,
            choice.cancelRequested,
            choice.reminderEnabled,
            at,
        )
    }

    override fun insertExport(e: PaymentExport, at: Instant) {
        update(
            """INSERT INTO payment_exports (export_id, party_id, account_id, format, direction, from_date, to_date, status,
                 file_name, content_type, content, download_token, expires_at, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            e.id, e.partyId, e.accountId, e.format, e.direction, Date.valueOf(e.from), Date.valueOf(e.to), e.status,
            e.fileName, e.contentType, e.content, e.token, e.expiresAt, at,
        )
    }

    override fun export(partyId: UUID, id: UUID): PaymentExport? =
        queryOne("SELECT * FROM payment_exports WHERE export_id = ? AND party_id = ?", id, partyId, map = ::export)

    override fun exportByToken(token: String): PaymentExport? =
        queryOne("SELECT * FROM payment_exports WHERE download_token = ?", token, map = ::export)

    private fun mandate(rs: ResultSet) = Mandate(
        rs.uuid("mandate_id"), rs.uuid("party_id"), rs.getString("scheme"), rs.getString("creditor_name"),
        rs.getString("creditor_reference"), rs.uuid("debtor_account_id"), rs.getBigDecimal("limit_amount"),
        rs.getString("currency"), rs.getString("frequency"), rs.getString("status"), rs.instant("created_at")!!,
    )

    private fun export(rs: ResultSet) = PaymentExport(
        rs.uuid(
            "export_id",
        ),
        rs.uuid("party_id"), rs.uuid("account_id"), rs.getString("format"), rs.getString("direction"),
        rs.getDate("from_date").toLocalDate(), rs.getDate("to_date").toLocalDate(), rs.getString("status"),
        rs.getString("file_name"), rs.getString("content_type"), rs.getBytes("content"), rs.getString("download_token"),
        rs.instant("expires_at")!!,
    )
}

@Singleton // no client proxy: a subclass of JdbcSupport has no no-args constructor to proxy
class JdbcCaseStore(dataSource: DataSource) :
    JdbcSupport(dataSource),
    CaseStore {

    override fun recallFor(transactionId: UUID): RecallCase? =
        queryOne("SELECT * FROM recall_cases WHERE transaction_id = ?", transactionId) { rs ->
            RecallCase(
                rs.uuid("case_id"),
                rs.uuid("party_id"),
                rs.uuid("transaction_id"),
                rs.getString("reference"),
                rs.getString("reason"),
                rs.getString("note"),
                rs.getString("status"),
                rs.getDate("answer_by").toLocalDate(),
            )
        }

    override fun insertRecall(c: RecallCase, at: Instant) {
        update(
            """INSERT INTO recall_cases (case_id, party_id, transaction_id, reference, reason, note, status, answer_by, created_at)
               VALUES (?,?,?,?,?,?,?,?,?)""",
            c.id, c.partyId, c.transactionId, c.reference, c.reason, c.note, c.status, Date.valueOf(c.answerBy), at,
        )
    }

    override fun topUp(partyId: UUID, id: UUID): TopUp? =
        queryOne("SELECT * FROM top_ups WHERE top_up_id = ? AND party_id = ?", id, partyId) { rs ->
            TopUp(
                rs.uuid("top_up_id"), rs.uuid("party_id"), rs.uuid("account_id"), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getBigDecimal("fee"), rs.getString("status"), rs.getString("acquirer_ref"),
                rs.getString("checkout_url"), rs.getObject("credited_transaction_id", UUID::class.java),
            )
        }

    override fun insertTopUp(t: TopUp, at: Instant) {
        update(
            """INSERT INTO top_ups (top_up_id, party_id, account_id, amount, currency, fee, status, acquirer_ref, checkout_url,
                 created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            t.id, t.partyId, t.accountId, t.amount, t.currency, t.fee, t.status, t.acquirerRef, t.checkoutUrl, at, at,
        )
    }

    override fun updateTopUp(t: TopUp, at: Instant) {
        update(
            "UPDATE top_ups SET status = ?, credited_transaction_id = ?, updated_at = ? WHERE top_up_id = ?",
            t.status,
            t.creditedTransactionId,
            at,
            t.id,
        )
    }

    override fun protection(partyId: UUID): Protection? =
        queryOne("SELECT * FROM payment_protection WHERE party_id = ?", partyId) { rs ->
            Protection(
                rs.getBoolean("hold_unusual"),
                rs.getBoolean("street_mode"),
                rs.getBigDecimal("street_mode_limit"),
                rs.getString("trusted_approver_name"),
                rs.getString("trusted_approver_phone"),
            )
        }

    override fun saveProtection(partyId: UUID, p: Protection, at: Instant) {
        update(
            """INSERT INTO payment_protection (party_id, hold_unusual, street_mode, street_mode_limit, trusted_approver_name,
                 trusted_approver_phone, updated_at) VALUES (?,?,?,?,?,?,?) ON CONFLICT (party_id) DO UPDATE SET
                 hold_unusual = EXCLUDED.hold_unusual, street_mode = EXCLUDED.street_mode,
                 street_mode_limit = EXCLUDED.street_mode_limit, trusted_approver_name = EXCLUDED.trusted_approver_name,
                 trusted_approver_phone = EXCLUDED.trusted_approver_phone, updated_at = EXCLUDED.updated_at""",
            partyId,
            p.holdUnusual,
            p.streetMode,
            p.streetModeLimit,
            p.trustedApproverName,
            p.trustedApproverPhone,
            at,
        )
    }
}
