// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.domain

import java.time.Duration
import java.time.Instant
import java.util.Locale

/** What a verification proves: an identity document (photo, chip, selfie, face match), Bank iD, or liveness alone. */
enum class VerificationKind(val checks: List<CheckType>) {
    DOCUMENT_SCAN(listOf(CheckType.DOCUMENT, CheckType.CHIP, CheckType.LIVENESS, CheckType.FACE_MATCH)),
    BANK_ID(listOf(CheckType.BANK_ID)),
    LIVENESS(listOf(CheckType.LIVENESS)),
}

enum class CheckType { DOCUMENT, CHIP, LIVENESS, FACE_MATCH, BANK_ID }

enum class CheckStatus { PENDING, PASSED, FAILED }

enum class VerificationStatus { IN_PROGRESS, VERIFIED, FAILED }

/** The person the bank asks about: what the provider compares the document or bank identity with. */
data class Applicant(val givenName: String, val familyName: String, val dateOfBirth: String?, val nationalId: String?)

/** One check of a verification, as the provider reports it. */
data class Check(val type: CheckType, val status: CheckStatus)

/** What the provider read and confirmed, once verified (Bank iD: the bank's record; scan: the document). */
data class VerifiedIdentity(
    val givenName: String,
    val familyName: String,
    val dateOfBirth: String?,
    val nationalId: String?,
)

/**
 * A provider session. Progress is a pure function of the time since [startedAt]: each check passes
 * [stepSeconds] after the previous one, which is how a vendor's asynchronous result looks to the bank.
 * An applicant whose family name starts with [FAIL_MARKER] fails the last check — the deterministic
 * way to drive the unhappy path in tests and demos.
 */
data class Verification(
    val id: String,
    val kind: VerificationKind,
    val reference: String,
    val applicant: Applicant,
    val documentType: String?,
    val startedAt: Instant,
    val stepSeconds: Long,
) {
    private val failing: Boolean get() = applicant.familyName.trim().lowercase(Locale.ROOT).startsWith(FAIL_MARKER)

    fun checksAt(now: Instant): List<Check> {
        val elapsed = Duration.between(startedAt, now).seconds
        return kind.checks.mapIndexed { index, type ->
            val due = (index + 1) * stepSeconds
            val status = when {
                elapsed < due -> CheckStatus.PENDING
                failing && index == kind.checks.lastIndex -> CheckStatus.FAILED
                else -> CheckStatus.PASSED
            }
            Check(type, status)
        }
    }

    fun statusAt(now: Instant): VerificationStatus {
        val checks = checksAt(now)
        return when {
            checks.any { it.status == CheckStatus.FAILED } -> VerificationStatus.FAILED
            checks.all { it.status == CheckStatus.PASSED } -> VerificationStatus.VERIFIED
            else -> VerificationStatus.IN_PROGRESS
        }
    }

    /** The confirmed identity, only once verified, and never for a liveness-only check (it proves presence, not who). */
    fun identityAt(now: Instant): VerifiedIdentity? =
        if (kind != VerificationKind.LIVENESS && statusAt(now) == VerificationStatus.VERIFIED) {
            VerifiedIdentity(applicant.givenName, applicant.familyName, applicant.dateOfBirth, applicant.nationalId)
        } else {
            null
        }

    companion object {
        const val FAIL_MARKER = "failidv"
    }
}

/** A message the OTP gateway delivered (e-mail or SMS). Kept so a sandbox can read what was sent. */
data class DeliveredMessage(
    val id: String,
    val channel: Channel,
    val recipient: String,
    val text: String,
    val sentAt: Instant,
) {
    enum class Channel { EMAIL, SMS }
}
