# Lock-In Engineering Handoff

Last updated: 2026-10-07 (final focused repair pass)

## Working rules

- Canonical repository is this repository. Do not create a second clone during the current recovery/development phase.
- Current branch: `astra-admission-gate-wip`.
- Recovery baseline HEAD: `2287a3c4d07efb666407a3b83069514bad00ff73`.
- Preserve Device Owner/DPC behavior, Safe Mode protection, broad sideload blocking, browser/VPN hard blocks, and explicit VPN quarantine.
- Always-On VPN lockdown is intentionally `false`. Do not change it to `true`.
- Manual Private DNS is user-controlled. Do not modify it.
- Do not weaken `LocalVpnService`, `NetworkQuarantineManager`, or `VpnSetupManager` without an explicit architecture reason.
- Hard-blocked browser/VPN packages are authoritative and can never be admitted.
- Admission Gate final states are ALLOW / DENY / UNKNOWN. UNKNOWN stays locked/suspended. The admission JSON snapshot is trusted only when its exact commit token matches a separately committed marker; failed approval persistence is converted to PENDING and remains suspended.
- Local production approval is forbidden. ALLOW must come through externally evaluated evidence and Android-side identity revalidation.
- No secrets/API keys/tokens belong in the Android APK or repository.

## Temporary physical-test constraint

Stay Focused Strict Mode prevents access to phone debugging/settings for approximately 30 days from 2026-10-07.

Until that restriction ends:
- Do not run ADB, USB debugging, wireless debugging, connected Android tests, or physical-device tests.
- Do not ask to disable Stay Focused.
- Laptop/backend/unit/build PASS is not a physical-device PASS.
- Physical validation remains a required final gate after the restriction ends.

## Admission Gate architecture

A genuinely new non-system app starts PENDING and suspended. Existing apps at first activation are grandfathered once. Approval identity binds package name, version, signing certificate digest, and reliable install source; the persisted local audit generation/install marker also prevents delayed results from crossing same-version reinstall races. Changed identity must be re-audited.

Local audit captures permissions and security-relevant capabilities including exported components, Accessibility/VPN/Device Admin services, browser/deep-link behavior, REQUEST_INSTALL_PACKAGES, overlays, package-query capabilities, version, installer/source, and signing identity.

Backend enrichment uses exact Play/package metadata plus independent web evidence and an AI classifier. AI is an interpreter, not an independent evidence source. Missing, conflicting, weak, or failed evidence resolves to UNKNOWN.

## Evidence policy invariants

- Deterministic local hard-block/browser/VPN evidence may DENY.
- Exact authoritative Play metadata for the exact identity may DENY when it clearly advertises prohibited functionality.
- Independent web corroboration requires at least two genuinely independent domains/sources.
- One arbitrary prohibited web result plus AI DENY must remain UNKNOWN; AI cannot count as a second source for the same evidence.
- Ambiguous prohibited evidence blocks ALLOW.
- Positive ALLOW requires strict exact identity/store evidence plus independent corroboration and complete clean local audit.
- Generic positive pages, Play presence alone, or absence of bad evidence are insufficient for ALLOW.

## Current recovery state

Before new engineering work, the recovered working tree contained exactly:
- `app/src/main/java/com/app/lockin/MainActivity.kt`
- `reputation-service/src/policy.ts`
- `reputation-service/test/policy.test.ts`

The recovered changes remove the user-facing VPN disable button and harden the evidence policy. Recovery verification reported backend typecheck/build PASS, 35 backend tests PASS, Android 6 unit tests PASS, APK assembly PASS, and `git diff --check` PASS. This audit pass added fail-closed state/reconciliation handling, strict identity/result application, same-version race protection, strict provider/request parsing, and registrable-domain evidence independence. Audit-pass verification: backend 41 tests PASS, Android 7 unit tests PASS, backend typecheck/build, Android debug unit/build and APK assembly, and `git diff --check` PASS. No physical-device or live-provider verification was performed. The final focused repair also restores locked-boot behavior to VPN reassertion only; credential-protected Admission Gate reconciliation runs at normal ACTION_BOOT_COMPLETED.

## Commit/push rule

Implementation and automated verification may proceed. Do not commit or push merely to checkpoint unless the user explicitly approves that consequential action.
