-- SPDX-License-Identifier: Apache-2.0
-- openbank-rewards-service: the FLUX engine (missions, ledger, coupons, budgets, liability).
-- Spec: "Energo-Pro Rewards — Implementation Spec", section openbank-rewards-service.
-- Rollback: this is the service's first migration; rolling back means dropping the database
-- openbank_rewards. No other service reads these tables.

CREATE TABLE market_param (
    market            VARCHAR(2)     NOT NULL PRIMARY KEY,
    currency          VARCHAR(3)     NOT NULL,
    flux_value        NUMERIC(12, 4) NOT NULL CHECK (flux_value > 0),
    funding_minimum   NUMERIC(14, 2) NOT NULL,
    card_minimum      NUMERIC(14, 2) NOT NULL,
    envelope_per_year NUMERIC(14, 2) NOT NULL,
    locale            VARCHAR(16)    NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL
);

CREATE TABLE party_membership (
    party_id          UUID        NOT NULL PRIMARY KEY,
    market            VARCHAR(2)  NOT NULL REFERENCES market_param (market),
    status            VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'OPTED_OUT')),
    quiet_mode        BOOLEAN     NOT NULL DEFAULT FALSE,
    notify_missions   BOOLEAN     NOT NULL DEFAULT TRUE,
    notify_coupons    BOOLEAN     NOT NULL DEFAULT TRUE,
    notify_expiry     BOOLEAN     NOT NULL DEFAULT TRUE,
    terms_version     VARCHAR(16) NOT NULL,
    joined_at         TIMESTAMPTZ NOT NULL,
    lifetime_charged  INTEGER     NOT NULL DEFAULT 0 CHECK (lifetime_charged >= 0),
    level_index       INTEGER     NOT NULL DEFAULT 1,
    last_activity_at  TIMESTAMPTZ NOT NULL,
    frozen_reason     VARCHAR(64),
    anonymised        BOOLEAN     NOT NULL DEFAULT FALSE
);

-- Projections: most banking events are keyed by account or card, not by party (Phase 1 gap).
CREATE TABLE account_projection (
    account_id   UUID        NOT NULL PRIMARY KEY,
    party_id     UUID        NOT NULL,
    account_type VARCHAR(32) NOT NULL,
    currency     VARCHAR(3)  NOT NULL,
    status       VARCHAR(32),
    opened_at    TIMESTAMPTZ
);
CREATE INDEX ix_account_projection_party ON account_projection (party_id);

CREATE TABLE card_projection (
    card_id    UUID        NOT NULL PRIMARY KEY,
    party_id   UUID        NOT NULL,
    account_id UUID,
    status     VARCHAR(32)
);
CREATE INDEX ix_card_projection_party ON card_projection (party_id);

-- Transactions are emitted as Initiated (with amounts) then Completed (without); kept until paired.
CREATE TABLE pending_transaction (
    transaction_id UUID        NOT NULL PRIMARY KEY,
    payload        TEXT        NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE behaviour_fact (
    fact_id         VARCHAR(160) NOT NULL PRIMARY KEY, -- natural key: <type>:<source id>
    party_id        UUID         NOT NULL,
    fact_type       VARCHAR(48)  NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    amount          NUMERIC(14, 2),
    currency        VARCHAR(3),
    ref             VARCHAR(128),
    payload         TEXT,
    source_event_id VARCHAR(64),
    recorded_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_behaviour_fact_party ON behaviour_fact (party_id, fact_type, occurred_at DESC);

CREATE TABLE budget_line (
    budget_line_id VARCHAR(48)    NOT NULL PRIMARY KEY,
    kind           VARCHAR(16)    NOT NULL CHECK (kind IN ('ENGAGEMENT', 'ACQUISITION', 'OFFER', 'MIGRATION', 'CREDIT')),
    market         VARCHAR(2)     NOT NULL REFERENCES market_param (market),
    amount         NUMERIC(16, 2) NOT NULL CHECK (amount >= 0),
    consumed       NUMERIC(16, 2) NOT NULL DEFAULT 0 CHECK (consumed >= 0),
    description    VARCHAR(200)   NOT NULL
);

CREATE TABLE mission_def (
    mission_id     VARCHAR(32)  NOT NULL,
    version        INTEGER      NOT NULL,
    market         VARCHAR(2)   NOT NULL REFERENCES market_param (market),
    kind           VARCHAR(16)  NOT NULL CHECK (kind IN ('ONE_TIME', 'RECURRING')),
    cadence        VARCHAR(16)  NOT NULL CHECK (cadence IN ('ONCE', 'WEEKLY', 'MONTHLY', 'YEARLY', 'ROLLING')),
    rule           TEXT         NOT NULL,
    reward         TEXT         NOT NULL,
    handoff        VARCHAR(32),
    sort_order     INTEGER      NOT NULL DEFAULT 100,
    budget_line_id VARCHAR(48)  NOT NULL REFERENCES budget_line (budget_line_id),
    status         VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'RETIRED')),
    approval_ref   VARCHAR(64)  NOT NULL,
    PRIMARY KEY (mission_id, market, version)
);

