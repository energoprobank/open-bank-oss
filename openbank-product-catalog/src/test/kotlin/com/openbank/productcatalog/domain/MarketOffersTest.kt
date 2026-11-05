// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarketOffersTest {
    private fun seed(code: String) = ProductSeed.products.single { it.code == code }

    @Test
    fun `each market offers three packages in its own currency`() {
        assertThat(MarketOffers.packages("CZ").map { it.currency }.toSet()).containsExactly("CZK")
        assertThat(MarketOffers.packages("bg").map { it.currency }.toSet()).containsExactly("EUR")
        assertThat(MarketOffers.packages("BG").map { it.code }).containsExactly("STANDARD", "PLUS", "PREMIUM")
        assertThat(MarketOffers.packages("XX")).isEqualTo(MarketOffers.packages("CZ"))
        assertThat(MarketOffers.packageByCode("BG", "plus")?.monthlyFee?.toPlainString()).isEqualTo("3.90")
    }

    @Test
    fun `the market comes from the product tag, not the currency`() {
        assertThat(MarketOffers.marketOf(seed("CURRENT_EUR_BG"))).isEqualTo("BG")
        assertThat(MarketOffers.marketOf(seed("CURRENT_CZK"))).isEqualTo("CZ")
        // A Czech EUR product stays under the Czech deposit guarantee scheme.
        val czechEur = ProductSeed.products.first { it.currency == "EUR" && MarketOffers.marketOf(it) == "CZ" }
        assertThat(MarketOffers.depositGuarantee(czechEur).schemeName.en).contains("Czech")
        assertThat(
            MarketOffers.depositGuarantee(seed("SAVINGS_EUR_BG")).schemeName.bg,
        ).isEqualTo("Фонд за гарантиране на влоговете в банките")
    }

    @Test
    fun `texts follow the language, Accept-Language style values included`() {
        assertThat(Lang.of("bg-BG,bg;q=0.9")).isEqualTo(Lang.BG)
        assertThat(Lang.of("cs")).isEqualTo(Lang.CS)
        assertThat(Lang.of(null)).isEqualTo(Lang.EN)
        assertThat(MarketOffers.feeLabel("Monthly Fee", Lang.BG)).isEqualTo("Месечна такса за сметката")
        assertThat(MarketOffers.feeLabel("Card Replacement", Lang.CS)).isEqualTo("Card Replacement")
    }

    @Test
    fun `the Bulgarian products have the sentinel ids account-service opens with and are later additions`() {
        assertThat(ProductIds.canonicalId("prod-030").toString()).isEqualTo("00000000-0000-0000-0000-0000000000c4")
        assertThat(ProductIds.canonicalId("prod-031").toString()).isEqualTo("00000000-0000-0000-0000-0000000000c5")
        assertThat(ProductSeed.laterAdditions).allMatch { code -> ProductSeed.products.any { it.code == code } }
    }
}
