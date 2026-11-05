// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.providersimulator.domain.Applicant
import com.openbank.providersimulator.domain.DeliveredMessage
import com.openbank.providersimulator.domain.EnergoProCustomer
import com.openbank.providersimulator.domain.EnergoProDirectory
import com.openbank.providersimulator.domain.Verification
import com.openbank.providersimulator.domain.VerificationKind
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The simulator's clock; tests replace it to move a provider session forward. */
@Singleton
class ClockProducer {
    @Produces
    @Singleton
    fun clock(): Clock = Clock.systemUTC()
}

/**
 * The third-party providers a bank needs for onboarding and payments, simulated in one process
 * (NON-PRODUCTION, like clearing-simulator): Energo-Pro's customer system, the e-mail/SMS gateway,
 * Czech Bank iD, and an identity-verification vendor (document, chip, liveness, face match).
 * Each has its own path and its own adapter port in the bank, so replacing one with the real provider
 * is a configuration change on the bank side. Sessions live in memory: a restart forgets them, as
 * an expired vendor session would.
 */
@ApplicationScoped
class ProviderSimulator @Inject constructor(
    private val clock: Clock,
    @ConfigProperty(name = "openbank.provider-simulator.step-seconds", defaultValue = "1")
    private val stepSeconds: Long,
    @ConfigProperty(name = "openbank.provider-simulator.energo-pro.generated-customers", defaultValue = "400")
    private val generatedCustomers: Int,
    @ConfigProperty(name = "openbank.provider-simulator.energo-pro.seed-file")
    private val seedFile: Optional<String>,
) {
    private val log = Logger.getLogger(ProviderSimulator::class.java)
    private val verifications = ConcurrentHashMap<String, Verification>()
    private val outbox = ConcurrentHashMap<String, DeliveredMessage>()

    val directory: EnergoProDirectory by lazy {
        val seeded = seedFile.filter { it.isNotBlank() }.map { loadSeed(Path.of(it)) }.orElse(emptyList())
        EnergoProDirectory(EnergoProDirectory.generate(generatedCustomers), seeded).also {
            log.infof("Energo-Pro directory: %d customers (%d from the seed file)", it.size, seeded.size)
        }
    }

    // ---------------------------------------------------------------- Energo-Pro

    fun energoProCustomer(customerNumber: String): EnergoProCustomer? = directory.find(customerNumber)

    // ---------------------------------------------------------------- identity providers

    fun startVerification(
        kind: VerificationKind,
        reference: String,
        applicant: Applicant,
        documentType: String?,
    ): Verification {
        require(reference.isNotBlank() && reference.length <= MAX_REFERENCE) {
            "reference must be 1..$MAX_REFERENCE characters"
        }
        require(applicant.familyName.isNotBlank()) { "applicant.family_name is required" }
        evictExpired()
        val verification = Verification(
            id = UUID.randomUUID().toString(),
            kind = kind,
            reference = reference,
            applicant = applicant,
            documentType = documentType,
            startedAt = clock.instant(),
            stepSeconds = stepSeconds,
        )
        verifications[verification.id] = verification
        return verification
    }

    fun verification(id: String, kind: VerificationKind): Verification? = verifications[id]?.takeIf { it.kind == kind }

    /** A document scan or a liveness check (never a Bank iD session, which has its own path). */
    fun verificationOfAnyKind(id: String): Verification? = verifications[id]?.takeIf {
        it.kind !=
            VerificationKind.BANK_ID
    }

    fun now() = clock.instant()

    // ---------------------------------------------------------------- OTP gateway

    fun deliver(channel: DeliveredMessage.Channel, recipient: String, text: String): DeliveredMessage {
        require(recipient.isNotBlank() && recipient.length <= MAX_RECIPIENT) {
            "recipient must be 1..$MAX_RECIPIENT characters"
        }
        require(text.isNotBlank() && text.length <= MAX_TEXT) { "text must be 1..$MAX_TEXT characters" }
        evictExpired()
        val message = DeliveredMessage(UUID.randomUUID().toString(), channel, recipient.trim(), text, clock.instant())
        outbox[message.id] = message
        return message
    }

    /** What was sent to [recipient] (newest first): the sandbox's view of a phone or a mailbox. */
    fun sentTo(recipient: String): List<DeliveredMessage> {
        val wanted = normalise(recipient)
        return outbox.values.filter { normalise(it.recipient) == wanted }.sortedByDescending { it.sentAt }
    }

    private fun normalise(recipient: String) = if (recipient.contains(
            '@',
        )
    ) {
        EnergoProCustomer.normaliseEmail(recipient)
    } else {
        EnergoProCustomer.normalisePhone(recipient)
    }

    private fun evictExpired() {
        val cutoff = clock.instant().minus(RETENTION)
        verifications.values.removeIf { it.startedAt.isBefore(cutoff) }
        outbox.values.removeIf { it.sentAt.isBefore(cutoff) }
        // A hard cap as well: a sandbox must not grow without bound under a load test.
        if (verifications.size > MAX_SESSIONS) {
            verifications.values.sortedBy {
                it.startedAt
            }.take(verifications.size - MAX_SESSIONS).forEach { verifications.remove(it.id) }
        }
        if (outbox.size > MAX_SESSIONS) {
            outbox.values.sortedBy { it.sentAt }.take(outbox.size - MAX_SESSIONS).forEach { outbox.remove(it.id) }
        }
    }

    private fun loadSeed(path: Path): List<EnergoProCustomer> {
        if (!Files.isReadable(path)) {
            log.warnf("Energo-Pro seed file %s is not readable; only the generated population is served", path)
            return emptyList()
        }
        val records: List<EnergoProCustomer> = SEED_MAPPER.readValue(path.toFile())
        records.forEach {
            require(EnergoProCustomer.NUMBER.matches(it.customerNumber)) {
                "seed: bad customer number ${it.customerNumber}"
            }
        }
        return records
    }

    companion object {
        val RETENTION: Duration = Duration.ofHours(2)
        const val MAX_SESSIONS = 10_000
        const val MAX_REFERENCE = 128
        const val MAX_RECIPIENT = 254
        const val MAX_TEXT = 480
        private val SEED_MAPPER: ObjectMapper = jacksonObjectMapper()
    }
}
