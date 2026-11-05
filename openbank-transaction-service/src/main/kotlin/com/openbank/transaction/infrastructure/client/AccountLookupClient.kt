// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.transaction.application.port.out.AccountLookupPort
import com.openbank.transaction.domain.reward.RewardTargetAccount
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * account-service `GET /api/v1/accounts/{accountId}` (action `account.read`), for the reward credit's
 * target checks (ADR-0344 D3). The bearer is this service's OWN identity via the named oidc-client
 * `m2m` (Keycloak client `openbank-transaction`, ROLE_API only), which account_rest_ext.rego grants
 * `account.read` and nothing else (#10486 pattern). No customer-party header is sent: this is a
 * service read, and the ownership comparison is done here against the engine-supplied partyId.
 */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
interface AccountLookupRestClient {

    @GET
    @Path("/{accountId}")
    fun getAccount(@PathParam("accountId") accountId: UUID): Uni<AccountLookupResponse>
}

/** The fields of account-service's AccountResponse this service reads; the rest are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountLookupResponse(
    val id: UUID? = null,
    val accountType: String? = null,
    val partyId: UUID? = null,
    val currencyCode: String? = null,
    val status: String? = null,
)

@ApplicationScoped
class AccountLookupClient(@RestClient private val client: AccountLookupRestClient) : AccountLookupPort {

    override suspend fun findAccount(accountId: UUID): RewardTargetAccount? {
        val body = try {
            client.getAccount(accountId).awaitSuspending()
        } catch (e: WebApplicationException) {
            if (e.response?.status == HTTP_NOT_FOUND) return null
            throw e
        }
        // A 200 missing any field the checks need is an upstream contract break: fail (500), never pay.
        return RewardTargetAccount(
            partyId = body.partyId ?: contractBreak(accountId, "partyId"),
            accountType = body.accountType ?: contractBreak(accountId, "accountType"),
            currencyCode = body.currencyCode ?: contractBreak(accountId, "currencyCode"),
            status = body.status ?: contractBreak(accountId, "status"),
        )
    }

    private fun contractBreak(accountId: UUID, field: String): Nothing =
        throw AccountLookupContractException("account-service answered account $accountId without $field")

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}

/** account-service answered 200 without a field the reward checks need. Unmapped: a 500. */
class AccountLookupContractException(message: String) : RuntimeException(message)
