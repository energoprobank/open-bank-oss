---
date: 2026-11-05
decision-status: proposed
delivery-status: planned
authors: [Tomas Kuzin]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ledger, transactions, authz]
summary: "FLUX rewards are booked as a liability: daily Dr 5300/5301 expense / Cr 2400/2401 provision via a ledger template, cash redemption debits the provision. Corrects ADR-0310's GL 5200, which is MM Interest Expense."
---

# ADR-0344 — Reward accounting: FLUX provision and daily liability posting

## Context

The FLUX rewards programme (Energo-Pro, experience v2; service `openbank-rewards-service`) gives
customers FLUX that are worth money (CZ: 1 FLUX = 0.25 CZK, BG: 1 FLUX = 0.01 EUR) and later pays
some of them out as real cash to the customer's own current account: bill cashback, instalment
relief, referral cash. The bank therefore carries an obligation from the moment FLUX are earned
until they are redeemed or expire.

What the repository had, measured on `experience/v2` on 2026-11-05:

- The only reward that moves money is account-service's welcome bonus (ADR-0267): a
  `POST /api/v1/transactions` `CREDIT` whose journal debits `1100/1101 Customer Cash Clearing` and
  credits `2100/2101 Customer Deposit Control`. It recognises no expense and is backed by no
  funding leg (ADR-0310's own description).
- The ledger chart (V1–V32) has no reward, provision or marketing account.
- **ADR-0310 D2 proposed `5200 Referral and marketing reward expense` as "unused in V1–V26".** It is
  not free: V29 seeded `5200 MM Interest Expense` (treasury, ADR-0315), V32 maps it for
  `INTERBANK_BORROWING` and `CNB_LOMBARD_BORROWING` / `INTEREST_EXPENSE`, and finrep-service's
  F02.00 mapper classifies 5200/5201 as interest expense. Posting a reward to 5200 would have
  reported marketing cost as money-market interest.
- `gl_accounts.type` is CHECK-closed to ASSET/LIABILITY/EQUITY/INCOME/EXPENSE, codes are globally
  unique, and every account is single-currency (the ledger refuses a line in another currency).

## Decision

### D1 — Chart of accounts (ledger-service migration V33)

| code | name | type | ccy |
|---|---|---|---|
| 2400 | FLUX Reward Provision | LIABILITY | CZK |
| 2401 | FLUX Reward Provision (EUR) | LIABILITY | EUR |
| 5300 | FLUX Reward Expense | EXPENSE | CZK |
| 5301 | FLUX Reward Expense (EUR) | EXPENSE | EUR |
| 5310 | Credit Reward Interest Forgone | EXPENSE | CZK |
| 5311 | Credit Reward Interest Forgone (EUR) | EXPENSE | EUR |

Fixed ids `a0000000-0000-0000-0000-00000000<code>` (the V14/V20/V26/V29 convention). No migration,
Kotlin constant or FINREP mapping used these codes before. Interest forgone on credit-rate
coupons is an EXPENSE, not a debit-balance INCOME account: the chart has no contra account and
every reader treats INCOME as credit-normal, so a contra row would net silently into interest
income. No `accounting_class_gl_map` rows: its role CHECK has no provision/reward role and neither
poster below needs one.

### D2 — Daily liability posting: a ledger-owned template

`POST /api/v1/ledger/reward-provisions` `{postingDate, market: CZ|BG, currency, deltaAmount
(signed), sourceRef}`. The engine reports the day's change of the outstanding FLUX liability in
money; the ledger chooses both accounts:

    delta > 0 (earned)                  Dr 5300/5301 FLUX Reward Expense   Cr 2400/2401 FLUX Reward Provision
    delta < 0 (breakage / expiry)       Dr 2400/2401 FLUX Reward Provision Cr 5300/5301 FLUX Reward Expense

Market fixes the currency (CZ = CZK, BG = EUR). Idempotent on `sourceRef` (ledger key
`flux-provision:<sourceRef>`); a replay with different content is a 409, not a silent replay.
It posts through the ordinary journal path, so every lock and account check applies.

### D3 — Redemption debits the provision (transaction-service)

A reward cash credit (`POST /api/v1/reward-credits` on transaction-service) books a real `CREDIT`
transaction whose journal is **Dr 2400/2401 FLUX Reward Provision / Cr 2100/2101 Customer Deposit
Control (sub = customer account)**, not the welcome bonus's cash-clearing leg. So the provision
holds exactly earned − redeemed − released, and the expense is recognised once (D2), never again at
payout.

### D4 — Authorization

Both actions are machine-only and granted by identity to `service-account-openbank-rewards` alone:
`ledger.rewardProvision` in `ledger_rest_ext.rego`, `transaction.rewardCredit` in
`transaction_rest_ext.rego`. Neither is a four-eyes verb (`post`/`credit` are), because both are
automated calls. The rewards principal holds no `ledger.create` and no `transaction.create`.

## Alternatives considered

- **Keep ADR-0310's 5200.** Rejected: it is MM Interest Expense (V29) and mapped as such in FINREP.
- **Let the engine post general journals (`ledger.create`).** Rejected: a rewards credential could
  then post any balanced journal to any account. The template limits it to one account pair.
- **Recognise expense at redemption only (welcome-bonus shape, Dr cash clearing).** Rejected: no
  liability is shown for earned-but-unredeemed FLUX, and the cash-clearing leg is unfunded.
- **Contra-income for interest forgone.** Rejected for the reason in D1.

## Consequences

**Positive**
- The obligation is on the balance sheet daily; the P&L shows programme cost in its own lines.
- Redemption and expiry close against the provision, so 2400/2401 can be tied out against the
  engine's outstanding-FLUX valuation.

**Negative**
- FINREP is fail-closed: until finrep-service classifies 2400/2401/5300/5301/5310/5311
  (`FinrepCoverage`, F01.02, F02.00), the first posting blocks those renders with
  `UNCLASSIFIED_GL_ACCOUNT`. Follow-up before the programme posts in production.
- The provision is only as right as the engine's delta; a tie-out job is a follow-up.

**Neutral**
- ADR-0310 D2's owner (billing-service) and account are superseded for FLUX by D2/D3; its referral
  qualification decisions are untouched.

## Compliance impact

- PCI DSS: not applicable — no cardholder data is involved.
- DORA:    not applicable — no change to ICT risk management, incident or third-party processes.
- GDPR:    not applicable — the provision posting carries no personal data; the cash credit carries the
  account id the transaction already carries.
- PSD2:    not applicable — bank-initiated credits to the customer's own account; no payment initiation.
- CNB:     the reward liability and expense become separate GL lines; FINREP classification is the
  follow-up named under Consequences.

## References

- ADR-0310 (growth lifecycle; D2 proposed GL 5200), ADR-0267 (welcome bonus), ADR-0315 (treasury
  chart, 5200), ADR-0341 (accounting-class map).
- `openbank-ledger-service/src/main/resources/db/migration/V33__flux_reward_accounts.sql`
- `docs/threat-models/openbank-ledger-service.md` §9, `docs/threat-models/openbank-transaction-service.md` §4i
