# openbank-everyday-service — agent notes

Everyday money for the mobile app (experience v2 step 3), port **8164**. Hexagonal (ADR-0002); blocking
JDBC (Agroal) + Flyway, no Hibernate — every caller is a `@Blocking` endpoint, the `@Blocking` Kafka
handler or a non-suspend `@Scheduled` job, none of which needs a Vert.x context.

## What it owns
- **Biller registry** (`billers`). Energo-Pro's collection accounts are Energo-Pro's own
  (`provider-simulator` `EnergoProBilling.COLLECTION`, and rewards-service's `biller` rows must agree:
  rewards recognises a BILL_PAID from the same domestic-payment event by the creditor account).
- **Energo-Pro electricity link** — only the customer's own Energo-Pro number (record name = party
  legal name, accents/case/order aside) with their data-sharing consent. "No such number" and "not
  yours" are one 404 (`NO_MATCH`): no oracle for whose number exists.
- **Bill payments** — seen on `openbank.domestic.payment.events` (`DOMESTIC_PAYMENT_CREATED`, creditor =
  a biller, with a variable symbol). Recorded, then Energo-Pro is notified; a failed notice is retried
  by `BillNoticeRetry` (every 2 min). The handler never rethrows; the DLQ is a safety net.
- **Bill reminders** (on/off, days before due).
- **Direct-debit consents** (CZ inkaso, SIPO, SEPA Core) held at the debtor's bank.
- **Subscriptions** DETECTED from the customer's card payments, direct debits and standing orders each
  time (`SubscriptionDetector`); only the customer's choices (cancel requested, reminder) are stored.
- **Insights** — spending by category (own transfers excluded) and a month-end cash-flow forecast; the
  edge passes today's balance (this service has no balance read).
- **Payments export** (CSV / PDF with embedded DejaVu Sans) behind a 30-minute download token.
- **Recall** of an outgoing transfer (a card payment is disputed instead), **card top-up** via the
  acquirer's checkout (credited once, idempotency key `top-up:<id>`), **payment protection** settings
  (customer-edge enforces street mode), the **energy cost trend** from the linked Energo-Pro bills.
- Reads accounts and transactions with its own identity, `service-account-openbank-everyday`
  (`account.list`, `transaction.list`, `transaction.create` for top-up credits only).

## Database — applied migrations + rollback notes
| Version | Creates | Rollback |
|---|---|---|
| V1 | `billers` (seeded), `electricity_links`, `bill_payments`, `bill_reminders` | `DROP TABLE bill_reminders, bill_payments, electricity_links, billers;` |
| V2 | `direct_debit_mandates`, `subscription_choices`, `payment_exports`, `recall_cases`, `top_ups`, `payment_protection` | `DROP TABLE payment_protection, top_ups, recall_cases, payment_exports, subscription_choices, direct_debit_mandates;` |

## Known gaps (follow-ups)
- No gitops manifests yet (Deployment, KafkaTopic/KafkaUser for the consumer and its DLQ, OPA bundle).
- BG bill payments are SEPA, not domestic-payment events: not seen yet.
- A payment is treated as a bill payment at CREATED; a later rejection does not undo the record.
