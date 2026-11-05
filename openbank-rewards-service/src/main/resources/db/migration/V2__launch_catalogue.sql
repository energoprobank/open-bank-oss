-- SPDX-License-Identifier: Apache-2.0
-- Launch data from the spec (Economy, Existing rewards, Credit rewards). Every value is an
-- operator-set default; the staff API changes them through maker-checker afterwards.
-- approval_ref 'SPEC-2026-11-05' = approved as part of the implementation spec.
-- Rollback: DELETE the rows below (no other data references them before first use).

INSERT INTO market_param (market, currency, flux_value, funding_minimum, card_minimum, envelope_per_year, locale, updated_at) VALUES
    ('CZ', 'CZK', 0.25, 250.00, 50.00, 250.00, 'cs-CZ', NOW()),
    ('BG', 'EUR', 0.01, 10.00, 2.00, 10.00, 'bg-BG', NOW());

INSERT INTO budget_line (budget_line_id, kind, market, amount, description) VALUES
    ('ENG-CZ', 'ENGAGEMENT', 'CZ', 500000.00, 'Missions, onboarding coupons, promotions (CZ)'),
    ('ACQ-CZ', 'ACQUISITION', 'CZ', 200000.00, 'Referral rewards (CZ)'),
    ('OFFER-CZ', 'OFFER', 'CZ', 100000.00, 'Promo-code offers (CZ)'),
    ('MIG-CZ', 'MIGRATION', 'CZ', 1000000.00, 'One-off Listky migration (CZ)'),
    ('CREDIT-CZ', 'CREDIT', 'CZ', 300000.00, 'Credit rewards, Finance + Risk (CZ)'),
    ('ENG-BG', 'ENGAGEMENT', 'BG', 20000.00, 'Missions, onboarding coupons, promotions (BG)'),
    ('ACQ-BG', 'ACQUISITION', 'BG', 8000.00, 'Referral rewards (BG)'),
    ('OFFER-BG', 'OFFER', 'BG', 4000.00, 'Promo-code offers (BG)'),
    ('MIG-BG', 'MIGRATION', 'BG', 1000.00, 'One-off migration (BG)'),
    ('CREDIT-BG', 'CREDIT', 'BG', 12000.00, 'Credit rewards, Finance + Risk (BG)');

INSERT INTO programme_switch (switch_id, enabled, changed_by, changed_at) VALUES
    ('EARN', TRUE, 'migration', NOW()), ('CHARGE', TRUE, 'migration', NOW()), ('USE', TRUE, 'migration', NOW());

-- Coupon catalogue. Caps are in the market currency.
INSERT INTO coupon_def (coupon_def_id, market, benefit_type, category, benefit_params, rarity, capacity, validity_days, level_gate,
                        in_catalogue, budget_line_id, status, approval_ref, sort_order)
SELECT d.id, m.market, d.benefit, d.category,
       CASE WHEN m.market = 'CZ' THEN d.params_cz ELSE d.params_bg END,
       d.rarity, d.capacity, d.validity, d.gate, d.in_cat,
       d.budget || '-' || m.market, 'ACTIVE', 'SPEC-2026-11-05', d.sort
FROM (VALUES
    ('BILL_CASHBACK_5', 'BILL_CASHBACK', 'BILLS', '{"percent":5,"cap":125}', '{"percent":5,"cap":5}', 'STANDARD', 60, 90, 1, TRUE, 'ENG', 10),
    ('BILL_CASHBACK_10', 'BILL_CASHBACK', 'BILLS', '{"percent":10,"cap":250}', '{"percent":10,"cap":10}', 'GOLD', 150, 90, 2, TRUE, 'ENG', 20),
    ('DEPOSIT_BOOST', 'DEPOSIT_RATE_BOOST', 'SAVINGS', '{"deltaBps":50,"durationDays":90,"balanceCap":125000}',
        '{"deltaBps":50,"durationDays":90,"balanceCap":5000}', 'STANDARD', 80, 60, 1, TRUE, 'ENG', 30),
    ('DEPOSIT_BOOST_PLUS', 'DEPOSIT_RATE_BOOST', 'SAVINGS', '{"deltaBps":100,"durationDays":90,"balanceCap":125000}',
        '{"deltaBps":100,"durationDays":90,"balanceCap":5000}', 'GOLD', 200, 60, 3, TRUE, 'ENG', 40),
    ('LOAN_RATE_CUT', 'LOAN_RATE_REDUCTION', 'LOANS', '{"deltaBps":-50,"durationDays":365}', '{"deltaBps":-50,"durationDays":365}',
        'GOLD', 250, 90, 2, TRUE, 'CREDIT', 50),
    ('INSTALMENT_RELIEF', 'INSTALMENT_RELIEF', 'LOANS', '{"percent":50,"cap":500}', '{"percent":50,"cap":20}', 'STANDARD', 120, 90, 1,
        TRUE, 'CREDIT', 60),
    ('APP_THEME', 'COSMETIC', 'STYLE', '{"theme":"aurora"}', '{"theme":"aurora"}', 'STANDARD', 30, NULL, 1, TRUE, 'ENG', 70),
    ('SIGNATURE_DEPOSIT_BOOST', 'DEPOSIT_RATE_BOOST', 'SAVINGS', '{"deltaBps":50,"durationDays":90,"balanceCap":125000}',
        '{"deltaBps":50,"durationDays":90,"balanceCap":5000}', 'SIGNATURE', 80, 90, 1, FALSE, 'OFFER', 80)
) AS d(id, benefit, category, params_cz, params_bg, rarity, capacity, validity, gate, in_cat, budget, sort)
CROSS JOIN (VALUES ('CZ'), ('BG')) AS m(market);

