// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.application.port.`in`.PostJournalOutcome
import com.openbank.ledger.application.port.`in`.PostRewardProvisionCommand
import com.openbank.ledger.application.port.`in`.RewardProvisionStatus
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.ledger.domain.model.LedgerConflictException
import com.openbank.ledger.domain.model.LedgerValidationException
import com.openbank.ledger.domain.model.RewardMarket
import com.openbank.libs.domain.money.Money
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class RewardProvisionServiceTest {

    private val ledger = mockk<LedgerUseCase>()
    private val service = RewardProvisionService(ledger)
    private val day = LocalDate.of(2026, 11, 4)

    private fun id(code: String) = UUID.fromString("a0000000-0000-0000-0000-00000000$code")

    private fun command(delta: String, sourceRef: String = "cz-2026-11-04", date: LocalDate = day) =
        PostRewardProvisionCommand(date, RewardMarket.CZ, "CZK", BigDecimal(delta), sourceRef)

    /** The entry the ledger would hand back for [cmd]: built from the command the service sent. */
    private fun entryFor(cmd: PostJournalCommand): JournalEntry {
        val journalId = UUID.randomUUID()
        return JournalEntry(
            id = journalId,
            entryNumber = 42L,
            transactionId = cmd.transactionId,
            entryDate = cmd.entryDate,
            valueDate = cmd.valueDate,
            description = cmd.description,
            status = JournalStatus.POSTED,
            lines = cmd.lines.mapIndexed { i, l ->
                JournalLine(
                    id = UUID.randomUUID(),
                    journalId = journalId,
                    glAccountId = l.glAccountId,
                    side = l.side,
                    amount = Money.of(l.amount, l.currencyCode),
                    fxRate = null,
                    baseAmount = Money.of(l.baseAmount, l.baseCurrencyCode),
                    sequence = i + 1,
                )
            },
            createdAt = Instant.EPOCH,
            createdBy = cmd.postedBy,
            version = 0,
        )
    }

    @Test
    fun `posts the fixed template under a key derived from sourceRef`(): Unit = runBlocking {
        val sent = slot<PostJournalCommand>()
        coEvery { ledger.postJournalWithOutcome(capture(sent)) } answers {
            PostJournalOutcome(entryFor(sent.captured), replayed = false)
        }

        val result = service.post(command("1000.00"))

        assertThat(result.status).isEqualTo(RewardProvisionStatus.POSTED)
        assertThat(result.entryNumber).isEqualTo(42L)
        val cmd = sent.captured
        assertThat(cmd.idempotencyKey).isEqualTo("flux-provision:cz-2026-11-04")
        assertThat(cmd.transactionId).isEqualTo(UUID.nameUUIDFromBytes(cmd.idempotencyKey.toByteArray()))
        assertThat(cmd.entryDate).isEqualTo(day)
        assertThat(cmd.valueDate).isEqualTo(day)
        assertThat(cmd.postedBy).isEqualTo(RewardProvisionService.REWARDS_POSTER)
        assertThat(cmd.lines.map { Triple(it.glAccountId, it.side, it.amount) }).containsExactly(
            Triple(id("5300"), JournalSide.DEBIT, BigDecimal("1000.00")),
            Triple(id("2400"), JournalSide.CREDIT, BigDecimal("1000.00")),
        )
        assertThat(cmd.lines).allSatisfy {
            assertThat(it.subAccountId).isNull()
            assertThat(it.currencyCode).isEqualTo("CZK")
            assertThat(it.baseCurrencyCode).isEqualTo("CZK")
            assertThat(it.baseAmount).isEqualTo(it.amount)
        }
    }

    @Test
    fun `the same sourceRef with the same content is a replay, not a second posting`(): Unit = runBlocking {
        val sent = slot<PostJournalCommand>()
        coEvery { ledger.postJournalWithOutcome(capture(sent)) } answers {
            PostJournalOutcome(entryFor(sent.captured), replayed = true)
        }

        val result = service.post(command("1000"))

        assertThat(result.status).isEqualTo(RewardProvisionStatus.REPLAYED)
    }

    @Test
    fun `the same sourceRef with a different amount is a 409, not a silent replay`(): Unit = runBlocking {
        // The ledger returns the ORIGINAL entry (1000.00) for the key; the caller now says 900.00.
        val original = slot<PostJournalCommand>()
        coEvery { ledger.postJournalWithOutcome(capture(original)) } answers {
            PostJournalOutcome(entryFor(original.captured), replayed = false)
        }
        service.post(command("1000.00"))
        val firstEntry = entryFor(original.captured)
        coEvery { ledger.postJournalWithOutcome(any()) } returns PostJournalOutcome(firstEntry, replayed = true)

        assertThatThrownBy { runBlocking { service.post(command("900.00")) } }
            .isInstanceOf(LedgerConflictException::class.java)
            .hasMessageContaining("cz-2026-11-04")
        // Opposite sign: same amount, flipped sides — also different content.
        assertThatThrownBy { runBlocking { service.post(command("-1000.00")) } }
            .isInstanceOf(LedgerConflictException::class.java)
        // Different day under the same reference.
        assertThatThrownBy { runBlocking { service.post(command("1000.00", date = day.plusDays(1))) } }
            .isInstanceOf(LedgerConflictException::class.java)
    }

    @Test
    fun `a blank or oversized sourceRef never reaches the ledger`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.post(command("1.00", sourceRef = "  ")) } }
            .isInstanceOf(LedgerValidationException::class.java)
        assertThatThrownBy { runBlocking { service.post(command("1.00", sourceRef = "x".repeat(201))) } }
            .isInstanceOf(LedgerValidationException::class.java)
        assertThatThrownBy { runBlocking { service.post(command("0")) } }
            .isInstanceOf(LedgerValidationException::class.java)
        coVerify(exactly = 0) { ledger.postJournalWithOutcome(any()) }
    }
}
