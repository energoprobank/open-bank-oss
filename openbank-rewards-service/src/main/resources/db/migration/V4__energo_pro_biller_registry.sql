-- SPDX-License-Identifier: Apache-2.0
-- Experience v2 step 3: Energo-Pro's collection accounts are no longer placeholders. They are the
-- Energo-Pro billing system's own (provider-simulator EnergoProBilling.COLLECTION), the same rows
-- everyday-service's biller registry holds, so a bill payment the customer signs is recognised here
-- (M-BILL-1, BILL_CASHBACK) and there (the bill shows PAID, Energo-Pro is notified).
-- Rollback:
--   UPDATE biller SET name = 'Energo-Pro (placeholder)', placeholder = TRUE WHERE biller_id = 'EP-CZ';
--   UPDATE biller SET name = 'Energo-Pro Bulgaria (placeholder)', placeholder = TRUE WHERE biller_id = 'EP-BG';
UPDATE biller SET name = 'Energo-Pro, a.s.', placeholder = FALSE
 WHERE biller_id = 'EP-CZ' AND account_number = '2001234561/2010';
UPDATE biller SET name = 'Енерго-Про Продажби АД', placeholder = FALSE
 WHERE biller_id = 'EP-BG' AND account_number = 'BG18RZBB91550123456789';
