# Provider Simulator API

NON-PRODUCTION stand-in for the third-party providers the bank needs for onboarding and payments
(experience v2 step 8). One process, one path per provider:

| Provider | Path | Bank-side port |
|---|---|---|
| Energo-Pro customer system | `/api/v1/energo-pro/customers/{number}` | onboarding `EnergoProCustomerPort` |
| E-mail and SMS gateway | `/api/v1/otp/messages`, sandbox `/api/v1/otp/outbox` | onboarding `ContactCodeDeliveryPort` |
| Czech Bank iD | `/api/v1/bankid/sessions` | onboarding `BankIdPort` |
| Identity verification + liveness | `/api/v1/idv/verifications` | onboarding `IdentityVerificationPort` |
| Step 9 onboarding/KYC faces | see below | onboarding |

Energo-Pro (the distributor) and Energo-Pro Bank are independent companies: the simulated Energo-Pro
customer base is mostly people who are not bank customers.

Deterministic behaviour: a verification's checks pass `step-seconds` apart; an applicant whose family
name starts with `Failidv` fails the last check (the unhappy path for tests and demos).

## Onboarding / KYC faces (experience v2 step 9)

Real-looking interfaces backed by one **person registry** (`ScenarioBook`). By default the person *is*
the applicant: the document, Bank iD and the registers agree with what was typed, so a new sign-up
passes. Every comparison is the bank's own code — the simulator only decides what a provider "finds".

| Face | Path | Auth |
|---|---|---|
| Bank iD OIDC (code + PKCE S256, confidential client) | `/bankid/.well-known/openid-configuration`, `/bankid/auth` (HTML), `/bankid/auth/confirm`, `/bankid/token`, `/bankid/userinfo` | anonymous (browser); `/token` checks the client secret, `/userinfo` Bank iD's own bearer token |
| Bank iD side channel: who is at the keyboard for a `state` | `POST /api/v1/bankid/oidc/expected-logins` | ROLE_API |
| IDV document (OCR + MRZ), chip (DG1 + passive authentication), selfie (liveness + face match) | `/api/v1/idv/documents`, `/api/v1/idv/documents/{id}/chip`, `/api/v1/idv/selfies` | M2M |
| CZ invalid documents, CZ insolvency | `/api/v1/mvcr/neplatne-doklady?doklad=`, `/api/v1/isir/osoby?rc=` | M2M |
| BG RegiX | `POST /api/v1/regix/operations/{GRAO.PersonDataSearch, MVR.GetPersonalIdentityV2, BG.InsolvencyCheck}` | M2M |
| Other bank (first payment) | `POST /api/v1/other-bank/payments` (anonymous: the customer's own bank), `GET /api/v1/other-bank/incoming?creditor_account=&variable_symbol=` (M2M) | |
| Scenario control | `GET /api/v1/sim/scenarios`, `PUT`/`DELETE /api/v1/sim/scenarios/{nationalId}` | ROLE_API, ROLE_ADMIN |

Deterministic findings: the document number is derived from the national id (so an IDV scan and a
register query agree with no shared state); expiry is today + 6 years; nationality is the issuing
country; sex comes from the RČ month (+50/+70 = female) or the EGN's ninth digit (odd = female).
GRAO answers only for an EGN some provider has already met (IDV document or expected login).

**Scenarios**, per national id (RČ/EGN digits), from `PROVIDER_SIMULATOR_IDENTITY_SCENARIOS_FILE`
(`[{"national_id":"9108131234","scenarios":["FACE_GREY"],"note":"..."}]`, re-read when it changes)
or a runtime `PUT {"scenarios":[...]}` that overrides the file until `DELETE`:

| Scenario | Effect |
|---|---|
| `EXPIRED_DOCUMENT` | document expiry two months ago; MVR `INVALID` |
| `INVALID_DOCUMENT` | MVČR `evidovan: true, duvod: ODCIZEN`; MVR `INVALID` |
| `CHIP_FORGED` | passive authentication `INVALID` |
| `LIVENESS_FAIL` | liveness 0.12, `passed: false` |
| `FACE_MISMATCH` / `FACE_GREY` | face match 0.42 / 0.71 (default 0.93) |
| `OCR_NAME_DIFFERS` | the document carries another family name |
| `BANKID_OTHER_PERSON` | `/userinfo` returns another person's claims |
| `INSOLVENT` | an ISIR record (`KONKURS`) and a BG insolvency proceeding |
| `FIRST_PAYMENT_OTHER_NAME` | the incoming payment's `payer_name` is somebody else's |
| `UNDERAGE_DOCUMENT` | the document's date of birth makes the holder sixteen |
| `GRAO_MISMATCH` | GRAO returns another name and date of birth |

The legacy family-name marker (`Failidv…`) still fails the old `/verifications` and `/sessions`
flows, which stay as they were (payment liveness uses them).

Config: `PROVIDER_SIMULATOR_PUBLIC_URL` (issuer base, default `http://localhost:8163`),
`PROVIDER_SIMULATOR_BANKID_CLIENTS` (`client_id:secret[,…]`; unset = any client with a non-blank
secret). Codes live 60 s and are single use (a reused code also revokes its token); access tokens 300 s;
the id_token is HS256 over the client secret.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running
service for the exact paths of its image.
