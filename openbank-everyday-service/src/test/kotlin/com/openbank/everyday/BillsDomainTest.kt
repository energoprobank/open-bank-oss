// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday

import com.openbank.everyday.domain.Biller
import com.openbank.everyday.domain.NameMatch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BillsDomainTest {

    private val energoPro = Biller(
        "EP-CZ",
        "CZ",
        "Energo-Pro, a.s.",
        "ELECTRICITY",
        "CZ1520100000002001234561",
        "2001234561/2010",
        "CZK",
        true,
    )

    @Test
    fun `a biller receives its account in any spelling, and nothing else`() {
        assertThat(energoPro.receives("2001234561/2010")).isTrue()
        assertThat(energoPro.receives("0002001234561/2010")).isTrue()
        assertThat(energoPro.receives("000000-2001234561/2010")).isTrue()
        assertThat(energoPro.receives("CZ15 2010 0000 0020 0123 4561")).isTrue()
        assertThat(energoPro.receives("2001234561/0100")).isFalse()
        assertThat(energoPro.receives("19-2001234561/2010")).isFalse()
    }

    @Test
    fun `a name matches regardless of accents, case and word order`() {
        assertThat(NameMatch.same("Tomáš Kužin", "tomas kuzin")).isTrue()
        assertThat(NameMatch.same("Kuzin Tomas", "Tomas  Kuzin")).isTrue()
        assertThat(NameMatch.same("Велина Тошкова", "велина тошкова")).isTrue()
        assertThat(NameMatch.same("Tomas Kuzin", "Tomas Kuzinova")).isFalse()
    }
}
