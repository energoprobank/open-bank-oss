<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->

# Energo-Pro Bank branding

[`brand.yaml`](brand.yaml) is the source of truth for the bank's name, colours, logos and
identity. No surface reads it at runtime, so each one keeps a copy. **Change `brand.yaml` first,
then the copies below.**

## Assets

| Path | What |
|---|---|
| `source/` | Logos as supplied by Energo-Pro (do not edit) |
| `generated/` | Variants derived by `tools/gen-logos.py` — white/Firefly lockups, blue/white marks, favicon, app icons |

Regenerate after replacing a source logo (needs Pillow, e.g. in a throwaway venv):

```bash
python3 branding/tools/gen-logos.py branding/source/logo-black-stacked.png branding/source/logo-color-horizontal.png branding/generated
```

Then copy the outputs the admin UI uses into `openbank-admin-ui/public/brand/energopro/` and
`openbank-admin-ui/src/app/favicon.ico`.

## Colour use

Curious Blue `#3688DD` is the brand colour, but it is only 3.67:1 on white, which fails WCAG AA
for text. Use it for the logo, decoration and accents on Firefly. For text and controls on light
surfaces use `#1F66B3` (text/links) or `#2470C2` (buttons with white labels). In dark mode the
accent is `#8BBCEF`. The admin UI's `ui-token-contrast.guard.test.ts` enforces this.

## Where the copies live

### Done
- **Admin UI**: `openbank-admin-ui/src/lib/brand.ts` (name and logo paths) and
  `src/app/globals.css` (colour tokens). The sidebar, page title, favicon, login and
  access-denied pages are rebranded.
- **Bank identity defaults** (env var → placeholder default):
  - `OPENBANK_BIC` → `EPROCZPP`: sepa-payment, sepa-instant, domestic-payment
  - `OPENBANK_BANK_BIC` → `EPROCZPPXXX`: customer-edge (yaml + `CustomerEdgeResource.kt`)
  - `OPENBANK_BANK_CODE` → `8899`: domestic-payment, account-service (IBAN generation)
  - Default currency is CZK upstream already; no change.
