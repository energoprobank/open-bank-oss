// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.transaction.application.usecase.RewardCreditConflictException
import com.openbank.transaction.application.usecase.TransactionNotFoundException
import com.openbank.transaction.application.usecase.TransactionUpdateConflictException
import com.openbank.transaction.domain.reward.RewardCreditRefusedException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class TransactionNotFoundExceptionMapper : ExceptionMapper<TransactionNotFoundException> {
    override fun toResponse(exception: TransactionNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND)
            .entity(mapOf("error" to (exception.message ?: "Not found")))
            .type(MediaType.APPLICATION_JSON)
            .build()
}

// NB: IllegalArgumentException is intentionally NOT mapped here. The canonical
// mapping lives in openbank-libs CommonExceptionMappers (IllegalArgumentException
// -> 400 VALIDATION_ERROR, IllegalStateException -> 422 BUSINESS_RULE_VIOLATION).
// A second service-local mapper for the same type would collide with the shared
// one non-deterministically, so domain invariants that must surface as 422 throw
// IllegalStateException (check(...)) rather than IllegalArgumentException.

// ExceptionMapper<Exception> (GlobalExceptionMapper) is intentionally NOT declared here.
// openbank-libs auto-registers GenericExceptionMapper (Exception → 500, correlation-aware) and
// WebApplicationExceptionMapper (WebApplicationException → pass-through) via Jandex. A second
// @Provider for the same type collides non-deterministically (ADR-0049 D4).

// 409 concurrent-modification conflict (#465): a state transition (complete/fail/reverse) raced
// another writer — the caller read a version the row no longer has. Dedicated type so the status
// is deterministic (see issue #526 on the IllegalStateException mapper collision between
// libs-runtime and services).
@Provider
class TransactionUpdateConflictExceptionMapper : ExceptionMapper<TransactionUpdateConflictException> {
    override fun toResponse(exception: TransactionUpdateConflictException): Response =
        Response.status(Response.Status.CONFLICT)
            .entity(mapOf("error" to (exception.message ?: "Transaction was modified concurrently")))
            .type(MediaType.APPLICATION_JSON)
            .build()
}

// SelfApprovalNotAllowedMapper / InvalidApprovalStateMapper (403/409) moved to
// openbank-libs-runtime's CommonExceptionMappers (issue #1394) — a service-local copy of the
// same exact type would collide non-deterministically with the shared one (issue #526).

// ADR-0344 D3: a reward credit refused before any money moved. 422 with the contract's {code, message}
// error shape, so the rewards engine can branch on the code (e.g. ACCOUNT_NOT_OWNED_BY_PARTY) instead of
// parsing prose. Dedicated type, not IllegalStateException, for the reason given above (#526).
@Provider
class RewardCreditRefusedExceptionMapper : ExceptionMapper<RewardCreditRefusedException> {
    override fun toResponse(exception: RewardCreditRefusedException): Response {
        val body = mapOf("code" to exception.refusal.name, "message" to (exception.message ?: exception.refusal.name))
        return Response.status(UNPROCESSABLE_ENTITY).entity(body).type(MediaType.APPLICATION_JSON).build()
    }

    private companion object {
        const val UNPROCESSABLE_ENTITY = 422
    }
}

// ADR-0344 D3: the sourceRef was already paid with a different account, amount, currency or reason.
@Provider
class RewardCreditConflictExceptionMapper : ExceptionMapper<RewardCreditConflictException> {
    override fun toResponse(exception: RewardCreditConflictException): Response =
        Response.status(Response.Status.CONFLICT)
            .entity(mapOf("code" to "SOURCE_REF_REUSED", "message" to (exception.message ?: "sourceRef reused")))
            .type(MediaType.APPLICATION_JSON)
            .build()
}
