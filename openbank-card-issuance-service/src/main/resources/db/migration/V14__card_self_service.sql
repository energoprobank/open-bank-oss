-- Experience v2 step 4: what a customer manages on their own card — the PIN, the design, a plastic
-- card order on its way from the manufacturer, travel notices, and card notifications.
-- Rollback: DROP TABLE card_notification_settings, card_travel_notices, physical_card_orders,
--           card_designs, card_pins;

-- The PIN is stored encrypted with the card-secret key (CardSecretCipher, the same key as the PAN
-- vault) because the customer can view it after SCA; it is never logged or returned without SCA.
CREATE TABLE card_pins (
    card_id        UUID        PRIMARY KEY,
    pin_cipher     TEXT        NOT NULL,
    set_at         TIMESTAMPTZ NOT NULL,
    attempts_left  INTEGER     NOT NULL DEFAULT 3 CHECK (attempts_left BETWEEN 0 AND 3)
);

CREATE TABLE card_designs (
    card_id     UUID        PRIMARY KEY,
    design      VARCHAR(24) NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

-- A plastic card on its way: the card exists (PENDING) from the order; it is ACTIVATED by the customer
-- with the last four digits printed on it once the manufacturer has delivered it.
CREATE TABLE physical_card_orders (
    order_id        UUID         PRIMARY KEY,
    party_id        UUID         NOT NULL,
    account_id      UUID         NOT NULL,
    card_id         UUID         NOT NULL UNIQUE,
    design          VARCHAR(24)  NOT NULL,
    name_on_card    VARCHAR(26)  NOT NULL,
    line1           VARCHAR(120) NOT NULL,
    line2           VARCHAR(120),
    city            VARCHAR(80)  NOT NULL,
    postal_code     VARCHAR(16)  NOT NULL,
    country_code    VARCHAR(2)   NOT NULL,
    factory_ref     VARCHAR(64),
    activated_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_physical_card_orders_party ON physical_card_orders (party_id, created_at DESC);

-- "I'm abroad in these countries on these dates": payments there are authorised even with payments
-- abroad switched off (CardAuthorizationPolicy).
CREATE TABLE card_travel_notices (
    notice_id   UUID         PRIMARY KEY,
    card_id     UUID         NOT NULL,
    countries   VARCHAR(200) NOT NULL,
    start_date  DATE         NOT NULL,
    end_date    DATE         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    removed_at  TIMESTAMPTZ,
    CHECK (end_date >= start_date)
);
CREATE INDEX idx_card_travel_notices_card ON card_travel_notices (card_id) WHERE removed_at IS NULL;

CREATE TABLE card_notification_settings (
    party_id                 UUID           PRIMARY KEY,
    every_payment            BOOLEAN        NOT NULL,
    declined_payments        BOOLEAN        NOT NULL,
    payments_abroad          BOOLEAN        NOT NULL,
    online_payments          BOOLEAN        NOT NULL,
    weekly_summary           BOOLEAN        NOT NULL,
    large_payment_threshold  NUMERIC(19, 2),
    currency                 VARCHAR(3)     NOT NULL,
    updated_at               TIMESTAMPTZ    NOT NULL
);
