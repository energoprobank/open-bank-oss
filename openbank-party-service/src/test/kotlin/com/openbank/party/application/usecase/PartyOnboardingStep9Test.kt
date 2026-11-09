// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.identity.BlindIndex
import com.openbank.party.application.port.`in`.OnboardingDetailsCommand
import com.openbank.party.application.port.`in`.VerifiedIdentityCommand
import com.openbank.party.application.port.out.PartyDocumentRepository
import com.openbank.party.domain.model.Address
import com.openbank.party.domain.model.DocumentType
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyDocument
import com.openbank.party.domain.model.PartyEvent
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/** Experience v2 step 9: the server is as strict as the app, adults only, one person per national number. */
class PartyOnboardingStep9Test {
    private val now = Instant.parse("2026-11-09T10:00:00Z")
    private val id = UUID.fromString("44444444-4444-4444-4444-444444444444")
    private val updated = slot<Party>()
    private val event = slot<PartyEvent>()
    private val sofia = Address("бул. Витоша 15", null, "София", "1000", "BG")

    private fun service(pepper: String? = null, required: Boolean = false) = PartyService().apply {
        partyRepo = mockk()
        metrics = mockk(relaxed = true)
        changeMetrics = mockk(relaxed = true)
        rcPepper = Optional.ofNullable(pepper)
        dedupRequired = required
        clock = Clock.fixed(now, ZoneOffset.UTC)
    }

    private fun applicant(market: String = "BG", partyId: UUID = id, status: PartyStatus = PartyStatus.PENDING_KYC) =
        Party(
            id = partyId, partyType = PartyType.INDIVIDUAL, status = status, legalName = "Мария Георгиева",
            tradingName = null, dateOfBirth = null, nationality = null, taxId = null, registrationNumber = null,
            email = "maria@example.bg", phone = null, address = null, kycStatus = KycStatus.NOT_STARTED,
            createdAt = now, updatedAt = now, market = market,
        )

    private fun details(dob: String, egn: String, nationality: String? = "BG") =
        OnboardingDetailsCommand(id, dob, egn, "+359871234567", sofia, nationality)

    @Test
    fun `an adult's details are stored with the nationality and a BG blind index`(): Unit = runBlocking {
        val service = service(pepper = "pepper")
        coEvery { service.partyRepo.findById(id) } returns applicant()
        coEvery { service.partyRepo.findByRcBlindIndex(any()) } returns null
        stubModify(service.partyRepo, updated, event)

        service.updateOnboardingDetails(details("1975-01-01", "7501010010", "bg"))

        assertThat(updated.captured.nationality).isEqualTo("BG")
        assertThat(updated.captured.rcBlindIndex).isEqualTo(BlindIndex.compute("pepper".toByteArray(), "BG:7501010010"))
    }

    @Test
    fun `a minor is refused`(): Unit = runBlocking {
        val service = service()
        coEvery { service.partyRepo.findById(id) } returns applicant()
        assertThatThrownBy { runBlocking { service.updateOnboardingDetails(details("2009-01-01", "0941010005")) } }
            .isInstanceOfSatisfying(ResourceConflictException::class.java) { assertThat(it.code).isEqualTo("UNDERAGE") }
    }

    @Test
    fun `a number another customer already has is refused`(): Unit = runBlocking {
        val service = service(pepper = "pepper")
        coEvery { service.partyRepo.findById(id) } returns applicant()
        coEvery { service.partyRepo.findByRcBlindIndex(any()) } returns applicant(partyId = UUID.randomUUID())
        assertThatThrownBy { runBlocking { service.updateOnboardingDetails(details("1975-01-01", "7501010010")) } }
            .isInstanceOfSatisfying(ResourceConflictException::class.java) {
                assertThat(it.code).isEqualTo("NATIONAL_ID_TAKEN")
            }
    }

    @Test
    fun `without a pepper, required dedup refuses rather than store a possible duplicate`(): Unit = runBlocking {
        val service = service(required = true)
        coEvery { service.partyRepo.findById(id) } returns applicant()
        assertThatThrownBy { runBlocking { service.updateOnboardingDetails(details("1975-01-01", "7501010010")) } }
            .isInstanceOfSatisfying(ResourceConflictException::class.java) {
                assertThat(it.code).isEqualTo("DEDUP_UNAVAILABLE")
            }
    }

    @Test
    fun `an unknown nationality and a future birth number are refused`(): Unit = runBlocking {
        val service = service()
        coEvery { service.partyRepo.findById(id) } returns applicant(market = "CZ")
        assertThatThrownBy {
            runBlocking { service.updateOnboardingDetails(details("1975-01-01", "7501010010", "XX")) }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            runBlocking { service.updateOnboardingDetails(details("2030-01-01", "3001011233", "CZ")) }
        }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("rodné číslo")
    }

    @Test
    fun `a verified identity records the method and the verified document once`(): Unit = runBlocking {
        val service = service()
        val documents = mockk<PartyDocumentRepository>()
        service.documentRepo = documents
        coEvery { service.partyRepo.findById(id) } returns applicant()
        coEvery { documents.findByPartyId(id) } returns emptyList()
        coEvery { documents.save(any()) } answers { firstArg<PartyDocument>() }
        stubModify(service.partyRepo, updated, event)

        service.recordVerifiedIdentity(
            VerifiedIdentityCommand(id, "DOCUMENT", DocumentType.NATIONAL_ID, "645123987", "bg", "2032-05-01"),
        )

        assertThat(updated.captured.identityMethod).isEqualTo("DOCUMENT")
        assertThat(updated.captured.identityVerifiedAt).isEqualTo(now)
        coVerify { documents.save(match { it.verifiedAt == now && it.issuingCountry == "BG" }) }
    }
}
