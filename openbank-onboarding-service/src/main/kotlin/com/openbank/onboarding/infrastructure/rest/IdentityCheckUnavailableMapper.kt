// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.onboarding.application.port.out.IdentityCheckUnavailable
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.time.Instant
import java.util.UUID

/** Step 9: a check the bank cannot run fails CLOSED — 503, retry later, nothing passes by default. */
@Provider
class IdentityCheckUnavailableMapper : ExceptionMapper<IdentityCheckUnavailable> {
    override fun toResponse(e: IdentityCheckUnavailable): Response =
        Response.status(Response.Status.SERVICE_UNAVAILABLE)
            .type(MediaType.APPLICATION_JSON)
            .entity(
                ApiError(
                    UUID.randomUUID().toString(),
                    Response.Status.SERVICE_UNAVAILABLE.statusCode,
                    "IDENTITY_CHECK_UNAVAILABLE",
                    e.message ?: "A check is not available right now",
                    Instant.now(),
                ),
            ).build()
}
