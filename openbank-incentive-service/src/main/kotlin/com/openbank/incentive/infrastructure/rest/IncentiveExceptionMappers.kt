// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonInclude
import com.openbank.incentive.domain.IncentiveConflict
import com.openbank.incentive.domain.IncentiveNotFound
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * [code] (and its [message] twin) appear only for errors that carry a reason code — the rewards
 * redemption's `CODE_UNKNOWN` / `CODE_USED` / `LIMIT_REACHED` / ...; every other body stays `{error}`.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class IncentiveError(val error: String, val code: String? = null, val message: String? = null) {
    companion object {
        fun of(text: String, code: String?) = IncentiveError(text, code, code?.let { text })
    }
}

@Provider
class IncentiveConflictMapper : ExceptionMapper<IncentiveConflict> {
    override fun toResponse(exception: IncentiveConflict): Response = Response.status(Response.Status.CONFLICT)
        .entity(IncentiveError.of(exception.message ?: "conflict", exception.code))
        .build()
}

@Provider
class IncentiveNotFoundMapper : ExceptionMapper<IncentiveNotFound> {
    override fun toResponse(exception: IncentiveNotFound): Response = Response.status(Response.Status.NOT_FOUND)
        .entity(IncentiveError.of(exception.message ?: "not found", exception.code))
        .build()
}
