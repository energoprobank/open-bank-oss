# Energo-Pro Bank public website

Rebranded from the upstream OpenBank site; name, colours and logos come from
[`branding/brand.yaml`](../../../branding/brand.yaml). The domain `energoprobank.example`
in canonical URLs, sitemap, robots, llms.txt and security.txt is a **placeholder** (reserved,
never routes): replace it before publishing.

Static HTML/CSS/JS; no frontend framework or client-side build. The customer app and
admin portal remain separate products. This page presents them, it does not simulate them.

## Preview

From the repository root:

```sh
python3 -m http.server 8766 --bind 127.0.0.1 --directory openbank-infra/web/landing
```

Open `http://127.0.0.1:8766`. Check the homepage, `platform.html` search and `labs.html`
at desktop and mobile widths.

## Files and sources

- `index.html`, `styles.css`: current design. Polar/Firefly neutrals and the Energo-Pro blues
  (`--blue` #2470C2 buttons, `--blue-deep` #1F66B3 links), Space Grotesk / Space Mono.
- `platform.html`: capability navigation and the **complete** repository module inventory.
  The marked catalog section is generated; never edit it by hand.
- `labs.html`: shared banking kernel overview, with links to the corresponding library modules.
- `catalog.js`: local search only. All modules are readable without JavaScript.
- `main.js`: footer year, scroll reveal. No forms, no network requests.
- `assets/energopro-*.png`, `favicon.ico`, `apple-touch-icon.png`: copies of
  `branding/generated/` (mark, Firefly lockup, app icon).

Removed in the rebrand: the iOS TestFlight signup (it posted e-mail addresses to the upstream
maintainer's Web3Forms inbox, with an hCaptcha widget), the demo-access dialog for the upstream
live sandbox, the historical `classic.html`, and the upstream mascot artwork and product
screenshots. The site collects no personal data. `landing.test.cjs` asserts that no form,
CAPTCHA or upstream sandbox link comes back.

## Refresh the public inventory

Install the admin UI's dependencies as documented there (the existing catalog generator
uses Node and `yaml`), then run:

```sh
python3 openbank-infra/web/generate-public-catalog.py
python3 openbank-infra/web/generate-public-catalog.py --check
node --test openbank-infra/web/tests/landing.test.cjs
```

The updater invokes `openbank-admin-ui/scripts/generate-catalog.mjs`; it does not create
another inventory definition. `AREAS` in the updater is an editorial navigation mapping,
not the governance `dataDomain` taxonomy. Every module appears once. New/unmapped modules
remain visible in Shared libraries & delivery. The source revision and date are explicit.
Regenerate after catalog input changes; deployment does this before contacting AWS.

## Publishing and rollback

`../deploy.sh` uses the existing S3/CloudFront deployment. `deploy.sh` and the `web-prod` stack still
target the upstream AWS account/profile; point them at Energo-Pro infrastructure before use.
Do not deploy a branch before required PR review. `.well-known/apple-app-site-association` and
`assetlinks.json` still name the upstream app IDs and must be replaced with Energo-Pro's own.

No health, compliance percentage or cost is presented as live on the public platform
page. Admin evidence surfaces are named by path; they require operator sign-in.
