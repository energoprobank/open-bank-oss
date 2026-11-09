// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.domain

import java.time.LocalDate
import java.util.Locale

enum class DocumentType { ID_CARD, PASSPORT, RESIDENCE_PERMIT }

/** What an identity document says. Its printed zone (OCR), MRZ and chip DG1 agree with each other. */
data class DocumentFacts(
    val givenName: String,
    val familyName: String,
    val dateOfBirth: LocalDate?,
    val sex: Sex?,
    val nationalId: String?,
    val documentNumber: String,
    val issuingCountry: String,
    val nationality: String,
    val expiryDate: LocalDate,
)

/** The chip read: DG1 (the data page), and passive authentication of the security object (SOD). */
data class ChipReading(
    val dg1: DocumentFacts,
    val passiveAuthenticationValid: Boolean,
    val signer: String,
    val detail: String,
)

data class SelfieFindings(val livenessScore: Double, val livenessPassed: Boolean, val faceMatchScore: Double)

/** Who signed in at their bank, as Bank iD's claims describe them. */
data class BankIdIdentity(
    val sub: String,
    val givenName: String,
    val familyName: String,
    val birthdate: LocalDate?,
    val sex: Sex?,
    val birthNumber: String?,
    val nationalities: List<String>,
)

/**
 * What the identity-verification vendor finds: the document, its chip, the selfie. Pure and
 * deterministic — the caller passes today's date and the applicant's scenarios; the document number is
 * derived from the national id, so a document scan and a register query agree without sharing state.
 */
object DocumentFindings {
    const val LIVENESS_OK = 0.97
    const val LIVENESS_FAILED = 0.12
    const val LIVENESS_THRESHOLD = 0.5
    const val FACE_OK = 0.93
    const val FACE_MISMATCH = 0.42
    const val FACE_GREY = 0.71
    const val DOCUMENT_VALID_YEARS = 6L
    const val EXPIRED_MONTHS_AGO = 2L
    const val UNDERAGE_YEARS = 16L
    const val CSCA_SIGNER_CZ = "CSCA-CZ 2021"
    const val CSCA_SIGNER_BG = "CSCA-BG 2020"
    private const val ID_CARD_DIGITS = 9
    private const val CZ_PASSPORT_DIGITS = 8
    private const val PERMIT_DIGITS = 7

    /** The number a person's [type] document carries: a stable function of their national id. */
    fun documentNumber(applicant: Applicant, type: DocumentType, country: String): String {
        val key = NationalIds.canonical(applicant.nationalId)
            ?: StableHash.lowercaseKey(applicant.givenName, applicant.familyName, applicant.dateOfBirth.orEmpty())
        return documentNumber(key, type, country)
    }

    fun documentNumber(key: String, type: DocumentType, country: String): String {
        val c = country.uppercase(Locale.ROOT)
        val seed = "$c|$type|$key"
        return when (type) {
            DocumentType.ID_CARD -> StableHash.digits(seed, ID_CARD_DIGITS)
            DocumentType.PASSPORT ->
                StableHash.digits(seed, if (c == NationalIds.CZ) CZ_PASSPORT_DIGITS else ID_CARD_DIGITS)
            DocumentType.RESIDENCE_PERMIT -> "RP" + StableHash.digits(seed, PERMIT_DIGITS)
        }
    }

    fun normaliseDocumentNumber(raw: String): String = raw.filterNot(Char::isWhitespace).uppercase(Locale.ROOT)

    fun expiry(scenarios: Set<IdentityScenario>, today: LocalDate): LocalDate =
        if (IdentityScenario.EXPIRED_DOCUMENT in scenarios) {
            today.minusMonths(EXPIRED_MONTHS_AGO)
        } else {
            today.plusYears(DOCUMENT_VALID_YEARS)
        }

    fun document(
        applicant: Applicant,
        type: DocumentType,
        issuingCountry: String,
        scenarios: Set<IdentityScenario>,
        today: LocalDate,
    ): DocumentFacts {
        val country = issuingCountry.trim().uppercase(Locale.ROOT)
        val sex = People.sexOf(applicant, country)
        return DocumentFacts(
            givenName = applicant.givenName.trim(),
            familyName = if (IdentityScenario.OCR_NAME_DIFFERS in scenarios) {
                People.otherPerson(applicant, country, sex).second
            } else {
                applicant.familyName.trim()
            },
            dateOfBirth = if (IdentityScenario.UNDERAGE_DOCUMENT in scenarios) {
                today.minusYears(UNDERAGE_YEARS)
            } else {
                People.dateOfBirth(applicant, country)
            },
            sex = sex,
            nationalId = NationalIds.canonical(applicant.nationalId) ?: applicant.nationalId?.trim(),
            documentNumber = documentNumber(applicant, type, country),
            issuingCountry = country,
            nationality = country,
            expiryDate = expiry(scenarios, today),
        )
    }

    fun chip(document: DocumentFacts, scenarios: Set<IdentityScenario>): ChipReading {
        val signer = if (document.issuingCountry == NationalIds.BG) CSCA_SIGNER_BG else CSCA_SIGNER_CZ
        return if (IdentityScenario.CHIP_FORGED in scenarios) {
            ChipReading(document, false, "unknown", "SOD signature does not chain to any trusted CSCA")
        } else {
            ChipReading(document, true, signer, "SOD signature valid; DG1 and DG2 hashes match")
        }
    }

    fun selfie(scenarios: Set<IdentityScenario>): SelfieFindings {
        val liveness = if (IdentityScenario.LIVENESS_FAIL in scenarios) LIVENESS_FAILED else LIVENESS_OK
        val face = when {
            IdentityScenario.FACE_MISMATCH in scenarios -> FACE_MISMATCH
            IdentityScenario.FACE_GREY in scenarios -> FACE_GREY
            else -> FACE_OK
        }
        return SelfieFindings(liveness, liveness >= LIVENESS_THRESHOLD, face)
    }
}
