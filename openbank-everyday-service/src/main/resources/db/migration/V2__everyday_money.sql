-- SPDX-License-Identifier: Apache-2.0
-- openbank-everyday-service V2 (experience v2 step 3): direct-debit consents, subscription choices,
-- payment exports, recall cases, card top-ups, payment protection.
-- Rollback: DROP TABLE payment_protection, top_ups, recall_cases, payment_exports,
--           subscription_choices, direct_debit_mandates;

-- A debtor's consent at their own bank to be collected from: CZ inkaso (creditor account), SIPO
-- (the household's SIPO number at Česká pošta), SEPA Core (creditor identifier, EUR).
CREATE TABLE direct_debit_mandates (
    mandate_id          UUID          PRIMARY KEY,
    party_id            UUID          NOT NULL,
    scheme              VARCHAR(16)   NOT NULL CHECK (scheme IN ('INKASO', 'SIPO', 'SEPA_CORE')),
    creditor_name       VARCHAR(120)  NOT NULL,
    creditor_reference  VARCHAR(64)   NOT NULL,
    debtor_account_id   UUID          NOT NULL,
    limit_amount        NUMERIC(19, 2),
    currency            VARCHAR(3)    NOT NULL,
    frequency           VARCHAR(12)   NOT NULL CHECK (frequency IN ('MONTHLY', 'QUARTERLY', 'YEARLY', 'ANY')),
    status              VARCHAR(12)   NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED')),
    created_at          TIMESTAMPTZ   NOT NULL,
    cancelled_at        TIMESTAMPTZ
);
CREATE INDEX ix_dd_mandates_party ON direct_debit_mandates (party_id, created_at DESC);

-- Subscriptions are DETECTED from the customer's payments each time; only the customer's choices
-- about one (cancel requested, reminder) are stored, keyed by the detected id.
CREATE TABLE subscription_choices (
    party_id          UUID         NOT NULL,
    subscription_id   VARCHAR(64)  NOT NULL,
    cancel_requested  BOOLEAN      NOT NULL DEFAULT FALSE,
    reminder_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at        TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (party_id, subscription_id)
);

-- A payments export (CSV or PDF). The file is downloaded through a short-lived capability token,
-- so the app can share the link; after expires_at the token is refused.
CREATE TABLE payment_exports (
    export_id       UUID          PRIMARY KEY,
    party_id        UUID          NOT NULL,
    account_id      UUID          NOT NULL,
    format          VARCHAR(4)    NOT NULL CHECK (format IN ('CSV', 'PDF')),
    direction       VARCHAR(8)    NOT NULL CHECK (direction IN ('ALL', 'INCOMING', 'OUTGOING')),
    from_date       DATE          NOT NULL,
    to_date         DATE          NOT NULL,
    status          VARCHAR(10)   NOT NULL CHECK (status IN ('PREPARING', 'READY', 'FAILED')),
    file_name       VARCHAR(120),
    content_type    VARCHAR(60),
    content         BYTEA,
    download_token  VARCHAR(64)   UNIQUE,
    expires_at      TIMESTAMPTZ   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL
);

-- A customer's request to get an outgoing payment back (camt.056 towards the recipient's bank).
CREATE TABLE recall_cases (
    case_id         UUID          PRIMARY KEY,
    party_id        UUID          NOT NULL,
    transaction_id  UUID          NOT NULL UNIQUE,
    reference       VARCHAR(20)   NOT NULL UNIQUE,
    reason          VARCHAR(16)   NOT NULL CHECK (reason IN ('WRONG_ACCOUNT', 'WRONG_AMOUNT', 'DUPLICATE', 'FRAUD')),
    note            VARCHAR(500),
    status          VARCHAR(14)   NOT NULL CHECK (status IN ('REQUESTED', 'SENT_TO_BANK', 'RETURNED', 'REFUSED')),
    answer_by       DATE          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL
);

-- Money added from a card at another bank, through the acquirer's checkout. The credit is booked
-- once (credited_transaction_id) after the acquirer authorises.
CREATE TABLE top_ups (
    top_up_id                UUID           PRIMARY KEY,
    party_id                 UUID           NOT NULL,
    account_id               UUID           NOT NULL,
    amount                   NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency                 VARCHAR(3)     NOT NULL,
    fee                      NUMERIC(19, 2) NOT NULL DEFAULT 0,
    status                   VARCHAR(12)    NOT NULL CHECK (status IN ('CREATED', 'AUTHORISED', 'CREDITED', 'FAILED')),
    acquirer_ref             VARCHAR(64)    NOT NULL,
    checkout_url             VARCHAR(300)   NOT NULL,
    credited_transaction_id  UUID,
    created_at               TIMESTAMPTZ    NOT NULL,
    updated_at               TIMESTAMPTZ    NOT NULL
);
CREATE INDEX ix_top_ups_party ON top_ups (party_id, created_at DESC);

-- Payment protection: hold unusual payments for a confirmation, street mode (a low limit while out),
-- and a trusted person who is told about held payments.
CREATE TABLE payment_protection (
    party_id                UUID           PRIMARY KEY,
    hold_unusual            BOOLEAN        NOT NULL,
    street_mode             BOOLEAN        NOT NULL,
    street_mode_limit       NUMERIC(19, 2) NOT NULL,
    trusted_approver_name   VARCHAR(120),
    trusted_approver_phone  VARCHAR(20),
    updated_at              TIMESTAMPTZ    NOT NULL
);
