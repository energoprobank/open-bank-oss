// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator

import com.openbank.providersimulator.domain.Applicant
import com.openbank.providersimulator.domain.BillStatus
import com.openbank.providersimulator.domain.CheckStatus
import com.openbank.providersimulator.domain.EnergoProBilling
import com.openbank.providersimulator.domain.EnergoProCustomer
import com.openbank.providersimulator.domain.EnergoProDirectory
import com.openbank.providersimulator.domain.EnergoProMarket
import com.openbank.providersimulator.domain.Verification
import com.openbank.providersimulator.domain.VerificationKind
import com.openbank.providersimulator.domain.VerificationStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class ProviderDomainTest {
    private val t0 = Instant.parse("2026-11-05T10:00:00Z")

    private fun verification(kind: VerificationKind, family: String = "Nováková") = Verification(
        "v1",
        kind,
        "ref",
        Applicant("Jana", family, "1990-01-01", "9051011234"),
        "ID_CARD",
        t0,
        stepSeconds = 2,
    )

    @Test
    fun `a document scan passes its four checks one after another`() {
        val scan = verification(VerificationKind.DOCUMENT_SCAN)
        assertThat(scan.statusAt(t0)).isEqualTo(VerificationStatus.IN_PROGRESS)
        assertThat(scan.checksAt(t0.plusSeconds(4)).map { it.status })
            .containsExactly(CheckStatus.PASSED, CheckStatus.PASSED, CheckStatus.PENDING, CheckStatus.PENDING)
        assertThat(scan.statusAt(t0.plusSeconds(8))).isEqualTo(VerificationStatus.VERIFIED)
        assertThat(scan.identityAt(t0.plusSeconds(8))?.nationalId).isEqualTo("9051011234")
        assertThat(scan.identityAt(t0.plusSeconds(7))).isNull()
    }

    @Test
    fun `the fail marker fails the last check`() {
        val scan = verification(VerificationKind.DOCUMENT_SCAN, family = "Failidv Test")
        assertThat(scan.checksAt(t0.plusSeconds(6)).last().status).isEqualTo(CheckStatus.PENDING)
        assertThat(scan.statusAt(t0.plusSeconds(8))).isEqualTo(VerificationStatus.FAILED)
        assertThat(scan.identityAt(t0.plusSeconds(8))).isNull()
    }

    @Test
    fun `a liveness check proves presence, never an identity`() {
        val liveness = verification(VerificationKind.LIVENESS)
        assertThat(liveness.statusAt(t0.plusSeconds(2))).isEqualTo(VerificationStatus.VERIFIED)
        assertThat(liveness.identityAt(t0.plusSeconds(2))).isNull()
    }

    @Test
    fun `the generated population is deterministic, mostly Czech, partly Bulgarian`() {
        val a = EnergoProDirectory.generate(400)
        assertThat(a).isEqualTo(EnergoProDirectory.generate(400))
        val bg = a.count { it.market == EnergoProMarket.BG }
        assertThat(bg).isBetween(60, 140)
        assertThat(a.map { it.customerNumber }).allMatch { EnergoProCustomer.NUMBER.matches(it) }
        assertThat(a.filter { it.market == EnergoProMarket.CZ }.map { it.phone }).allMatch {
            it.startsWith("+420") &&
                it.length == 13
        }
        assertThat(a.filter { it.market == EnergoProMarket.BG }.map { it.phone }).allMatch {
            it.startsWith("+359") &&
                it.length == 13
        }
    }

    @Test
    fun `a seeded record replaces a generated one and numbers are normalised`() {
        val seeded =
            EnergoProCustomer(
                "EP-1000000",
                EnergoProMarket.CZ,
                "Tomáš",
                "Test",
                "Ulice 1",
                "Praha",
                "110 00",
                "t@example.cz",
                "+420 601 000 001",
            )
        val directory = EnergoProDirectory(EnergoProDirectory.generate(3), listOf(seeded))
        assertThat(directory.find(" ep 1000000 ")).isEqualTo(seeded)
        assertThat(directory.find("EP-9999999")).isNull()
    }

    @Test
    fun `contact matching ignores case and phone formatting`() {
        val c =
            EnergoProCustomer(
                "EP-1234567",
                EnergoProMarket.BG,
                "Мария",
                "Георгиева",
                "бул. Витоша 15",
                "София",
                "1000",
                "Maria@Example.bg",
                "+359 87 123 4567",
            )
        assertThat(c.hasContact("maria@example.bg", null)).isTrue()
        assertThat(c.hasContact(null, "+359871234567")).isTrue()
        assertThat(c.hasContact("other@example.bg", "+359870000000")).isFalse()
    }

    @Test
    fun `bills follow the calendar - a year of history, the newest DUE until the 20th, then OVERDUE`() {
        val c = EnergoProDirectory.generate(8).first { it.market == EnergoProMarket.CZ }
        val nov6 = EnergoProBilling.bills(c, LocalDate.of(2026, 11, 6), emptySet())
        assertThat(nov6).hasSize(12)
        assertThat(nov6[0].period).isEqualTo("2026-10")
        assertThat(nov6[0].status).isEqualTo(BillStatus.DUE)
        assertThat(nov6[0].dueDate).isEqualTo(LocalDate.of(2026, 11, 20))
        assertThat(nov6[0].reference).hasSize(10).startsWith(c.customerNumber.filter(Char::isDigit))
        assertThat(nov6.drop(1)).allMatch { it.status == BillStatus.PAID }
        // before the 5th, October's bill is not issued yet
        assertThat(EnergoProBilling.bills(c, LocalDate.of(2026, 11, 4), emptySet())[0].period).isEqualTo("2026-09")
        val nov21 = LocalDate.of(2026, 11, 21)
        assertThat(EnergoProBilling.bills(c, nov21, emptySet())[0].status).isEqualTo(BillStatus.OVERDUE)
        assertThat(EnergoProBilling.bills(c, nov21, setOf(nov6[0].reference))[0].status).isEqualTo(BillStatus.PAID)
        // winter uses more electricity than summer for the same household
        val january = nov6.first { it.period == "2026-01" }.consumptionKwh
        val july = nov6.first { it.period == "2026-07" }.consumptionKwh
        assertThat(january).isGreaterThan(july)
    }
}
