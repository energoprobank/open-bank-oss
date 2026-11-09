// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.identity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EgnTest {
    @Test
    fun `a valid EGN yields its birthdate and gender`() {
        val parsed = Egn.parse("7501010010") as Egn.Parsed
        assertThat(parsed.birthdate).isEqualTo(LocalDate.of(1975, 1, 1))
        assertThat(parsed.gender).isEqualTo(RodneCislo.Gender.FEMALE) // ninth digit 1 is odd
    }

    @Test
    fun `the month encodes the century`() {
        assertThat((Egn.parse("0541010000") as Egn.Parsed).birthdate).isEqualTo(LocalDate.of(2005, 1, 1))
    }

    @Test
    fun `bad numbers are invalid with a reason`() {
        assertThat(Egn.parse("7501010011")).isEqualTo(Egn.Invalid("checksum mismatch"))
        assertThat(Egn.parse("7513010010")).isEqualTo(Egn.Invalid("invalid month component"))
        assertThat(Egn.parse("7502300010")).isEqualTo(Egn.Invalid("invalid date component"))
        assertThat(Egn.parse("750101001")).isEqualTo(Egn.Invalid("length must be 10 digits"))
        assertThat(Egn.isValid("75O1010010")).isFalse()
    }

    @Test
    fun `a birthdate after today is refused when today is given`() {
        val today = LocalDate.of(2026, 11, 9)
        assertThat(Egn.parse("3051010005")).isInstanceOf(Egn.Parsed::class.java)
        assertThat(Egn.parse("3051010005", today)).isEqualTo(Egn.Invalid("birthdate in the future"))
    }
}
