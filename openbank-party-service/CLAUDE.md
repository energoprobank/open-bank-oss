# openbank-party-service — service notes

## Party events leave through the outbox, and only through the outbox (#4007)

**Decision: wire the apparatus, do not delete it.**

`party_outbox` shipped complete — Flyway migration, `PartyOutboxDispatcher`, backlog gauge,
`openbank.outbox.dispatch-enabled: true`, an atomic `claimProcessable` — and nothing ever wrote a
row to it. Every lifecycle event (`PARTY_CREATED`, `PARTY_UPDATED`, `KYC_STATUS_CHANGED`,
`PARTY_ERASED`, `PARTY_MERGED`) went out through `KafkaPartyEventPublisher`, a bare
`@Channel("party-events-out")` emitter called *after* the repository transaction had already
committed. That is a dual write: if the emit fails after the row commits the event is lost with no
record, and if it succeeds and the transaction then rolls back a consumer has acted on something
that did not happen. `PARTY_MERGED` (ADR-0179) is the expensive case — a consumer that misses it
keeps writing to a retired identity.

Deleting the apparatus was the other honest option and was rejected: the events are consumed by
account-, aml-, kyc-, onboarding- and copilot-service, several of them on the money path, so the
atomicity is wanted here.

What the wiring looks like now:

- `PartyEvents` (domain) builds the flat JSON envelope. It is the WIRE CONTRACT — the same field
  names, order and flat shape the direct emitter produced — pinned by
  `PartyEventEnvelopeContractTest` and by the provider-side pacts.
- `PartyRepository.save/anonymize` have event-carrying overloads, and `modify` (the only full-state
  update) always carries one. The impl chains
  `outboxRepository.persistInTransaction(...)` inside the SAME `Panache.withTransaction` block as
  the state change, so the row and the event commit together or neither does.
- The direct emitter and its `party-events-out` channel are **removed**. `party-outbox-out`
  publishes to the same topic (`openbank.party.events`), so no consumer sees a difference beyond
  the additive `OutboxKafkaHeaders` and a partition key. Two publishers on one topic would race,
  and only one of them can be atomic.

Three things to keep true when touching this:

- **`openbank.outbox.dispatch-enabled` must stay `true`** in `application.yaml`. It defaults to
  `false`, and when it is false events never dispatch — no error, `attempt_count` stays 0.
- **`persist()` is correct here, `merge()` is not needed.** `PartyEntity` extends `PanacheEntity`,
  so the `@Id` is generated and `save` is a genuine INSERT; `party_id` is a business key, not the
  id. The fleet's application-assigned-id trap (persist is INSERT-only, every lifecycle transition
  dies on the PK) does not apply — but it would the moment someone changes that base class.
- **Only a real-DB IT can prove any of this.** A unit test that mocks the repository cannot tell
  which publisher a use case called, which is exactly why the defect survived a green suite for the
  life of the service. `PartyOutboxWriteIT` drives the REST endpoints (a `Panache.withTransaction`
  repo called from a bare `@QuarkusTest` thread throws "No current Vertx context found" — only an
  HTTP request carries a context) and asserts the row with a plain JDBC read.

## `V8__party_aml_status.sql` cites the wrong ADR, permanently (#5785)

The header comment of `src/main/resources/db/migration/V8__party_aml_status.sql` reads

```
-- AML screening outcome — second key of the KYC+AML activation gate (ADR-0073).
```

**ADR-0073 is "Hardware-backed credential storage for the customer app"** — mobile
Keystore/Keychain secrets, nothing to do with the activation gate. The decision that actually
covers `parties.aml_status` as the second key of the two-key activation gate is
**ADR-0267 — Event-driven onboarding account lifecycle** (§2).

The citation cannot be corrected. Flyway checksums the **whole migration file, comments
included**, so editing an applied migration fails startup with `checksum mismatch`. This note is
the correction; do not "fix" the SQL. If you are reading `V8` for the AML status column, read
ADR-0267, not ADR-0073.

## Access-control change log (STRIDE: information disclosure)

