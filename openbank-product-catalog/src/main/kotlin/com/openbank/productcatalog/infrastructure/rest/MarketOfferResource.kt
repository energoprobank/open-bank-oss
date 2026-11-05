// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.productcatalog.application.ProductCatalogService
import com.openbank.productcatalog.domain.AccountPackage
import com.openbank.productcatalog.domain.Lang
import com.openbank.productcatalog.domain.MarketOffers
import com.openbank.productcatalog.infrastructure.security.CatalogRoles
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.math.BigDecimal

/**
 * Retail market offer (experience v2 step 8): account packages and the pre-contract disclosures a
 * customer sees before signing — the fee information and the deposit-guarantee information.
 * `lang` is cs, bg or en (an Accept-Language value works too); unknown → English.
 */
@ApplicationScoped
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
class MarketOfferResource(private val service: ProductCatalogService) {

    @GET
    @Path("/packages")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.list")
    fun packages(@QueryParam("market") market: String?, @QueryParam("lang") lang: String?): List<PackageView> {
        val language = Lang.of(lang)
        return MarketOffers.packages(market).map { PackageView.of(it, language) }
    }

    @GET
    @Path("/products/by-code/{code}/disclosures")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read", resource = "#code")
    suspend fun disclosures(@PathParam("code") code: String, @QueryParam("lang") lang: String?): Response {
        val product = service.findByCode(code)
            ?: return Response.status(Response.Status.NOT_FOUND).entity(
                mapOf(
                    "error" to "Product with code '$code' not found",
                ),
            ).build()
        val language = Lang.of(lang)
        val guarantee = MarketOffers.depositGuarantee(product)
        return Response.ok(
            DisclosuresView(
                productCode = product.code,
                market = MarketOffers.marketOf(product),
                fees = product.fees.map {
                    FeeView(
                        label = MarketOffers.feeLabel(it.name, language),
                        amount = BigDecimal.valueOf(it.amount),
                        currency = it.currency,
                        period = when (it.frequency) {
                            "MONTHLY" -> "MONTH"
                            "PER_TRANSACTION", "PERCENTAGE" -> "TRANSACTION"
                            else -> "ONCE"
                        },
                        percentage = it.frequency == "PERCENTAGE",
                    )
                },
                depositGuarantee = GuaranteeView(
                    schemeName = guarantee.schemeName.of(language),
                    limit = guarantee.limit,
                    currency = guarantee.currency,
                    summary = guarantee.summary.of(language),
                ),
            ),
        ).build()
    }
}

data class PackageView(
    val code: String,
    val name: String,
    val monthlyFee: BigDecimal,
    val currency: String,
    val benefits: List<String>,
) {
    companion object {
        fun of(p: AccountPackage, lang: Lang) =
            PackageView(p.code, p.name.of(lang), p.monthlyFee, p.currency, p.benefits.map { it.of(lang) })
    }
}

data class FeeView(
    val label: String,
    val amount: BigDecimal,
    val currency: String,
    val period: String,
    val percentage: Boolean,
)

data class GuaranteeView(val schemeName: String, val limit: BigDecimal, val currency: String, val summary: String)

data class DisclosuresView(
    val productCode: String,
    val market: String,
    val fees: List<FeeView>,
    val depositGuarantee: GuaranteeView,
)
