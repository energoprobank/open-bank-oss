-- FLUX rewards: an offer the rewards engine may redeem in one step in any context (hub, onboarding,
-- term deposit), whatever its product scope. Defaults FALSE, so every existing offer keeps its
-- product-bound behaviour. An offer flagged claimable may carry an empty product scope.
-- Rollback: stop rewards redemptions, then DROP INDEX promo_reservation_party_product_status_idx and
-- ALTER TABLE incentive_offer DROP COLUMN claimable_in_hub. Redemptions already committed remain
-- ordinary COMMITTED reservations; their evidence on Kafka is append-only.
ALTER TABLE incentive_offer ADD COLUMN claimable_in_hub BOOLEAN NOT NULL DEFAULT FALSE;

-- The stacking check reads a party's committed reservations per context on every redemption.
CREATE INDEX promo_reservation_party_product_status_idx
  ON promo_reservation (party_ref, product_ref, status);
