// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.incentive.infrastructure.rest

import com.openbank.libs.authz.Authorize
import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.ForbiddenException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.io.File

/** FLUX rewards: only the rewards engine's own principal may redeem a code in one step. */
class RedemptionCallerGuardTest {
    private fun jwtIdentity(azp: String?, username: String?, vararg roles: String, sub: String? = "sa-subject") =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(TestJwt(mapOf("azp" to azp, "preferred_username" to username, "sub" to sub)))
            .addRoles(roles.toSet())
            .build()

    private class TestJwt(private val claims: Map<String, Any?>) : JsonWebToken {
        override fun getName(): String = claims["preferred_username"] as? String ?: "anonymous"
        override fun getClaimNames(): Set<String> = claims.filterValues { it != null }.keys

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> getClaim(claimName: String): T = claims[claimName] as T
    }

    @Test
    fun `the rewards engine's own service account may redeem`() {
        assertThatCode {
            requireRewardsRedeemer(jwtIdentity("openbank-rewards", "service-account-openbank-rewards", "ROLE_API"))
        }.doesNotThrowAnyException()
    }

    @Test
    fun `every other caller is refused, staff and the customer edge included`() {
        listOf(
            jwtIdentity("openbank-edge", "service-account-openbank-edge", "ROLE_API"),
            jwtIdentity("openbank-services", "service-account-openbank-services", "ROLE_API", "ROLE_OPERATOR"),
            jwtIdentity("openbank-admin-ui", "u-staff", "ROLE_OPERATOR"),
            // the rewards name on a token minted for another client
            jwtIdentity("openbank-edge", "service-account-openbank-rewards", "ROLE_API"),
            jwtIdentity("openbank-rewards", "service-account-openbank-rewards", "ROLE_API", sub = null),
            QuarkusSecurityIdentity.builder()
                .setPrincipal(QuarkusPrincipal("service-account-openbank-rewards"))
                .addRole("ROLE_API")
                .build(),
        ).forEach { caller ->
            assertThatThrownBy { requireRewardsRedeemer(caller) }.isInstanceOf(ForbiddenException::class.java)
        }
    }

    @Test
    fun `the route is ROLE_API, OPA-gated, and the rego grants the same single principal`() {
        // A class of its own: IncentiveResource's class-level ROLE_OPERATOR would also bind it.
        assertThat(IncentiveRedemptionResource::class.java.getAnnotation(RolesAllowed::class.java).value)
            .containsExactly("ROLE_API")
        val redeem = IncentiveRedemptionResource::class.java.declaredMethods.first { it.name == "redeem" }
        assertThat(redeem.getAnnotation(Authorize::class.java).action).isEqualTo("incentive.redemption.redeem")
        assertThat(REDEMPTION_CALLERS).containsExactly("service-account-openbank-rewards")

        val rego = File("../openbank-infra/gitops/components/incentive/incentive_rest_ext.rego").readText()
        val principals = Regex("""machine_named\("([^"]+)"\)|principal\.id == "([^"]+)"""")
            .findAll(rego)
            .map { it.groupValues.drop(1).first { g -> g.isNotEmpty() } }
            .toSet()
        assertThat(principals).isEqualTo(REDEMPTION_CALLERS)
        assertThat(rego).contains("input.action == \"incentive.redemption.redeem\"")
    }
}
