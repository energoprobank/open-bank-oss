// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.application

import com.openbank.providersimulator.domain.Applicant
import com.openbank.providersimulator.domain.ChipReading
import com.openbank.providersimulator.domain.DocumentFacts
import com.openbank.providersimulator.domain.DocumentFindings
import com.openbank.providersimulator.domain.DocumentType
import com.openbank.providersimulator.domain.SelfieFindings
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A document the vendor scanned: what it read, and whether the chip has been read since. */
data class ScannedDocument(
    val id: String,
    val reference: String,
    val type: DocumentType,
    val applicant: Applicant,
    val facts: DocumentFacts,
    val scannedAt: Instant,
    val chipRead: Boolean = false,
)

data class Selfie(
    val id: String,
    val reference: String,
    val documentId: String,
    val findings: SelfieFindings,
    val against: String,
)

/**
 * The identity-verification vendor's step-by-step API (NON-PRODUCTION): the document photo (OCR +
 * MRZ), the NFC chip (DG1 + passive authentication), the selfie (liveness + face match). What it finds
 * is [DocumentFindings] over the applicant's scenarios; documents live in memory for two hours.
 */
@ApplicationScoped
class IdvDocumentDesk @Inject constructor(private val clock: Clock, private val scenarios: ScenarioBook) {
    private val documents = ConcurrentHashMap<String, ScannedDocument>()

    fun scan(reference: String, type: DocumentType, issuingCountry: String, applicant: Applicant): ScannedDocument {
        require(reference.isNotBlank() && reference.length <= ProviderSimulator.MAX_REFERENCE) {
            "reference must be 1..${ProviderSimulator.MAX_REFERENCE} characters"
        }
        val country = issuingCountry.trim().uppercase(Locale.ROOT)
        require(COUNTRY.matches(country)) { "issuing_country is an ISO 3166 alpha-2 code" }
        require(applicant.givenName.isNotBlank() && applicant.familyName.isNotBlank()) {
            "applicant.given_name and applicant.family_name are required"
        }
        require(applicant.givenName.length <= MAX_NAME && applicant.familyName.length <= MAX_NAME) {
            "applicant names are too long"
        }
        evictExpired()
        scenarios.remember(applicant)
        val found = scenarios.scenariosFor(applicant.nationalId)
        val facts = DocumentFindings.document(applicant, type, country, found, today())
        val doc = ScannedDocument(UUID.randomUUID().toString(), reference, type, applicant, facts, clock.instant())
        documents[doc.id] = doc
        return doc
    }

    fun document(id: String): ScannedDocument? = documents[id]

    /** The chip read of a scanned document; null for an unknown one. */
    fun readChip(id: String): Pair<ScannedDocument, ChipReading>? {
        val doc = documents.computeIfPresent(id) { _, d -> d.copy(chipRead = true) } ?: return null
        return doc to DocumentFindings.chip(doc.facts, scenarios.scenariosFor(doc.applicant.nationalId))
    }

    /**
     * A selfie against the chip photo (or the printed photo when the chip was never read); null for an
     * unknown document.
     */
    fun selfie(reference: String, documentId: String): Selfie? {
        require(reference.isNotBlank() && reference.length <= ProviderSimulator.MAX_REFERENCE) {
            "reference must be 1..${ProviderSimulator.MAX_REFERENCE} characters"
        }
        val doc = documents[documentId] ?: return null
        val findings = DocumentFindings.selfie(scenarios.scenariosFor(doc.applicant.nationalId))
        val against = if (doc.chipRead) "CHIP_PHOTO" else "DOCUMENT_PHOTO"
        return Selfie(UUID.randomUUID().toString(), reference, documentId, findings, against)
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun evictExpired() {
        val cutoff = clock.instant().minus(ProviderSimulator.RETENTION)
        documents.values.removeIf { it.scannedAt.isBefore(cutoff) }
        if (documents.size > ProviderSimulator.MAX_SESSIONS) {
            documents.values.sortedBy { it.scannedAt }.take(documents.size - ProviderSimulator.MAX_SESSIONS)
                .forEach { documents.remove(it.id) }
        }
    }

    private companion object {
        const val MAX_NAME = 100
        val COUNTRY = Regex("[A-Z]{2}")
    }
}
