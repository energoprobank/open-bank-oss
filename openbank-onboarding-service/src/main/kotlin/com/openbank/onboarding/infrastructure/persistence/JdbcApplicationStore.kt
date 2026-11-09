// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.persistence

import com.openbank.onboarding.application.port.out.ApplicationStore
import com.openbank.onboarding.application.port.out.ContactVerificationStore
import com.openbank.onboarding.application.port.out.LivenessStore
import com.openbank.onboarding.domain.model.ApplicationStatus
import com.openbank.onboarding.domain.model.ContactVerification
import com.openbank.onboarding.domain.model.IdentityMethod
import com.openbank.onboarding.domain.model.IdentityStatus
import com.openbank.onboarding.domain.model.IdentityVerification
import com.openbank.onboarding.domain.model.LivenessPolicy
import com.openbank.onboarding.domain.model.LivenessPurpose
import com.openbank.onboarding.domain.model.LivenessSession
import com.openbank.onboarding.domain.model.LivenessStatus
import com.openbank.onboarding.domain.model.OnboardingApplication
import jakarta.enterprise.context.ApplicationScoped
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The application tables (V5) over the JDBC datasource Flyway already uses. Plain blocking JDBC on
 * purpose: these calls come from `@Blocking` REST endpoints, never from a Vert.x event loop, and a
 * reactive Panache repository would need a Vert.x context that a REST worker thread does not carry.
 */