CREATE TABLE mission_instance (
    party_id      UUID        NOT NULL,
    mission_id    VARCHAR(32) NOT NULL,
    version       INTEGER     NOT NULL,
    cycle         VARCHAR(16) NOT NULL,
    state         VARCHAR(16) NOT NULL CHECK (state IN ('IN_PROGRESS', 'CLAIM_PENDING', 'CLAIMED', 'LAPSED')),
    progress      TEXT        NOT NULL,
    reward        TEXT        NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL,
    completed_at  TIMESTAMPTZ,
    claimed_at    TIMESTAMPTZ,
    window_ends_at TIMESTAMPTZ,
    PRIMARY KEY (party_id, mission_id, cycle)
);
CREATE INDEX ix_mission_instance_claim_pending ON mission_instance (completed_at) WHERE state = 'CLAIM_PENDING';

CREATE TABLE ledger_lot (
    lot_id    UUID        NOT NULL PRIMARY KEY,
    party_id  UUID        NOT NULL,
    amount    INTEGER     NOT NULL CHECK (amount > 0),
    remaining INTEGER     NOT NULL CHECK (remaining >= 0 AND remaining <= amount),
    earned_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_ledger_lot_open ON ledger_lot (party_id, earned_at) WHERE remaining > 0;

-- Append-only. flux = signed change of the balance; coupon_flux = signed change of FLUX held in
-- coupons. Liability = SUM(flux) + SUM(coupon_flux) over a market's parties.
CREATE TABLE ledger_entry (
    entry_id       UUID         NOT NULL PRIMARY KEY,
    party_id       UUID         NOT NULL,
    entry_type     VARCHAR(16)  NOT NULL CHECK (entry_type IN ('EARN', 'CHARGE', 'RETURN', 'BURN', 'EXPIRE', 'REVERSE', 'MIGRATION')),
    source         VARCHAR(24)  NOT NULL CHECK (source IN ('mission', 'referral', 'promoCode', 'migratedLoyalty', 'promotionBonus',
                                                         'expiryReturn', 'redemption', 'manualGrant')),
    flux           INTEGER      NOT NULL,
    coupon_flux    INTEGER      NOT NULL DEFAULT 0,
    coupon_id      UUID,
    source_ref     VARCHAR(160) NOT NULL,
    rule_version   VARCHAR(16)  NOT NULL,
    budget_line_id VARCHAR(48),
    market         VARCHAR(2)   NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL
);
-- Exactly-once ledger effects: one entry per (party, type, source, source_ref).
CREATE UNIQUE INDEX uq_ledger_entry_natural ON ledger_entry (party_id, entry_type, source, source_ref);
CREATE INDEX ix_ledger_entry_party ON ledger_entry (party_id, occurred_at DESC);

CREATE TABLE coupon_def (
    coupon_def_id  VARCHAR(48)  NOT NULL,
    market         VARCHAR(2)   NOT NULL REFERENCES market_param (market),
    benefit_type   VARCHAR(32)  NOT NULL CHECK (benefit_type IN ('BILL_CASHBACK', 'DEPOSIT_RATE_BOOST', 'LOAN_RATE_REDUCTION',
                                                               'INSTALMENT_RELIEF', 'COSMETIC', 'FEE_WAIVER', 'CASH_CREDIT')),
    category       VARCHAR(16)  NOT NULL CHECK (category IN ('BILLS', 'SAVINGS', 'LOANS', 'STYLE')),
    benefit_params TEXT         NOT NULL,
    rarity         VARCHAR(16)  NOT NULL CHECK (rarity IN ('STANDARD', 'GOLD', 'SIGNATURE')),
    capacity       INTEGER      NOT NULL CHECK (capacity > 0),
    validity_days  INTEGER,
    level_gate     INTEGER      NOT NULL DEFAULT 1,
    in_catalogue   BOOLEAN      NOT NULL DEFAULT TRUE,
    budget_line_id VARCHAR(48)  NOT NULL REFERENCES budget_line (budget_line_id),
    status         VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'RETIRED')),
    approval_ref   VARCHAR(64)  NOT NULL,
    sort_order     INTEGER      NOT NULL DEFAULT 100,
    PRIMARY KEY (coupon_def_id, market)
);

