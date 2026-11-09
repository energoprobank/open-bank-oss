-- Experience v2 step 9: an onboarding assessment on the KYC case — the evidence it rests on, the
-- policy that produced it, why a person must look, and who submitted it (a reviewer may not be the
-- same principal). due_diligence_level and next_review_date already exist (V2) and are now written.
-- Rollback: ALTER TABLE kyc_cases DROP COLUMN evidence_json, DROP COLUMN policy_version,
--           DROP COLUMN review_reasons, DROP COLUMN assessed_by, DROP COLUMN assessed_at;
ALTER TABLE kyc_cases
    ADD COLUMN IF NOT EXISTS evidence_json  TEXT,
    ADD COLUMN IF NOT EXISTS policy_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS review_reasons TEXT,
    ADD COLUMN IF NOT EXISTS assessed_by    VARCHAR(100),
    ADD COLUMN IF NOT EXISTS assessed_at    TIMESTAMPTZ;
