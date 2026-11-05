// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.libs.domain.account.CzechAccountNumber
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.money.CurrencyCode
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.security.SecureRandom

/**
 * Generates IBANs in the customer's market: Czech IBANs with a **nationally valid BBAN** (ČNB 169/2011 Sb.),
 * or Bulgarian IBANs (see [bulgarian]).
 *
 * The BBAN is `bankCode(4) + prefix(6) + base(10)`, where the base satisfies the Czech mod-11
 * weighted checksum (and the empty prefix trivially does). Only then do we add the ISO 13616
 * mod-97-10 IBAN check digits. The previous implementation emitted a padded `System.nanoTime()`
 * tail with a hard-coded `0000` bank code: it passed the generic IBAN mod-97 test but produced a
 * BBAN that no Czech bank could issue (no mod-11) — i.e. an account that does not exist in the
 * national scheme. Both checks now hold; see [CzechAccountNumber] for the weighting.
 *
 * [bankCode] is the 4-digit ČNB-assigned bank identifier (config `openbank.account.bank-code`).
 * The default is the Energo-Pro Bank PLACEHOLDER `8899` (branding/brand.yaml `bank.bank_code`; the
 * upstream sandbox used `0000`), which is **not** assigned to any real Czech bank, so generated
 * IBANs never impersonate a live institution (the earlier `2010` default is Fio banka's real code). The bank code does not participate in the mod-11 account checksum —
 * that is the prefix+base — so any 4-digit value still yields a nationally valid BBAN. A production
 * deployment MUST set the real assigned code.
 */
@ApplicationScoped
class IbanGenerator @Inject constructor(
    @ConfigProperty(name = "openbank.account.bank-code", defaultValue = "8899")
    private val bankCode: String,
    /**
     * Bulgarian BBAN parts (experience v2 step 8): the 4-letter bank code (the BIC's first four
     * letters, placeholder `EPRO` = BIC EPROBGSF) and the 4-digit branch (BAE code). Placeholders —
     * a production deployment MUST set the BNB-assigned values.
     */
    @ConfigProperty(name = "openbank.account.bg.bank-code", defaultValue = "EPRO")
    private val bgBankCode: String = "EPRO",
    @ConfigProperty(name = "openbank.account.bg.branch-code", defaultValue = "9661")
    private val bgBranchCode: String = "9661",
) {
    private val random = SecureRandom()

    /** A new IBAN in the customer's [market] (CZ default); the currency does not choose the country. */
    fun generate(currency: CurrencyCode, market: String = "CZ"): Iban = when (market.uppercase()) {
        "BG" -> bulgarian()
        else -> czech()
    }

    private fun czech(): Iban {
        val base = CzechAccountNumber.generateBase()
        return Iban.compose("CZ", CzechAccountNumber.composeBban(bankCode = bankCode, base = base))
    }

    /**
     * BG BBAN = bank code (4 letters) + branch (4 digits) + account type (2 digits, `10` = current or
     * savings account of a person) + account number (8 digits). Bulgaria has no national check digit
     * inside the BBAN: the IBAN's mod-97 pair is the only checksum.
     */
    private fun bulgarian(): Iban {
        val number = (1..BG_ACCOUNT_DIGITS).joinToString("") { random.nextInt(DECIMAL).toString() }
        return Iban.compose("BG", "$bgBankCode$bgBranchCode$BG_ACCOUNT_TYPE$number")
    }

    private companion object {
        const val BG_ACCOUNT_DIGITS = 8
        const val BG_ACCOUNT_TYPE = "10"
        const val DECIMAL = 10
    }
}
