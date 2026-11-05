// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain

import java.math.BigDecimal

/**
 * What the bank offers a retail customer in one market beyond the account product itself
 * (experience v2 step 8): account packages and the pre-contract disclosures — the fee information
 * and the deposit-guarantee information a customer must see before signing.
 *
 * Texts are in Czech, Bulgarian and English; [Lang.of] falls back to English.
 */
enum class Lang {
    CS,
    BG,
    EN,
    ;

    companion object {
        /** `cs`, `bg`, `en` or an Accept-Language value like `bg-BG,bg;q=0.9`. */
        fun of(raw: String?): Lang {
            val first = raw?.split(',', ';')?.firstOrNull()?.trim()?.take(2)?.uppercase()
            return entries.firstOrNull { it.name == first } ?: EN
        }
    }
}

/** A text in the three app languages. */
data class Text3(val cs: String, val bg: String, val en: String) {
    fun of(lang: Lang): String = when (lang) {
        Lang.CS -> cs
        Lang.BG -> bg
        Lang.EN -> en
    }
}

/** An account package: extra benefits for a monthly fee, on top of the current account. */
data class AccountPackage(
    val code: String,
    val market: String,
    val name: Text3,
    val monthlyFee: BigDecimal,
    val currency: String,
    val benefits: List<Text3>,
)

/** The statutory deposit-guarantee information for one market. */
data class DepositGuarantee(val schemeName: Text3, val limit: BigDecimal, val currency: String, val summary: Text3)

object MarketOffers {
    private val STANDARD_BENEFITS = listOf(
        Text3(
            "Virtuální karta a platby v aplikaci",
            "Виртуална карта и плащания в приложението",
            "Virtual card and payments in the app",
        ),
        Text3("Odměny Energo-Pro", "Награди Energo-Pro", "Energo-Pro Rewards"),
    )
    private val PLUS_BENEFITS = listOf(
        Text3("Plastová karta zdarma", "Безплатна пластмасова карта", "Free plastic card"),
        Text3("Výběry z bankomatů zdarma", "Безплатни тегления от банкомат", "Free ATM withdrawals"),
        Text3(
            "Dvojnásobek FLUX za platby kartou",
            "Двойно повече FLUX за плащания с карта",
            "Double FLUX for card payments",
        ),
    )
    private val PREMIUM_BENEFITS = listOf(
        Text3("Vše z balíčku Plus", "Всичко от пакет Плюс", "Everything in Plus"),
        Text3(
            "Cestovní pojištění pro celou rodinu",
            "Пътническа застраховка за цялото семейство",
            "Travel insurance for the whole family",
        ),
        Text3("Směna měn bez přirážky", "Обмяна на валута без надценка", "Currency exchange at no mark-up"),
        Text3("Osobní bankéř na telefonu", "Личен банкер по телефона", "A personal banker on the phone"),
    )

    private fun packages(market: String, currency: String, plus: String, premium: String) = listOf(
        AccountPackage(
            "STANDARD",
            market,
            Text3("Standard", "Стандарт", "Standard"),
            BigDecimal.ZERO,
            currency,
            STANDARD_BENEFITS,
        ),
        AccountPackage("PLUS", market, Text3("Plus", "Плюс", "Plus"), BigDecimal(plus), currency, PLUS_BENEFITS),
        AccountPackage(
            "PREMIUM",
            market,
            Text3("Premium", "Премиум", "Premium"),
            BigDecimal(premium),
            currency,
            PREMIUM_BENEFITS,
        ),
    )

    private val PACKAGES: Map<String, List<AccountPackage>> = mapOf(
        "CZ" to packages("CZ", "CZK", "99.00", "249.00"),
        "BG" to packages("BG", "EUR", "3.90", "9.90"),
    )

    private val GUARANTEES: Map<String, DepositGuarantee> = mapOf(
        "CZ" to DepositGuarantee(
            schemeName = Text3(
                "Garanční systém finančního trhu",
                "Гаранционна система на финансовия пазар (Чехия)",
                "Financial Market Guarantee System (Czech Republic)",
            ),
            limit = BigDecimal("100000"),
            currency = "EUR",
            summary = Text3(
                "Vklady jsou pojištěny do výše ekvivalentu 100 000 € na klienta a banku " +
                    "a vyplaceny do 7 pracovních dnů.",
                "Депозитите са гарантирани до равностойността на 100 000 € на клиент и банка " +
                    "и се изплащат до 7 работни дни.",
                "Deposits are guaranteed up to the equivalent of €100,000 per customer and bank " +
                    "and paid out within 7 working days.",
            ),
        ),
        "BG" to DepositGuarantee(
            schemeName = Text3(
                "Fond pro garantování vkladů v bankách (Bulharsko)",
                "Фонд за гарантиране на влоговете в банките",
                "Bulgarian Deposit Insurance Fund",
            ),
            limit = BigDecimal("100000"),
            currency = "EUR",
            summary = Text3(
                "Vklady jsou garantovány do výše 100 000 € na klienta a banku a vyplaceny do 7 pracovních dnů.",
                "Влоговете са гарантирани до 100 000 € на клиент в една банка и се изплащат в срок до 7 работни дни.",
                "Deposits are guaranteed up to €100,000 per customer and bank and paid out within 7 working days.",
            ),
        ),
    )

    /** The packages offered in [market] (CZ default for an unknown value). */
    fun packages(market: String?): List<AccountPackage> = PACKAGES[market?.uppercase()] ?: PACKAGES.getValue("CZ")

    fun packageByCode(market: String?, code: String): AccountPackage? = packages(market).firstOrNull {
        it.code ==
            code.uppercase()
    }

    private val FEE_LABELS: Map<String, Text3> = mapOf(
        "Monthly Fee" to Text3("Měsíční poplatek za účet", "Месечна такса за сметката", "Monthly account fee"),
        "Account Maintenance" to Text3("Vedení účtu", "Поддръжка на сметката", "Account maintenance"),
        "SEPA Transfer" to Text3("Převod SEPA", "SEPA превод", "SEPA transfer"),
        "Domestic CERTIS Transfer" to Text3("Tuzemský převod", "Вътрешен превод (Чехия)", "Domestic transfer"),
        "SWIFT Transfer" to Text3("Převod SWIFT", "SWIFT превод", "SWIFT transfer"),
        "FX Conversion" to
            Text3("Směna měn (z částky)", "Обмяна на валута (от сумата)", "Currency exchange (of the amount)"),
    )

    /** A fee's name in [lang]; a fee without a known label keeps its catalogue name. */
    fun feeLabel(name: String, lang: Lang): String = FEE_LABELS[name]?.of(lang) ?: name

    /** The market a product is sold in: its `market-<cc>` tag; untagged products are Czech (the original catalogue). */
    fun marketOf(product: Product): String =
        product.tags.firstOrNull { it.startsWith(MARKET_TAG) }?.removePrefix(MARKET_TAG)?.uppercase() ?: "CZ"

    /** The deposit guarantee of the scheme the product's market belongs to — not of its currency. */
    fun depositGuarantee(product: Product): DepositGuarantee =
        GUARANTEES[marketOf(product)] ?: GUARANTEES.getValue("CZ")

    private const val MARKET_TAG = "market-"
}
