# openbank-onboarding-service — agent notes

Onboarding read-model for the admin-UI cockpit (**ADR-0068**; the four-eyes approval queue is not hosted here yet), port **8130**. Hexagonal
per ADR-0002. Not a money-path service — single approval sufficient.

## What it does

Maintains a **denormalized onboarding funnel read-model** over the party/KYC/SCA state machine.
Events from `party-service` drive state transitions; the admin-UI "Onboarding Cockpit" queries
this service to display where each applicant is in the funnel (`funnel_stage`).

Key concepts:
- One `onboarding_records` row per party. Created on `PARTY_CREATED`; updated on KYC/SCA events.
- `funnel_stage` drives the admin-UI board column (PENDING_KYC, KYC_IN_PROGRESS, PENDING_SCA, …).
- No four-eyes gate lives in this service: the override control is in the owning service
  (ADR-0068 §5) and is only observed here.

## Database — applied migrations + rollback notes

**Never edit an applied migration file** — Flyway checksum will mismatch → crashloop.
Rollback notes for applied migrations are kept here, not in the SQL file.

| Version | File | Creates | Rollback |
|---------|------|---------|---------|
| V1 | `V1__create_onboarding_records.sql` | `onboarding_records` table + indexes | `DROP INDEX idx_onboarding_updated_at; DROP INDEX idx_onboarding_funnel_stage; DROP TABLE onboarding_records;` |
| V2 | `V2__onboarding_records_seq.sql` | `onboarding_records_seq` sequence (Hibernate ORM 6 pooled allocationSize=50) | `DROP SEQUENCE IF EXISTS onboarding_records_seq;` |
| V5 | `V5__onboarding_applications.sql` | step 8 write side: `contact_verifications`, `onboarding_applications`, `identity_verifications`, `liveness_policies`, `liveness_sessions` | `DROP TABLE liveness_sessions, liveness_policies, identity_verifications, onboarding_applications, contact_verifications;` |

V2 existed because V1 used `BIGSERIAL` (implicit sequence `onboarding_records_id_seq`) but
Hibernate ORM 6 / Panache looks for `onboarding_records_seq` by naming convention — inserts
failed with "relation does not exist". V2 adds the correctly-named sequence.

## Layout

- `domain/model/` — `OnboardingRecord`, `BusinessOnboardingRecord`, `OnboardingEvent`, `ProjectionResult`
- `application/` — `OnboardingProjectionService`, `BusinessOnboardingProjectionService`; ports `OnboardingRepository`, `OnboardingUseCase` (in `OnboardingQueries.kt`)
- `infrastructure/persistence/` — `OnboardingRepositoryImpl` (reactive Panache)
- `infrastructure/kafka/` — `OnboardingEventConsumer` (party/KYC/SCA events), `BusinessOnboardingEventConsumer`
- `infrastructure/rest/` — `OnboardingResource`, `BusinessOnboardingResource`

## Build / test

```
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :openbank-onboarding-service:test --offline
```

## Config gotchas

- Flyway uses JDBC datasource; Panache uses the reactive datasource — both must be configured.
- The OIDC config uses env-overridable placeholders; tests use `%test` profile with `oidc.enabled: false`.

## Self-service account opening — write side (experience v2 step 8)

Next to the read model, this service now holds the application's own state and the third-party
checks (`application/usecase/OnboardingApplicationService`, `LivenessService`; JDBC store over the
V5 tables, called from `@Blocking` endpoints — no reactive Panache, so no Vert.x context needed).
Facts that belong to the person (date of birth, national number, address, tax residency) are written
through to party-service; nothing personal is duplicated here beyond the contact being verified.

- **Providers** (one adapter port each, one REST-client config key each — `energo-pro`, `otp-gateway`,
  `bank-id`, `idv`): locally all faces of `openbank-provider-simulator`. Replacing one with the real
  vendor is a URL/adapter change.
- **One-time codes** are stored only as HMACs (`openbank.onboarding.otp.hmac-key`; absent = random
  per process). `openbank.onboarding.otp.reveal-codes` echoes them to the caller — SANDBOX ONLY.
- **Energo-Pro prefill** is released only against a CONFIRMED contact that is on the Energo-Pro
  record; an unknown number and a mismatch answer the same 404 (no enumeration oracle), 3 tries.
  Energo-Pro the distributor is an independent company: most bank customers are not its customers.
- **Face check** (liveness for payments above the customer's limit) lives here because this service
  owns the identity-verification port; customer-edge enforces it on payments and spends a PAYMENT
  session exactly once (`/sessions/{id}/consume`).
