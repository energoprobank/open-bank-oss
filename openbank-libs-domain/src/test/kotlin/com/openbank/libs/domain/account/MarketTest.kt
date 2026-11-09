// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.account

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MarketTest {
    @Test
    fun `each market validates its own national number`() {
        assertThat(Market.CZ.canonicalNationalId("900101/1239")).isEqualTo("9001011239")
        assertThat(Market.BG.canonicalNationalId("900101/1239")).isNull()
        assertThat(Market.BG.canonicalNationalId("7501010010")).isEqualTo("7501010010")
        assertThat(Market.BG.birthdateOf("7501010010")).isEqualTo(LocalDate.of(1975, 1, 1))
    }

    @Test
    fun `markets parse leniently and map currencies`() {
        assertThat(Market.of(" bg ")).isEqualTo(Market.BG)
        assertThat(Market.of("SK")).isNull()
        assertThat(Market.forCurrency("eur")).isEqualTo(Market.BG)
        assertThat(Market.DEFAULT.currency).isEqualTo("CZK")
    }

    @Test
    fun `an IBAN is composed with valid check digits`() {
        val bg = Iban.compose("BG", "EPRO96611012345678")
        assertThat(bg.value).hasSize(22).startsWith("BG")
        assertThat(Iban.isValid(bg.value)).isTrue()
        // A known published example: GB82 WEST 1234 5698 7654 32.
        assertThat(Iban.compose("GB", "WEST12345698765432").value).isEqualTo("GB82WEST12345698765432")
    }

    @Test
    fun `nationalId reads birthdate, sex and age with the app's rules`() {
        val today = java.time.LocalDate.of(2026, 11, 9)
        val bg = Market.BG.nationalId("7501010010", today)!!
        org.assertj.core.api.Assertions.assertThat(bg.sex).isEqualTo("F") // ninth digit 1: odd = female
        org.assertj.core.api.Assertions.assertThat(bg.ageOn(today)).isEqualTo(51)
        org.assertj.core.api.Assertions.assertThat(Market.CZ.nationalId("3001011233", today)).isNull()
        org.assertj.core.api.Assertions.assertThat(
            Market.CZ.nationalId("0921150120", today)!!.ageOn(today),
        ).isEqualTo(17)
    }
}
