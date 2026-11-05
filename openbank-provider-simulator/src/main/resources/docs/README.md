# Provider Simulator API

NON-PRODUCTION stand-in for the third-party providers the bank needs for onboarding and payments
(experience v2 step 8). One process, one path per provider:

| Provider | Path | Bank-side port |
|---|---|---|
| Energo-Pro customer system | `/api/v1/energo-pro/customers/{number}` | onboarding `EnergoProCustomerPort` |
| E-mail and SMS gateway | `/api/v1/otp/messages`, sandbox `/api/v1/otp/outbox` | onboarding `ContactCodeDeliveryPort` |
| Czech Bank iD | `/api/v1/bankid/sessions` | onboarding `BankIdPort` |
| Identity verification + liveness | `/api/v1/idv/verifications` | onboarding `IdentityVerificationPort` |

Energo-Pro (the distributor) and Energo-Pro Bank are independent companies: the simulated Energo-Pro
customer base is mostly people who are not bank customers.

Deterministic behaviour: a verification's checks pass `step-seconds` apart; an applicant whose family
name starts with `Failidv` fails the last check (the unhappy path for tests and demos).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running
service for the exact paths of its image.
