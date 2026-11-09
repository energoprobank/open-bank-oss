-- SPDX-License-Identifier: Apache-2.0
-- Parties under ongoing sanctions monitoring (screen request `monitor = true`). When a list refresh
-- publishes SANCTIONS_LIST_CHANGED (or a change storm), every subject watching that list is screened
-- again; a new HIT / POTENTIAL_HIT lands in the ordinary review queue as a normal check.
-- list_types '[]' means "every list", mirroring an absent listTypes on the screen request.
-- Rollback: roll back pods to a build without monitoring first (it upserts into this table on every
-- monitored screen), then DROP TABLE sanctions_monitored_subjects. No other table references it, and
-- the checks the re-screens created stay in sanctions_checks as ordinary checks.
CREATE TABLE IF NOT EXISTS sanctions_monitored_subjects (
    party_id          VARCHAR(255) PRIMARY KEY,
    entity_type       VARCHAR(20)  NOT NULL,
    name              VARCHAR(255) NOT NULL,
    aliases           JSONB        NOT NULL DEFAULT '[]',
    date_of_birth     VARCHAR(32),
    nationality       VARCHAR(8),
    list_types        JSONB        NOT NULL DEFAULT '[]',
    last_screened_at  TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_monitored_subjects_list_types
    ON sanctions_monitored_subjects USING gin (list_types);
