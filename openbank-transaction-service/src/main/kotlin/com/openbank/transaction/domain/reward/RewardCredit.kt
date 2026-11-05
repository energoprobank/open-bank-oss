// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.domain.reward

import java.math.BigDecimal

/**
 * Why the FLUX rewards programme pays cash to a customer (ADR-0344 D3). A transaction carrying a
 * reason is funded from the FLUX provision (GL 2400/2401), never from cash clearing — the reason is
 * what [com.openbank.transaction.application.usecase.PaymentJournalFactory] keys the funding leg on,
 * and it is persisted (V23 `reward_reason`) so the saga, which re-reads the row, books the same leg.
 *
 * [description] is what the customer sees in their transaction history (Czech / English, the same
 * bilingual form other bank-originated credits use).
 */
enum class RewardReason(val description: String) {
    BILL_CASHBACK("FLUX odměna – cashback za vyúčtování / FLUX reward – bill cashback"),
    INSTALMENT_RELIEF("FLUX odměna – příspěvek na splátku / FLUX reward – instalment relief"),
    REFERRAL_CASH("FLUX odměna – doporučení / FLUX reward – referral"),
}

/** The account a reward is paid to, as account-service describes it. */
data class RewardTargetAccount(
    val partyId: java.util.UUID,
    val accountType: String,
    val currencyCode: String,
    val status: String,
)

/** Why a reward credit is refused before any money moves. Each maps to 422 with this code. */
enum class RewardCreditRefusal {
    ACCOUNT_NOT_FOUND,
    ACCOUNT_NOT_OWNED_BY_PARTY,
    ACCOUNT_NOT_CURRENT,
    ACCOUNT_NOT_ACTIVE,
    CURRENCY_MISMATCH,
    CURRENCY_NOT_SUPPORTED,
    AMOUNT_NOT_POSITIVE,
    AMOUNT_PRECISION,
    AMOUNT_ABOVE_CAP,
}

class RewardCreditRefusedException(val refusal: RewardCreditRefusal, message: String) : RuntimeException(message)

/**
 * Per-call ceilings (ADR-0344): the most one reward credit may pay, per currency. A currency with no
 * cap is not a reward currency at all (CZ = CZK, BG = EUR) and is refused, never paid uncapped.
 *
 * Pure: the caps come from configuration through the infrastructure layer.
 */
data class RewardCreditPolicy(val caps: Map<String, BigDecimal>) {

    /** Validates an amount; returns it at two decimal places. Throws [RewardCreditRefusedException]. */
    fun checkAmount(amount: BigDecimal, currency: String): BigDecimal {
        val cap = caps[currency] ?: refuse(
            RewardCreditRefusal.CURRENCY_NOT_SUPPORTED,
            "rewards are paid in ${caps.keys.sorted().joinToString()}, not $currency",
        )
        if (amount.signum() <= 0) refuse(RewardCreditRefusal.AMOUNT_NOT_POSITIVE, "amount must be positive")
        if (amount.stripTrailingZeros().scale() > MONEY_SCALE) {
            refuse(RewardCreditRefusal.AMOUNT_PRECISION, "amount must have at most $MONEY_SCALE decimal places")
        }
        if (amount > cap) {
            refuse(RewardCreditRefusal.AMOUNT_ABOVE_CAP, "amount $amount $currency exceeds the per-credit cap $cap")
        }
        return amount.setScale(MONEY_SCALE)
    }

    /** The account must be the party's own ACTIVE CURRENT account, denominated in [currency]. */
    fun checkTarget(account: RewardTargetAccount?, partyId: java.util.UUID, currency: String) {
        // Unknown and not-yours answer differently on purpose: the only caller is the rewards engine
        // (OPA-gated), which already knows the party's accounts — there is no enumeration surface.
        if (account == null) refuse(RewardCreditRefusal.ACCOUNT_NOT_FOUND, "account not found")
        if (account.partyId != partyId) {
            refuse(RewardCreditRefusal.ACCOUNT_NOT_OWNED_BY_PARTY, "account does not belong to party $partyId")
        }
        if (account.accountType != CURRENT) {
            refuse(RewardCreditRefusal.ACCOUNT_NOT_CURRENT, "rewards are paid to a CURRENT account only")
        }
        if (account.status != ACTIVE) refuse(RewardCreditRefusal.ACCOUNT_NOT_ACTIVE, "account is not ACTIVE")
        if (account.currencyCode != currency) {
            refuse(
                RewardCreditRefusal.CURRENCY_MISMATCH,
                "account is denominated in ${account.currencyCode}, not $currency",
            )
        }
    }

    private fun refuse(refusal: RewardCreditRefusal, message: String): Nothing =
        throw RewardCreditRefusedException(refusal, message)

    companion object {
        const val MONEY_SCALE = 2
        const val CURRENT = "CURRENT"
        const val ACTIVE = "ACTIVE"
    }
}
