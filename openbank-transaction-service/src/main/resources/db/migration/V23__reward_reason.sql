-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0344 D3: a FLUX reward cash credit (POST /api/v1/reward-credits) is an ordinary CREDIT
-- transaction whose journal is funded from the FLUX provision (ledger GL 2400 CZK / 2401 EUR) instead
-- of cash clearing. The saga re-reads the row before it builds the journal, so the funding choice must
-- be persisted: a non-null reward_reason is that choice, and also the audit record of WHY the bank paid.
-- Nullable: every existing row and every non-reward transaction stays NULL (cash-clearing funded, as
-- before). The CHECK closes the vocabulary to the three reasons the rewards contract defines.
-- Idempotency on the caller's sourceRef rides the existing global key registry (V22) as
-- idempotency_key = 'reward-credit:<sourceRef>'.
--
-- The column add is metadata-only (nullable, no default): no rewrite of the booking-date partitions.
-- The CHECK is validated once by a scan of every partition, trivially true while the column is all NULL.
ALTER TABLE transactions ADD COLUMN reward_reason VARCHAR(32);
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_reward_reason
    CHECK (reward_reason IS NULL OR reward_reason IN ('BILL_CASHBACK', 'INSTALMENT_RELIEF', 'REFERRAL_CASH'));

-- Rollback (safe at any time for the schema; the rows keep their journals in the ledger):
--   ALTER TABLE transactions DROP CONSTRAINT chk_transactions_reward_reason;
--   ALTER TABLE transactions DROP COLUMN reward_reason;
-- Roll the application back FIRST: a previous release ignores the column, but a release with this
-- code against a schema without it fails every insert. A pending reward credit whose saga has not yet
-- posted would, after the column is gone, be booked from cash clearing — drain PENDING reward credits
-- (reward_reason IS NOT NULL AND status IN ('PENDING','PROCESSING')) before dropping it.
