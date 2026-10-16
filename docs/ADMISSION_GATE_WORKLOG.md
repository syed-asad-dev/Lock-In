# Admission Gate Worklog

Last updated: 2026-10-07 (final focused repair pass)

## Goal

Finish Admission Gate code-side and backend-side as far as possible without a physical phone, while preserving fail-closed security semantics. Physical validation is deferred until Stay Focused Strict Mode ends.

## Completed before current work

- Android PENDING / APPROVED / DENIED persistence and suspension flow.
- One-time grandfathering with consolidated snapshot/migration.
- Install/update/reinstall and boot/startup reconciliation.
- Identity verification for signer/source/version.
- Production local Approve removed; external result is the production ALLOW path.
- Async JobScheduler evaluation with network requirement/backoff.
- Backend HTTP contract/client with HTTPS, bearer auth, strict JSON, response cap, timeouts, no redirects, failure -> UNKNOWN.
- Local app audit and managed restrictions.
- Reputation service with 42matters exact package lookup, Brave search, AI classifier, deterministic policy.
- Provider/deadline hardening and normalized bounded AI input.
- User-facing emergency VPN-disable button removed while internal VPN recovery remains.
- Policy fix: one arbitrary prohibited web source plus AI DENY does not become DENY.
- Automated recovery verification: backend 35 tests, Android 6 unit tests, typecheck/build/APK assembly PASS.

## Current phase

Final focused repair pass complete: durable approval persistence and locked-boot behavior were corrected without changing the established Admission Gate, Device Owner, VPN, quarantine, or evidence-policy architecture.

## Deferred physical campaign

After phone access returns, validate:
- fresh install -> immediate PENDING suspension
- DENY remains suspended
- UNKNOWN remains suspended
- valid ALLOW unsuspends only after identity revalidation
- signer/source/version mismatch fails closed
- reboot persistence and reconciliation
- uninstall/reinstall behavior
- hard-block packages never admitted
- explicit VPN quarantine behavior and normal Internet for legitimate apps
- Device Owner restrictions, Safe Mode protection, sideload blocking
- no bypass through local UI
- race/reinstall/update cases

## Implementation audit pass (2026-10-07)

Concrete fixes completed:

- Canonical malformed admission snapshots now quarantine into an initialized, empty fail-closed state; they cannot fall through to legacy state and re-grandfather packages.
- Package enumeration distinguishes a real empty package set from PackageManager failure. Reconciliation no longer deletes all records after an enumeration exception and requeues repair work; install-created records are excluded from any later baseline retry so an enumeration failure cannot re-grandfather them.
- DENIED records survive uninstall/removal and are preserved through absent-package reconciliation for reinstall persistence. APPROVED update preservation now stores the fresh audit and only preserves when the install instance and clean local audit remain valid.
- External DENY/UNKNOWN/ALLOW results are checked against the current PackageManager identity, stored audit, reliable install-source fields, and an audit generation token. Same-version reinstall and delayed-result races fail closed and retry; identity-failure transitions are persisted as UNKNOWN before requeue.
- Reliable source comparison now detects newly populated/null source fields and source reliability changes. ALLOW application also rejects incomplete/truncated or locally prohibited fresh audits, persists APPROVED before unsuspension, and retries unsuspend failure.
- Locked-boot startup reasserts the established Always-On VPN only; credential-protected Admission Gate reconciliation is deferred to normal ACTION_BOOT_COMPLETED. Browser-capability lookup failures fail closed.
- Android response parsing now requires the exact response decision field, typed reason/evidence/reputation data, exact certificate-digest arrays, and strict identity/source structure.
- Backend request validation now rejects malformed rather than silently normalized identity, source, audit booleans, permission, and capability fields. Brave evidence independence uses registrable domains so subdomains cannot count as separate sources.
- Added regression coverage for source consistency, malformed provider/request data, registrable-domain independence, prohibited evidence policy, exact Play metadata DENY, and fail-closed provider cases.

## Verification recorded for this pass

Commands were run without ADB, USB/wireless debugging, emulator/device installs, live provider calls, deployment, dependency installation, or tooling replacement:

- `cmd.exe /c "cd /d C:\\Users\\Syed Asad Ali\\Desktop\\New folder\\lock-in\\reputation-service && npm.cmd run typecheck && npm.cmd test && npm.cmd run build"` — exit 0. Typecheck PASS; complete backend suite: 41 tests passed, 0 failed; TypeScript build PASS.
- `powershell.exe -NoProfile -Command '$env:JAVA_HOME="C:\\Program Files\\Android\\Android Studio\\jbr"; Set-Location "C:\\Users\\Syed Asad Ali\\Desktop\\New folder\\lock-in"; $gradle="C:\\Users\\Syed Asad Ali\\.gradle\\wrapper\\dists\\gradle-9.3.1-bin\\23ovyewtku6u96viwx3xl3oks\\gradle-9.3.1\\bin\\gradle.bat"; & $gradle :app:testDebugUnitTest; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }; & $gradle :app:assembleDebug; exit $LASTEXITCODE'` — exit 0. Android unit tests: 7 passed, 0 failed; debug APK assembly PASS. Windows Gradle 9.3.1 used with Android Studio JDK at `C:\\Program Files\\Android\\Android Studio\\jbr`.
- `git diff --check` — exit 0.

