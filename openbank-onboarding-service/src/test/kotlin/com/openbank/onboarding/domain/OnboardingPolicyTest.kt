// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.domain

import com.openbank.onboarding.domain.model.CheckOutcome
import com.openbank.onboarding.domain.model.FaceThresholds
import com.openbank.onboarding.domain.model.FinancialProfile
import com.openbank.onboarding.domain.model.IdentityComparison
import com.openbank.onboarding.domain.model.NameMatch
import com.openbank.onboarding.domain.model.Person
import com.openbank.onboarding.domain.model.RiskModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Experience v2 step 9: the bank's own comparison, face and risk rules (onboarding-policy-v1). */
class OnboardingPolicyTest {

    private val applicant = Person("Tomáš", "Kužin", "1985-03-21", "8503211233", "M")

    @Test
    fun `names match across accents, case, order and an extra given name — never on a missing side`() {
        assertThat(NameMatch.same(NameMatch.tokens("Tomáš", "Kužin"), NameMatch.tokens("KUZIN", "tomas"))).isTrue()
        assertThat(
            NameMatch.same(NameMatch.tokens("Tomáš", "Kužin"), NameMatch.tokens("Tomáš Josef", "Kužin")),
        ).isTrue()
        assertThat(NameMatch.same(NameMatch.tokens("Tomáš", "Kužin"), NameMatch.tokens("Tomáš", "Novák"))).isFalse()
        assertThat(NameMatch.same(NameMatch.tokens("Tomáš", "Kužin"), emptySet())).isFalse()
    }

    @Test
    fun `a provider that returns nothing fails every comparison (no null pass)`() {
        val checks = IdentityComparison.compare("Bank iD", applicant, Person(null, null, null, null, null))
        assertThat(checks).allMatch { it.status == CheckOutcome.FAILED }
        val same = IdentityComparison.compare("Bank iD", applicant, applicant.copy(givenName = "TOMAS"))
        assertThat(same).allMatch { it.status == CheckOutcome.PASSED }
        val otherSex = IdentityComparison.compare("Document", applicant, applicant.copy(sex = "F"))
        assertThat(otherSex.single { it.type == "SEX" }.status).isEqualTo(CheckOutcome.FAILED)
    }

    @Test
    fun `age is checked against the document's date of birth`() {
        val today = LocalDate.of(2026, 11, 9)
        assertThat(IdentityComparison.ageCheck("2010-01-01", today).status).isEqualTo(CheckOutcome.FAILED)
        assertThat(IdentityComparison.ageCheck("2008-11-09", today).status).isEqualTo(CheckOutcome.PASSED)
        assertThat(IdentityComparison.ageCheck(null, today).status).isEqualTo(CheckOutcome.FAILED)
    }

    @Test
    fun `face thresholds — pass, grey zone to a person, fail`() {
        assertThat(FaceThresholds.faceMatch(0.93).status).isEqualTo(CheckOutcome.PASSED)
        assertThat(FaceThresholds.faceMatch(0.71).status).isEqualTo(CheckOutcome.REVIEW)
        assertThat(FaceThresholds.faceMatch(0.42).status).isEqualTo(CheckOutcome.FAILED)
        assertThat(FaceThresholds.liveness(0.85).status).isEqualTo(CheckOutcome.FAILED)
    }

    private val ordinary = FinancialProfile(
        setOf("EVERYDAY_BANKING"),
        setOf("EMPLOYMENT"),
        "EMPLOYED",
        "UP_TO_30K",
        false,
        false,
        listOf("CZ"),
        false,
    )

    @Test
    fun `an ordinary Czech employee via Bank iD is LOW, via a document still LOW`() {
        assertThat(
            RiskModel.score(ordinary, "CZ", pepScreened = false, documentChannel = false).rating,
        ).isEqualTo("LOW")
        val doc = RiskModel.score(ordinary, "CZ", pepScreened = false, documentChannel = true)
        assertThat(doc.score).isEqualTo(10)
        assertThat(doc.rating).isEqualTo("LOW")
        assertThat(doc.dueDiligence).isEqualTo("CDD")
    }

    @Test
    fun `a PEP is HIGH and needs EDD, a high-risk country plus cash is VERY_HIGH`() {
        val pep = RiskModel.score(ordinary.copy(pep = true), "CZ", pepScreened = false, documentChannel = false)
        assertThat(pep.rating).isEqualTo("HIGH")
        assertThat(pep.dueDiligence).isEqualTo("EDD")
        val risky = RiskModel.score(
            ordinary.copy(cashIntensive = true),
            "IR",
            pepScreened = false,
            documentChannel = true,
        )
        assertThat(risky.rating).isEqualTo("VERY_HIGH")
        assertThat(risky.factors.map { it.code }).contains("HIGH_RISK_COUNTRY", "OUTSIDE_EEA", "CASH", "CHANNEL")
    }

    @Test
    fun `large turnover from gifts is MEDIUM — a person looks`() {
        val r = RiskModel.score(
            ordinary.copy(expectedMonthlyTurnover = "UP_TO_500K", incomeSources = setOf("INHERITANCE_GIFT")),
            "CZ",
            pepScreened = false,
            documentChannel = false,
        )
        assertThat(r.rating).isEqualTo("MEDIUM")
    }
}
