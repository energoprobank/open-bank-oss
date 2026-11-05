// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    id("openbank.quarkus-service")
}

dependencies {
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    // No database: the simulated providers are deterministic, sessions live in memory (like
    // clearing-simulator, ADR-0104 D2). libs brings the shared error mapping (400 on require()).
    implementation(project(":openbank-libs"))

    testImplementation(libs.quarkus.test.security)
}

kover {
    reports {
        filters {
            excludes {
                annotatedBy("io.quarkus.runtime.annotations.RegisterForReflection")
            }
        }
        verify {
            rule {
                bound {
                    minValue = 80
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
