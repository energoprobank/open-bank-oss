// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * ADR-0319 moved [TransactionServiceClient] from a hand-written interface to a sub-interface of the
 * generated one. The outbound identity must not have moved with it: this client authenticates as the
 * DEFAULT oidc-client, which `@OidcClientFilter` with no value selects - exactly as on `main`.
 *
 * [CardIssuanceClient] is the opposite case: card-issuance refuses the shared principal for the card
 * lookup, so that client must select the NAMED oidc-client `card-issuance`, and that client must exist
 * with card-processing's own Keycloak client and its own secret variable.
 *
 * Two ways to get that wrong and neither fails at runtime: naming a client that does not exist in
 * `application.yaml` (the first call fails, not the build), or ALSO registering
 * [OidcClientRequestReactiveFilter] as a provider next to the annotation, which attaches the bearer
 * twice. The generated parent carries neither annotation, so what the sub-interface declares is the
 * whole truth.
 */
class M2mOidcClientIdentityWiringTest {

    private val oidcClient: Map<*, *> = run {
        val doc = Yaml().load<Map<String, Any>>(
            javaClass.classLoader.getResourceAsStream("application.yaml")!!,
        )
        (doc["quarkus"] as Map<*, *>)["oidc-client"] as Map<*, *>
    }

    @Test
    fun `the transaction client keeps the bare default oidc-client filter, as on main`() {
        val filter = TransactionServiceClient::class.java.getAnnotation(OidcClientFilter::class.java)

        assertThat(filter).describedAs("@OidcClientFilter on TransactionServiceClient").isNotNull
        assertThat(filter.value).describedAs("a bare filter selects the default oidc-client").isEmpty()
    }

    @Test
    fun `the oidc request filter is not registered a second time as a provider`() {
        val providers = TransactionServiceClient::class.java
            .getAnnotationsByType(RegisterProvider::class.java)
            .map { it.value.java }

        assertThat(providers).doesNotContain(OidcClientRequestReactiveFilter::class.java)
        assertThat(providers).containsExactly(SyntheticTaintClientFilter::class.java)
    }

    @Test
    fun `the config key and the route come from this consumer, not the generated parent`() {
        val rest = TransactionServiceClient::class.java.getAnnotation(RegisterRestClient::class.java)

        assertThat(rest.configKey).isEqualTo("transaction-api")
    }

    @Test
    fun `the card-issuance client selects the named oidc-client and nothing else attaches a bearer`() {
        val filter = CardIssuanceClient::class.java.getAnnotation(OidcClientFilter::class.java)

        assertThat(filter).describedAs("@OidcClientFilter on CardIssuanceClient").isNotNull
        assertThat(filter.value).isEqualTo(CARD_ISSUANCE)
        val providers = CardIssuanceClient::class.java
            .getAnnotationsByType(RegisterProvider::class.java)
            .map { it.value.java }
        assertThat(providers).doesNotContain(OidcClientRequestReactiveFilter::class.java)
    }

    @Test
    fun `the named oidc-client authenticates as openbank-card-processing with its own secret`() {
        val named = oidcClient[CARD_ISSUANCE] as Map<*, *>

        assertThat(named["client-id"]).isEqualTo("openbank-card-processing")
        assertThat((named["credentials"] as Map<*, *>)["secret"] as String)
            .startsWith("\${OIDC_CARD_ISSUANCE_CLIENT_SECRET:")
        assertThat((named["grant"] as Map<*, *>)["type"]).isEqualTo("client")
    }

    @Test
    fun `the default client stays the shared one for the transaction and fraud clients`() {
        assertThat(oidcClient["client-id"]).isEqualTo("openbank-services")
        listOf(TransactionServiceClient::class.java, FraudServiceClient::class.java).forEach { client ->
            assertThat(client.getAnnotation(OidcClientFilter::class.java).value)
                .describedAs("%s oidc-client name", client.simpleName)
                .isEmpty()
        }
    }

    private companion object {
        const val CARD_ISSUANCE = "card-issuance"
    }
}
