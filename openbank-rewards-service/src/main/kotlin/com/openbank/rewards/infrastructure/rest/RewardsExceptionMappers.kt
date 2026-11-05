// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.infrastructure.rest

import com.openbank.rewards.application.DownstreamRejected
import com.openbank.rewards.application.DownstreamUnavailable
import com.openbank.rewards.domain.RewardsNotFoundException
import com.openbank.rewards.domain.RewardsRuleException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

/** Error body for every refusal: `{code, message}` (contract). */
class RewardsExceptionMappers {
    @ServerExceptionMapper
    fun rule(e: RewardsRuleException): Response = body(CONFLICT, e.code, e.message)

    @ServerExceptionMapper
    fun notFound(e: RewardsNotFoundException): Response = body(NOT_FOUND, e.code, e.message)

    @ServerExceptionMapper
    fun rejected(e: DownstreamRejected): Response = body(CONFLICT, e.code, e.message)

    @ServerExceptionMapper
    @Suppress("UnusedParameter") // the downstream detail is logged by the client, never shown to customers
    fun unavailable(e: DownstreamUnavailable): Response =
        body(UNAVAILABLE, "DOWNSTREAM_UNAVAILABLE", "a bank service is temporarily unavailable")

    private fun body(status: Int, code: String, message: String?): Response =
        Response.status(status).type(MediaType.APPLICATION_JSON).entity(
            mapOf(
                "code" to code,
                "message" to (message ?: code),
            ),
        ).build()

    companion object {
        const val CONFLICT = 409
        const val NOT_FOUND = 404
        const val UNAVAILABLE = 503
    }
}
