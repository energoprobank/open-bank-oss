// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.party.application.port.`in`.CreatePartyCommand
import com.openbank.party.application.port.`in`.OnboardingDetailsCommand
import com.openbank.party.application.port.`in`.TaxSelfCertificationCommand
import com.openbank.party.domain.model.AccountPurpose
import com.openbank.party.domain.model.Address
import com.openbank.party.domain.model.AmlProfileDeclaration
import com.openbank.party.domain.model.AmlProfiles
import com.openbank.party.domain.model.CrsStatus
import com.openbank.party.domain.model.ExpectedMonthlyTurnover
import com.openbank.party.domain.model.IncomeSource
import com.openbank.party.domain.model.InvalidAmlProfileException
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.Occupation
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyEvent
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
import com.openbank.party.domain.model.PepDeclaration
import com.openbank.party.domain.model.TaxResidency
import io.mockk.coEvery
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

/** Experience v2 step 8: the market on the party, onboarding details and the tax self-certification. */
class PartyMarketOnboardingTest {
    private val now = Instant.parse("2026-11-05T10:00:00Z")
    private val id = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val updated = slot<Party>()
    private val event = slot<PartyEvent>()

    private fun service() = PartyService().apply {
        partyRepo = mockk()
        metrics = mockk(relaxed = true)
        changeMetrics = mockk(relaxed = true)
        rcPepper = Optional.empty()
        clock = Clock.fixed(now, ZoneOffset.UTC)
    }

    private fun applicant(market: String, status: PartyStatus = PartyStatus.PENDING_KYC, taxId: String? = null) = Party(
        id = id,
        partyType = PartyType.INDIVIDUAL,
        status = status,
        legalName = "Мария Георгиева",
        tradingName = null,
        dateOfBirth = null,
        nationality = null,
        taxId = taxId,
        registrationNumber = null,
        email = "maria@example.bg",
        phone = null,
        address = null,
        kycStatus = KycStatus.NOT_STARTED,
        createdAt = now,
        updatedAt = now,
        market = market,
    )

    private val sofia = Address("бул. Витоша 15", null, "София", "1000", "BG")

    @Test
    fun `a party is created in the market it asks for and the event says so`(): Unit = runBlocking {
        val service = service()
        val saved = slot<Party>()
        coEvery { service.partyRepo.findByEmail(any()) } returns null
        coEvery { service.partyRepo.save(capture(saved), capture(event)) } answers { saved.captured }
        service.createParty(
            CreatePartyCommand(
                "k", PartyType.INDIVIDUAL, "Мария Георгиева", null, null, null, null, null,
                "maria@example.bg", null, null, market = "BG",
            ),
        )
        assertThat(saved.captured.market).isEqualTo("BG")
        assertThat(event.captured.envelope["market"]).isEqualTo("BG")
    }

