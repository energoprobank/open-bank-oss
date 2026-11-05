# openbank-rewards-service — service notes

The FLUX engine (Energo-Pro rewards, experience v2 step 2). The design lives in the Claude Doc
"Energo-Pro Rewards — Implementation Spec"; this file is the operational tail.

## What FLUX must never become

FLUX is a closed-loop engagement unit with a notional value per market only so Finance can book a
liability. It is never bought, never paid out as cash for FLUX, never transferred between
parties. Benefits pay money only through a coupon's fulfilment (bill cashback, instalment relief),
funded from the FLUX provision GL account. Adding a cash-out or a transfer is a change of
regulatory class, not a feature.

## The traps in this code

- **Plain JDBC, one transaction per command.** Every customer command locks the party's
  `party_membership` row first (`FOR UPDATE`), so a party's commands serialise. `JdbcRewardsStore.tx`
  joins an outer transaction on the same thread; never open a second connection while holding
  party locks.
- **Natural keys are the idempotency.** `ledger_entry (party, type, source, source_ref)`,
  `behaviour_fact.fact_id`, `coupon (party, source_ref)`. The REST idempotency record is the fast
  path; the unique index is the guard against races. A duplicate CHARGE throws `DUPLICATE_REQUEST`
  so the whole transaction (including lot debits) rolls back.
- **Liability = SUM(flux) + SUM(coupon_flux)** over the ledger. Every ledger effect, including
  pre-charge and promotion bonus (balance delta 0, coupon delta +n), must be an entry, or the GL
  posting drifts.
- **Credit guardrail G-CR-01 is code, not config**: `CreditGuardrail.DENIED`. Mission definitions
  naming a denied fact are refused at approval; the lending consumer only reads on-time repayments.
- **Credit coupons (LOANS) are never in promotions and never auto-activate** (G-CR-02).
- **The benefit saga is the `fulfilment` table**, not Temporal: USING + PENDING row, command,
  then USED + BURN or compensation. Transient failures retry every minute, up to 6 attempts.
  Cash credits use `rc:<fulfilmentId>` because transaction-service keeps a FAILED credit FAILED
  for its sourceRef.
- **Projections are backfilled on first contact** (account-service, card-issuance) because Kafka
  keeps 7 days. The rewards client must stay a named reader in both services.
- **Consumers read `ce-type` first, payload `eventType` second**; producers are inconsistent.
