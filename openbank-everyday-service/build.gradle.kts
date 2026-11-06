// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    id("openbank.quarkus-service")
}

dependencies {
    // Blocking JDBC (Agroal) + Flyway: every caller is a @Blocking endpoint, a @Blocking Kafka handler
    // or a scheduled job, so no reactive session is involved.
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.agroal)
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    // Payments export as PDF (DejaVu Sans embedded: Czech and Bulgarian text).
    implementation(libs.pdfbox)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    testImplementation(libs.mockk)
    testImplementation(libs.quarkus.test.security)
    testImplementation(project(":openbank-libs-testing"))
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
                    minValue = 75
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
