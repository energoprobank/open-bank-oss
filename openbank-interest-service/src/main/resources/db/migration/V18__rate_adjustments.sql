-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- Additive, time-boxed rate adjustments (FLUX rewards "Deposit Rate Boost"). Unlike the per-account
-- override in interest_rate_configs (V11: replaces the rate, one per account, no provenance), an
-- adjustment is a signed delta in basis points ADDED to whatever rate applies on the accrual date.
-- Any number may be ACTIVE at once; the accrual sums those whose [valid_from, valid_to) window covers
-- the date and clamps the result at 0. Cancellation is a status flip (the row is kept for audit).
--
-- source_ref is the caller's idempotency key: a replayed create with identical terms returns the
-- existing row, different terms are a 409. The unique index is the race backstop.
--
-- Purely additive: older binaries never read this table, so a rolling rollback keeps working (it
-- simply stops applying boosts).
-- Rollback: DROP TABLE interest_rate_adjustments;
--   (drops every recorded adjustment — export the table first if any ACTIVE rows exist, and tell
--   the rewards engine so it can compensate the coupons it believes are applied.)

CREATE TABLE interest_rate_adjustments (
    adjustment_id UUID PRIMARY KEY,
    account_id    UUID         NOT NULL,
    delta_bps     INTEGER      NOT NULL CHECK (delta_bps <> 0 AND delta_bps BETWEEN -300 AND 300),
    valid_from    DATE         NOT NULL,
    valid_to      DATE         NOT NULL,
    source        VARCHAR(32)  NOT NULL,
    source_ref    VARCHAR(128) NOT NULL,
    approval_ref  VARCHAR(128) NOT NULL,
    status        VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED')),
    created_by    VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    cancelled_by  VARCHAR(255),
    cancelled_at  TIMESTAMPTZ,
    CONSTRAINT ck_rate_adjustment_window CHECK (valid_to > valid_from AND valid_to - valid_from <= 400),
    CONSTRAINT ck_rate_adjustment_cancel CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

CREATE UNIQUE INDEX ux_rate_adjustment_source_ref ON interest_rate_adjustments (source_ref);

-- The accrual lookup: ACTIVE adjustments of one account covering one date.
CREATE INDEX ix_rate_adjustment_account_active ON interest_rate_adjustments (account_id, valid_from)
    WHERE status = 'ACTIVE';
