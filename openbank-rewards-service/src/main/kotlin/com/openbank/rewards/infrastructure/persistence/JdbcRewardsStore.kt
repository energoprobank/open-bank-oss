// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.rewards.infrastructure.persistence

import com.openbank.rewards.application.AccountRef
import com.openbank.rewards.application.Biller
import com.openbank.rewards.application.CardRef
import com.openbank.rewards.application.ChangeRequest
import com.openbank.rewards.application.Fulfilment
import com.openbank.rewards.application.FulfilmentState
import com.openbank.rewards.application.LiabilitySnapshot
import com.openbank.rewards.application.OfferReward
import com.openbank.rewards.application.OutboxEvent
import com.openbank.rewards.application.Promotion
import com.openbank.rewards.application.ReferralReward
import com.openbank.rewards.application.ReferralSide
import com.openbank.rewards.application.ReferralState
import com.openbank.rewards.application.RewardsStore
import com.openbank.rewards.application.RewardsTx
import com.openbank.rewards.application.StoredResponse
import com.openbank.rewards.domain.BehaviourFact
import com.openbank.rewards.domain.BenefitType
import com.openbank.rewards.domain.BudgetKind
import com.openbank.rewards.domain.BudgetLine
import com.openbank.rewards.domain.Cadence
import com.openbank.rewards.domain.Coupon
import com.openbank.rewards.domain.CouponCategory
import com.openbank.rewards.domain.CouponDef
import com.openbank.rewards.domain.CouponState
import com.openbank.rewards.domain.EntrySource
import com.openbank.rewards.domain.EntryType
import com.openbank.rewards.domain.FactType
import com.openbank.rewards.domain.LedgerEntry
import com.openbank.rewards.domain.Lot
import com.openbank.rewards.domain.Market
import com.openbank.rewards.domain.MarketParams
import com.openbank.rewards.domain.Membership
import com.openbank.rewards.domain.MembershipStatus
import com.openbank.rewards.domain.MissionDef
import com.openbank.rewards.domain.MissionInstance
import com.openbank.rewards.domain.MissionKind
import com.openbank.rewards.domain.MissionState
import com.openbank.rewards.domain.ProgrammeSwitch
import com.openbank.rewards.domain.Rarity
import com.openbank.rewards.domain.TargetKind
import io.agroal.api.AgroalDataSource
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * JDBC adapter of [RewardsStore]. A transaction is one connection with autocommit off; a nested
 * [tx] on the same thread joins the outer one, so a helper that opens its own unit of work can
 * never deadlock against rows its caller already locked.
 */
@ApplicationScoped
class JdbcRewardsStore(private val ds: AgroalDataSource) : RewardsStore {
    private val current = ThreadLocal<JdbcTx?>()

    override fun <T> tx(block: (RewardsTx) -> T): T {
        current.get()?.let { return block(it) }
        ds.connection.use { conn ->
            conn.autoCommit = false
            val tx = JdbcTx(conn)
            current.set(tx)
            try {
                val result = block(tx)
                conn.commit()
                return result
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) { // any failure must roll back
                runCatching { conn.rollback() }
                throw e
            } finally {
                current.remove()
            }
        }
    }
}

@Suppress("TooManyFunctions", "LargeClass")
class JdbcTx(private val c: Connection) : RewardsTx {

    // ---------------------------------------------------------------- helpers