CREATE TABLE coupon (
    coupon_id      UUID         NOT NULL PRIMARY KEY,
    party_id       UUID         NOT NULL,
    coupon_def_id  VARCHAR(48)  NOT NULL,
    market         VARCHAR(2)   NOT NULL,
    state          VARCHAR(16)  NOT NULL CHECK (state IN ('ISSUED', 'CHARGING', 'READY', 'USING', 'USED', 'EXPIRED', 'WITHDRAWN')),
    capacity       INTEGER      NOT NULL,
    charged_own    INTEGER      NOT NULL DEFAULT 0 CHECK (charged_own >= 0),
    charged_bonus  INTEGER      NOT NULL DEFAULT 0 CHECK (charged_bonus >= 0),
    pre_charge     INTEGER      NOT NULL DEFAULT 0 CHECK (pre_charge >= 0),
    issued_at      TIMESTAMPTZ  NOT NULL,
    expires_at     TIMESTAMPTZ,
    used_at        TIMESTAMPTZ,
    source_ref     VARCHAR(160) NOT NULL,
    target_kind    VARCHAR(24),
    target_id      VARCHAR(64),
    outcome        TEXT,
    CONSTRAINT coupon_within_capacity CHECK (charged_own + charged_bonus + pre_charge <= capacity)
);
CREATE INDEX ix_coupon_party ON coupon (party_id, issued_at DESC);
CREATE UNIQUE INDEX uq_coupon_one_charging ON coupon (party_id) WHERE state = 'CHARGING';
CREATE UNIQUE INDEX uq_coupon_source ON coupon (party_id, source_ref);

CREATE TABLE promotion (
    promotion_id   UUID         NOT NULL PRIMARY KEY,
    market         VARCHAR(2)   NOT NULL REFERENCES market_param (market),
    name           VARCHAR(120) NOT NULL,
    multiplier     NUMERIC(3, 1) NOT NULL CHECK (multiplier > 1 AND multiplier <= 3),
    starts_at      TIMESTAMPTZ  NOT NULL,
    ends_at        TIMESTAMPTZ  NOT NULL CHECK (ends_at > starts_at),
    per_party_cap  INTEGER      NOT NULL CHECK (per_party_cap > 0),
    budget_line_id VARCHAR(48)  NOT NULL REFERENCES budget_line (budget_line_id),
    status         VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'STOPPED')),
    approval_ref   VARCHAR(64)  NOT NULL
);

