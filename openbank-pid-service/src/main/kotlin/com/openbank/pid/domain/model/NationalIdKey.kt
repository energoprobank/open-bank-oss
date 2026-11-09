// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.domain.model

import com.openbank.libs.identity.Egn
import com.openbank.libs.identity.RodneCislo
import java.time.LocalDate

/**
 * A national number read for the market it belongs to (experience v2 step 9): a Czech rodné číslo
 * or a Bulgarian ЕГН. [indexValue] is what the blind index is computed over — the canonical RČ as
 * before (so existing BIRTH_NUMBER rows still match), and `BG:<egn>` for an EGN so the two number
 * spaces can never collide.
 */
data class NationalIdKey(val indexValue: String, val birthdate: LocalDate, val gender: RodneCislo.Gender) {
    companion object {
        fun of(raw: String, market: String?): NationalIdKey? = when (market?.trim()?.uppercase() ?: "CZ") {
            "BG" -> (Egn.parse(raw) as? Egn.Parsed)?.let {
                NationalIdKey("BG:${it.canonical}", it.birthdate, it.gender)
            }
            "CZ" -> (RodneCislo.parse(raw) as? RodneCislo.Parsed)?.let {
                NationalIdKey(it.canonical, it.birthdate, it.gender)
            }
            else -> null
        }
    }
}