    private fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
        c.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }

    private fun <T> one(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
        query(sql, *args, map = map).firstOrNull()

    private fun update(sql: String, vararg args: Any?): Int = c.prepareStatement(sql).use { ps ->
        bind(ps, args)
        ps.executeUpdate()
    }

    private fun bind(ps: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> ps.setNull(idx, Types.NULL)
                is Instant -> ps.setTimestamp(idx, Timestamp.from(a))
                is LocalDate -> ps.setObject(idx, a)
                is UUID -> ps.setObject(idx, a)
                is Enum<*> -> ps.setString(idx, a.name)
                is Market -> ps.setString(idx, a.name)
                else -> ps.setObject(idx, a)
            }
        }
    }

    private fun ResultSet.instant(col: String): Instant? = getTimestamp(col)?.toInstant()
    private fun ResultSet.uuid(col: String): UUID? = getObject(col, UUID::class.java)
    private fun ResultSet.int(col: String): Int? = getInt(col).takeUnless { wasNull() }
    private fun lock(forUpdate: Boolean) = if (forUpdate) " FOR UPDATE" else ""

    // ---------------------------------------------------------------- markets, switches, budgets

    private fun mapParams(rs: ResultSet) = MarketParams(
        Market.valueOf(rs.getString("market")),
        rs.getString("currency"),
        rs.getBigDecimal("flux_value"),
        rs.getBigDecimal("funding_minimum"),
        rs.getBigDecimal("card_minimum"),
        rs.getBigDecimal("envelope_per_year"),
        rs.getString("locale"),
    )

    override fun marketParams(market: Market): MarketParams =
        one("SELECT * FROM market_param WHERE market = ?", market.name, map = ::mapParams)
            ?: error("market $market not configured")

    override fun allMarketParams(): List<MarketParams> =
        query("SELECT * FROM market_param ORDER BY market", map = ::mapParams)

    override fun upsertMarketParams(params: MarketParams) {
        update(
            """INSERT INTO market_param (market, currency, flux_value, funding_minimum, card_minimum, envelope_per_year, locale, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
               ON CONFLICT (market) DO UPDATE SET currency = EXCLUDED.currency, flux_value = EXCLUDED.flux_value,
                 funding_minimum = EXCLUDED.funding_minimum, card_minimum = EXCLUDED.card_minimum,
                 envelope_per_year = EXCLUDED.envelope_per_year, locale = EXCLUDED.locale, updated_at = NOW()""",
            params.market.name,
            params.currency,
            params.fluxValue,
            params.fundingMinimum,
            params.cardMinimum,
            params.envelopePerYear,
            params.locale,
        )
    }

    override fun switchEnabled(switch: ProgrammeSwitch): Boolean =
        one("SELECT enabled FROM programme_switch WHERE switch_id = ?", switch.name) { it.getBoolean(1) } ?: true

    override fun setSwitch(switch: ProgrammeSwitch, enabled: Boolean, by: String, at: Instant) {
        update(
            """INSERT INTO programme_switch (switch_id, enabled, changed_by, changed_at) VALUES (?, ?, ?, ?)
               ON CONFLICT (switch_id) DO UPDATE SET enabled = EXCLUDED.enabled, changed_by = EXCLUDED.changed_by,
               changed_at = EXCLUDED.changed_at""",
            switch.name,
            enabled,
            by,
            at,
        )
    }

    private fun mapBudget(rs: ResultSet) = BudgetLine(
        rs.getString("budget_line_id"),
        BudgetKind.valueOf(rs.getString("kind")),
        Market.valueOf(rs.getString("market")),
        rs.getBigDecimal("amount"),
        rs.getBigDecimal("consumed"),
        rs.getString("description"),
    )

    override fun budgetLine(id: String, forUpdate: Boolean): BudgetLine? =
        one("SELECT * FROM budget_line WHERE budget_line_id = ?" + lock(forUpdate), id, map = ::mapBudget)

    override fun budgetLines(): List<BudgetLine> =
        query("SELECT * FROM budget_line ORDER BY market, budget_line_id", map = ::mapBudget)

    override fun upsertBudgetLine(line: BudgetLine) {
        update(
            """INSERT INTO budget_line (budget_line_id, kind, market, amount, consumed, description) VALUES (?, ?, ?, ?, 0, ?)
               ON CONFLICT (budget_line_id) DO UPDATE SET amount = EXCLUDED.amount,
               description = EXCLUDED.description""",
            line.budgetLineId,
            line.kind.name,
            line.market.name,
            line.amount,
            line.description,
        )
    }

    override fun consumeBudget(id: String, value: BigDecimal) {
        update("UPDATE budget_line SET consumed = consumed + ? WHERE budget_line_id = ?", value, id)
    }

    // ---------------------------------------------------------------- membership and projections

    private fun mapMembership(rs: ResultSet) = Membership(
        partyId = rs.uuid("party_id")!!, market = Market.valueOf(rs.getString("market")),
        status = MembershipStatus.valueOf(rs.getString("status")), quietMode = rs.getBoolean("quiet_mode"),
        notifyMissions = rs.getBoolean("notify_missions"), notifyCoupons = rs.getBoolean("notify_coupons"),
        notifyExpiry = rs.getBoolean(
            "notify_expiry",
        ),
        termsVersion = rs.getString("terms_version"), joinedAt = rs.instant("joined_at")!!,
        lifetimeCharged = rs.getInt(
            "lifetime_charged",
        ),
        lastActivityAt = rs.instant("last_activity_at")!!, frozenReason = rs.getString("frozen_reason"),
    )

    override fun membership(partyId: UUID, forUpdate: Boolean): Membership? =
        one("SELECT * FROM party_membership WHERE party_id = ?" + lock(forUpdate), partyId, map = ::mapMembership)

    override fun insertMembership(m: Membership) {
        update(
            """INSERT INTO party_membership (party_id, market, status, quiet_mode, notify_missions, notify_coupons, notify_expiry,
                 terms_version, joined_at, lifetime_charged, level_index, last_activity_at, frozen_reason)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (party_id) DO NOTHING""",
            m.partyId, m.market.name, m.status.name, m.quietMode, m.notifyMissions, m.notifyCoupons, m.notifyExpiry,
            m.termsVersion, m.joinedAt, m.lifetimeCharged, m.level.index, m.lastActivityAt, m.frozenReason,
        )
    }

    override fun updateMembership(m: Membership) {
        update(
            """UPDATE party_membership SET status = ?, quiet_mode = ?, notify_missions = ?, notify_coupons = ?, notify_expiry = ?,
                 lifetime_charged = ?, level_index = ?, last_activity_at = ?, frozen_reason = ? WHERE party_id = ?""",
            m.status.name, m.quietMode, m.notifyMissions, m.notifyCoupons, m.notifyExpiry,
            m.lifetimeCharged, m.level.index, m.lastActivityAt, m.frozenReason, m.partyId,
        )
    }

    override fun memberships(market: Market?, limit: Int): List<Membership> = query(
        "SELECT * FROM party_membership WHERE (?::text IS NULL OR market = ?) ORDER BY joined_at LIMIT ?",
        market?.name,
        market?.name,
        limit,
        map = ::mapMembership,
    )

    private fun mapAccount(rs: ResultSet) = AccountRef(
        rs.uuid("account_id")!!,
        rs.uuid("party_id")!!,
        rs.getString("account_type"),
        rs.getString("currency"),
        rs.getString("status"),
        rs.instant("opened_at"),
    )

    override fun upsertAccount(a: AccountRef) {
        update(
            """INSERT INTO account_projection (account_id, party_id, account_type, currency, status, opened_at) VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT (account_id) DO UPDATE SET status = COALESCE(EXCLUDED.status, account_projection.status),
                 opened_at = COALESCE(account_projection.opened_at, EXCLUDED.opened_at)""",
            a.accountId,
            a.partyId,
            a.accountType,
            a.currency,
            a.status,
            a.openedAt,
        )
    }

    override fun account(accountId: UUID): AccountRef? =
        one("SELECT * FROM account_projection WHERE account_id = ?", accountId, map = ::mapAccount)

    override fun accountsOf(partyId: UUID): List<AccountRef> = query(
        "SELECT * FROM account_projection WHERE party_id = ? ORDER BY opened_at NULLS LAST",
        partyId,
        map = ::mapAccount,
    )

    override fun accountsOpenedOn(monthDay: String): List<AccountRef> = query(
        "SELECT * FROM account_projection WHERE to_char(opened_at AT TIME ZONE 'UTC', 'MM-DD') = ?",
        monthDay,
        map = ::mapAccount,
    )

    override fun upsertCard(c: CardRef) {
        update(
            """INSERT INTO card_projection (card_id, party_id, account_id, status) VALUES (?, ?, ?, ?)
               ON CONFLICT (card_id) DO UPDATE SET status = COALESCE(EXCLUDED.status, card_projection.status)""",
            c.cardId,
            c.partyId,
            c.accountId,
            c.status,
        )
    }

    override fun card(cardId: UUID): CardRef? = one("SELECT * FROM card_projection WHERE card_id = ?", cardId) {
        CardRef(it.uuid("card_id")!!, it.uuid("party_id")!!, it.uuid("account_id"), it.getString("status"))
    }

    override fun putPendingTransaction(transactionId: UUID, payload: String, at: Instant) {
        update(
            "INSERT INTO pending_transaction (transaction_id, payload, received_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            transactionId,
            payload,
            at,
        )
    }

    override fun takePendingTransaction(transactionId: UUID): String? =
        one("DELETE FROM pending_transaction WHERE transaction_id = ? RETURNING payload", transactionId) {
            it.getString(1)
        }

    // ---------------------------------------------------------------- facts and missions

    override fun insertFact(fact: BehaviourFact, payload: String?, at: Instant): Boolean = update(
        """INSERT INTO behaviour_fact (fact_id, party_id, fact_type, occurred_at, amount, currency, ref, payload, source_event_id, recorded_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (fact_id) DO NOTHING""",
        fact.factId, fact.partyId, fact.type.name, fact.occurredAt, fact.amount, fact.currency, fact.ref, payload,
        fact.sourceEventId, at,
    ) == 1

    private fun mapFact(rs: ResultSet) = BehaviourFact(
        rs.getString("fact_id"),
        rs.uuid("party_id")!!,
        FactType.valueOf(rs.getString("fact_type")),
        rs.instant("occurred_at")!!,
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getString("ref"),
        rs.getString("source_event_id"),
    )

    override fun fact(factId: String): BehaviourFact? =
        one("SELECT * FROM behaviour_fact WHERE fact_id = ?", factId, map = ::mapFact)

    override fun factsOf(partyId: UUID, type: FactType, limit: Int): List<BehaviourFact> = query(
        "SELECT * FROM behaviour_fact WHERE party_id = ? AND fact_type = ? ORDER BY occurred_at DESC LIMIT ?",
        partyId,
        type.name,
        limit,
        map = ::mapFact,
    )

    private fun mapMissionDef(rs: ResultSet) = MissionDef(
        missionId = rs.getString(
            "mission_id",
        ),
        version = rs.getInt("version"), market = Market.valueOf(rs.getString("market")),
        kind = MissionKind.valueOf(rs.getString("kind")), cadence = Cadence.valueOf(rs.getString("cadence")),
        rule = RewardsJson.rule(
            rs.getString("rule"),
        ),
        reward = RewardsJson.reward(rs.getString("reward")), handoff = rs.getString("handoff"),
        sortOrder = rs.getInt("sort_order"), budgetLineId = rs.getString("budget_line_id"),
        active =
        rs.getString("status") == "ACTIVE",
    )

    /** The newest version of each mission in the market. */
    override fun missionDefs(market: Market, includeInactive: Boolean): List<MissionDef> = query(
        """SELECT DISTINCT ON (mission_id) * FROM mission_def WHERE market = ? AND (? OR status = 'ACTIVE')
           ORDER BY mission_id, version DESC""",
        market.name,
        includeInactive,
        map = ::mapMissionDef,
    )

    override fun upsertMissionDef(def: MissionDef, approvalRef: String) {
        update(
            """INSERT INTO mission_def (mission_id, version, market, kind, cadence, rule, reward, handoff, sort_order, budget_line_id, status, approval_ref)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (mission_id, market, version) DO UPDATE SET status = EXCLUDED.status,
               approval_ref = EXCLUDED.approval_ref""",
            def.missionId, def.version, def.market.name, def.kind.name, def.cadence.name,
            RewardsJson.write(
                def.rule,
            ),
            RewardsJson.write(def.reward),
            def.handoff, def.sortOrder, def.budgetLineId, if (def.active) "ACTIVE" else "INACTIVE", approvalRef,
        )
    }

    private fun mapInstance(rs: ResultSet) = MissionInstance(
        partyId = rs.uuid(
            "party_id",
        )!!,
        missionId = rs.getString("mission_id"), version = rs.getInt("version"), cycle = rs.getString("cycle"),
        state = MissionState.valueOf(rs.getString("state")), progress = RewardsJson.progress(rs.getString("progress")),
        reward = RewardsJson.reward(
            rs.getString("reward"),
        ),
        startedAt = rs.instant("started_at")!!, completedAt = rs.instant("completed_at"),
        claimedAt = rs.instant("claimed_at"), windowEndsAt = rs.instant("window_ends_at"),
    )

    override fun instance(partyId: UUID, missionId: String, cycle: String, forUpdate: Boolean): MissionInstance? = one(
        "SELECT * FROM mission_instance WHERE party_id = ? AND mission_id = ? AND cycle = ?" + lock(forUpdate),
        partyId,
        missionId,
        cycle,
        map = ::mapInstance,
    )

    override fun upsertInstance(instance: MissionInstance) {
        update(
            """INSERT INTO mission_instance (party_id, mission_id, version, cycle, state, progress, reward, started_at, completed_at, claimed_at, window_ends_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (party_id, mission_id, cycle) DO UPDATE SET state = EXCLUDED.state,
               progress = EXCLUDED.progress,
                 completed_at = EXCLUDED.completed_at, claimed_at = EXCLUDED.claimed_at""",
            instance.partyId, instance.missionId, instance.version, instance.cycle, instance.state.name,
            RewardsJson.write(
                instance.progress,
            ),
            RewardsJson.write(
                instance.reward,
            ),
            instance.startedAt, instance.completedAt, instance.claimedAt, instance.windowEndsAt,
        )
    }

    override fun archiveRollingInstance(partyId: UUID, missionId: String, newCycle: String) {
        update(
            "UPDATE mission_instance SET cycle = ? WHERE party_id = ? AND mission_id = ? AND cycle = 'rolling'",
            newCycle,
            partyId,
            missionId,
        )
    }

    override fun instancesOf(partyId: UUID): List<MissionInstance> =
        query("SELECT * FROM mission_instance WHERE party_id = ? ORDER BY started_at", partyId, map = ::mapInstance)

    override fun claimPendingBefore(before: Instant, limit: Int): List<MissionInstance> = query(
        "SELECT * FROM mission_instance WHERE state = 'CLAIM_PENDING' AND completed_at < ? LIMIT ?",
        before,
        limit,
        map = ::mapInstance,
    )

    override fun inProgressEndedBefore(before: Instant, limit: Int): List<MissionInstance> = query(
        "SELECT * FROM mission_instance WHERE state = 'IN_PROGRESS' AND window_ends_at <= ? LIMIT ?",
        before,
        limit,
        map = ::mapInstance,
    )

    // ---------------------------------------------------------------- ledger

    override fun insertEntry(entry: LedgerEntry): Boolean = update(
        """INSERT INTO ledger_entry (entry_id, party_id, entry_type, source, flux, coupon_flux, coupon_id, source_ref, rule_version, budget_line_id, market, occurred_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (party_id, entry_type, source, source_ref) DO NOTHING""",
        entry.entryId, entry.partyId, entry.type.name, entry.source.wire, entry.flux, entry.couponFlux,
        entry.couponId, entry.sourceRef,
        entry.ruleVersion, entry.budgetLineId, entry.market.name, entry.occurredAt,
    ) == 1

    private fun mapEntry(rs: ResultSet) = LedgerEntry(
        rs.uuid(
            "entry_id",
        )!!,
        rs.uuid(
            "party_id",
        )!!,
        EntryType.valueOf(rs.getString("entry_type")), EntrySource.ofWire(rs.getString("source")),
        rs.getInt(
            "flux",
        ),
        rs.getInt("coupon_flux"), rs.uuid("coupon_id"), rs.getString("source_ref"), rs.getString("rule_version"),
        rs.getString("budget_line_id"), Market.valueOf(rs.getString("market")), rs.instant("occurred_at")!!,
    )

    override fun entries(partyId: UUID, page: Int, size: Int): List<LedgerEntry> = query(
        "SELECT * FROM ledger_entry WHERE party_id = ? ORDER BY occurred_at DESC, entry_id LIMIT ? OFFSET ?",
        partyId,
        size,
        page.toLong() * size,
        map = ::mapEntry,
    )

    override fun countEntries(partyId: UUID): Long =
        one("SELECT COUNT(*) FROM ledger_entry WHERE party_id = ?", partyId) { it.getLong(1) } ?: 0

    override fun openLots(partyId: UUID): List<Lot> = query(
        "SELECT * FROM ledger_lot WHERE party_id = ? AND remaining > 0 ORDER BY earned_at, lot_id FOR UPDATE",
        partyId,
    ) {
        Lot(
            it.uuid("lot_id")!!,
            it.uuid("party_id")!!,
            it.getInt("amount"),
            it.getInt("remaining"),
            it.instant("earned_at")!!,
        )
    }

    override fun insertLot(lot: Lot) {
        update(
            "INSERT INTO ledger_lot (lot_id, party_id, amount, remaining, earned_at) VALUES (?, ?, ?, ?, ?)",
            lot.lotId,
            lot.partyId,
            lot.amount,
            lot.remaining,
            lot.earnedAt,
        )
    }

    override fun debitLot(lotId: UUID, amount: Int) {
        check(
            update(
                "UPDATE ledger_lot SET remaining = remaining - ? WHERE lot_id = ? AND remaining >= ?",
                amount,
                lotId,
                amount,
            ) ==
                1,
        ) { "lot $lotId cannot cover $amount" }
    }

    override fun balance(partyId: UUID): Int =
        one("SELECT COALESCE(SUM(remaining), 0) FROM ledger_lot WHERE party_id = ?", partyId) { it.getInt(1) } ?: 0

    override fun liabilityFlux(market: Market): Pair<Long, Long> = one(
        """SELECT COALESCE(SUM(flux), 0) AS balance, COALESCE(SUM(coupon_flux), 0) AS charged FROM ledger_entry WHERE market = ?""",
        market.name,
    ) { it.getLong("balance") to it.getLong("charged") } ?: (0L to 0L)

    // ---------------------------------------------------------------- coupons

    private fun mapCouponDef(rs: ResultSet) = CouponDef(
        couponDefId = rs.getString("coupon_def_id"), market = Market.valueOf(rs.getString("market")),
        benefitType = BenefitType.valueOf(
            rs.getString("benefit_type"),
        ),
        category = CouponCategory.valueOf(rs.getString("category")),
        params = RewardsJson.params(
            rs.getString("benefit_params"),
        ),
        rarity = Rarity.valueOf(rs.getString("rarity")), capacity = rs.getInt("capacity"),
        validityDays = rs.int(
            "validity_days",
        ),
        levelGate = rs.getInt("level_gate"), inCatalogue = rs.getBoolean("in_catalogue"),
        budgetLineId = rs.getString(
            "budget_line_id",
        ),
        active = rs.getString("status") == "ACTIVE", sortOrder = rs.getInt("sort_order"),
    )

    override fun couponDefs(market: Market, includeInactive: Boolean): List<CouponDef> = query(
        "SELECT * FROM coupon_def WHERE market = ? AND (? OR status = 'ACTIVE') ORDER BY sort_order",
        market.name,
        includeInactive,
        map = ::mapCouponDef,
    )

    override fun couponDef(market: Market, couponDefId: String): CouponDef? = one(
        "SELECT * FROM coupon_def WHERE market = ? AND coupon_def_id = ?",
        market.name,
        couponDefId,
        map = ::mapCouponDef,
    )

    override fun upsertCouponDef(def: CouponDef, approvalRef: String) {
        update(
            """INSERT INTO coupon_def (coupon_def_id, market, benefit_type, category, benefit_params, rarity, capacity, validity_days, level_gate,
                 in_catalogue, budget_line_id, status, approval_ref, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (coupon_def_id, market) DO UPDATE SET benefit_params = EXCLUDED.benefit_params,
               rarity = EXCLUDED.rarity,
                 capacity = EXCLUDED.capacity, validity_days = EXCLUDED.validity_days, level_gate = EXCLUDED.level_gate,
                 in_catalogue = EXCLUDED.in_catalogue, budget_line_id = EXCLUDED.budget_line_id,
                 status = EXCLUDED.status,
                 approval_ref = EXCLUDED.approval_ref, sort_order = EXCLUDED.sort_order""",
            def.couponDefId, def.market.name, def.benefitType.name, def.category.name,
            RewardsJson.write(
                def.params,
            ),
            def.rarity.name, def.capacity,
            def.validityDays, def.levelGate, def.inCatalogue, def.budgetLineId,
            if (def.active) "ACTIVE" else "INACTIVE", approvalRef, def.sortOrder,
        )
    }

    private fun mapCoupon(rs: ResultSet) = Coupon(
        couponId = rs.uuid("coupon_id")!!, partyId = rs.uuid("party_id")!!, couponDefId = rs.getString("coupon_def_id"),
        market = Market.valueOf(
            rs.getString("market"),
        ),
        state = CouponState.valueOf(rs.getString("state")), capacity = rs.getInt("capacity"),
        chargedOwn = rs.getInt(
            "charged_own",
        ),
        chargedBonus = rs.getInt("charged_bonus"), preCharge = rs.getInt("pre_charge"),
        issuedAt = rs.instant(
            "issued_at",
        )!!,
        expiresAt = rs.instant(
            "expires_at",
        ),
        usedAt = rs.instant("used_at"), sourceRef = rs.getString("source_ref"),
        targetKind = rs.getString(
            "target_kind",
        )?.let(TargetKind::valueOf),
        targetId = rs.getString("target_id"), outcome = rs.getString("outcome"),
    )

    override fun coupons(partyId: UUID): List<Coupon> =
        query("SELECT * FROM coupon WHERE party_id = ? ORDER BY issued_at DESC", partyId, map = ::mapCoupon)

    override fun coupon(couponId: UUID, forUpdate: Boolean): Coupon? = one(
        "SELECT * FROM coupon WHERE coupon_id = ?" + lock(forUpdate),
        couponId,
        map = ::mapCoupon,
    )

    override fun couponBySource(partyId: UUID, sourceRef: String): Coupon? =
        one("SELECT * FROM coupon WHERE party_id = ? AND source_ref = ?", partyId, sourceRef, map = ::mapCoupon)

    override fun insertCoupon(coupon: Coupon) {
        update(
            """INSERT INTO coupon (coupon_id, party_id, coupon_def_id, market, state, capacity, charged_own, charged_bonus, pre_charge, issued_at,
                 expires_at, used_at, source_ref, target_kind, target_id, outcome) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,
                 ?, ?, ?, ?, ?, ?, ?)""",
            coupon.couponId, coupon.partyId, coupon.couponDefId, coupon.market.name, coupon.state.name,
            coupon.capacity, coupon.chargedOwn,
            coupon.chargedBonus, coupon.preCharge, coupon.issuedAt, coupon.expiresAt, coupon.usedAt,
            coupon.sourceRef, coupon.targetKind?.name,
            coupon.targetId, coupon.outcome,
        )
    }

    override fun updateCoupon(coupon: Coupon) {
        update(
            """UPDATE coupon SET state = ?, charged_own = ?, charged_bonus = ?, pre_charge = ?, expires_at = ?, used_at = ?, target_kind = ?,
                 target_id = ?, outcome = ? WHERE coupon_id = ?""",
            coupon.state.name, coupon.chargedOwn, coupon.chargedBonus, coupon.preCharge, coupon.expiresAt,
            coupon.usedAt, coupon.targetKind?.name,
            coupon.targetId, coupon.outcome, coupon.couponId,
        )
    }

    override fun expiringCoupons(before: Instant, limit: Int): List<Coupon> = query(
        "SELECT * FROM coupon WHERE state IN ('ISSUED', 'CHARGING', 'READY') AND expires_at IS NOT NULL AND expires_at < ? LIMIT ?",
        before,
        limit,
        map = ::mapCoupon,
    )

    // ---------------------------------------------------------------- promotions

    private fun mapPromotion(rs: ResultSet) = Promotion(
        rs.uuid(
            "promotion_id",
        )!!,
        Market.valueOf(rs.getString("market")), rs.getString("name"), rs.getBigDecimal("multiplier"),
        rs.instant("starts_at")!!, rs.instant("ends_at")!!, rs.getInt("per_party_cap"), rs.getString("budget_line_id"),
        rs.getString("status") == "ACTIVE",
    )

    override fun activePromotion(market: Market, at: Instant): Promotion? = one(
        "SELECT * FROM promotion WHERE market = ? AND status = 'ACTIVE' AND starts_at <= ? AND ends_at > ? ORDER BY starts_at DESC LIMIT 1",
        market.name,
        at,
        at,
        map = ::mapPromotion,
    )

    override fun promotions(market: Market?): List<Promotion> = query(
        "SELECT * FROM promotion WHERE (?::text IS NULL OR market = ?) ORDER BY starts_at DESC",
        market?.name,
        market?.name,
        map = ::mapPromotion,
    )

    override fun insertPromotion(p: Promotion, approvalRef: String) {
        update(
            """INSERT INTO promotion (promotion_id, market, name, multiplier, starts_at, ends_at, per_party_cap, budget_line_id, status, approval_ref)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)""",
            p.promotionId, p.market.name, p.name, p.multiplier, p.startsAt, p.endsAt, p.perPartyCap, p.budgetLineId,
            approvalRef,
        )
    }

    override fun stopPromotion(id: UUID) {
        update("UPDATE promotion SET status = 'STOPPED' WHERE promotion_id = ?", id)
    }

    override fun bonusReceived(partyId: UUID, promotionId: UUID): Int = one(
        "SELECT COALESCE(SUM(coupon_flux), 0) FROM ledger_entry WHERE party_id = ? AND source = 'promotionBonus' AND source_ref LIKE ?",
        partyId,
        "bonus:$promotionId:%",
    ) { it.getInt(1) } ?: 0

    // ---------------------------------------------------------------- fulfilment

    private fun mapFulfilment(rs: ResultSet) = Fulfilment(
        rs.uuid("fulfilment_id")!!, rs.uuid("coupon_id")!!, rs.uuid("party_id")!!, rs.getString("benefit_type"),
        FulfilmentState.valueOf(
            rs.getString("state"),
        ),
        rs.getInt("attempts"), rs.getString("command_ref"), rs.getString("outcome"),
        rs.getString("last_error"), rs.instant("created_at")!!, rs.instant("updated_at")!!,
    )

    override fun insertFulfilment(f: Fulfilment) {
        update(
            """INSERT INTO fulfilment (fulfilment_id, coupon_id, party_id, benefit_type, state, attempts, command_ref, outcome, last_error, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            f.fulfilmentId, f.couponId, f.partyId, f.benefitType, f.state.name, f.attempts, f.commandRef, f.outcome,
            f.lastError, f.createdAt, f.updatedAt,
        )
    }

    override fun updateFulfilment(f: Fulfilment) {
        update(
            "UPDATE fulfilment SET state = ?, attempts = ?, command_ref = ?, outcome = ?, last_error = ?, updated_at = ? WHERE fulfilment_id = ?",
            f.state.name,
            f.attempts,
            f.commandRef,
            f.outcome,
            f.lastError,
            f.updatedAt,
            f.fulfilmentId,
        )
    }

    override fun fulfilmentForCoupon(couponId: UUID): Fulfilment? = one(
        "SELECT * FROM fulfilment WHERE coupon_id = ? ORDER BY created_at DESC LIMIT 1",
        couponId,
        map = ::mapFulfilment,
    )

    override fun fulfilment(fulfilmentId: UUID): Fulfilment? =
        one("SELECT * FROM fulfilment WHERE fulfilment_id = ?", fulfilmentId, map = ::mapFulfilment)

    override fun pendingFulfilments(limit: Int): List<Fulfilment> = query(
        "SELECT * FROM fulfilment WHERE state = 'PENDING' ORDER BY updated_at LIMIT ?",
        limit,
        map = ::mapFulfilment,
    )

    // ---------------------------------------------------------------- referral, offers, billers, lessons

    private fun mapReferral(rs: ResultSet) = ReferralReward(
        rs.uuid(
            "invite_id",
        )!!,
        ReferralSide.valueOf(
            rs.getString("side"),
        ),
        rs.uuid("party_id")!!, rs.uuid("other_party")!!, rs.uuid("program_id"),
        rs.getInt(
            "flux",
        ),
        ReferralState.valueOf(rs.getString("state")), rs.instant("vested_at")!!, rs.instant("release_by")!!,
        rs.instant("released_at"), rs.getString("note"),
    )

    override fun insertReferralReward(r: ReferralReward): Boolean = update(
        """INSERT INTO referral_reward (invite_id, side, party_id, other_party, program_id, flux, state, vested_at, release_by, released_at, note)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (invite_id, side) DO NOTHING""",
        r.inviteId, r.side.name, r.partyId, r.otherParty, r.programId, r.flux, r.state.name, r.vestedAt, r.releaseBy,
        r.releasedAt, r.note,
    ) == 1

    override fun updateReferralReward(r: ReferralReward) {
        update(
            "UPDATE referral_reward SET state = ?, released_at = ?, note = ? WHERE invite_id = ? AND side = ?",
            r.state.name,
            r.releasedAt,
            r.note,
            r.inviteId,
            r.side.name,
        )
    }

    override fun referralRewardsOf(partyId: UUID): List<ReferralReward> =
        query("SELECT * FROM referral_reward WHERE party_id = ? ORDER BY vested_at DESC", partyId, map = ::mapReferral)

    override fun vestedReferralsForReferee(refereePartyId: UUID): List<ReferralReward> = query(
        "SELECT * FROM referral_reward WHERE party_id = ? AND side = 'REFEREE' AND state = 'VESTED' FOR UPDATE",
        refereePartyId,
        map = ::mapReferral,
    )

    override fun referralRewardsForInvite(inviteId: UUID): List<ReferralReward> =
        query("SELECT * FROM referral_reward WHERE invite_id = ? FOR UPDATE", inviteId, map = ::mapReferral)

    override fun vestedReferralsDueBefore(before: Instant, limit: Int): List<ReferralReward> = query(
        "SELECT * FROM referral_reward WHERE state = 'VESTED' AND release_by < ? LIMIT ?",
        before,
        limit,
        map = ::mapReferral,
    )

    override fun offerReward(offerId: UUID): OfferReward? =
        one("SELECT * FROM offer_reward WHERE offer_id = ?", offerId) {
            OfferReward(
                it.uuid("offer_id")!!,
                Market.valueOf(it.getString("market")),
                it.getString("reward"),
                it.getString("budget_line_id"),
            )
        }

    override fun upsertOfferReward(o: OfferReward, approvalRef: String) {
        update(
            """INSERT INTO offer_reward (offer_id, market, reward, budget_line_id, approval_ref) VALUES (?, ?, ?, ?, ?)
               ON CONFLICT (offer_id) DO UPDATE SET reward = EXCLUDED.reward,
               budget_line_id = EXCLUDED.budget_line_id, approval_ref = EXCLUDED.approval_ref""",
            o.offerId,
            o.market.name,
            o.rewardJson,
            o.budgetLineId,
            approvalRef,
        )
    }

    override fun billers(market: Market?): List<Biller> = query(
        "SELECT * FROM biller WHERE (?::text IS NULL OR market = ?) ORDER BY biller_id",
        market?.name,
        market?.name,
    ) {
        Biller(
            it.getString("biller_id"),
            Market.valueOf(it.getString("market")),
            it.getString("name"),
            it.getString("account_number"),
            it.getBoolean("placeholder"),
            it.getBoolean("active"),
        )
    }

    override fun upsertBiller(b: Biller) {
        update(
            """INSERT INTO biller (biller_id, market, name, account_number, placeholder, active) VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT (biller_id) DO UPDATE SET name = EXCLUDED.name, account_number = EXCLUDED.account_number,
                 placeholder = EXCLUDED.placeholder, active = EXCLUDED.active""",
            b.billerId,
            b.market.name,
            b.name,
            b.accountNumber,
            b.placeholder,
            b.active,
        )
    }

    override fun lessonContent(lessonId: String): Pair<String, Int>? =
        one("SELECT content, pass_pct FROM lesson WHERE lesson_id = ?", lessonId) { it.getString(1) to it.getInt(2) }

    override fun lessons(): List<Pair<String, String>> =
        query("SELECT lesson_id, mission_id FROM lesson ORDER BY lesson_id") {
            it.getString(1) to
                it.getString(2)
        }

    // ---------------------------------------------------------------- idempotency, maker-checker, liability, outbox

    override fun storedResponse(partyId: UUID, key: String, operation: String): StoredResponse? = one(
        "SELECT status_code, response FROM idempotency_record WHERE party_id = ? AND idem_key = ? AND operation = ?",
        partyId,
        key,
        operation,
    ) { StoredResponse(it.getInt(1), it.getString(2)) }

    override fun storeResponse(partyId: UUID, key: String, operation: String, response: StoredResponse, at: Instant) {
        update(
            "INSERT INTO idempotency_record (party_id, idem_key, operation, status_code, response, created_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            partyId,
            key,
            operation,
            response.statusCode,
            response.body,
            at,
        )
    }

    private fun mapChange(rs: ResultSet) = ChangeRequest(
        rs.uuid(
            "request_id",
        )!!,
        rs.getString(
            "kind",
        ),
        rs.getString("payload"), rs.getString("reason"), rs.getString("maker"), rs.getString("status"),
        rs.getString("checker"), rs.getString("result"), rs.instant("created_at")!!, rs.instant("decided_at"),
    )

    override fun insertChangeRequest(cr: ChangeRequest) {
        update(
            "INSERT INTO change_request (request_id, kind, payload, reason, maker, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            cr.requestId,
            cr.kind,
            cr.payload,
            cr.reason,
            cr.maker,
            cr.status,
            cr.createdAt,
        )
    }

    override fun changeRequest(id: UUID, forUpdate: Boolean): ChangeRequest? =
        one("SELECT * FROM change_request WHERE request_id = ?" + lock(forUpdate), id, map = ::mapChange)

    override fun changeRequests(status: String?, limit: Int): List<ChangeRequest> = query(
        "SELECT * FROM change_request WHERE (?::text IS NULL OR status = ?) ORDER BY created_at DESC LIMIT ?",
        status,
        status,
        limit,
        map = ::mapChange,
    )

    override fun decideChangeRequest(id: UUID, status: String, checker: String, result: String?, at: Instant) {
        update(
            "UPDATE change_request SET status = ?, checker = ?, result = ?, decided_at = ? WHERE request_id = ?",
            status,
            checker,
            result,
            at,
            id,
        )
    }

    private fun mapSnapshot(rs: ResultSet) = LiabilitySnapshot(
        rs.getObject("snapshot_date", LocalDate::class.java),
        Market.valueOf(rs.getString("market")),
        rs.getLong("balance_flux"),
        rs.getLong("charged_flux"),
        rs.getBigDecimal("value_ccy"),
        rs.getString("currency"),
        rs.getBigDecimal("posted_delta"),
        rs.getString("gl_ref"),
    )

    override fun lastSnapshot(market: Market): LiabilitySnapshot? = one(
        "SELECT * FROM liability_snapshot WHERE market = ? AND gl_ref IS NOT NULL ORDER BY snapshot_date DESC LIMIT 1",
        market.name,
        map = ::mapSnapshot,
    )

    override fun upsertSnapshot(s: LiabilitySnapshot, at: Instant) {
        update(
            """INSERT INTO liability_snapshot (snapshot_date, market, balance_flux, charged_flux, value_ccy, currency, posted_delta, gl_ref, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (snapshot_date, market) DO UPDATE SET balance_flux = EXCLUDED.balance_flux,
               charged_flux = EXCLUDED.charged_flux,
                 value_ccy = EXCLUDED.value_ccy,
                 posted_delta = COALESCE(liability_snapshot.posted_delta, 0) + COALESCE(EXCLUDED.posted_delta, 0),
                 gl_ref = COALESCE(EXCLUDED.gl_ref, liability_snapshot.gl_ref), created_at = EXCLUDED.created_at""",
            s.date, s.market.name, s.balanceFlux, s.chargedFlux, s.value, s.currency, s.postedDelta, s.glRef, at,
        )
    }

    override fun snapshots(limit: Int): List<LiabilitySnapshot> = query(
        "SELECT * FROM liability_snapshot ORDER BY snapshot_date DESC, market LIMIT ?",
        limit,
        map = ::mapSnapshot,
    )

    override fun enqueue(event: OutboxEvent, at: Instant) {
        val eventId = UUID.randomUUID()
        val body = linkedMapOf<String, Any?>(
            "eventId" to eventId,
            "eventType" to event.eventType,
            "partyId" to event.partyId,
            "occurredAt" to at,
            "sourceService" to "rewards-service",
        ) + event.payload
        update(
            "INSERT INTO rewards_outbox (event_id, aggregate_id, event_type, payload, status, created_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', ?, ?)",
            eventId,
            event.partyId,
            event.eventType,
            RewardsJson.write(body),
            at,
            at,
        )
    }
}
