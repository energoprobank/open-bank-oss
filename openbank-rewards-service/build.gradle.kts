// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

plugins {
    id("openbank.quarkus-service")
}

dependencies {
    // Plain Agroal JDBC + Flyway, the agent-service precedent: every customer command touches
    // several tables plus the outbox in ONE transaction, and a blocking JDBC transaction on a
    // worker thread states that directly. No Hibernate (reactive or ORM) on this classpath.
    implementation(libs.quarkus.agroal)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.opentelemetry)
    // Outbound commands to interest, lending, transaction, ledger, incentive and account services
    // carry a client-credentials token of the rewards client (service-account-openbank-rewards).
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.mockk)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 60
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
