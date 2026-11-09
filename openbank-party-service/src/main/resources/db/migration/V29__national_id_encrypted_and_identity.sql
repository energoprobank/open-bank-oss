-- Experience v2 step 9: robust onboarding.
--  * national_id_ct: a natural person's national number (rodné číslo / ЕГН) encrypted at rest
--    (AES-256-GCM, AAD = party id, "v<key>:" prefix). Once written, tax_id is NULL for that row —
--    only the blind index (rc_blind_index, now for both markets) stays searchable.
--  * identity_verified_at / identity_method: how the bank verified the person at account opening.
--  * risk_rating is now written from the KYC decision. Its V2 DEFAULT 'MEDIUM' was never a rating
--    anyone computed (no code wrote the column), so the default goes and the defaulted values are
--    cleared rather than shown as an assessment.
-- Rollback: ALTER TABLE parties DROP COLUMN national_id_ct, DROP COLUMN national_id_key_version,
--           DROP COLUMN identity_verified_at, DROP COLUMN identity_method;
--           ALTER TABLE parties ALTER COLUMN risk_rating SET DEFAULT 'MEDIUM';
--           (a national number already moved to national_id_ct must be decrypted back first)
ALTER TABLE parties
    ADD COLUMN IF NOT EXISTS national_id_ct          TEXT,
    ADD COLUMN IF NOT EXISTS national_id_key_version INTEGER,
    ADD COLUMN IF NOT EXISTS identity_verified_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS identity_method         VARCHAR(20);

ALTER TABLE parties ALTER COLUMN risk_rating DROP DEFAULT;
UPDATE parties SET risk_rating = NULL WHERE risk_rating = 'MEDIUM';