    @Test
    fun `an unknown market is refused`(): Unit = runBlocking {
        val service = service()
        coEvery { service.partyRepo.findByEmail(any()) } returns null
        assertThatThrownBy {
            runBlocking {
                service.createParty(
                    CreatePartyCommand(
                        "k",
                        PartyType.INDIVIDUAL,
                        "X",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "x@example.com",
                        null,
                        null,
                        market = "SK",
                    ),
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a Bulgarian applicant gives an EGN that matches the date of birth`(): Unit = runBlocking {
        val service = service()
        coEvery { service.partyRepo.findById(id) } returns applicant("BG")
        stubModify(service.partyRepo, updated, event)
        service.updateOnboardingDetails(
            OnboardingDetailsCommand(id, "1975-01-01", "7501010010", "+359871234567", sofia),
        )
        assertThat(updated.captured.taxId).isEqualTo("7501010010")
        assertThat(updated.captured.dateOfBirth).isEqualTo("1975-01-01")
        assertThat(updated.captured.address).isEqualTo(sofia)
        assertThat(event.captured.eventType).isEqualTo("PARTY_UPDATED")
    }

    @Test
    fun `a Czech birth number is not an EGN, and the number must encode the birthdate`(): Unit = runBlocking {
        val service = service()
        coEvery { service.partyRepo.findById(id) } returns applicant("BG")
        stubModify(service.partyRepo, updated, event)
        assertThatThrownBy {
            runBlocking {
                service.updateOnboardingDetails(
                    OnboardingDetailsCommand(id, "1990-01-01", "9001011239", "+359871234567", sofia),
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            runBlocking {
                service.updateOnboardingDetails(
                    OnboardingDetailsCommand(id, "1975-01-02", "7501010010", "+359871234567", sofia),
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("dateOfBirth")
    }

    @Test
    fun `details are refused once the bank has decided, and the national number is set only once`(): Unit =
        runBlocking {
            val service = service()
            stubModify(service.partyRepo, updated, event)
            coEvery { service.partyRepo.findById(id) } returns applicant("BG", status = PartyStatus.ACTIVE)
            assertThatThrownBy {
                runBlocking {
                    service.updateOnboardingDetails(
                        OnboardingDetailsCommand(id, "1975-01-01", "7501010010", "+359871234567", sofia),
                    )
                }
            }.isInstanceOf(ResourceConflictException::class.java)
            coEvery { service.partyRepo.findById(id) } returns applicant("BG", taxId = "0541010000")
            assertThatThrownBy {
                runBlocking {
                    service.updateOnboardingDetails(
                        OnboardingDetailsCommand(id, "1975-01-01", "7501010010", "+359871234567", sofia),
                    )
                }
            }.isInstanceOf(ResourceConflictException::class.java)
        }

    @Test
    fun `a tax self-certification is stored without declaring the AML profile`(): Unit = runBlocking {
        val service = service()
        coEvery { service.partyRepo.findById(id) } returns applicant("BG")
        stubModify(service.partyRepo, updated, event)
        service.certifyTaxResidency(
            TaxSelfCertificationCommand(id, false, listOf(TaxResidency("bg", "7501010010"), TaxResidency("DE", "123"))),
        )
        val cert = updated.captured.taxSelfCertification!!
        assertThat(cert.residencies.map { it.country }).containsExactly("BG", "DE")
        assertThat(updated.captured.amlProfileDeclared).isFalse()
        assertThatThrownBy {
            runBlocking {
                service.certifyTaxResidency(TaxSelfCertificationCommand(id, false, listOf(TaxResidency("US", null))))
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            runBlocking {
                service.certifyTaxResidency(TaxSelfCertificationCommand(id, false, listOf(TaxResidency("XX", null))))
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the AML profile treats the party's own market as domestic`() {
        val declaration = AmlProfileDeclaration(
            purposes = setOf(AccountPurpose.entries.first()), purposeNote = null,
            incomeSources = setOf(IncomeSource.entries.first { it != IncomeSource.OTHER }), incomeNote = null,
            occupation = Occupation.entries.first { it != Occupation.OTHER }, occupationNote = null,
            expectedMonthlyTurnover = ExpectedMonthlyTurnover.entries.first(), cashIntensive = false,
            pep = PepDeclaration(false, null, null), taxResidencies = listOf(TaxResidency("BG", null)),
            usPerson = false, truthful = true,
        )
        // Before markets: BG counted as foreign for everyone.
        assertThat(AmlProfiles.derive(declaration).crsStatus).isEqualTo(CrsStatus.REPORTABLE)
        val bulgarian = declaration.copy(domesticCountry = "BG")
        bulgarian.validate()
        assertThat(AmlProfiles.derive(bulgarian).crsStatus).isEqualTo(CrsStatus.NON_REPORTABLE)
        assertThatThrownBy { bulgarian.copy(taxResidencies = listOf(TaxResidency("BG", "7501010010"))).validate() }
            .isInstanceOf(InvalidAmlProfileException::class.java)
    }
}