- **Admin UI copy and imagery**: every user-facing "OpenBank" in `openbank-admin-ui/src` now
  comes from `BRAND` (breadcrumb roots, privacy notice, auth error page, referral landing, agent
  dock, docs hub, Temporal/SDLC/agent pages); `BRAND` also carries the placeholder `legalName` and
  `supportEmail`. The "OpenBank Explorer" lion/lioness mascot and its images are removed: the login
  story panel shows the white logo on Firefly with a Curious Blue glow, and the orientation banners
  use `components/brand/BrandGuide.tsx` (white mark). App-chrome indigo/violet literals (dashboard
  hero, campaign studio, agent diagnostics, links) are mapped to the brand blues; categorical
  chart/map palettes are deliberately unchanged. Still upstream on purpose: SPDX headers, code
  identifiers, generated `catalog.json` API titles (from service OpenAPI specs), and
  `public/.well-known/security.txt` (needs the bank's real security contact and domain).
- **PDF letterhead and legal entity** (placeholder values from `brand.yaml`):
  `openbank-document-service/.../DocumentTemplateSeed.kt` (letterhead with the Energo-Pro mark as
  an inline ~1 KB `data:` PNG derived from `generated/mark-blue.png`, Curious Blue rule, Firefly
  text, `Energo-Pro Bank a.s.`, IČO `000 00 019`, `Ukázková 1, 110 00 Praha`) and
  `BusinessTemplateBodies.kt`. Every PUBLISHED seed template moved one minor version with new fixed
  ids; `DocumentTemplateSeeder` inserts them on boot and retires the previous version (no
  migration). `BusinessAgreementService.kt` `BANK_NAME`/`BANK_ICO`/seat; PAdES `O=Energo-Pro Bank`
  (`PadesSigning.kt`) and seal name/reason (`PdfBoxPadesSealAdapter.kt`). The pension `.hbs` and
  statement-service templates name no bank, so they are unchanged.
- **Notifications**: sender `noreply@energoprobank.example` (notification-service
  `application.yaml`); copy in `NotificationConsumer.kt`, `NotificationCopyCatalog.kt`,
  `ApprovalCopy.kt`, `PensionCopy.kt`, `OperatorMessageService.kt`, `OversightWebhook.kt`.
  `V18__managed_notification_templates.sql` seeds no rows (managed templates are authored in the
  admin UI), so no migration was needed.
- **Customer edge**: WebAuthn relying-party name and user display name (`WebAuthnResource.kt`;
  rpId/origins unchanged), fallback names and consent `granteeName` in `CustomerEdgeResource.kt`.
  SCA login push text (`ScaService.kt`).
- **Hardcoded bank identity**: `DomesticPaymentService.kt` now reads `openbank.bank.bank-code`
  (8899) instead of `OWN_BANK_CODE = "0000"`; simulation BIC `EPROCZPPXXX`; `IbanGenerator.kt`
  default `8899`.

- **Keycloak**: login theme `openbank-infra/gitops/components/keycloak/theme/openbank/login/`
  (theme id and file names kept — k8s mounts them): Firefly page, white card, `#2470C2` button,
  `#1F66B3` links, Energo-Pro white lockup as `img/openbank-vault.png`, new `img/favicon.ico`,
  `messages_en.properties` ("Energo-Pro Bank"). ConfigMap regenerated with
  `gen-theme-configmap.sh` (now packs the favicon; `keycloak.yaml` mounts it). Realm
  `displayName`/`displayNameHtml` "Energo-Pro Bank" and demo users' `lastName` "Energo-Pro" in
  `realm-template.json`, `customers-realm-template.json`, `docker/keycloak/realm/openbank-realm.json`
  and the Vault import merge in `external-secrets/es-keycloak-realm-import.yaml`. Local
  docker-compose Keycloak mounts the theme and the local realm sets `loginTheme: openbank`
  (an existing local DB needs the realm updated via the admin API; import runs on cold start only).
- **Developer portal**: `openbank-developer-portal/site/` name, colours (`--brand` #2470C2,
  links #1F66B3, Firefly header), mark, favicon; OpenAPI `servers` URL and `security.txt` contact
  moved to placeholders. Vendored Redoc untouched.
- **Marketing and status sites** (`openbank-infra/web/landing`, `web/status`): rebranded (name,
  colours, logos, favicon, placeholder domain). Removed: the TestFlight signup that posted to the
  upstream maintainer's Web3Forms inbox (form, hCaptcha loader, privacy dialog,
  `README-testflight.md`), the demo-credentials dialog and all links to `open-bank.tech` /
  `admin.open-bank.tech`, the historical `classic.html`, and the upstream lion mascots and
  OpenBank-branded screenshots. GitHub links remain as "Built on the OpenBank open-source platform".

### To do
- **Backend copy left on purpose** (each needs its own process, so it is not a find-and-replace):
  - `info.title` / descriptions in about 25 `openbank-*/src/main/resources/openapi.yaml` files
    and the SmallRye `info-title` keys (agent, ap2, mcp, pid `application.yaml`): API-contract
    edits need an `info.version` PATCH bump per ADR-0048 / `rules.yaml`.
  - The customer copilot system prompt (`CopilotChatService.kt`, mock text in
    `MockModelProvider.kt`) is governed by `openbank-libs/governance/prompts/customer-copilot/`
    and its eval recordings; agent-service prompts likewise.
  - Seed/demo names: tpp-registry `V1__init.sql` "OpenBank Sandbox TPP" (would need a new
    migration), kyb `DemoEntity.LEGAL_NAME` "OpenBank Demo s.r.o.", the pension annuity-partner
    protocol (`annuity-partner-protocol-v1.yaml`), and the service docs in
    `src/main/resources/docs/*.md`.
- **Keycloak / web leftovers**: realm client `name`s ("OpenBank Account Service", …) and the
  `browserSecurityHeaders` CSP naming `admin.open-bank.tech` are unchanged (technical, operator-only).
  The status checker `openbank-infra/web/status/backend/handler.py`, both `deploy.sh` scripts and
  the `web-prod` stack still target the upstream hosts/AWS setup; the landing
  `.well-known/apple-app-site-association` / `assetlinks.json` still name the upstream app IDs; the
  `static-site` CloudFront CSP still allows Web3Forms/hCaptcha (harmless now, can be tightened).
  All `*.energoprobank.example` domains and `security@`/`support@` mailboxes are placeholders.
  `generate-public-catalog.py --check` was already stale at HEAD (catalog revision predates the
  release commit); regenerate `platform.html`'s catalog section when convenient.
