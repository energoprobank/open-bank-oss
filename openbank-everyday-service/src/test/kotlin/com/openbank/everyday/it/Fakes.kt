// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.it

import com.openbank.everyday.application.EnergoProCustomer
import com.openbank.everyday.application.EnergoProPort
import com.openbank.everyday.application.PartyFacts
import com.openbank.everyday.application.PartyPort
import com.openbank.everyday.domain.Bill
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** The customers party-service "holds". */
object People {
    val JANA: UUID = UUID.fromString("00000000-0000-0000-0000-0000000e0001")
    val PETR: UUID = UUID.fromString("00000000-0000-0000-0000-0000000e0002")
    val VELINA: UUID = UUID.fromString("00000000-0000-0000-0000-0000000e0003")
}

@Alternative
@Priority(1)
@ApplicationScoped
class FakeParties : PartyPort {
    override fun party(partyId: UUID): PartyFacts? = when (partyId) {
        People.JANA -> PartyFacts(partyId, "Jana Nováková", "CZ")
        People.PETR -> PartyFacts(partyId, "Petr Svoboda", "CZ")
        People.VELINA -> PartyFacts(partyId, "Велина Тошкова", "BG")
        else -> null
    }
}

/** Energo-Pro with one customer (Jana, EP-4000001) and one due bill; records the notices it got. */
@Alternative
@Priority(1)
@ApplicationScoped
class FakeEnergoPro : EnergoProPort {
    val notices = CopyOnWriteArrayList<String>()

    @Volatile
    var reachable = true

    override fun customer(customerNumber: String): EnergoProCustomer? =
        if (customerNumber == JANA_NUMBER) EnergoProCustomer(JANA_NUMBER, "CZ", "JANA", "Novakova") else null

    override fun bills(customerNumber: String): List<Bill>? = if (customerNumber != JANA_NUMBER) {
        null
    } else {
        listOf(
            bill("2026-10", "1480.40", "DUE", DUE_REFERENCE),
            bill("2026-09", "1120.00", "PAID", "4000001092"),
            bill("2026-08", "980.00", "PAID", "4000001082"),
        )
    }

    override fun notifyPaid(reference: String, amount: BigDecimal, currency: String, bankReference: String) {
        check(reachable) { "Energo-Pro unreachable" }
        notices += reference
    }

    private fun bill(period: String, amount: String, status: String, reference: String) = Bill(
        "$JANA_NUMBER-$period",
        period,
        BigDecimal(amount),
        "CZK",
        LocalDate.parse("$period-20").plusMonths(1),
        status,
        "CZ1520100000002001234561",
        reference,
    )

    companion object {
        const val JANA_NUMBER = "EP-4000001"
        const val DUE_REFERENCE = "4000001102"
    }
}
