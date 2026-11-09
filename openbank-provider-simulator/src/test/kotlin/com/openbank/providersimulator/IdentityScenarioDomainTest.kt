// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator

import com.openbank.providersimulator.domain.Applicant
import com.openbank.providersimulator.domain.AuthorizationCode
import com.openbank.providersimulator.domain.DocumentFindings
import com.openbank.providersimulator.domain.DocumentType
import com.openbank.providersimulator.domain.HsJwt
import com.openbank.providersimulator.domain.IdentityScenario
import com.openbank.providersimulator.domain.IdentityScenario.BANKID_OTHER_PERSON
import com.openbank.providersimulator.domain.IdentityScenario.CHIP_FORGED
import com.openbank.providersimulator.domain.IdentityScenario.EXPIRED_DOCUMENT
import com.openbank.providersimulator.domain.IdentityScenario.FACE_GREY
import com.openbank.providersimulator.domain.IdentityScenario.FACE_MISMATCH
import com.openbank.providersimulator.domain.IdentityScenario.FIRST_PAYMENT_OTHER_NAME
import com.openbank.providersimulator.domain.IdentityScenario.GRAO_MISMATCH
import com.openbank.providersimulator.domain.IdentityScenario.INSOLVENT
import com.openbank.providersimulator.domain.IdentityScenario.INVALID_DOCUMENT
import com.openbank.providersimulator.domain.IdentityScenario.LIVENESS_FAIL
import com.openbank.providersimulator.domain.IdentityScenario.OCR_NAME_DIFFERS
import com.openbank.providersimulator.domain.IdentityScenario.UNDERAGE_DOCUMENT
import com.openbank.providersimulator.domain.NationalIds
import com.openbank.providersimulator.domain.OidcClients
import com.openbank.providersimulator.domain.OtherBank
import com.openbank.providersimulator.domain.OtherBankOrder
import com.openbank.providersimulator.domain.People
import com.openbank.providersimulator.domain.Pkce
import com.openbank.providersimulator.domain.Redirects
import com.openbank.providersimulator.domain.RegisterFindings
import com.openbank.providersimulator.domain.Sex
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class IdentityScenarioDomainTest {
    private val today = LocalDate.parse("2026-11-09")
    private val jana = Applicant("Jana", "Nováková", "1990-05-01", "905501/1234")
    private val ivan = Applicant("Ivan Petrov", "Georgiev", "1988-02-14", "8802145663")
    private val none = emptySet<IdentityScenario>()

    private fun doc(scenarios: Set<IdentityScenario>, applicant: Applicant = jana, country: String = "CZ") =
        DocumentFindings.document(applicant, DocumentType.ID_CARD, country, scenarios, today)

    // ---------------------------------------------------------------- national ids

    @Test
    fun `birth numbers and EGNs carry the date of birth and the sex`() {
        assertThat(NationalIds.canonical("905501/1234")).isEqualTo("9055011234")
        assertThat(NationalIds.canonical("12-34")).isNull()
        assertThat(NationalIds.sexOf("9055011234", "CZ")).isEqualTo(Sex.FEMALE)
        assertThat(NationalIds.sexOf("9075011234", "CZ")).isEqualTo(Sex.FEMALE)
        assertThat(NationalIds.sexOf("9005011234", "CZ")).isEqualTo(Sex.MALE)
        assertThat(NationalIds.sexOf("8802145673", "BG")).isEqualTo(Sex.FEMALE)
        assertThat(NationalIds.sexOf("8802145663", "BG")).isEqualTo(Sex.MALE)
        assertThat(NationalIds.sexOf("880214566", "BG")).isNull()
        assertThat(NationalIds.birthDateOf("9055011234", "CZ")).isEqualTo(LocalDate.parse("1990-05-01"))
        assertThat(NationalIds.birthDateOf("0525011234", "CZ")).isEqualTo(LocalDate.parse("2005-05-01"))
        assertThat(NationalIds.birthDateOf("520501123", "CZ")).isEqualTo(LocalDate.parse("1952-05-01"))
        assertThat(NationalIds.birthDateOf("0545011234", "BG")).isEqualTo(LocalDate.parse("2005-05-01"))
        assertThat(NationalIds.birthDateOf("8825011234", "BG")).isEqualTo(LocalDate.parse("1888-05-01"))
        assertThat(NationalIds.birthDateOf("8802145673", "BG")).isEqualTo(LocalDate.parse("1988-02-14"))
        assertThat(NationalIds.birthDateOf("8835011234", "BG")).isNull()
        assertThat(NationalIds.birthDateOf("9013451234", "CZ")).isNull()
    }

    @Test
    fun `generated birth numbers and EGNs are valid`() {
        val rc = NationalIds.czechBirthNumber(LocalDate.parse("1984-03-15"), Sex.FEMALE, 123)
        assertThat(rc).startsWith("845315123")
        assertThat(rc.last().digitToInt().toLong()).isEqualTo(rc.take(9).toLong() % 11 % 10)
        val egn = NationalIds.bulgarianEgn(LocalDate.parse("2001-07-04"), Sex.FEMALE, 42)
        assertThat(egn).startsWith("014704")
        assertThat(NationalIds.sexOf(egn, "BG")).isEqualTo(Sex.FEMALE)
        assertThat(NationalIds.birthDateOf(egn, "BG")).isEqualTo(LocalDate.parse("2001-07-04"))
        val weights = intArrayOf(2, 4, 8, 5, 10, 9, 7, 3, 6)
        val sum = egn.take(9).mapIndexed { i, c -> c.digitToInt() * weights[i] }.sum()
        assertThat(egn.last().digitToInt()).isEqualTo(sum % 11 % 10)
        assertThat(NationalIds.sexOf(NationalIds.bulgarianEgn(LocalDate.parse("1975-01-02"), Sex.MALE, 7), "BG"))
            .isEqualTo(Sex.MALE)
    }

    @Test
    fun `an unknown scenario code is refused`() {
        assertThat(IdentityScenario.parse(" face_grey ")).isEqualTo(FACE_GREY)
        assertThatThrownBy { IdentityScenario.parse("NOPE") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---------------------------------------------------------------- IDV

    @Test
    fun `with no scenario the document is the applicant as submitted`() {
        val d = doc(none)
        assertThat(d.givenName).isEqualTo("Jana")
        assertThat(d.familyName).isEqualTo("Nováková")
        assertThat(d.dateOfBirth).isEqualTo(LocalDate.parse("1990-05-01"))
        assertThat(d.sex).isEqualTo(Sex.FEMALE)
        assertThat(d.nationalId).isEqualTo("9055011234")
        assertThat(d.nationality).isEqualTo("CZ")
        assertThat(d.expiryDate).isEqualTo(today.plusYears(6))
        assertThat(d.documentNumber).hasSize(9).isEqualTo(doc(none).documentNumber)
        assertThat(DocumentFindings.documentNumber(jana, DocumentType.PASSPORT, "CZ")).hasSize(8)
        assertThat(DocumentFindings.documentNumber(jana, DocumentType.RESIDENCE_PERMIT, "CZ")).startsWith("RP")
        assertThat(doc(none, jana.copy(nationalId = "9105011234")).documentNumber).isNotEqualTo(d.documentNumber)
        val noId = Applicant("Jana", "Nováková", null, null)
        assertThat(doc(none, noId).dateOfBirth).isNull()
        assertThat(doc(none, noId).documentNumber).hasSize(9)
        assertThat(doc(none, jana.copy(dateOfBirth = null)).dateOfBirth).isEqualTo(LocalDate.parse("1990-05-01"))
    }

    @Test
    fun `document scenarios - expired, other name, underage`() {
        assertThat(doc(setOf(EXPIRED_DOCUMENT)).expiryDate).isBefore(today)
        assertThat(doc(setOf(OCR_NAME_DIFFERS)).familyName).isNotEqualTo("Nováková").isEqualTo("Svobodová")
        assertThat(doc(setOf(OCR_NAME_DIFFERS), jana.copy(familyName = "Svobodová")).familyName)
            .isEqualTo("Dvořáková")
        assertThat(doc(setOf(UNDERAGE_DOCUMENT)).dateOfBirth).isEqualTo(today.minusYears(16))
    }

    @Test
    fun `the chip verifies unless forged`() {
        val d = doc(none)
        val ok = DocumentFindings.chip(d, none)
        assertThat(ok.passiveAuthenticationValid).isTrue()
        assertThat(ok.signer).isEqualTo("CSCA-CZ 2021")
        assertThat(ok.dg1).isEqualTo(d)
        assertThat(DocumentFindings.chip(d, setOf(CHIP_FORGED)).passiveAuthenticationValid).isFalse()
        assertThat(DocumentFindings.chip(doc(none, ivan, "BG"), none).signer).isEqualTo("CSCA-BG 2020")
    }

    @Test
    fun `selfie scenarios - liveness fails, face mismatches or is grey`() {
        val ok = DocumentFindings.selfie(none)
        assertThat(ok.livenessScore).isEqualTo(0.97)
        assertThat(ok.livenessPassed).isTrue()
        assertThat(ok.faceMatchScore).isEqualTo(0.93)
        assertThat(DocumentFindings.selfie(setOf(LIVENESS_FAIL)).livenessPassed).isFalse()
        assertThat(DocumentFindings.selfie(setOf(FACE_MISMATCH)).faceMatchScore).isEqualTo(0.42)
        assertThat(DocumentFindings.selfie(setOf(FACE_GREY)).faceMatchScore).isEqualTo(0.71)
    }

    // ---------------------------------------------------------------- Bank iD

    @Test
    fun `Bank iD returns the applicant, or somebody else under BANKID_OTHER_PERSON`() {
        val me = People.bankId(jana, none)
        assertThat(me.givenName).isEqualTo("Jana")
        assertThat(me.birthNumber).isEqualTo("9055011234")
        assertThat(me.sex).isEqualTo(Sex.FEMALE)
        assertThat(me.birthdate).isEqualTo(LocalDate.parse("1990-05-01"))
        assertThat(me.sub).isEqualTo(People.bankId(jana, none).sub)
        val other = People.bankId(jana, setOf(BANKID_OTHER_PERSON))
        assertThat(other.familyName).isNotEqualTo("Nováková")
        assertThat(other.birthNumber).isNotEqualTo("9055011234")
        val otherNumber = requireNotNull(other.birthNumber)
        assertThat(otherNumber.last().digitToInt().toLong()).isEqualTo(otherNumber.take(9).toLong() % 11 % 10)
        assertThat(other.sub).isNotEqualTo(me.sub)
        assertThat(other.birthdate).isNotEqualTo(me.birthdate)
        val anonymous = People.bankId(Applicant("Jan", "Novák", null, null), setOf(BANKID_OTHER_PERSON))
        assertThat(anonymous.sex).isEqualTo(Sex.MALE)
    }

    // ---------------------------------------------------------------- registers

    @Test
    fun `registers - invalid document, insolvency, GRAO and MVR`() {
        val number = doc(none).documentNumber
        assertThat(RegisterFindings.listedAsInvalid(" $number ", listOf("9055011234"))).isTrue()
        assertThat(RegisterFindings.listedAsInvalid(number, listOf("9105011234"))).isFalse()
        assertThat(RegisterFindings.isir("9055011234", none)).isEmpty()
        assertThat(RegisterFindings.isir("9055011234", setOf(INSOLVENT)).single().caseNumber)
            .startsWith("KSPH 38 INS ")
        assertThat(RegisterFindings.bgInsolvency("8802145673", setOf(INSOLVENT))).hasSize(1)
        assertThat(RegisterFindings.bgInsolvency("8802145673", none)).isEmpty()

        val grao = RegisterFindings.grao("8802145663", ivan, none)
        assertThat(listOf(grao.firstName, grao.surName, grao.familyName))
            .containsExactly("Ivan", "Petrov", "Georgiev")
        assertThat(grao.birthDate).isEqualTo(LocalDate.parse("1988-02-14"))
        val mismatch = RegisterFindings.grao("8802145663", ivan, setOf(GRAO_MISMATCH))
        assertThat(mismatch.familyName).isNotEqualTo("Georgiev")
        assertThat(mismatch.birthDate).isNotEqualTo(grao.birthDate)

        val bgNumber = doc(none, ivan, "BG").documentNumber
        assertThat(RegisterFindings.mvr("8802145663", bgNumber, none, today).valid).isTrue()
        assertThat(RegisterFindings.mvr("8802145663", bgNumber, none, today).validTo).isEqualTo(today.plusYears(6))
        assertThat(RegisterFindings.mvr("8802145663", bgNumber, setOf(INVALID_DOCUMENT), today).valid).isFalse()
        assertThat(RegisterFindings.mvr("8802145663", bgNumber, setOf(EXPIRED_DOCUMENT), today).valid).isFalse()
        assertThat(RegisterFindings.mvr("8802145663", "123", none, today).validTo).isNull()
    }

    // ---------------------------------------------------------------- other bank

    @Test
    fun `the first payment carries the payer's name unless FIRST_PAYMENT_OTHER_NAME`() {
        val order = OtherBankOrder(
            "Jana Nováková", "19-2000145399", "0800", BigDecimal("1"), "czk", "2002222222", "2010",
            "9055011234", "9055011234",
        )
        OtherBank.validate(order)
        val t = Instant.parse("2026-11-09T10:00:00Z")
        val plain = OtherBank.receive("p1", order, none, t)
        assertThat(plain.payerName).isEqualTo("Jana Nováková")
        assertThat(plain.payerBankName).isEqualTo("Česká spořitelna")
        assertThat(plain.amount.toPlainString()).isEqualTo("1.00")
        assertThat(plain.currency).isEqualTo("CZK")
        assertThat(OtherBank.matches(plain, "2002222222", "9055011234")).isTrue()
        assertThat(OtherBank.matches(plain, "2002222222", null)).isTrue()
        assertThat(OtherBank.matches(plain, "2002222222", "1")).isFalse()
        val other = OtherBank.receive("p2", order, setOf(FIRST_PAYMENT_OTHER_NAME), t)
        assertThat(other.payerName).isNotEqualTo("Jana Nováková").isEqualTo("Lucie Svobodová")
        assertThat(OtherBank.receive("p3", order.copy(payerBankCode = "9999"), none, t).payerBankName)
            .isEqualTo("Bank 9999")
        assertThatThrownBy { OtherBank.validate(order.copy(amount = BigDecimal.ZERO)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { OtherBank.validate(order.copy(variableSymbol = "12345678901")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---------------------------------------------------------------- OIDC primitives

    @Test
    fun `PKCE S256 matches RFC 7636's example and refuses anything else`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertThat(Pkce.s256(verifier)).isEqualTo(challenge)
        assertThat(Pkce.verifies(verifier, challenge)).isTrue()
        assertThat(Pkce.verifies(verifier.reversed(), challenge)).isFalse()
        assertThat(Pkce.verifies("short", Pkce.s256("short"))).isFalse()
        assertThat(Pkce.verifies(null, challenge)).isFalse()
    }

    @Test
    fun `clients - configured ones by secret, otherwise any non-blank secret`() {
        val closed = OidcClients.parse(" openbank-onboarding:secret , other:x")
        assertThat(closed.knows("openbank-onboarding")).isTrue()
        assertThat(closed.knows("stranger")).isFalse()
        assertThat(closed.authenticates("openbank-onboarding", "secret")).isTrue()
        assertThat(closed.authenticates("openbank-onboarding", "wrong")).isFalse()
        assertThat(closed.authenticates("stranger", "secret")).isFalse()
        val open = OidcClients.parse(null)
        assertThat(open.open).isTrue()
        assertThat(open.knows("anyone")).isTrue()
        assertThat(open.authenticates("anyone", "s")).isTrue()
        assertThat(open.authenticates("anyone", " ")).isFalse()
        assertThatThrownBy { OidcClients.parse("nosecret") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a code is redeemed only by its client, redirect and verifier, within its life`() {
        val verifier = "a".repeat(43)
        val t = Instant.parse("2026-11-09T10:00:00Z")
        val identity = People.bankId(jana, none)
        val code = AuthorizationCode("c", "client", "https://bank/cb", Pkce.s256(verifier), "n", identity, t)
        val ttl = Duration.ofSeconds(60)
        assertThat(code.refusal("client", "https://bank/cb", verifier, t.plusSeconds(10), ttl)).isNull()
        assertThat(code.refusal("client", "https://bank/cb", verifier, t.plusSeconds(61), ttl)).contains("expired")
        assertThat(code.refusal("other", "https://bank/cb", verifier, t, ttl)).contains("another client")
        assertThat(code.refusal("client", "https://evil/cb", verifier, t, ttl)).contains("redirect_uri")
        assertThat(code.refusal("client", "https://bank/cb", "b".repeat(43), t, ttl)).contains("PKCE")
    }

    @Test
    fun `id tokens are HS256 over the client secret, redirects carry their parameters`() {
        val jwt = HsJwt.sign("""{"sub":"x"}""", "secret")
        val parts = jwt.split('.')
        assertThat(parts).hasSize(3)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("secret".toByteArray(), "HmacSHA256")) }
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal("${parts[0]}.${parts[1]}".toByteArray()))
        assertThat(parts[2]).isEqualTo(expected)
        assertThat(String(Base64.getUrlDecoder().decode(parts[1]))).isEqualTo("""{"sub":"x"}""")

        assertThat(Redirects.withParams("app://cb", listOf("code" to "a b", "state" to "s")))
            .isEqualTo("app://cb?code=a+b&state=s")
        assertThat(Redirects.withParams("https://b/cb?x=1", listOf("code" to "c"))).isEqualTo("https://b/cb?x=1&code=c")
        assertThat(Redirects.isAcceptable("https://bank/cb")).isTrue()
        assertThat(Redirects.isAcceptable("/relative")).isFalse()
        assertThat(Redirects.isAcceptable("https://bank/cb#frag")).isFalse()
        assertThat(Redirects.isAcceptable("not a uri")).isFalse()
        val basic = "Basic " + Base64.getEncoder().encodeToString("my%3Aclient:s3cr%26t".toByteArray())
        assertThat(Redirects.basicCredentials(basic)).isEqualTo("my:client" to "s3cr&t")
        assertThat(Redirects.basicCredentials("Bearer x")).isNull()
        assertThat(Redirects.basicCredentials("Basic !!!")).isNull()
        assertThat(Redirects.basicCredentials("Basic " + Base64.getEncoder().encodeToString("nocolon".toByteArray())))
            .isNull()
    }
}