-- Launch missions. rule.fact = BehaviourFact type; minAmount = market's FUNDING or CARD minimum;
-- hidden = not listed until the customer has an instance (referee side, repayments, anniversary).
INSERT INTO mission_def (mission_id, version, market, kind, cadence, rule, reward, handoff, sort_order, budget_line_id, status, approval_ref)
SELECT d.id, 1, m.market, d.kind, d.cadence, d.rule, d.reward, d.handoff, d.sort, d.budget || '-' || m.market, d.status, 'SPEC-2026-11-05'
FROM (VALUES
    ('M-ONB-1', 'ONE_TIME', 'ONCE', '{"fact":"ACCOUNT_ACTIVATED","count":1}', '{"couponDefId":"BILL_CASHBACK_5","preChargePct":50}', NULL, 10, 'ENG', 'ACTIVE'),
    ('M-CARD-ACT', 'ONE_TIME', 'ONCE', '{"fact":"CARD_ACTIVATED","count":1}', '{"couponDefId":"DEPOSIT_BOOST","preChargePct":100}', 'cards', 20, 'ENG', 'ACTIVE'),
    ('M-ACC-1', 'ONE_TIME', 'ONCE', '{"fact":"FUNDING_RECEIVED","count":1,"minAmount":"FUNDING"}', '{"couponDefId":"DEPOSIT_BOOST","preChargePct":40}', NULL, 30, 'ENG', 'ACTIVE'),
    ('M-ONB-3', 'ONE_TIME', 'ONCE', '{"fact":"QUIZ_PASSED","count":1,"ref":"deposit-guarantee"}', '{"flux":5}', 'lessons', 40, 'ENG', 'ACTIVE'),
    ('M-PAY-1', 'ONE_TIME', 'ONCE', '{"fact":"TRANSFER_SENT","count":1}', '{"flux":10}', 'payments.transfer', 50, 'ENG', 'ACTIVE'),
    ('M-CARD-2', 'ONE_TIME', 'ONCE', '{"fact":"CARD_PAYMENT","count":1}', '{"flux":10}', 'cards', 60, 'ENG', 'ACTIVE'),
    ('M-CARD-3', 'RECURRING', 'WEEKLY', '{"fact":"CARD_PAYMENT","count":5,"minAmount":"CARD","distinctMerchants":3}', '{"flux":25}', 'cards', 70, 'ENG', 'ACTIVE'),
    ('M-BILL-1', 'ONE_TIME', 'ONCE', '{"fact":"BILL_PAID","count":1}', '{"flux":15}', 'payments.bill', 80, 'ENG', 'ACTIVE'),
    ('M-BILL-3', 'RECURRING', 'ROLLING', '{"fact":"BILL_PAID","consecutiveMonths":3}', '{"flux":45,"couponDefId":"BILL_CASHBACK_10","preChargePct":50}', 'payments.bill', 90, 'ENG', 'ACTIVE'),
    ('M-SAV-1', 'ONE_TIME', 'ONCE', '{"fact":"SAVINGS_GOAL_SET","count":1}', '{"flux":10}', 'savings.goal', 100, 'ENG', 'ACTIVE'),
    ('M-SAV-2', 'RECURRING', 'MONTHLY', '{"fact":"SAVINGS_TOPUP","count":4}', '{"flux":30}', 'savings.topup', 110, 'ENG', 'ACTIVE'),
    ('M-REF-1', 'RECURRING', 'MONTHLY', '{"fact":"REFERRAL_RELEASED_REFERRER","count":1}', '{"flux":250}', 'referral', 120, 'ACQ', 'ACTIVE'),
    ('M-REF-1-REFEREE', 'ONE_TIME', 'ONCE', '{"fact":"REFERRAL_RELEASED_REFEREE","count":1,"hidden":true}', '{"flux":100}', 'referral', 125, 'ACQ', 'ACTIVE'),
    ('M-REPAY-1', 'RECURRING', 'MONTHLY', '{"fact":"INSTALMENT_PAID_ON_TIME","count":1,"hidden":true}', '{"flux":20}', 'loans', 130, 'ENG', 'ACTIVE'),
    ('M-TENURE', 'RECURRING', 'YEARLY', '{"fact":"TENURE_ANNIVERSARY","count":1,"hidden":true}', '{"flux":100}', NULL, 140, 'ENG', 'ACTIVE'),
    -- P2: defined, switched off until their producers exist (spec phase P2).
    ('M-ACC-2', 'ONE_TIME', 'ONCE', '{"fact":"SALARY_INFLOW","count":1}', '{"flux":25}', NULL, 200, 'ENG', 'INACTIVE'),
    ('M-BILL-2', 'ONE_TIME', 'ONCE', '{"fact":"UTILITY_MANDATE_ACTIVE","count":1}', '{"couponDefId":"BILL_CASHBACK_10","preChargePct":35}', 'payments.bill', 210, 'ENG', 'INACTIVE'),
    ('M-WBK-1', 'RECURRING', 'ROLLING', '{"fact":"REACTIVATED","count":1}', '{"couponDefId":"BILL_CASHBACK_5","preChargePct":50}', NULL, 220, 'ENG', 'INACTIVE'),
    ('M-CHECKIN', 'RECURRING', 'MONTHLY', '{"fact":"APP_DAY","count":4}', '{"flux":5}', NULL, 230, 'ENG', 'INACTIVE')
) AS d(id, kind, cadence, rule, reward, handoff, sort, budget, status)
CROSS JOIN (VALUES ('CZ'), ('BG')) AS m(market);