The direct WSL interop attempt `cd reputation-service && npm.cmd run typecheck` exited 2 with `/mnt/c/Program Files/nodejs/npm.cmd: line 13: unexpected EOF while looking for matching '\''`; the explicit Windows `cmd.exe /c ... npm.cmd` command above was then used successfully. Initial direct `cmd.exe` Gradle quoting attempts also failed before the explicit PowerShell Windows Gradle command succeeded.

## Final focused repair pass (2026-10-07)

Concrete fixes:

- `AdmissionGateManager.saveState()` now writes a fresh commit token into the JSON snapshot and commits a matching marker separately. A snapshot is trusted only when the non-empty token matches the marker and is not the process-local token from a failed commit. If either commit fails, the attempted value remains untrusted even if Android's in-memory SharedPreferences map was already updated; a failed marker is best-effort restored to its prior value.
- Untrusted snapshots fail closed by suspending previously APPROVED packages and converting those records to PENDING/UNKNOWN with no trusted external result. External ALLOW queues a retry after failed persistence, and reconciliation converts a failed APPROVED refresh to PENDING before any possible unsuspension. Successful ALLOW still requires the complete snapshot-plus-marker persistence before unsuspension.
- Added plain-JVM state-transition/commit-marker regression coverage in `AdmissionPersistenceTest.kt`; Android framework SharedPreferences failure injection was not added because the existing test stack has no clean framework implementation.
- `ACTION_LOCKED_BOOT_COMPLETED` now reasserts Always-On VPN only and logs that Admission Gate reconciliation is deferred. Admission Gate reconciliation and work scheduling run only for normal `ACTION_BOOT_COMPLETED`; ordinary credential-protected SharedPreferences remain the only Admission Gate storage.

Fresh verification for this final focused pass:

- Windows backend command: `cmd.exe /c "cd /d C:\\Users\\Syed Asad Ali\\Desktop\\New folder\\lock-in\\reputation-service && npm.cmd run typecheck && npm.cmd test && npm.cmd run build"` — exit 0; typecheck PASS; complete backend suite **41/41 passed, 0 failed**; TypeScript build PASS.
- Windows Gradle 9.3.1 with Android Studio JDK command: `powershell.exe -NoProfile -Command '$env:JAVA_HOME="C:\\Program Files\\Android\\Android Studio\\jbr"; Set-Location "C:\\Users\\Syed Asad Ali\\Desktop\\New folder\\lock-in"; $gradle="C:\\Users\\Syed Asad Ali\\.gradle\\wrapper\\dists\\gradle-9.3.1-bin\\23ovyewtku6u96viwx3xl3oks\\gradle-9.3.1\\bin\\gradle.bat"; & $gradle :app:testDebugUnitTest; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }; & $gradle :app:assembleDebug; exit $LASTEXITCODE'` — exit 0; Android JVM unit suite **9/9 passed, 0 failed**; debug APK assembly PASS.
- `git diff --check` — exit 0.

Remaining deployment/credential gaps: no 42matters, Brave, or AI credentials were read or used; no live provider call, backend deployment, managed HTTPS endpoint, or managed bearer-token configuration was performed. Physical install/reboot/Device Owner/suspension/unsuspension/VPN/quarantine and bypass-resistance validation remains deferred because the phone is unavailable under Stay Focused Strict Mode. No ADB, USB/wireless debugging, emulator/device install, connected Android test, or physical-device test was run.

The initial Android parser regression test attempt was removed because local JVM tests use unmocked Android `org.json` methods; the production parser is compiled and the remaining Android unit suite passes. Parser behavior remains a physical/backend integration gap to exercise with a device or a dedicated supported test runtime.

Known non-code gaps:

- No 42matters, Brave, or AI credentials were read or used; no live provider call or production deployment was performed. Provision HTTPS endpoint, managed bearer token, and server-only provider credentials through deployment configuration before rollout.
- No phone is available during the Stay Focused Strict Mode restriction. Physical validation is deferred and must separately test install/reinstall/update races, suspension/unsuspension, boot, hard blocks, Device Owner/Safe Mode/sideload controls, VPN quarantine, and bypass resistance.

## Status vocabulary

- CODE COMPLETE: implementation and laptop-side verification complete.
- AUTOMATED VERIFIED: relevant automated tests/builds pass.
- PHYSICAL VERIFIED: reserved until real-device testing is performed after the restriction ends.