CREATE TABLE fulfilment (
    fulfilment_id UUID         NOT NULL PRIMARY KEY,
    coupon_id     UUID         NOT NULL REFERENCES coupon (coupon_id),
    party_id      UUID         NOT NULL,
    benefit_type  VARCHAR(32)  NOT NULL,
    state         VARCHAR(16)  NOT NULL CHECK (state IN ('PENDING', 'APPLIED', 'FAILED')),
    attempts      INTEGER      NOT NULL DEFAULT 0,
    command_ref   VARCHAR(160),
    outcome       TEXT,
    last_error    TEXT,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_fulfilment_pending ON fulfilment (updated_at) WHERE state = 'PENDING';

CREATE TABLE referral_reward (
    invite_id    UUID        NOT NULL,
    side         VARCHAR(8)  NOT NULL CHECK (side IN ('REFERRER', 'REFEREE')),
    party_id     UUID        NOT NULL,
    other_party  UUID        NOT NULL,
    program_id   UUID,
    flux         INTEGER     NOT NULL CHECK (flux > 0),
    state        VARCHAR(16) NOT NULL CHECK (state IN ('VESTED', 'RELEASED', 'LAPSED')),
    vested_at    TIMESTAMPTZ NOT NULL,
    release_by   TIMESTAMPTZ NOT NULL,
    released_at  TIMESTAMPTZ,
    note         VARCHAR(64),
    PRIMARY KEY (invite_id, side)
);
CREATE INDEX ix_referral_reward_party ON referral_reward (party_id);

CREATE TABLE offer_reward (
    offer_id       UUID        NOT NULL PRIMARY KEY,
    market         VARCHAR(2)  NOT NULL REFERENCES market_param (market),
    reward         TEXT        NOT NULL,
    budget_line_id VARCHAR(48) NOT NULL REFERENCES budget_line (budget_line_id),
    approval_ref   VARCHAR(64) NOT NULL
);

CREATE TABLE biller (
    biller_id      VARCHAR(48)  NOT NULL PRIMARY KEY,
    market         VARCHAR(2)   NOT NULL REFERENCES market_param (market),
    name           VARCHAR(120) NOT NULL,
    account_number VARCHAR(64)  NOT NULL, -- CZ "prefix-number/bank" or an IBAN
    placeholder    BOOLEAN      NOT NULL DEFAULT TRUE,
    active         BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE lesson (
    lesson_id  VARCHAR(48) NOT NULL PRIMARY KEY,
    mission_id VARCHAR(32) NOT NULL,
    content    TEXT        NOT NULL, -- {"questions":[{"text":{"cs":..,"en":..,"bg":..},"options":{..},"answer":n}]}
    pass_pct   INTEGER     NOT NULL DEFAULT 80
);

CREATE TABLE idempotency_record (
    party_id     UUID         NOT NULL,
    idem_key     VARCHAR(128) NOT NULL,
    operation    VARCHAR(64)  NOT NULL,
    status_code  INTEGER      NOT NULL,
    response     TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (party_id, idem_key, operation)
);

-- Maker-checker for every staff write (spec: all staff writes are draft -> approve).
CREATE TABLE change_request (
    request_id   UUID         NOT NULL PRIMARY KEY,
    kind         VARCHAR(32)  NOT NULL,
    payload      TEXT         NOT NULL,
    reason       VARCHAR(400) NOT NULL,
    maker        VARCHAR(128) NOT NULL,
    status       VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'FAILED')),
    checker      VARCHAR(128),
    result       TEXT,
    created_at   TIMESTAMPTZ  NOT NULL,
    decided_at   TIMESTAMPTZ
);

CREATE TABLE programme_switch (
    switch_id  VARCHAR(32) NOT NULL PRIMARY KEY, -- EARN, CHARGE, USE
    enabled    BOOLEAN     NOT NULL,
    changed_by VARCHAR(128),
    changed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE liability_snapshot (
    snapshot_date DATE           NOT NULL,
    market        VARCHAR(2)     NOT NULL,
    balance_flux  BIGINT         NOT NULL,
    charged_flux  BIGINT         NOT NULL,
    value_ccy     NUMERIC(16, 2) NOT NULL,
    currency      VARCHAR(3)     NOT NULL,
    posted_delta  NUMERIC(16, 2),
    gl_ref        VARCHAR(128),
    created_at    TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (snapshot_date, market)
);

CREATE TABLE rewards_outbox (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID         NOT NULL UNIQUE,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    sent_at         TIMESTAMPTZ,
    last_error      TEXT,
    claimed_at      TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    synthetic       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW() CHECK (created_at >= TIMESTAMPTZ '2020-01-01'),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_rewards_outbox_claim ON rewards_outbox (created_at, id) WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');
CREATE INDEX ix_rewards_outbox_aggregate ON rewards_outbox (aggregate_id, created_at, id) WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');
