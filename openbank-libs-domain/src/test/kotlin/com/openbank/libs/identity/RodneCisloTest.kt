// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.identity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RodneCisloTest {

    @Test
    fun `parses a valid 10-digit male number`() {
        val r = RodneCislo.parse("760506/0342")
        assertThat(r).isInstanceOf(RodneCislo.Parsed::class.java)
        r as RodneCislo.Parsed
        assertThat(r.canonical).isEqualTo("7605060342")
        assertThat(r.birthdate).isEqualTo(LocalDate.of(1976, 5, 6))
        assertThat(r.gender).isEqualTo(RodneCislo.Gender.MALE)
    }

    @Test
    fun `slash and spaces are normalized away`() {
        val a = RodneCislo.parse(" 760506 / 0342 ")
        val b = RodneCislo.parse("7605060342")
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `decodes female month offset of 50`() {
        val r = RodneCislo.parse("8555230453") as RodneCislo.Parsed
        assertThat(r.gender).isEqualTo(RodneCislo.Gender.FEMALE)
        assertThat(r.birthdate).isEqualTo(LocalDate.of(1985, 5, 23))
    }

    @Test
    fun `decodes post-2004 plus-20 overflow as a 2000s male`() {
        val r = RodneCislo.parse("0921150120") as RodneCislo.Parsed
        assertThat(r.gender).isEqualTo(RodneCislo.Gender.MALE)
        assertThat(r.birthdate).isEqualTo(LocalDate.of(2009, 1, 15))
    }

    @Test
    fun `applies the historical checksum-remainder-10 to check-digit-0 quirk`() {
        // firstNine % 11 == 10 → check digit must be 0
        assertThat(RodneCislo.isValid("5401010000")).isTrue()
    }

    @Test
    fun `nine-digit pre-1954 form has no checksum and maps to the 1900s`() {
        val r = RodneCislo.parse("480312123") as RodneCislo.Parsed
        assertThat(r.birthdate).isEqualTo(LocalDate.of(1948, 3, 12))
        assertThat(r.gender).isEqualTo(RodneCislo.Gender.MALE)
    }

    @Test
    fun `rejects a wrong check digit`() {
        val r = RodneCislo.parse("7605060343")
        assertThat(r).isInstanceOf(RodneCislo.Invalid::class.java)
        assertThat((r as RodneCislo.Invalid).reason).contains("checksum")
    }

    @Test
    fun `rejects an impossible date`() {
        assertThat(RodneCislo.parse("7602300342")).isInstanceOf(RodneCislo.Invalid::class.java)
    }

    @Test
    fun `rejects an out-of-range month component`() {
        assertThat(RodneCislo.parse("7613060342")).isInstanceOf(RodneCislo.Invalid::class.java)
    }

    @Test
    fun `rejects wrong length`() {
        assertThat(RodneCislo.parse("12345")).isInstanceOf(RodneCislo.Invalid::class.java)
    }

    @Test
    fun `rejects non-digit characters`() {
        assertThat(RodneCislo.parse("76050x0342")).isInstanceOf(RodneCislo.Invalid::class.java)
    }

    @Test
    fun `rejects empty input`() {
        assertThat(RodneCislo.parse("   ")).isInstanceOf(RodneCislo.Invalid::class.java)
    }

    // ---- experience v2 step 9: as strict as the app

    @Test
    fun `a nine-digit number from 1954 on is refused`() {
        assertThat(RodneCislo.parse("540312123")).isInstanceOf(RodneCislo.Invalid::class.java)
        assertThat(RodneCislo.parse("992312123")).isInstanceOf(RodneCislo.Invalid::class.java)
    }

    @Test
    fun `the plus-20 overflow before 2004 is refused, from 2004 accepted`() {
        assertThat(RodneCislo.parse("0222081233")).isInstanceOf(RodneCislo.Invalid::class.java)
        assertThat((RodneCislo.parse("0522081230") as RodneCislo.Parsed).birthdate).isEqualTo(LocalDate.of(2005, 2, 8))
    }

    @Test
    fun `a birthdate after today is refused only when today is given`() {
        val today = LocalDate.of(2026, 11, 9)
        assertThat(RodneCislo.parse("3001011233")).isInstanceOf(RodneCislo.Parsed::class.java)
        assertThat((RodneCislo.parse("3001011233", today) as RodneCislo.Invalid).reason).contains("future")
        assertThat(RodneCislo.parse("0921150120", today)).isInstanceOf(RodneCislo.Parsed::class.java)
    }
}
