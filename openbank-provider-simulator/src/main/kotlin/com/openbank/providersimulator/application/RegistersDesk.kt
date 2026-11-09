// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.application

import com.openbank.providersimulator.domain.GraoRecord
import com.openbank.providersimulator.domain.IdentityScenario
import com.openbank.providersimulator.domain.InsolvencyRecord
import com.openbank.providersimulator.domain.MvrDocumentStatus
import com.openbank.providersimulator.domain.NationalIds
import com.openbank.providersimulator.domain.RegisterFindings
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.LocalDate

/**
 * Public registers (NON-PRODUCTION): the Czech invalid-documents register (MVČR), the Czech
 * insolvency register (ISIR), and Bulgaria's RegiX (GRAO population register, MVR identity documents,
 * insolvency). Nothing is stored: every answer is [RegisterFindings] over the person's scenarios.
 */
@ApplicationScoped
class RegistersDesk @Inject constructor(private val clock: Clock, private val scenarios: ScenarioBook) {
    /** MVČR: is this document number listed as invalid (lost, stolen)? */
    fun invalidDocument(documentNumber: String): Boolean {
        require(documentNumber.isNotBlank() && documentNumber.length <= MAX_DOCUMENT) { "doklad is required" }
        return RegisterFindings.listedAsInvalid(documentNumber, scenarios.holdersOf(IdentityScenario.INVALID_DOCUMENT))
    }

    fun isir(birthNumber: String): Pair<String, List<InsolvencyRecord>> {
        val id = canonical(birthNumber, "rc")
        return id to RegisterFindings.isir(id, scenarios.scenariosFor(id))
    }

    /** GRAO for an EGN; null when the register has no such person (nobody with it has been seen). */
    fun grao(egn: String): GraoRecord? {
        val id = canonical(egn, "egn")
        val applicant = scenarios.applicant(id) ?: return null
        return RegisterFindings.grao(id, applicant, scenarios.scenariosFor(id))
    }

    fun mvr(egn: String, documentNumber: String): MvrDocumentStatus {
        val id = canonical(egn, "egn")
        require(documentNumber.isNotBlank() && documentNumber.length <= MAX_DOCUMENT) { "document_number is required" }
        return RegisterFindings.mvr(id, documentNumber, scenarios.scenariosFor(id), LocalDate.now(clock))
    }

    fun bgInsolvency(egn: String): List<InsolvencyRecord> {
        val id = canonical(egn, "egn")
        return RegisterFindings.bgInsolvency(id, scenarios.scenariosFor(id))
    }

    private fun canonical(raw: String, field: String): String =
        requireNotNull(NationalIds.canonical(raw)) { "$field must be 9 or 10 digits" }

    private companion object {
        const val MAX_DOCUMENT = 20
    }
}
