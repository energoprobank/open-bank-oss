// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator

import com.openbank.providersimulator.application.ScenarioBook
import com.openbank.providersimulator.domain.Applicant
import com.openbank.providersimulator.domain.IdentityScenario
import com.openbank.providersimulator.domain.ScenarioSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class ScenarioBookTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `the file is read, re-read when it changes, and a runtime PUT overrides it until DELETE`(@TempDir dir: Path) {
        val file = dir.resolve("identity-scenarios.json")
        Files.writeString(
            file,
            """[{"national_id":"910813/1234","scenarios":["FACE_GREY","NOPE"],"note":"grey"},
               {"national_id":"x","scenarios":["INSOLVENT"]}]""",
        )
        val book = ScenarioBook(clock, Optional.of(file.toString()))
        assertThat(book.scenariosFor("9108131234")).containsExactly(IdentityScenario.FACE_GREY)
        assertThat(book.all().single().source).isEqualTo(ScenarioSource.FILE)
        assertThat(book.scenariosFor(null)).isEmpty()

        Files.writeString(file, """[{"national_id":"9108131234","scenarios":["INSOLVENT"]}]""")
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2030-01-01T00:00:00Z")))
        assertThat(book.scenariosFor("9108131234")).containsExactly(IdentityScenario.INSOLVENT)

        Files.writeString(file, "not json")
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2031-01-01T00:00:00Z")))
        assertThat(book.scenariosFor("9108131234")).containsExactly(IdentityScenario.INSOLVENT)

        book.put("9108131234", emptyList(), "cleared")
        assertThat(book.scenariosFor("910813/1234")).isEmpty()
        assertThat(book.all().single().source).isEqualTo(ScenarioSource.RUNTIME)
        assertThat(book.delete("9108131234")).isTrue()
        assertThat(book.delete("9108131234")).isFalse()
        assertThat(book.scenariosFor("9108131234")).containsExactly(IdentityScenario.INSOLVENT)
        assertThat(book.holdersOf(IdentityScenario.INSOLVENT)).containsExactly("9108131234")
    }

    @Test
    fun `no file means no fixtures, and bad runtime input is refused`() {
        val book = ScenarioBook(clock, Optional.empty())
        assertThat(book.all()).isEmpty()
        assertThatThrownBy { book.put("12", listOf("FACE_GREY"), null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { book.put("9108131234", listOf("NOPE"), null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ScenarioBook(clock, Optional.of("/nonexistent/file.json")).all()).isEmpty()
    }

    @Test
    fun `the book remembers who a provider met, by national id`() {
        val book = ScenarioBook(clock, Optional.empty())
        book.remember(Applicant("Ivan", "Georgiev", null, "8802145663"))
        book.remember(Applicant("No", "Id", null, null))
        assertThat(book.applicant("8802145663")?.familyName).isEqualTo("Georgiev")
        assertThat(book.applicant("0000000000")).isNull()
    }
}
