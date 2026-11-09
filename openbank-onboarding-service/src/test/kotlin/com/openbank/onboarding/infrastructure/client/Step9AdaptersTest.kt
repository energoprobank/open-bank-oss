// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.client

import com.openbank.onboarding.application.port.out.IdentityCheckUnavailable
import com.openbank.onboarding.application.port.out.OutgoingPayment
import com.openbank.onboarding.application.port.out.PidApplicant
import com.openbank.onboarding.application.port.out.PidDecision
import com.openbank.onboarding.domain.model.Person
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/** Step 9 adapters: the wire mapping, and that every dependency FAILS CLOSED (503, never a pass). */
class Step9AdaptersTest {

    private val party = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val applicant = PidApplicant("Jana", "Testová", "1990-04-12", "9004120004", "CZ", "CZ")

    private fun status(code: Int) = WebApplicationException(Response.status(code).build())

    @Test
    fun `pid resolve maps the decision, and an outage or an unknown answer fails closed`() {
        val client = mockk<PidClient>()
        val body = slot<PidResolveDto>()
        every { client.resolve(capture(body)) } returns PidResolveResponse("MATCH_EXISTING", party, null)
        val adapter = PidAdapter(client)
        assertThat(adapter.resolve(applicant).decision).isEqualTo(PidDecision.MATCH_EXISTING)
        assertThat(body.captured.market).isEqualTo("CZ")
        assertThat(body.captured.nationalities).containsExactly("CZ")

        every { client.resolve(any()) } returns PidResolveResponse("SOMETHING_ELSE")
        assertThatThrownBy { adapter.resolve(applicant) }.isInstanceOf(IdentityCheckUnavailable::class.java)
        every { client.resolve(any()) } throws status(503)
        assertThatThrownBy { adapter.resolve(applicant) }.isInstanceOf(IdentityCheckUnavailable::class.java)
        every { client.resolve(any()) } throws ProcessingException("connection refused")
        assertThatThrownBy { adapter.resolve(applicant) }.isInstanceOf(IdentityCheckUnavailable::class.java)
        every { client.resolve(any()) } throws status(400)
        assertThatThrownBy { adapter.resolve(applicant) }.isInstanceOf(WebApplicationException::class.java)

        val reg = slot<PidResolveDto>()
        every { client.register(capture(reg)) } returns Unit
        adapter.register(party, applicant)
        assertThat(reg.captured.partyId).isEqualTo(party)
        assertThat(reg.captured.partyType).isEqualTo("NATURAL_PERSON")
    }

    @Test
    fun `Bank iD builds a PKCE authorize URL, exchanges with the bank's secret, maps claims`() {
        val sim = mockk<BankIdSimulatorClient>(relaxed = true)
        val oidc = mockk<BankIdOidcClient>()
        val adapter = BankIdOidcAdapter(sim, oidc, "http://bankid.test/", "client", "secret", "http://edge/cb")
        val url = adapter.authorizeUrl("st", "no", "ch")
        assertThat(url).startsWith("http://bankid.test/bankid/auth?response_type=code&client_id=client")
            .contains(
                "redirect_uri=http%3A%2F%2Fedge%2Fcb",
                "code_challenge=ch",
                "code_challenge_method=S256",
                "state=st",
            )
        adapter.expectLogin("st", Person("Jana", "Testová", "1990-04-12", "9004120004", "F"))
        verify { sim.expect(match { it.state == "st" && it.applicant.familyName == "Testová" }) }

        every { oidc.token("authorization_code", "code", "http://edge/cb", "ver", "client", "secret") } returns
            TokenDto("tok")
        every { oidc.userinfo("Bearer tok") } returns
            UserinfoDto("Jana", "Testová", "1990-04-12", "female", "9004120004")
        val person = adapter.userinfo(adapter.exchange("code", "ver"))
        assertThat(person).isEqualTo(Person("Jana", "Testová", "1990-04-12", "9004120004", "F"))
    }

