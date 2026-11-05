# Threat model: openbank-rewards-service (FLUX)

Status: draft, experience v2 step 2 (2026-11-05). Scope: the FLUX engine and the commands it issues
into money-path services. Method: STRIDE over the data flows below.

## Data flows

1. customer-edge → rewards customer API (edge principal only; partyId injected by the edge).
2. Operators → staff API (humans only; maker-checker on every write).
3. Kafka banking topics → ingest consumers (accounts, transactions, cards, card processing,
   domestic payments, referral, lending, incentive).
4. rewards → interest-service (rate adjustment), lending (rate adjustment), transaction-service
   (reward cash credit from the FLUX provision), ledger-service (daily provision posting),
   incentive-service (code redemption), account-service and card-issuance (read-only backfill).

## Threats and controls

| STRIDE | Threat | Control |
|---|---|---|
| S | A caller other than the edge reads or writes a customer's FLUX | OPA `edge-rewards-customer` admits only `machine_named("service-account-openbank-edge")`; partyId comes from the edge's verified customer token |
| S | A machine account acts as an operator | `rewards.staff.*` vetoed for every machine at the allow head (`prohibited`) |
| T | Replayed or duplicated events mint FLUX twice | Natural-key unique indexes on facts and ledger entries; idempotency records on writes |
| T | One operator grants FLUX to a friend | Every staff write is a change request; the maker cannot approve (FOUR_EYES) |
| R | A grant or migration cannot be traced | change_request keeps maker, checker, reason, payload and result; ledger entries carry source and source_ref |
| I | Ledger history leaks across parties | Customer reads are party-scoped by the edge; staff reads need VIEWER+ |
| D | Rewards outage blocks banking | Banking never calls rewards; consumers nack to DLQ; the app hides rewards on 503 |
| E | Rewards client escalates in money services | Each target's rego admits the rewards client for one action only (interest.adjust*, lending rate adjustment, transaction.rewardCredit, ledger.rewardProvision, incentive redemption); per-call cap on cash credits (2 000 CZK / 100 EUR) |
| E | Farming card payments or cycling own funds | Card Week needs ≥ 3 distinct merchants and a minimum amount; own-account transfers earn nothing; self-referral ignored |
| E | Rewards nudges borrowing | G-CR-01 deny-list in code; credit coupons outside promotions; lending enforces arrears/forbearance blocks (CREDIT_COUPON_BLOCKED) |

## Residual risks

- Abuse velocity rules (device, onboarding farming) and the abuse case queue are not built yet.
- The kill switch stops earn/charge/use programme-wide, not per instrument.
- FINREP must classify the FLUX GL accounts before production postings.