-- Biller registry: PLACEHOLDER Energo-Pro collection accounts (spec: replaceable assumptions;
-- step 3 of experience v2 replaces them with the Energo-Pro simulator's real registry).
INSERT INTO biller (biller_id, market, name, account_number, placeholder, active) VALUES
    ('EP-CZ', 'CZ', 'Energo-Pro (placeholder)', '2001234567/2010', TRUE, TRUE),
    ('EP-BG', 'BG', 'Energo-Pro Bulgaria (placeholder)', 'BG18RZBB91550123456789', TRUE, TRUE);

INSERT INTO lesson (lesson_id, mission_id, pass_pct, content) VALUES ('deposit-guarantee', 'M-ONB-3', 80, '{"questions":[
 {"text":{"en":"Up to what amount are deposits guaranteed per person and per bank in the EU?","cs":"Do jaké výše jsou v EU pojištěny vklady jednoho člověka u jedné banky?","bg":"До каква сума са гарантирани депозитите на един човек в една банка в ЕС?"},
  "options":{"en":["€100,000","€20,000","€1,000,000","There is no limit"],"cs":["100 000 €","20 000 €","1 000 000 €","Bez omezení"],"bg":["100 000 €","20 000 €","1 000 000 €","Няма ограничение"]},"answer":0},
 {"text":{"en":"Who pays out guaranteed deposits if a bank fails?","cs":"Kdo vyplatí pojištěné vklady, když banka zkrachuje?","bg":"Кой изплаща гарантираните депозити, ако банката фалира?"},
  "options":{"en":["The deposit guarantee scheme","The central bank, as a loan","Nobody","The government, after a court case"],"cs":["Systém pojištění vkladů","Centrální banka formou půjčky","Nikdo","Stát až po soudním řízení"],"bg":["Фондът за гарантиране на влоговете","Централната банка като заем","Никой","Държавата след съдебно дело"]},"answer":0},
 {"text":{"en":"Within how many working days must guaranteed deposits be paid out?","cs":"Do kolika pracovních dnů musí být pojištěné vklady vyplaceny?","bg":"В рамките на колко работни дни трябва да бъдат изплатени гарантираните депозити?"},
  "options":{"en":["7","30","90","365"],"cs":["7","30","90","365"],"bg":["7","30","90","365"]},"answer":0},
 {"text":{"en":"Are savings accounts and term deposits covered?","cs":"Vztahuje se pojištění na spořicí účty a termínované vklady?","bg":"Покрити ли са спестовните сметки и срочните депозити?"},
  "options":{"en":["Yes","No","Only above €100,000","Only deposits in euros"],"cs":["Ano","Ne","Jen nad 100 000 €","Jen vklady v eurech"],"bg":["Да","Не","Само над 100 000 €","Само депозити в евро"]},"answer":0},
 {"text":{"en":"Do you have to apply to be covered?","cs":"Musíte o pojištění vkladů žádat?","bg":"Трябва ли да кандидатствате, за да сте покрити?"},
  "options":{"en":["No, cover is automatic","Yes, with a form","Yes, it is paid insurance","Only businesses must apply"],"cs":["Ne, platí automaticky","Ano, formulářem","Ano, je to placené pojištění","Žádat musí jen firmy"],"bg":["Не, покритието е автоматично","Да, с формуляр","Да, това е платена застраховка","Само фирмите кандидатстват"]},"answer":0}
]}');
