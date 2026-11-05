-- SPDX-License-Identifier: Apache-2.0
-- Additive, time-boxed loan rate adjustments (FLUX rewards CR-1 loan rate coupon).
--
-- The contract rate on loan.nominal_annual_rate is never rewritten: an ACTIVE row lowers the rate
-- of every instalment whose due date falls in (valid_from, valid_to], and applying or cancelling
-- one re-amortises the unpaid, not-yet-accrued tail of `installment` in place. source_ref is the
-- caller's idempotency handle and is UNIQUE across the table. The before/after columns hold the
-- terms disclosed at application time so an idempotent retry replays them verbatim.
--
-- The id is application-assigned (UUIDv7), so no <table>_seq is needed.
--
-- Rollback:
--   DROP TABLE loan_rate_adjustment;
-- Dropping the table does NOT restore the instalment amounts it repriced. Before rolling back,
-- cancel every ACTIVE adjustment through DELETE /api/v1/loans/{loanId}/rate-adjustments/{id}
-- (which re-amortises the tail at the contract rate), or the repriced schedules stay as they are
-- with no row left explaining them.

CREATE TABLE loan_rate_adjustment (
    id                  UUID          PRIMARY KEY,
    loan_id             UUID          NOT NULL REFERENCES loan(id),
    delta_bps           INT           NOT NULL CHECK (delta_bps <> 0),
    valid_from          DATE          NOT NULL,
    valid_to            DATE          NOT NULL,
    source              VARCHAR(16)   NOT NULL,
    source_ref          VARCHAR(128)  NOT NULL,
    approval_ref        VARCHAR(128)  NOT NULL,
    status              VARCHAR(16)   NOT NULL,
    created_by          VARCHAR(128)  NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    cancelled_by        VARCHAR(128),
    cancelled_at        TIMESTAMPTZ,
    currency            CHAR(3),
    before_rate_pct     NUMERIC(10, 4),
    before_aprc_pct     NUMERIC(10, 2),
    before_instalment   NUMERIC(20, 2),
    after_rate_pct      NUMERIC(10, 4),
    after_aprc_pct      NUMERIC(10, 2),
    after_instalment    NUMERIC(20, 2),
    effective_from      DATE,
    CONSTRAINT uq_loan_rate_adjustment_source_ref UNIQUE (source_ref),
    CONSTRAINT ck_loan_rate_adjustment_window CHECK (valid_to > valid_from),
    CONSTRAINT ck_loan_rate_adjustment_status CHECK (status IN ('ACTIVE', 'CANCELLED'))
);

CREATE INDEX idx_loan_rate_adjustment_loan ON loan_rate_adjustment(loan_id);
