// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rewards

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal

/**
 * Additive loan rate adjustments (FLUX rewards CR-1).
 *
 * [productFloorRate] is the lowest nominal rate (a FRACTION, 0.01 = 1 % p.a.) any adjustment may
 * take a loan to; the clamp never goes below 0 % whatever this says. Lending's product catalogue
 * profile carries no per-product floor yet, so this is one value for the book.
 *
 * [writerPrincipals] may create and cancel adjustments; [previewPrincipals] may only preview. Both
 * are service-account NAMES checked against the VERIFIED token (`ServiceAccountIdentity`), not the
 * token's own name claim — rego (`lending_rest_ext.rego`) states the same intent, but runs advisory
 * until AUTHZ_ENFORCE flips, so this check is the control that holds today.
 *
 * No Kotlin default values on the parameters — see CustomerIntakeConfig for why.
 */
@ApplicationScoped
class RateAdjustmentConfig(
    @param:ConfigProperty(name = "lending.rate-adjustments.product-floor-rate", defaultValue = "0")
    val productFloorRate: BigDecimal,
    @param:ConfigProperty(
        name = "lending.rate-adjustments.writer-principals",
        defaultValue = "service-account-openbank-rewards",
    )
    val writerPrincipals: List<String>,
    @param:ConfigProperty(
        name = "lending.rate-adjustments.preview-principals",
        defaultValue = "service-account-openbank-rewards,service-account-openbank-edge",
    )
    val previewPrincipals: List<String>,
)
