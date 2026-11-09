// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.application

import com.openbank.providersimulator.domain.IncomingPayment
import com.openbank.providersimulator.domain.OtherBank
import com.openbank.providersimulator.domain.OtherBankOrder
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * "The customer's other bank" (NON-PRODUCTION): it sends the first payment of a document sign-up, and
 * the receiving side (our collection account) can list what arrived. Payments are kept a day, so an
 * applicant who pays and comes back later still finds it.
 */
@ApplicationScoped
class OtherBankDesk @Inject constructor(private val clock: Clock, private val scenarios: ScenarioBook) {
    private val payments = ConcurrentHashMap<String, IncomingPayment>()

    fun send(order: OtherBankOrder): IncomingPayment {
        OtherBank.validate(order)
        evictExpired()
        val payment = OtherBank.receive(
            UUID.randomUUID().toString(),
            order,
            scenarios.scenariosFor(order.nationalId),
            clock.instant(),
        )
        payments[payment.id] = payment
        return payment
    }

    /** What reached [creditorAccount] (optionally with [variableSymbol]), oldest first. */
    fun incoming(creditorAccount: String, variableSymbol: String?): List<IncomingPayment> {
        require(creditorAccount.isNotBlank()) { "creditor_account is required" }
        return payments.values.filter { OtherBank.matches(it, creditorAccount, variableSymbol) }.sortedBy {
            it.receivedAt
        }
    }

    private fun evictExpired() {
        val cutoff = clock.instant().minus(RETENTION)
        payments.values.removeIf { it.receivedAt.isBefore(cutoff) }
        if (payments.size > ProviderSimulator.MAX_SESSIONS) {
            payments.values.sortedBy { it.receivedAt }.take(payments.size - ProviderSimulator.MAX_SESSIONS)
                .forEach { payments.remove(it.id) }
        }
    }

    companion object {
        val RETENTION: Duration = Duration.ofHours(24)
    }
}
