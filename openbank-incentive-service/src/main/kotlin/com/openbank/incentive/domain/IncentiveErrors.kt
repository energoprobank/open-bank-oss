// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.domain

/** [code] is a stable machine-readable reason (e.g. `CODE_UNKNOWN`); null keeps the legacy body. */
class IncentiveNotFound(message: String, val code: String? = null) : RuntimeException(message)
class IncentiveConflict(message: String, val code: String? = null) : RuntimeException(message)
class IncentiveValidation(message: String) : RuntimeException(message)

/** Reason codes of the service-to-service redemption (FLUX rewards engine). */
object RedemptionErrorCodes {
    const val CODE_UNKNOWN = "CODE_UNKNOWN"
    const val CODE_USED = "CODE_USED"
    const val LIMIT_REACHED = "LIMIT_REACHED"
    const val NOT_STACKABLE = "NOT_STACKABLE"
    const val CODE_NOT_CLAIMABLE = "CODE_NOT_CLAIMABLE"
    const val IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"
}
