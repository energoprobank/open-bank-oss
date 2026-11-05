-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0344: GL accounts for the FLUX rewards programme (Energo-Pro, experience v2). Per currency from
-- the start (postJournal 422s a line whose currency differs from its account's): CZ market posts CZK,
-- BG market posts EUR. Fixed ids a0000000-0000-0000-0000-00000000<code>, the V14/V20/V26/V29 convention,
-- because two posters address them by id: the ledger's own daily provision template
-- (POST /api/v1/ledger/reward-provisions) and transaction-service's reward cash credit, whose journal
-- debits the provision (Dr 2400/2401, Cr 2100/2101 sub=customer account).
--
-- | code | name                                         | type      | ccy |
-- | 2400 | FLUX Reward Provision                        | LIABILITY | CZK |  outstanding FLUX the bank owes
-- | 2401 | FLUX Reward Provision (EUR)                  | LIABILITY | EUR |  (earned, not yet redeemed/expired)
-- | 5300 | FLUX Reward Expense                          | EXPENSE   | CZK |  marketing expense recognised daily
-- | 5301 | FLUX Reward Expense (EUR)                    | EXPENSE   | EUR |  as the provision moves
-- | 5310 | Credit Reward Interest Forgone               | EXPENSE   | CZK |  rate-reduction coupons (CR-1); an
-- | 5311 | Credit Reward Interest Forgone (EUR)         | EXPENSE   | EUR |  expense, not a contra-income (below)
--
-- Why these codes. ADR-0310 D2 proposed 5200 for reward expense; 5200 is "MM Interest Expense" (V29),
-- mapped in the accounting-class map (V32, INTERBANK_BORROWING / CNB_LOMBARD_BORROWING) and classified
-- as interest expense in FINREP F02.00. 5300-5311 and 2400-2401 are used by no migration, no Kotlin
-- constant and no FINREP mapping (checked 2026-11-05). 53xx sits after 5100 (loan loss) and 5200 (MM
-- interest) in the expense range; 24xx after 2300-2320 (MM / CNB borrowings) in the liability range.
--
-- Why interest forgone is EXPENSE, not a debit-balance INCOME account. gl_accounts.type allows exactly
-- ASSET/LIABILITY/EQUITY/INCOME/EXPENSE (V1 chk_gl_accounts_type) and the chart has no contra account:
-- every INCOME account is credit-normal and every reader (trial balance, FINREP F02.00 creditBalance)
-- treats it so. A debit-balance INCOME row would net silently against interest income; an EXPENSE row
-- keeps the cost of the programme visible and separately reportable.
--
-- No accounting_class_gl_map rows (V32): its role CHECK has no provision/reward role, and both posters
-- above are covered without one (the ledger owns its own template; transaction-service already names
-- every leg of PaymentJournalFactory by fixed id). Mapping 5310/5311 for lending is a later migration.
--
-- FINREP: FinrepCoverage is fail-closed, so the first posting to any of these accounts makes F01/F02
-- report UNCLASSIFIED_GL_ACCOUNT until finrep-service classifies them (follow-up, see ADR-0344).

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM gl_accounts WHERE code IN ('2400','2401','5300','5301','5310','5311')) THEN
        RAISE EXCEPTION 'FLUX reward GL codes already in use: %',
            (SELECT string_agg(code || ' ' || name, ', ') FROM gl_accounts
             WHERE code IN ('2400','2401','5300','5301','5310','5311'));
    END IF;
END $$;

INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    ('a0000000-0000-0000-0000-000000002400', '2400', 'FLUX Reward Provision', 'LIABILITY', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000002401', '2401', 'FLUX Reward Provision (EUR)', 'LIABILITY', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000005300', '5300', 'FLUX Reward Expense', 'EXPENSE', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000005301', '5301', 'FLUX Reward Expense (EUR)', 'EXPENSE', 'EUR', true, true),
    ('a0000000-0000-0000-0000-000000005310', '5310', 'Credit Reward Interest Forgone', 'EXPENSE', 'CZK', true, true),
    ('a0000000-0000-0000-0000-000000005311', '5311', 'Credit Reward Interest Forgone (EUR)', 'EXPENSE', 'EUR', true, true);

-- Rollback (safe only while no journal_line and no frozen trial-balance line references these
-- accounts -- check both first):
--   SELECT count(*) FROM journal_lines WHERE gl_account_id IN (SELECT id FROM gl_accounts
--       WHERE code IN ('2400','2401','5300','5301','5310','5311'));
--   SELECT count(*) FROM ledger_closed_period_trial_balance_line WHERE gl_account_id IN (...same...);
--   DELETE FROM gl_accounts WHERE code IN ('2400','2401','5300','5301','5310','5311');
-- Once anything has posted, roll back by disabling instead (UPDATE gl_accounts SET is_enabled = false
-- WHERE code IN (...)): postJournal then refuses new postings and the history stays intact.
