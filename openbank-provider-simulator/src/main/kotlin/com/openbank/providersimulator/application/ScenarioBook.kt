// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.application

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.providersimulator.domain.Applicant
import com.openbank.providersimulator.domain.IdentityScenario
import com.openbank.providersimulator.domain.NationalIds
import com.openbank.providersimulator.domain.ScenarioEntry
import com.openbank.providersimulator.domain.ScenarioSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/** One record of the scenarios file: `{"national_id":"9108131234","scenarios":["FACE_GREY"],"note":"..."}`. */
data class ScenarioFileEntry(
    @JsonProperty("national_id") val nationalId: String? = null,
    val scenarios: List<String>? = null,
    val note: String? = null,
)

/**
 * The person registry behind every simulated identity provider: which scenarios hold for a national
 * id (from the scenarios file, overridden by runtime PUTs), and which applicants the providers have
 * already seen (a register that only receives an EGN answers with the person the IDV vendor met).
 *
 * The file is re-read when it changes on disk, so a developer can edit
 * `~/.openbank-local/simulators/identity-scenarios.json` without a restart.
 */
@ApplicationScoped
class ScenarioBook @Inject constructor(
    private val clock: Clock,
    @ConfigProperty(name = "openbank.provider-simulator.identity-scenarios-file")
    private val scenariosFile: Optional<String>,
) {
    private val log = Logger.getLogger(ScenarioBook::class.java)
    private val runtime = ConcurrentHashMap<String, ScenarioEntry>()
    private val seen = ConcurrentHashMap<String, Pair<Applicant, Instant>>()

    @Volatile
    private var fileEntries: Map<String, ScenarioEntry> = emptyMap()

    @Volatile
    private var fileStamp: FileTime? = null

    /** The scenarios that hold for [nationalId] (any spelling); none for an unknown or absent id. */
    fun scenariosFor(nationalId: String?): Set<IdentityScenario> {
        val id = NationalIds.canonical(nationalId) ?: return emptySet()
        return (runtime[id] ?: fromFile()[id])?.scenarios ?: emptySet()
    }

    fun all(): List<ScenarioEntry> {
        val merged = fromFile() + runtime
        return merged.values.sortedBy { it.nationalId }
    }

    /** National ids that currently carry [scenario]. */
    fun holdersOf(scenario: IdentityScenario): List<String> = all().filter { scenario in it.scenarios }.map {
        it.nationalId
    }

    fun put(nationalId: String, codes: List<String>, note: String?): ScenarioEntry {
        val id = requireNotNull(NationalIds.canonical(nationalId)) { "national id must be 9 or 10 digits" }
        require(codes.size <= IdentityScenario.entries.size) { "too many scenarios" }
        require(note == null || note.length <= MAX_NOTE) { "note is too long" }
        val entry = ScenarioEntry(id, codes.map(IdentityScenario::parse).toSet(), note, ScenarioSource.RUNTIME)
        if (!runtime.containsKey(id) && runtime.size >= MAX_ENTRIES) {
            runtime.keys.firstOrNull()?.let(runtime::remove)
        }
        runtime[id] = entry
        return entry
    }

    /** Removes the runtime override (a file entry, if any, holds again). */
    fun delete(nationalId: String): Boolean {
        val id = requireNotNull(NationalIds.canonical(nationalId)) { "national id must be 9 or 10 digits" }
        return runtime.remove(id) != null
    }

    /** Remember who a provider met, so a register keyed only by national id can answer with that person. */
    fun remember(applicant: Applicant) {
        val id = NationalIds.canonical(applicant.nationalId) ?: return
        val now = clock.instant()
        seen[id] = applicant to now
        val cutoff = now.minus(ProviderSimulator.RETENTION)
        seen.values.removeIf { it.second.isBefore(cutoff) }
        if (seen.size > ProviderSimulator.MAX_SESSIONS) {
            seen.entries.sortedBy { it.value.second }.take(seen.size - ProviderSimulator.MAX_SESSIONS)
                .forEach { seen.remove(it.key) }
        }
    }

    fun applicant(nationalId: String): Applicant? = NationalIds.canonical(nationalId)?.let { seen[it]?.first }

    private fun fromFile(): Map<String, ScenarioEntry> {
        val path = scenariosFile.map(String::trim).filter(String::isNotEmpty).map(Path::of).orElse(null)
            ?: return emptyMap()
        if (!Files.isReadable(path)) return fileEntries
        try {
            val stamp = Files.getLastModifiedTime(path)
            if (stamp != fileStamp) {
                fileEntries = parse(MAPPER.readValue<List<ScenarioFileEntry>>(path.toFile()))
                fileStamp = stamp
                log.infof("identity scenarios: %d people from %s", fileEntries.size, path)
            }
        } catch (e: IOException) {
            log.warnf("identity scenarios file %s is unreadable (%s); keeping the previous fixtures", path, e.message)
        } catch (e: JacksonException) {
            log.warnf("identity scenarios file %s is not valid JSON (%s); keeping the previous ones", path, e.message)
        }
        return fileEntries
    }

    private fun parse(records: List<ScenarioFileEntry>): Map<String, ScenarioEntry> = records.mapNotNull { r ->
        val id = NationalIds.canonical(r.nationalId)
        if (id == null) {
            log.warnf("identity scenarios: skipping an entry with national_id '%s'", r.nationalId)
            return@mapNotNull null
        }
        val scenarios = r.scenarios.orEmpty().mapNotNull { code ->
            IdentityScenario.entries.firstOrNull { it.name == code.trim() }
                ?: null.also { log.warnf("identity scenarios: unknown scenario '%s' for %s ignored", code, id) }
        }.toSet()
        ScenarioEntry(id, scenarios, r.note, ScenarioSource.FILE)
    }.associateBy { it.nationalId }

    companion object {
        const val MAX_ENTRIES = 10_000
        const val MAX_NOTE = 500
        private val MAPPER = jacksonObjectMapper()
    }
}