    @Test
    fun `documents, chip and selfie map the provider's reads`() {
        val client = mockk<IdvDocumentClient>()
        val ocr = OcrDto("Jana", "Testová", "1990-04-12", "F", "9004120004", "204512345", "CZ", "CZ", "2031-01-01")
        every { client.document(any()) } returns DocumentDto("d1", ocr, ocr.copy(checkDigitsValid = true))
        every { client.chip("d1", any()) } returns ChipDto(ocr, PassiveAuthDto("VALID", "CSCA-CZ"))
        every { client.selfie(any()) } returns SelfieDto(ScoreDto(0.97), ScoreDto(0.91))
        val adapter = DocumentVerificationAdapter(client)
        val read = adapter.readDocument("ref", "ID_CARD", "CZ", Person("Jana", "Testová", null, null, null))
        assertThat(read.mrzValid).isTrue()
        assertThat(read.documentNumber).isEqualTo("204512345")
        assertThat(adapter.readChip("d1").passiveAuthentication).isEqualTo("VALID")
        assertThat(adapter.selfie("ref", "d1").faceMatch).isEqualTo(0.91)
    }

    @Test
    fun `registers report a record, a listing or nothing`() {
        val client = mockk<RegistersClient>()
        every { client.invalidDocument("1") } returns InvalidDocumentDto(true, "ODCIZEN")
        every { client.isir("rc") } returns IsirDto(listOf(mapOf("spisova_znacka" to "KSPH 1", "stav" to "KONKURS")))
        every { client.grao(any()) } throws status(404)
        every { client.mvr(any()) } returns MvrDocumentDto("VALID", "2030-01-01")
        every { client.bgInsolvency(any()) } returns ProceedingsDto()
        val adapter = RegistersAdapter(client)
        assertThat(adapter.czInvalidDocument("1").record).isTrue()
        assertThat(adapter.czInsolvency("rc").result).isEqualTo("RECORD")
        assertThat(adapter.bgPerson("egn")).isNull()
        assertThat(adapter.bgDocument("egn", "1").record).isFalse()
        assertThat(adapter.bgInsolvency("egn").result).isEqualTo("NO_RECORD")
    }

    @Test
    fun `the other bank, screening, KYC and party extras map their wire types`() {
        val bank = mockk<OtherBankClient>(relaxed = true)
        every { bank.incoming("acc", "vs") } returns
            listOf(IncomingDto("p", "Jana Testová", "19-1", "0800", "Other", BigDecimal.ONE, "CZK", null))
        OtherBankAdapter(bank).send(
            OutgoingPayment("Jana Testová", "19-1", "0800", BigDecimal.ONE, "CZK", "acc", "8899", "vs", null),
        )
        verify { bank.send(match { it.amount == "1" && it.variableSymbol == "vs" }) }
        assertThat(OtherBankAdapter(bank).incoming("acc", "vs").single().payerName).isEqualTo("Jana Testová")

        val sanctions = mockk<SanctionsClient>()
        every { sanctions.screen(match { it.monitor && it.identifiers["partyId"] == party.toString() }) } returns
            ScreenResponseDto("c1", "POTENTIAL_HIT", 0.7)
        val outcome = ScreeningAdapter(sanctions).screen(party, "Jana", "1990-04-12", "CZ", listOf("PEP_GLOBAL"), "k")
        assertThat(outcome.status).isEqualTo("POTENTIAL_HIT")
        assertThat(outcome.topScore).isEqualTo(0.7)

        val kyc = mockk<KycClient>()
        every { kyc.assess(party, any()) } returns KycCaseDto("case", "UNDER_REVIEW", null)
        every { kyc.latest(party) } throws status(404)
        assertThat(KycAssessmentAdapter(kyc).assess(party, emptyMap()).status).isEqualTo("UNDER_REVIEW")
        assertThat(KycAssessmentAdapter(kyc).status(party)).isNull()

        val parties = mockk<PartyIdentityClient>(relaxed = true)
        every { parties.nationalId(party) } returns NationalIdDto("9004120004")
        every { parties.party(party) } returns PartyNationalityDto("CZ")
        every { parties.amlProfile(party) } returns AmlProfileDto(
            purpose = listOf("SALARY"),
            incomeSources = listOf("EMPLOYMENT"),
            occupation = "EMPLOYED",
            taxResidencies = listOf("CZ", mapOf("country" to "DE")),
        )
        val identity = PartyIdentityAdapter(parties)
        assertThat(identity.nationalId(party)).isEqualTo("9004120004")
        assertThat(identity.nationality(party)).isEqualTo("CZ")
        assertThat(identity.financialProfile(party)!!.taxResidencies).containsExactly("CZ", "DE")
        identity.recordVerifiedIdentity(party, "DOCUMENT", "NATIONAL_ID", "1", "CZ", null)
        verify { parties.verified(party, match { it.method == "DOCUMENT" }) }
        every { parties.amlProfile(party) } throws status(404)
        assertThat(identity.financialProfile(party)).isNull()
    }
}
