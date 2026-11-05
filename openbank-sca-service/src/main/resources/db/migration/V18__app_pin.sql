-- Experience v2 step 8: the app PIN, the fallback to Face ID for confirming on an enrolled device.
-- The app never sends the PIN: it sends a verifier (SHA-256 over credentialId:PIN) and the bank stores
-- only a salted PBKDF2-HMAC-SHA256 of that verifier. One PIN per device credential.
-- Rollback: DROP TABLE sca_app_pins;
CREATE TABLE sca_app_pins (
    credential_id VARCHAR(512) PRIMARY KEY,
    party_id      UUID         NOT NULL,
    salt          VARCHAR(64)  NOT NULL,
    iterations    INT          NOT NULL,
    pin_hash      VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_sca_app_pins_party ON sca_app_pins (party_id);