party-service has no full threat model yet (baselined under #9167), so access decisions on the
PII surface are recorded here.

- **2026-10-02 — `ROLE_RISK` may read the single-party detail.** `GET /api/v1/parties/{id}`
  (`PartyResource.getParty`) admits `ROLE_RISK` so a risk officer reviewing large exposures can see
  the obligor's name (owner decision). Search, list and every write stay closed to it.
  - *Disclosure:* one party's detail, by id; the id comes from an instrument the officer already
    sees, and the response is what `ROLE_VIEWER` already receives.
  - *Narrowing:* admin-ui uses a separate `parties:view-detail` permission, so the role gets no
    directory link. `party-rbac-alignment.guard.test.ts` pins that permission to `getParty`'s
    `@RolesAllowed`, and `PartyApiIT` proves 200 on detail and 403 on search and list.
  - *Residual risk:* no per-party scoping — an officer who learns another id can read that party;
    accepted because the role is internal staff and VIEWER has the same reach.
- **2026-11-05 — machine writes for self-service account opening (experience v2 step 8).**
  `PUT /api/v1/parties/{id}/onboarding-details` (date of birth, national number, phone, address)
  and `PUT /api/v1/parties/{id}/tax-self-certification` admit `ROLE_API` so onboarding-service can
  record what the applicant typed after customer-edge has taken the party from their token.
  - *Tampering:* onboarding-details is refused (409) once the party leaves `PENDING_KYC`, and the
    national number is set once — a later change goes through the operator PATCH. The number is
    validated for the party's `market` (rodné číslo / ЕГН) and must encode the date of birth.
  - *Elevation:* no customer reaches these routes directly; customer-edge never forwards a path
    party id, only the token's.
  - *Residual risk:* any `ROLE_API` principal could call them for any PENDING_KYC party; the OPA
    actions `party.onboarding-details` / `party.tax-self-certification` are where a per-principal
    grant narrows that once authz enforcement is on.
- **Known gap (not changed here, plan step 7):** `PartyRepositoryImpl.applyFrom` does not copy
  `legalName`, `nationality` (and until step 8 `dateOfBirth`), so the operator PATCH accepts those
  material fields, emits PARTY_UPDATED and then does not persist them.

## National numbers at rest (experience v2 step 9)

- A natural person's national number (rodné číslo / ЕГН) is stored ENCRYPTED in `national_id_ct`
  (AES-256-GCM, AAD = party id, `v<key>:` prefix; `AesGcmNationalIdCipher`, `@Startup`), and `tax_id` is
  NULL for that row. A company's tax id stays in `tax_id`. The repository decrypts on read, so the domain
  still sees `Party.taxId` in clear; the REST detail shows it MASKED (`910813/****`, `nationalIdLast4`).
  The clear value is `GET /{id}/identity/national-id` (onboarding-service + KYC staff, audited).
- Config: `OPENBANK_PARTY_NATIONAL_ID_KEY` (base64, 32 bytes) and `…_ENCRYPTION_REQUIRED=true` wherever
  real people are onboarded (a missing key then fails the boot). Without a key nothing is encrypted (dev).
- `NationalIdEncryptionBackfill` (every 10 min, suspend) encrypts rows still in clear and derives a missing
  date of birth from the number. Idempotent.
- The blind index (`rc_blind_index`) covers BOTH markets now (BG domain-separated as `BG:<egn>`).
  `openbank.identity.dedup-required=true` refuses onboarding details without a pepper (409 DEDUP_UNAVAILABLE).
- Onboarding details are as strict as the app (future dates, 9-digit ≥ 1954, +20/+70 before 2004) and
  adults only (409 UNDERAGE); a number another party holds is 409 NATIONAL_ID_TAKEN.
- `applyFrom` now persists `legalName` and `nationality` (the operator PATCH used to drop them silently),
  and the detail answers `dateOfBirth`, `nationality`, `riskRating` (from the KYC decision event),
  `identityVerifiedAt`/`identityMethod`.
- V29 rollback: see the migration header (a value already moved to `national_id_ct` must be decrypted back first).