@ApplicationScoped
@Suppress("TooManyFunctions")
class JdbcApplicationStore(private val dataSource: DataSource) :
    ApplicationStore,
    ContactVerificationStore,
    LivenessStore {

    private fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use(block)

    private fun update(sql: String, vararg args: Any?): Int = withConnection { c ->
        c.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeUpdate()
        }
    }

    private fun <T> queryOne(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = withConnection { c ->
        c.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
        }
    }

    private fun bind(ps: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                is Instant -> ps.setTimestamp(i + 1, Timestamp.from(a))
                is Enum<*> -> ps.setString(i + 1, a.name)
                else -> ps.setObject(i + 1, a)
            }
        }
    }

    private fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()

    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

    // ---------------------------------------------------------------- contact verifications

    override fun insertContactVerification(v: ContactVerification) {
        update(
            """INSERT INTO contact_verifications (id, email, phone, email_code_hash, phone_code_hash, attempts,
               email_verified_at, phone_verified_at, prefill_attempts, expires_at, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            v.id, v.email, v.phone, v.emailCodeHash, v.phoneCodeHash, v.attempts, v.emailVerifiedAt, v.phoneVerifiedAt,
            v.prefillAttempts, v.expiresAt, v.createdAt,
        )
    }

    override fun contactVerification(id: UUID): ContactVerification? =
        queryOne("SELECT * FROM contact_verifications WHERE id = ?", id) { rs ->
            ContactVerification(
                id = rs.uuid("id"), email = rs.getString("email"), phone = rs.getString("phone"),
                emailCodeHash = rs.getString("email_code_hash"), phoneCodeHash = rs.getString("phone_code_hash"),
                attempts = rs.getInt("attempts"), emailVerifiedAt = rs.instant("email_verified_at"),
                phoneVerifiedAt = rs.instant("phone_verified_at"), prefillAttempts = rs.getInt("prefill_attempts"),
                expiresAt = rs.instant("expires_at")!!, createdAt = rs.instant("created_at")!!,
            )
        }

    override fun countContactVerifications(email: String, phone: String, since: Instant): Int = queryOne(
        "SELECT COUNT(*) FROM contact_verifications WHERE (email = ? OR phone = ?) AND created_at > ?",
        email,
        phone,
        since,
    ) { it.getInt(1) } ?: 0

    override fun updateContactVerification(v: ContactVerification) {
        update(
            """UPDATE contact_verifications SET attempts = ?, email_verified_at = ?, phone_verified_at = ?,
               prefill_attempts = ? WHERE id = ?""",
            v.attempts,
            v.emailVerifiedAt,
            v.phoneVerifiedAt,
            v.prefillAttempts,
            v.id,
        )
    }

    // ---------------------------------------------------------------- applications

    override fun application(partyId: UUID): OnboardingApplication? =
        queryOne("SELECT * FROM onboarding_applications WHERE party_id = ?", partyId) { rs ->
            OnboardingApplication(
                partyId = rs.uuid("party_id"),
                packageCode = rs.getString("package_code"),
                personalDetailsAt = rs.instant("personal_details_at"),
                taxResidencyAt = rs.instant("tax_residency_at"),
                identityVerifiedAt = rs.instant("identity_verified_at"),
                identityMethod = rs.getString("identity_method")?.let(IdentityMethod::valueOf),
                updatedAt = rs.instant("updated_at")!!,
                status = ApplicationStatus.valueOf(rs.getString("status")),
                pidDecision = rs.getString("pid_decision"),
                pidCaseId = rs.getObject("pid_case_id", UUID::class.java),
                firstPaymentReference = rs.getString("first_payment_reference"),
                firstPaymentJson = rs.getString("first_payment_json"),
                assessmentJson = rs.getString("assessment_json"),
                kycCaseId = rs.getString("kyc_case_id"),
                submittedAt = rs.instant("submitted_at"),
            )
        }

    override fun upsertApplication(a: OnboardingApplication) {
        update(
            """INSERT INTO onboarding_applications (party_id, package_code, personal_details_at, tax_residency_at,
               identity_verified_at, identity_method, updated_at, status, pid_decision, pid_case_id,
               first_payment_reference, first_payment_json, assessment_json, kyc_case_id, submitted_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (party_id) DO UPDATE SET package_code = EXCLUDED.package_code,
               personal_details_at = EXCLUDED.personal_details_at, tax_residency_at = EXCLUDED.tax_residency_at,
               identity_verified_at = EXCLUDED.identity_verified_at, identity_method = EXCLUDED.identity_method,
               updated_at = EXCLUDED.updated_at, status = EXCLUDED.status, pid_decision = EXCLUDED.pid_decision,
               pid_case_id = EXCLUDED.pid_case_id, first_payment_reference = EXCLUDED.first_payment_reference,
               first_payment_json = EXCLUDED.first_payment_json, assessment_json = EXCLUDED.assessment_json,
               kyc_case_id = EXCLUDED.kyc_case_id, submitted_at = EXCLUDED.submitted_at""",
            a.partyId, a.packageCode, a.personalDetailsAt, a.taxResidencyAt, a.identityVerifiedAt,
            a.identityMethod, a.updatedAt, a.status, a.pidDecision, a.pidCaseId, a.firstPaymentReference,
            a.firstPaymentJson, a.assessmentJson, a.kycCaseId, a.submittedAt,
        )
    }

    // ---------------------------------------------------------------- identity verifications

    override fun insertIdentityVerification(v: IdentityVerification) {
        update(
            """INSERT INTO identity_verifications (id, party_id, method, document_type, provider_ref, status,
               failure_reason, created_at, updated_at, oidc_state, oidc_verifier, oidc_nonce, document_id,
               evidence_json, needs_review) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            v.id, v.partyId, v.method, v.documentType, v.providerRef,
            v.status, v.failureReason, v.createdAt, v.updatedAt,
            v.oidcState, v.oidcVerifier, v.oidcNonce, v.documentId, v.evidenceJson, v.needsReview,
        )
    }

    override fun identityVerification(id: UUID): IdentityVerification? =
        queryOne("SELECT * FROM identity_verifications WHERE id = ?", id, map = ::verification)

    override fun identityVerificationByState(state: String): IdentityVerification? =
        queryOne("SELECT * FROM identity_verifications WHERE oidc_state = ?", state, map = ::verification)

    override fun identityVerifications(partyId: UUID): List<IdentityVerification> = withConnection { c ->
        val ps = c.prepareStatement("SELECT * FROM identity_verifications WHERE party_id = ? ORDER BY created_at")
        bind(ps, arrayOf(partyId))
        val rs = ps.executeQuery()
        buildList { while (rs.next()) add(verification(rs)) }
    }

    private fun verification(rs: ResultSet) = IdentityVerification(
        id = rs.uuid("id"),
        partyId = rs.uuid("party_id"), method = IdentityMethod.valueOf(rs.getString("method")),
        documentType = rs.getString("document_type"), providerRef = rs.getString("provider_ref"),
        status = IdentityStatus.valueOf(rs.getString("status")), failureReason = rs.getString("failure_reason"),
        createdAt = rs.instant("created_at")!!, updatedAt = rs.instant("updated_at")!!,
        oidcState = rs.getString("oidc_state"), oidcVerifier = rs.getString("oidc_verifier"),
        oidcNonce = rs.getString("oidc_nonce"), documentId = rs.getString("document_id"),
        evidenceJson = rs.getString("evidence_json"), needsReview = rs.getBoolean("needs_review"),
    )

    override fun updateIdentityVerification(v: IdentityVerification) {
        update(
            """UPDATE identity_verifications SET status = ?, failure_reason = ?, updated_at = ?, document_id = ?,
               evidence_json = ?, needs_review = ?, oidc_state = ? WHERE id = ?""",
            v.status, v.failureReason, v.updatedAt, v.documentId, v.evidenceJson, v.needsReview, v.oidcState, v.id,
        )
    }

    // ---------------------------------------------------------------- liveness

    override fun livenessPolicy(partyId: UUID): LivenessPolicy? =
        queryOne("SELECT * FROM liveness_policies WHERE party_id = ?", partyId) { rs ->
            LivenessPolicy(
                rs.uuid("party_id"),
                rs.getBoolean("enabled"),
                rs.getBigDecimal("threshold"),
                rs.getString("currency"),
                rs.instant("last_check_at"),
            )
        }

    override fun upsertLivenessPolicy(p: LivenessPolicy) {
        update(
            """INSERT INTO liveness_policies (party_id, enabled, threshold, currency, last_check_at)
               VALUES (?, ?, ?, ?, ?)
               ON CONFLICT (party_id) DO UPDATE SET enabled = EXCLUDED.enabled, threshold = EXCLUDED.threshold,
               currency = EXCLUDED.currency, last_check_at = EXCLUDED.last_check_at""",
            p.partyId,
            p.enabled,
            p.threshold,
            p.currency,
            p.lastCheckAt,
        )
    }

    override fun insertLivenessSession(s: LivenessSession) {
        update(
            """INSERT INTO liveness_sessions (id, party_id, purpose, provider_ref, status, payment_reference,
               consumed_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            s.id, s.partyId, s.purpose, s.providerRef, s.status, s.paymentReference, s.consumedAt, s.createdAt,
        )
    }

    override fun livenessSession(id: UUID): LivenessSession? =
        queryOne("SELECT * FROM liveness_sessions WHERE id = ?", id) { rs ->
            LivenessSession(
                id = rs.uuid("id"),
                partyId = rs.uuid("party_id"),
                purpose = LivenessPurpose.valueOf(rs.getString("purpose")),
                providerRef = rs.getString("provider_ref"),
                status = LivenessStatus.valueOf(rs.getString("status")),
                paymentReference = rs.getString("payment_reference"),
                consumedAt = rs.instant("consumed_at"),
                createdAt = rs.instant("created_at")!!,
            )
        }

    override fun updateLivenessSession(s: LivenessSession) {
        update("UPDATE liveness_sessions SET status = ? WHERE id = ?", s.status, s.id)
    }

    override fun consumeLivenessSession(id: UUID, at: Instant): Boolean =
        update("UPDATE liveness_sessions SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL", at, id) == 1
}
