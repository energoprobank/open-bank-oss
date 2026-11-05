-- Experience v2 step 8: multi-market retail customers and the account-opening FATCA/CRS
-- self-certification.
--
-- market: the commercial market the person banks in. Every existing party was opened in Czechia,
-- so the default is CZ; a new value needs an application change (the CHECK lists them).
-- tax_self_certification: JSON {usPerson, residencies[{country, tin}], certifiedAt}; kept apart from
-- party_aml_profiles so it never sets fatca_status (which means "full AML profile declared").
--
-- Rollback (only while no BG party or certification exists):
--   ALTER TABLE parties DROP COLUMN tax_self_certification;
--   ALTER TABLE parties DROP CONSTRAINT parties_market_check;
--   ALTER TABLE parties DROP COLUMN market;
ALTER TABLE parties ADD COLUMN market VARCHAR(2) NOT NULL DEFAULT 'CZ';
ALTER TABLE parties ADD CONSTRAINT parties_market_check CHECK (market IN ('CZ', 'BG'));
ALTER TABLE parties ADD COLUMN tax_self_certification TEXT;
