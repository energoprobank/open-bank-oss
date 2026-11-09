-- Experience v2 step 9: the account opening is orchestrated here — identity resolution (pid), the
-- identity checks with their evidence, the first payment, the assessment sent to kyc-service.
-- Rollback (see CLAUDE.md):
--   ALTER TABLE onboarding_applications DROP COLUMN status, DROP COLUMN pid_decision, DROP COLUMN pid_case_id,
--     DROP COLUMN first_payment_reference, DROP COLUMN first_payment_json, DROP COLUMN assessment_json,
--     DROP COLUMN kyc_case_id, DROP COLUMN submitted_at;
--   ALTER TABLE identity_verifications DROP COLUMN oidc_state, DROP COLUMN oidc_verifier, DROP COLUMN oidc_nonce,
--     DROP COLUMN document_id, DROP COLUMN evidence_json, DROP COLUMN needs_review;
ALTER TABLE onboarding_applications
    ADD COLUMN IF NOT EXISTS status                  VARCHAR(30) NOT NULL DEFAULT 'IN_PROGRESS',
    ADD COLUMN IF NOT EXISTS pid_decision            VARCHAR(40),
    ADD COLUMN IF NOT EXISTS pid_case_id             UUID,
    ADD COLUMN IF NOT EXISTS first_payment_reference VARCHAR(10),
    ADD COLUMN IF NOT EXISTS first_payment_json      TEXT,
    ADD COLUMN IF NOT EXISTS assessment_json         TEXT,
    ADD COLUMN IF NOT EXISTS kyc_case_id             VARCHAR(64),
    ADD COLUMN IF NOT EXISTS submitted_at            TIMESTAMPTZ;

ALTER TABLE identity_verifications
    ADD COLUMN IF NOT EXISTS oidc_state    VARCHAR(64),
    ADD COLUMN IF NOT EXISTS oidc_verifier VARCHAR(128),
    ADD COLUMN IF NOT EXISTS oidc_nonce    VARCHAR(64),
    ADD COLUMN IF NOT EXISTS document_id   VARCHAR(128),
    ADD COLUMN IF NOT EXISTS evidence_json TEXT,
    ADD COLUMN IF NOT EXISTS needs_review  BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_identity_verifications_oidc_state
    ON identity_verifications (oidc_state) WHERE oidc_state IS NOT NULL;
