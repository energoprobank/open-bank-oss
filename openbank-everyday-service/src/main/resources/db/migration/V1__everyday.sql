-- SPDX-License-Identifier: Apache-2.0
-- openbank-everyday-service V1 (experience v2 step 3): biller registry, the Energo-Pro electricity
-- link, bill payments the bank saw, bill reminders.
-- Rollback: DROP TABLE bill_reminders, bill_payments, electricity_links, billers;

-- Who a customer can pay as a bill. A CZ biller is matched on a payment by its domestic account
-- (number/bank, as domestic-payment events carry it); a BG biller by IBAN.
CREATE TABLE billers (
    biller_id       VARCHAR(32)  PRIMARY KEY,
    market          VARCHAR(2)   NOT NULL CHECK (market IN ('CZ', 'BG')),
    name            VARCHAR(120) NOT NULL,
    category        VARCHAR(24)  NOT NULL,
    iban            VARCHAR(34)  NOT NULL,
    account_number  VARCHAR(40),
    currency        VARCHAR(3)   NOT NULL,
    -- The biller whose bills the bank can show (Energo-Pro): bills come from its billing system.
    connected       BOOLEAN      NOT NULL DEFAULT FALSE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order      INTEGER      NOT NULL DEFAULT 100
);

-- Energo-Pro's collection accounts are the provider simulator's (EnergoProBilling.COLLECTION); the
-- rest are the usual Czech household billers (fictitious accounts that pass the mod-11 check).
INSERT INTO billers (biller_id, market, name, category, iban, account_number, currency, connected, sort_order) VALUES
    ('EP-CZ',    'CZ', 'Energo-Pro, a.s.',               'ELECTRICITY', 'CZ1520100000002001234561', '2001234561/2010', 'CZK', TRUE, 10),
    ('EP-BG',    'BG', 'Енерго-Про Продажби АД',         'ELECTRICITY', 'BG18RZBB91550123456789',   NULL,              'EUR', TRUE, 10),
    ('CEZ',      'CZ', 'ČEZ Prodej, a.s.',               'ENERGY',      'CZ1003000000000001387008', '1387008/0300',    'CZK', FALSE, 20),
    ('PRE',      'CZ', 'Pražská energetika, a.s.',       'ENERGY',      'CZ1127000000000002004005', '2004005/2700',    'CZK', FALSE, 30),
    ('PVK',      'CZ', 'Pražské vodovody a kanalizace',  'WATER',       'CZ6901000000000002001007', '2001007/0100',    'CZK', FALSE, 40),
    ('O2',       'CZ', 'O2 Czech Republic a.s.',         'TELECOM',     'CZ8801000000000001501018', '1501018/0100',    'CZK', FALSE, 50),
    ('VODAFONE', 'CZ', 'Vodafone Czech Republic a.s.',   'TELECOM',     'CZ0908000000000001903009', '1903009/0800',    'CZK', FALSE, 60),
    ('SIPO',     'CZ', 'Česká pošta — SIPO',             'HOUSEHOLD',   'CZ4203000000000003366008', '3366008/0300',    'CZK', FALSE, 70);

-- A customer's own word that a given Energo-Pro customer number is theirs (the bank never learns
-- it from Energo-Pro). One electricity account per customer.
CREATE TABLE electricity_links (
    party_id         UUID         PRIMARY KEY,
    customer_number  VARCHAR(16)  NOT NULL,
    market           VARCHAR(2)   NOT NULL,
    linked_at        TIMESTAMPTZ  NOT NULL
);

-- Bill payments the bank made for its customers (from domestic-payment events). Energo-Pro is told
-- (notice_sent_at); until it is, a scheduler retries. The bank's own record keeps a bill PAID even if
-- the biller forgets.
CREATE TABLE bill_payments (
    payment_id       UUID         PRIMARY KEY,
    party_id         UUID         NOT NULL,
    biller_id        VARCHAR(32)  NOT NULL REFERENCES billers (biller_id),
    reference        VARCHAR(35)  NOT NULL,
    amount           NUMERIC(19, 2) NOT NULL,
    currency         VARCHAR(3)   NOT NULL,
    paid_at          TIMESTAMPTZ  NOT NULL,
    notice_sent_at   TIMESTAMPTZ,
    notice_attempts  INTEGER      NOT NULL DEFAULT 0,
    notice_error     VARCHAR(200)
);
CREATE INDEX ix_bill_payments_party ON bill_payments (party_id, paid_at DESC);
CREATE INDEX ix_bill_payments_pending ON bill_payments (paid_at) WHERE notice_sent_at IS NULL;

CREATE TABLE bill_reminders (
    party_id     UUID        PRIMARY KEY,
    enabled      BOOLEAN     NOT NULL,
    days_before  INTEGER     NOT NULL CHECK (days_before BETWEEN 0 AND 14),
    updated_at   TIMESTAMPTZ NOT NULL
);
