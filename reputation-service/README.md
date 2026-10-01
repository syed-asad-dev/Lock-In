# Lock-In reputation service

Small TypeScript backend for the Android `admission-gate.v1` client. It
exposes `POST /v1/evaluate`, authenticates the Android bearer token, validates
the request, and fails closed when providers or evidence are unavailable.

## Providers

- `Matters42PlayMetadataProvider` calls the official 42matters lookup endpoint:
  `GET https://data.42matters.com/api/v2.0/android/apps/lookup.json?p=<package>&access_token=<server key>`.
- `BraveWebSearchProvider` calls Brave Web Search at
  `https://api.search.brave.com/res/v1/web/search` using
  `X-Subscription-Token`. It issues bounded package/title/developer searches.
- `OpenAiCompatibleClassifierProvider` is an optional OpenAI-compatible JSON
  classifier. Without all AI settings it returns unavailable; policy then
  returns `UNKNOWN`, never a safe default.

Google Play presence alone never permits `ALLOW`. `ALLOW` requires exact local
identity consistency, positive store identity, corroborated normal-use web
evidence, and a valid AI `ALLOW`. Local VPN/browser capability, authoritative
hard-block packages, and corroborated prohibited web evidence are deterministic
`DENY` paths. Everything else is `UNKNOWN` and remains suspended.

## Run

Copy `.env.example` to `.env` and set `ADMISSION_BACKEND_BEARER_TOKEN` plus
only the provider configuration you have provisioned. Load environment
variables using the deployment platform; this service does not read or create
secrets from the repository.

```text
npm.cmd install
npm.cmd run typecheck
npm.cmd test
npm.cmd run build
npm.cmd start
```

The Android managed app restrictions must point `admission_backend_endpoint` at
an HTTPS deployment ending in `/v1/evaluate` and set the same bearer token in
`admission_backend_bearer_token`. Never put provider keys in the APK.
