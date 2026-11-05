-- SPDX-License-Identifier: Apache-2.0
-- The CZ placeholder Energo-Pro collection account 2001234567/2010 fails the Czech account-number
-- mod-11 check, so domestic-payment refuses a payment to it and no bill could ever be recognised.
-- 2001234561/2010 passes. Still a PLACEHOLDER (step 3 brings the Energo-Pro simulator's registry).
-- Rollback: UPDATE biller SET account_number = '2001234567/2010' WHERE biller_id = 'EP-CZ';
UPDATE biller SET account_number = '2001234561/2010' WHERE biller_id = 'EP-CZ' AND account_number = '2001234567/2010';
