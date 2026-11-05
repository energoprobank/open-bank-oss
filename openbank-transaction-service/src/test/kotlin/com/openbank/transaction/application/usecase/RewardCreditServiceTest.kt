// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.usecase

import com.openbank.libs.domain.money.Money
import com.openbank.transaction.application.port.`in`.InitiateTransactionCommand
import com.openbank.transaction.application.port.`in`.RewardCreditCommand
import com.openbank.transaction.application.port.`in`.TransactionUseCase
import com.openbank.transaction.application.port.out.AccountLookupPort
import com.openbank.transaction.application.port.out.TransactionRepository
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.domain.reward.RewardCreditPolicy
import com.openbank.transaction.domain.reward.RewardCreditRefusal
import com.openbank.transaction.domain.reward.RewardCreditRefusedException
import com.openbank.transaction.domain.reward.RewardReason
import com.openbank.transaction.domain.reward.RewardTargetAccount
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class RewardCreditServiceTest {

    private val transactions = mockk<TransactionUseCase>()
    private val repository = mockk<TransactionRepository>()
    private val accounts = mockk<AccountLookupPort>()
    private val policy = RewardCreditPolicy(mapOf("CZK" to BigDecimal("2000.00"), "EUR" to BigDecimal("100.00")))

    // 23:30 UTC on 4 Nov is already 5 Nov in Prague: the value date is the bank's day.
    private val clock = Clock.fixed(Instant.parse("2026-11-04T23:30:00Z"), ZoneOffset.UTC)
    private val service = RewardCreditService(transactions, repository, accounts, policy, clock)

    private val party = UUID.randomUUID()
    private val account = UUID.randomUUID()

    private fun command(
        amount: String = "150.00",
        currency: String = "CZK",
        reason: RewardReason = RewardReason.BILL_CASHBACK,
        accountId: UUID = account,
        sourceRef: String = "coupon-use-42",
    ) = RewardCreditCommand(party, accountId, BigDecimal(amount), currency, reason, sourceRef)

    private fun stored(cmd: InitiateTransactionCommand, status: TransactionStatus = TransactionStatus.COMPLETED) =
        Transaction(
            id = UUID.randomUUID(),
            referenceNumber = "TXN-R",
            type = cmd.type,
            sourceAccountId = cmd.sourceAccountId,
            targetAccountId = cmd.targetAccountId,
            amount = Money.of(cmd.amount, cmd.currencyCode),
            fxRate = null,
            baseAmount = Money.of(cmd.amount, cmd.currencyCode),
            status = status,
            description = cmd.description,
            valueDate = cmd.valueDate,
            bookingDate = cmd.valueDate,
            initiatedAt = Instant.EPOCH,
            completedAt = null,
            failedAt = null,
            failureReason = null,
            idempotencyKey = cmd.idempotencyKey,
            version = 0,
            rewardReason = cmd.rewardReason,
        )

    private fun ownCurrentAccount(currency: String = "CZK") {
        coEvery { accounts.findAccount(account) } returns RewardTargetAccount(party, "CURRENT", currency, "ACTIVE")
    }

    @Test
    fun `pays a provision-funded CREDIT to the checked account`(): Unit = runBlocking {
        coEvery { repository.findByIdempotencyKey("reward-credit:coupon-use-42") } returns null
        ownCurrentAccount()
        val sent = slot<InitiateTransactionCommand>()
        coEvery { transactions.initiateTransaction(capture(sent)) } answers { stored(sent.captured) }

        val result = service.credit(command(amount = "150"))

        val cmd = sent.captured
        assertThat(cmd.idempotencyKey).isEqualTo("reward-credit:coupon-use-42")
        assertThat(cmd.type).isEqualTo(TransactionType.CREDIT)
        assertThat(cmd.sourceAccountId).isNull()
        assertThat(cmd.targetAccountId).isEqualTo(account)
        assertThat(cmd.amount).isEqualTo(BigDecimal("150.00"))
        assertThat(cmd.currencyCode).isEqualTo("CZK")
        assertThat(cmd.rewardReason).isEqualTo(RewardReason.BILL_CASHBACK)
        assertThat(cmd.description).isEqualTo(RewardReason.BILL_CASHBACK.description)
        assertThat(cmd.initiatedByPartyId).describedAs("bank-initiated: no SCA").isNull()
        assertThat(cmd.initiatedBy).isEqualTo(RewardCreditService.REWARDS_INITIATOR)
        assertThat(cmd.valueDate).isEqualTo(LocalDate.of(2026, 11, 5))
        assertThat(result.status).isEqualTo(TransactionStatus.COMPLETED)
        assertThat(result.replayed).isFalse()
        assertThat(result.creditId).isEqualTo(RewardCreditService.creditId("reward-credit:coupon-use-42"))
    }

    @Test
    fun `a refused target moves no money`(): Unit = runBlocking {
        coEvery { repository.findByIdempotencyKey(any()) } returns null
        val someoneElses = RewardTargetAccount(UUID.randomUUID(), "CURRENT", "CZK", "ACTIVE")
        coEvery { accounts.findAccount(account) } returns someoneElses

        assertThatThrownBy { runBlocking { service.credit(command()) } }
            .isInstanceOfSatisfying(RewardCreditRefusedException::class.java) {
                assertThat(it.refusal).isEqualTo(RewardCreditRefusal.ACCOUNT_NOT_OWNED_BY_PARTY)
            }
        coVerify(exactly = 0) { transactions.initiateTransaction(any()) }
    }

    @Test
    fun `an amount above the cap is refused before account-service is even asked`(): Unit = runBlocking {
        coEvery { repository.findByIdempotencyKey(any()) } returns null

        assertThatThrownBy { runBlocking { service.credit(command(amount = "100.01", currency = "EUR")) } }
            .isInstanceOfSatisfying(RewardCreditRefusedException::class.java) {
                assertThat(it.refusal).isEqualTo(RewardCreditRefusal.AMOUNT_ABOVE_CAP)
            }
        coVerify(exactly = 0) { accounts.findAccount(any()) }
        coVerify(exactly = 0) { transactions.initiateTransaction(any()) }
    }

    @Test
    fun `the same sourceRef with the same content answers the original, without re-checking or re-paying`(): Unit =
        runBlocking {
            ownCurrentAccount()
            val sent = slot<InitiateTransactionCommand>()
            coEvery { repository.findByIdempotencyKey(any()) } returns null
            coEvery { transactions.initiateTransaction(capture(sent)) } answers { stored(sent.captured) }
            val first = service.credit(command())
            val original = stored(sent.captured)
            coEvery { repository.findByIdempotencyKey("reward-credit:coupon-use-42") } returns original

            val replay = service.credit(command(amount = "150"))

            assertThat(replay.replayed).isTrue()
            assertThat(replay.transactionId).isEqualTo(original.id)
            assertThat(replay.creditId).isEqualTo(first.creditId)
            coVerify(exactly = 1) { transactions.initiateTransaction(any()) }
            coVerify(exactly = 1) { accounts.findAccount(any()) }
        }

    @Test
    fun `the same sourceRef with different content is a conflict, never a silent replay`(): Unit = runBlocking {
        ownCurrentAccount()
        val sent = slot<InitiateTransactionCommand>()
        coEvery { repository.findByIdempotencyKey(any()) } returns null
        coEvery { transactions.initiateTransaction(capture(sent)) } answers { stored(sent.captured) }
        service.credit(command())
        val original = stored(sent.captured)
        coEvery { repository.findByIdempotencyKey("reward-credit:coupon-use-42") } returns original

        listOf(
            command(amount = "151.00"),
            command(reason = RewardReason.REFERRAL_CASH),
            command(accountId = UUID.randomUUID()),
            command(currency = "EUR", amount = "150.00"),
        ).forEach { different ->
            assertThatThrownBy { runBlocking { service.credit(different) } }
                .isInstanceOf(RewardCreditConflictException::class.java)
        }
    }

    @Test
    fun `a sourceRef that is blank or too long for the key column never reaches anything`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.credit(command(sourceRef = " ")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { runBlocking { service.credit(command(sourceRef = "x".repeat(81))) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { repository.findByIdempotencyKey(any()) }
    }
}
