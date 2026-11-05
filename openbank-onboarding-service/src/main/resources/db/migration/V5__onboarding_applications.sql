-- Experience v2 step 8: the write side of self-service account opening, next to the funnel read
-- model. Personal master data (date of birth, national number, address, tax residency) is NOT stored
-- here — it is written through to party-service; these tables hold the application's own state and
-- the third-party checks. Rollback: DROP TABLE liveness_sessions, liveness_policies,
-- identity_verifications, onboarding_applications, contact_verifications;

-- E-mail + phone one-time codes, stored only as HMACs; expire after 10 minutes, 5 attempts.
CREATE TABLE contact_verifications (
    id                UUID PRIMARY KEY,
    email             VARCHAR(254) NOT NULL,
    phone             VARCHAR(20)  NOT NULL,
    email_code_hash   VARCHAR(64)  NOT NULL,
    phone_code_hash   VARCHAR(64)  NOT NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    email_verified_at TIMESTAMPTZ,
    phone_verified_at TIMESTAMPTZ,
    prefill_attempts  INT          NOT NULL DEFAULT 0,
    expires_at        TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_contact_verifications_email ON contact_verifications (email, created_at);
CREATE INDEX idx_contact_verifications_phone ON contact_verifications (phone, created_at);

-- One row per applicant (party id from the customer's token).
CREATE TABLE onboarding_applications (
    party_id             UUID PRIMARY KEY,
    package_code         VARCHAR(20),
    personal_details_at  TIMESTAMPTZ,
    tax_residency_at     TIMESTAMPTZ,
    identity_verified_at TIMESTAMPTZ,
    identity_method      VARCHAR(20),
    updated_at           TIMESTAMPTZ NOT NULL
);

-- Bank iD / document-scan checks against the identity providers.
CREATE TABLE identity_verifications (
    id             UUID PRIMARY KEY,
    party_id       UUID        NOT NULL,
    method         VARCHAR(20) NOT NULL,
    document_type  VARCHAR(20),
    provider_ref   VARCHAR(128) NOT NULL,
    status         VARCHAR(20) NOT NULL,
    failure_reason VARCHAR(40),
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_identity_verifications_party ON identity_verifications (party_id, created_at);

-- Face check for large payments: the customer's limit, and the single-use liveness sessions.
CREATE TABLE liveness_policies (
    party_id      UUID PRIMARY KEY,
    enabled       BOOLEAN       NOT NULL,
    threshold     NUMERIC(19,2) NOT NULL,
    currency      VARCHAR(3)    NOT NULL,
    last_check_at TIMESTAMPTZ
);

CREATE TABLE liveness_sessions (
    id                UUID PRIMARY KEY,
    party_id          UUID         NOT NULL,
    purpose           VARCHAR(20)  NOT NULL,
    provider_ref      VARCHAR(128) NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    payment_reference VARCHAR(128),
    consumed_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_liveness_sessions_party ON liveness_sessions (party_id, created_at);
