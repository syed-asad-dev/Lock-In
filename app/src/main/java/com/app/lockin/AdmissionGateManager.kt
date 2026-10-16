package com.app.lockin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val GATE_TAG = "AdmissionGateManager"
private const val PREFS_NAME = "admission_gate_prefs"
private const val KEY_STATE_SNAPSHOT = "admission_gate_state_snapshot"
private const val KEY_STATE_COMMIT_MARKER = "admission_gate_state_commit_marker"
private const val STATE_COMMIT_TOKEN = "stateCommitToken"

private const val LEGACY_KEY_IS_GRANDFATHERED = "is_grandfathered_initialized"
private const val LEGACY_KEY_RECORDS_JSON = "admission_gate_records_json"

private const val STATE_SCHEMA_VERSION = 2

data class AdmissionGateState(
    val schemaVersion: Int,
    val snapshotId: String,
    val grandfatheringInitialized: Boolean,
    val grandfatheredAtEpochMs: Long?,
    val records: MutableMap<String, AdmissionGateRecord>
)

/**
 * Device-owner admission enforcement.
 *
 * Admission state is one JSON envelope plus a commit marker for that exact
 * envelope. The marker prevents a failed SharedPreferences commit—which may
 * still update the process-local preference cache—from becoming trusted.
 * All allow transitions are fail-closed and persist APPROVED before attempting
 * to unsuspend.
 */
internal fun failClosedAdmissionStateAfterPersistenceFailure(
    state: AdmissionGateState
): AdmissionGateState = state.copy(
    records = state.records.mapValuesTo(mutableMapOf()) { (_, record) ->
        when {
            record.hardBlocked -> record.copy(
                status = AdmissionStatus.DENIED,
                externalResult = null,
                finalDecision = ClassificationDecision.DENY,
                enforcementMessage = "HARD_BLOCKED_AUTHORITATIVE"
            )
            record.status == AdmissionStatus.APPROVED -> record.copy(
                status = AdmissionStatus.PENDING,
                externalResult = null,
                finalDecision = ClassificationDecision.UNKNOWN,
                enforcementMessage = "APPROVAL_PERSISTENCE_FAILED_FAIL_CLOSED"
            )
            else -> record
        }
    }
)

internal fun isAdmissionSnapshotCommitTrusted(stateCommitToken: String?, marker: String?): Boolean =
    !stateCommitToken.isNullOrBlank() && stateCommitToken == marker

object AdmissionGateManager {
    // A failed commit can leave the attempted value in this process's
    // SharedPreferences cache. Keep that exact token untrusted until a later
    // successful snapshot-plus-marker save supersedes it.
    private var untrustedSnapshotToken: String? = null

    @Synchronized
    fun isGrandfatheredInitialized(context: Context): Boolean =
        loadState(context).grandfatheringInitialized

    @Synchronized
    fun initializeGrandfatheringIfNeeded(context: Context, excludePackageName: String? = null) {
        val existingState = loadState(context)
        if (existingState.grandfatheringInitialized) return

        val pm = context.packageManager
        val now = System.currentTimeMillis()
        val snapshotId = UUID.randomUUID().toString()
        val records = existingState.records.toMutableMap()
        val pendingForReview = mutableListOf<String>()
        // Records created while the one-time baseline was unavailable (for
        // example by an install receiver) must never be grandfathered later.
        // An APPROVED record in an uninitialized snapshot is also treated as
        // untrusted and re-audited.
        records.values.toList().forEach { record ->
            if (record.status == AdmissionStatus.APPROVED) {
                records[record.packageName] = record.copy(
                    status = AdmissionStatus.PENDING,
                    finalDecision = ClassificationDecision.UNKNOWN,
                    enforcementMessage = "BASELINE_UNINITIALIZED_REAUDIT"
                )
            }
            if (records[record.packageName]?.status == AdmissionStatus.PENDING) {
                suspendPackageIfNeeded(context, record.packageName, true)
                pendingForReview += record.packageName
            }
        }
        var grandfatheredCount = 0

        // One package-manager read is the baseline snapshot. The snapshot id
        // is written in the same JSON envelope as every generated record.
        val installedSnapshot = getInstalledPackages(pm)
        if (!installedSnapshot.succeeded) {
            Log.e(GATE_TAG, "Cannot initialize grandfathering while package enumeration is unavailable; retrying later")
            return
        }
        val installedPackages = installedSnapshot.packages
        installedPackages.forEach { packageInfo ->
            val packageName = packageInfo.packageName
            if (packageName == excludePackageName || existingState.records.containsKey(packageName)) return@forEach

            if (isHardBlocked(context, packageName)) {
                enforceHardBlock(context, packageName)
                records.remove(packageName)
                return@forEach
            }
            if (shouldExemptPackage(context, packageInfo)) {
                records.remove(packageName)
                return@forEach
            }

            val audit = AppAuditCollector.collect(context, packageInfo)
            val identity = audit.identity
            val status = if (identity.signingIdentitySha256.isNotEmpty()) {
                AdmissionStatus.APPROVED
            } else {
                AdmissionStatus.PENDING
            }
            if (status == AdmissionStatus.PENDING) {
                suspendPackageIfNeeded(context, packageName, true)
                pendingForReview += packageName
            }
            records[packageName] = recordFromAudit(
                audit = audit,
                status = status,
                now = now,
                hardBlocked = false,
                externalResult = null,
                enforcementMessage = if (status == AdmissionStatus.APPROVED) {
                    "GRANDFATHERED_BASELINE"
                } else {
                    "SIGNING_IDENTITY_UNAVAILABLE"
                }
            )
            grandfatheredCount++
        }

        val state = AdmissionGateState(
            schemaVersion = STATE_SCHEMA_VERSION,
            snapshotId = snapshotId,
            grandfatheringInitialized = true,
            grandfatheredAtEpochMs = now,
            records = records
        )
        if (!saveState(context, state)) {
            Log.e(GATE_TAG, "Could not commit baseline snapshot $snapshotId; it will be retried.")
            return
        }

        pendingForReview.forEach { AdmissionWorkScheduler.enqueue(context, it) }
        Log.i(
            GATE_TAG,
            "Committed baseline snapshot $snapshotId: grandfathered=$grandfatheredCount pending=${pendingForReview.size}"
        )
    }

    /** Called synchronously by the install receiver after hard-block enforcement. */
    @Synchronized
    fun onPackageInstalled(context: Context, packageName: String, isReplacing: Boolean) {
        if (isHardBlocked(context, packageName)) {
            onHardBlockedPackage(context, packageName)
            return
        }

        val packageInfo = AdmissionPackageQueries.getPackageInfo(context.packageManager, packageName)
            ?: run {
                Log.w(GATE_TAG, "Package $packageName was not queryable after install.")
                return
            }
        if (shouldExemptPackage(context, packageInfo)) return

        // Suspend the event package before any potentially expensive first-run
        // baseline scan. It is excluded from that snapshot below, so a fresh
        // install can never execute during grandfather initialization.
        suspendPackageIfNeeded(context, packageName, true)

        if (!isGrandfatheredInitialized(context)) {
            // The event package is deliberately excluded from the one-time
            // baseline so it can never be grandfathered by a race.
            initializeGrandfatheringIfNeeded(context, excludePackageName = packageName)
        }

        val state = loadState(context)
        val existing = state.records[packageName]
        val audit = AppAuditCollector.collect(context, packageInfo)
        val identity = audit.identity
        val now = System.currentTimeMillis()

        val existingIdentityMatches = existing?.let {
            AdmissionIdentityVerifier.validate(
                expected = it.storedIdentity(),
                actual = identity,
                requireVersionMatch = false
            ).isMatch
        } == true

        // A local DENY is authoritative for this package name. Reinstalling or
        // replacing it cannot turn it into an externally approved package.
        val localDenied = existing?.status == AdmissionStatus.DENIED && !existing.hardBlocked
        val preserveApproved = isReplacing &&
            existing?.status == AdmissionStatus.APPROVED &&
            existingIdentityMatches &&
            sameInstallInstance(existing.auditResult, audit) &&
            isCleanApprovalAudit(audit) &&
            identity.signingIdentitySha256.isNotEmpty()

        val record: AdmissionGateRecord
        val shouldQueue: Boolean
        if (preserveApproved) {
            record = mergeCurrentIdentity(
                existing = existing!!,
                identity = identity,
                // Keep the approval for a same-signer/source update, but bind
                // the record to the freshly collected audit rather than an
                // obsolete pre-update snapshot.
                audit = audit,
                now = now,
                status = AdmissionStatus.APPROVED,
                externalResult = existing.externalResult,
                enforcementMessage = "APPROVED_IDENTITY_REVALIDATED"
            )
            shouldQueue = false
        } else {
            val status = if (localDenied) AdmissionStatus.DENIED else AdmissionStatus.PENDING
            record = recordFromAudit(
                audit = audit,
                status = status,
                now = now,
                installedAtEpochMs = existing?.installedAtEpochMs ?: now,
                hardBlocked = false,
                externalResult = if (status == AdmissionStatus.DENIED) existing?.externalResult else null,
                enforcementMessage = if (status == AdmissionStatus.DENIED) {
                    "LOCAL_DENY_PRESERVED"
                } else {
                    "PENDING_SUSPENDED"
                }
            )
            shouldQueue = status == AdmissionStatus.PENDING
        }

        val updatedRecords = state.records.toMutableMap().apply { put(packageName, record) }
        val updatedState = state.copy(records = updatedRecords)
        if (!saveState(context, updatedState)) {
            Log.e(GATE_TAG, "Failed to persist install decision for $packageName")
            return
        }

        if (preserveApproved) {
            // This is an already approved, same-signer/source replacement. The
            // state is committed before restoring normal package availability.
            if (!suspendPackageIfNeeded(context, packageName, false)) {
                val pending = record.copy(
                    status = AdmissionStatus.PENDING,
                    finalDecision = ClassificationDecision.UNKNOWN,
                    statusUpdatedAtEpochMs = System.currentTimeMillis(),
                    enforcementMessage = "UNSUSPEND_FAILED_FAIL_CLOSED"
                )
                saveState(context, updatedState.copy(records = updatedState.records.toMutableMap().apply {
                    put(packageName, pending)
                }))
                AdmissionWorkScheduler.enqueue(context, packageName)
            }
        } else if (shouldQueue) {
            AdmissionWorkScheduler.enqueue(context, packageName)
        }
    }

    @Synchronized
    fun onHardBlockedPackage(context: Context, packageName: String) {
        // Hard-block enforcement is intentionally repeated here even though
        // the receiver already called it. This keeps all alternate entry paths
        // authoritative and prevents an old APPROVED record being reused.
        enforceHardBlock(context, packageName)
        val state = loadState(context)
        val old = state.records[packageName]
        val packageInfo = AdmissionPackageQueries.getPackageInfo(context.packageManager, packageName)
        val audit = packageInfo?.let { AppAuditCollector.collect(context, it) }
        val now = System.currentTimeMillis()
        val denied = if (audit != null) {
            recordFromAudit(
                audit = audit,
                status = AdmissionStatus.DENIED,
                now = now,
                installedAtEpochMs = old?.installedAtEpochMs ?: now,
                hardBlocked = true,
                externalResult = old?.externalResult,
                enforcementMessage = "HARD_BLOCKED_AUTHORITATIVE"
            )
        } else if (old != null) {
            old.copy(
                status = AdmissionStatus.DENIED,
                hardBlocked = true,
                finalDecision = ClassificationDecision.DENY,
                statusUpdatedAtEpochMs = now,
                enforcementMessage = "HARD_BLOCKED_AUTHORITATIVE"
            )
        } else {
            AdmissionGateRecord(
                packageName = packageName,
                status = AdmissionStatus.DENIED,
                signingCertDigest = "",
                installerPackageName = null,
                versionCode = 0L,
                versionName = null,
                appLabel = packageName,
                installedAtEpochMs = now,
                statusUpdatedAtEpochMs = now,
                finalDecision = ClassificationDecision.DENY,
                hardBlocked = true,
                enforcementMessage = "HARD_BLOCKED_AUTHORITATIVE"
            )
        }
        val updated = state.copy(records = state.records.toMutableMap().apply { put(packageName, denied) })
        saveState(context, updated)
    }

    @Synchronized
    fun onPackageRemoved(context: Context, packageName: String, isReplacing: Boolean) {
        if (isReplacing) return
        val state = loadState(context)
        val record = state.records[packageName] ?: return
        // DENIED is package-name persistent by policy: uninstall/reinstall must
        // not erase a prior denial before the replacement can be audited again.
        // APPROVED and PENDING records are tied to the removed install instance.
        if (record.status == AdmissionStatus.DENIED) return
        if (state.records.remove(packageName) != null) saveState(context, state)
    }

    /** Re-applies fail-closed states after boot, foreground startup, or repair. */
    @Synchronized
    fun reconcileState(context: Context) {
        if (!isGrandfatheredInitialized(context)) {
            initializeGrandfatheringIfNeeded(context)
            return
        }

        val state = loadState(context)
        val records = state.records.toMutableMap()
        val installedSnapshot = getInstalledPackages(context.packageManager)
        if (!installedSnapshot.succeeded) {
            // An empty result is not proof that every package was removed. Do
            // not delete durable state on a transient PackageManager failure;
            // re-assert only the already-known fail-closed states and retry.
            records.values.filter { it.status != AdmissionStatus.APPROVED }.forEach { record ->
                suspendPackageIfNeeded(context, record.packageName, true)
            }
            AdmissionWorkScheduler.enqueue(context)
            return
        }
        val installedPackages = installedSnapshot.packages
        val installedMap = installedPackages.associateBy { it.packageName }
        val pendingForReview = mutableListOf<String>()
        val now = System.currentTimeMillis()

        records.values.toList().forEach { record ->
            val packageInfo = installedMap[record.packageName]
            if (packageInfo == null) {
                // DENIED is intentionally retained across uninstall/reinstall.
                // Other states describe an install instance that no longer
                // exists and can be discarded safely.
                if (record.status != AdmissionStatus.DENIED) {
                    records.remove(record.packageName)
                }
                return@forEach
            }

            if (isHardBlocked(context, record.packageName)) {
                enforceHardBlock(context, record.packageName)
                records[record.packageName] = record.copy(
                    status = AdmissionStatus.DENIED,
                    hardBlocked = true,
                    finalDecision = ClassificationDecision.DENY,
                    statusUpdatedAtEpochMs = now,
                    enforcementMessage = "HARD_BLOCKED_AUTHORITATIVE"
                )
                return@forEach
            }
            if (shouldExemptPackage(context, packageInfo)) {
                records.remove(record.packageName)
                return@forEach
            }

            val audit = AppAuditCollector.collect(context, packageInfo)
            val identity = audit.identity
            when (record.status) {
                AdmissionStatus.PENDING -> {
                    suspendPackageIfNeeded(context, record.packageName, true)
                    records[record.packageName] = mergeCurrentIdentity(
                        existing = record,
                        identity = identity,
                        audit = audit,
                        now = record.statusUpdatedAtEpochMs,
                        status = AdmissionStatus.PENDING,
                        externalResult = record.externalResult,
                        enforcementMessage = "PENDING_SUSPENDED"
                    )
                    pendingForReview += record.packageName
                }
                AdmissionStatus.DENIED -> {
                    suspendPackageIfNeeded(context, record.packageName, true)
                    records[record.packageName] = mergeCurrentIdentity(
                        existing = record,
                        identity = identity,
                        audit = audit,
                        now = record.statusUpdatedAtEpochMs,
                        status = AdmissionStatus.DENIED,
                        externalResult = record.externalResult,
                        enforcementMessage = if (record.hardBlocked) {
                            "HARD_BLOCKED_AUTHORITATIVE"
                        } else {
                            "LOCAL_DENY_PRESERVED"
                        }
                    )
                }
                AdmissionStatus.APPROVED -> {
                    val validation = AdmissionIdentityVerifier.validate(
                        expected = record.storedIdentity(),
                        actual = identity,
                        requireVersionMatch = false
                    )
                    if (!validation.isMatch || identity.signingIdentitySha256.isEmpty() ||
                        !sameInstallInstance(record.auditResult, audit) || !isCleanApprovalAudit(audit)
                    ) {
                        suspendPackageIfNeeded(context, record.packageName, true)
                        val pending = mergeCurrentIdentity(
                            existing = record,
                            identity = identity,
                            audit = audit,
                            now = now,
                            status = AdmissionStatus.PENDING,
                            externalResult = null,
                            enforcementMessage = validation.reasonCodes.joinToString(",")
                                .ifBlank { "APPROVED_IDENTITY_REVALIDATION_FAILED" }
                        )
                        records[record.packageName] = pending
                        pendingForReview += record.packageName
                    } else {
                        // The stored signer and reliable install source still
                        // match; only then is an APPROVED package unsuspended.
                        val refreshed = mergeCurrentIdentity(
                            existing = record,
                            identity = identity,
                            audit = audit,
                            now = record.statusUpdatedAtEpochMs,
                            status = AdmissionStatus.APPROVED,
                            externalResult = record.externalResult,
                            enforcementMessage = "APPROVED_IDENTITY_REVALIDATED"
                        )
                        records[record.packageName] = refreshed
                        if (saveState(context, state.copy(records = records))) {
                            if (!suspendPackageIfNeeded(context, record.packageName, false)) {
                                records[record.packageName] = refreshed.copy(
                                    status = AdmissionStatus.PENDING,
                                    finalDecision = ClassificationDecision.UNKNOWN,
                                    statusUpdatedAtEpochMs = now,
                                    enforcementMessage = "UNSUSPEND_FAILED_FAIL_CLOSED"
                                )
                                pendingForReview += record.packageName
                            }
                        } else {
                            // A failed refresh commit must not leave an
                            // APPROVED value trusted in this reconciliation.
                            records[record.packageName] = failClosedAdmissionStateAfterPersistenceFailure(
                                state.copy(records = mutableMapOf(record.packageName to refreshed))
                            ).records.getValue(record.packageName)
                            pendingForReview += record.packageName
                        }
                    }
                }
            }
        }

        // Discover packages installed while the receiver/app was inactive.
        installedPackages.forEach { packageInfo ->
            val packageName = packageInfo.packageName
            if (records.containsKey(packageName)) return@forEach
            if (isHardBlocked(context, packageName)) {
                enforceHardBlock(context, packageName)
                return@forEach
            }
            if (shouldExemptPackage(context, packageInfo)) return@forEach

            val audit = AppAuditCollector.collect(context, packageInfo)
            suspendPackageIfNeeded(context, packageName, true)
            records[packageName] = recordFromAudit(
                audit = audit,
                status = AdmissionStatus.PENDING,
                now = now,
                hardBlocked = false,
                externalResult = null,
                enforcementMessage = "DISCOVERED_PENDING_SUSPENDED"
            )
            pendingForReview += packageName
        }

        if (saveState(context, state.copy(records = records))) {
            pendingForReview.distinct().forEach { AdmissionWorkScheduler.enqueue(context, it) }
        }
    }

    @Synchronized
    fun denyPackage(context: Context, packageName: String): Boolean {
        if (isHardBlocked(context, packageName)) {
            onHardBlockedPackage(context, packageName)
            return false
        }
        val state = loadState(context)
        val record = state.records[packageName] ?: return false
        suspendPackageIfNeeded(context, packageName, true)
        val updated = record.copy(
            status = AdmissionStatus.DENIED,
            finalDecision = ClassificationDecision.DENY,
            statusUpdatedAtEpochMs = System.currentTimeMillis(),
            enforcementMessage = "LOCAL_DENY"
        )
        val saved = saveState(context, state.copy(records = state.records.toMutableMap().apply {
            put(packageName, updated)
        }))
        return saved
    }

    /**
     * Applies an external response. This is the only production ALLOW path;
     * there is deliberately no local approve method or UI action.
     */
    @Synchronized
    fun applyExternalResult(
        context: Context,
        packageName: String,
        externalResult: AdmissionExternalResult,
        reviewedAudit: AppAuditResult
    ): Boolean {
        val state = loadState(context)
        val record = state.records[packageName] ?: return false
        if (isHardBlocked(context, packageName) || record.hardBlocked) {
            onHardBlockedPackage(context, packageName)
            return false
        }
        // A response that raced with a local/external DENY, removal, or an
        // earlier approval is stale and must never overwrite that state.
        if (record.status != AdmissionStatus.PENDING) return false
        // Do not mutate a newer review/install with an older result, even if
        // package/signer/source/version are identical.
        if (record.auditResult?.auditId != reviewedAudit.auditId) return false

        // Every decision, not only ALLOW, is bound to the install that was
        // audited. This prevents a delayed DENY/UNKNOWN/ALLOW from being
        // applied to a same-version reinstall or a replacement package.
        val packageInfo = AdmissionPackageQueries.getPackageInfo(context.packageManager, packageName)
            ?: return transitionToUnknownAfterIdentityFailure(
                context, state, record, externalResult, "IDENTITY_PACKAGE_MISSING"
            )
        val currentAudit = AppAuditCollector.collect(context, packageInfo)
        val currentIdentity = currentAudit.identity
        val expectedIdentity = record.storedIdentity()
        val auditIdentity = record.auditResult?.identity
        val responseIdentity = externalResult.identity
        val reasons = mutableListOf<String>()
        if (responseIdentity == null) reasons += "IDENTITY_EXTERNAL_MISSING"
        if (isHardBlocked(context, packageName)) reasons += "HARD_BLOCKED_AUTHORITATIVE"
        if (externalResult.decision == ClassificationDecision.ALLOW && auditIdentity == null) {
            reasons += "IDENTITY_LOCAL_AUDIT_MISSING"
        }
        if (responseIdentity != null) {
            reasons += AdmissionIdentityVerifier.validate(
                expected = expectedIdentity,
                actual = responseIdentity,
                requireVersionMatch = true
            ).reasonCodes
        }
        if (auditIdentity != null) {
            reasons += AdmissionIdentityVerifier.validate(
                expected = auditIdentity,
                actual = currentIdentity,
                requireVersionMatch = true
            ).reasonCodes
            if (!sameInstallInstance(record.auditResult, currentAudit)) {
                reasons += "IDENTITY_INSTALL_INSTANCE_MISMATCH"
            }
        }
        reasons += AdmissionIdentityVerifier.validate(
            expected = expectedIdentity,
            actual = currentIdentity,
            requireVersionMatch = true
        ).reasonCodes
        if (externalResult.decision == ClassificationDecision.ALLOW && !isCleanApprovalAudit(currentAudit)) {
            reasons += "LOCAL_AUDIT_UNSAFE_OR_INCOMPLETE"
        }

        if (reasons.isNotEmpty() || currentIdentity.signingIdentitySha256.isEmpty()) {
            return transitionToUnknownAfterIdentityFailure(
                context,
                state,
                record,
                externalResult,
                reasons.distinct().joinToString(",").ifBlank { "IDENTITY_RECHECK_FAILED" },
                currentAudit
            )
        }

        if (externalResult.decision == ClassificationDecision.DENY) {
            suspendPackageIfNeeded(context, packageName, true)
            val denied = mergeCurrentIdentity(
                existing = record,
                identity = currentIdentity,
                audit = currentAudit,
                now = System.currentTimeMillis(),
                status = AdmissionStatus.DENIED,
                externalResult = externalResult,
                enforcementMessage = "EXTERNAL_DENY_SUSPENDED"
            )
            return saveState(context, state.copy(records = state.records.toMutableMap().apply {
                put(packageName, denied)
            }))
        }

        if (externalResult.decision != ClassificationDecision.ALLOW) {
            suspendPackageIfNeeded(context, packageName, true)
            val pending = mergeCurrentIdentity(
                existing = record,
                identity = currentIdentity,
                audit = currentAudit,
                now = System.currentTimeMillis(),
                status = AdmissionStatus.PENDING,
                externalResult = externalResult,
                enforcementMessage = externalResult.reasonCodes.joinToString(",")
                    .ifBlank { "EXTERNAL_UNKNOWN_SUSPENDED" }
            )
            val saved = saveState(context, state.copy(records = state.records.toMutableMap().apply {
                put(packageName, pending)
            }))
            if (saved && externalResult.retryable) AdmissionWorkScheduler.enqueue(context, packageName)
            return saved
        }

        // The state commit precedes unsuspension. If commit or Device Owner
        // enforcement fails, the package remains safe/suspended.
        val approved = mergeCurrentIdentity(
            existing = record,
            identity = currentIdentity,
            audit = currentAudit,
            now = System.currentTimeMillis(),
            status = AdmissionStatus.APPROVED,
            externalResult = externalResult,
            enforcementMessage = "EXTERNAL_ALLOW_IDENTITY_VERIFIED"
        )
        val saved = saveState(context, state.copy(records = state.records.toMutableMap().apply {
            put(packageName, approved)
        }))
        if (!saved) {
            // saveState leaves the attempted snapshot untrusted and the
            // package suspended; retry the external review rather than ever
            // treating the failed APPROVED write as durable.
            AdmissionWorkScheduler.enqueue(context, packageName)
            return false
        }

        if (!suspendPackageIfNeeded(context, packageName, false)) {
            val pending = approved.copy(
                status = AdmissionStatus.PENDING,
                finalDecision = ClassificationDecision.UNKNOWN,
                statusUpdatedAtEpochMs = System.currentTimeMillis(),
                enforcementMessage = "UNSUSPEND_FAILED_FAIL_CLOSED"
            )
            saveState(context, state.copy(records = state.records.toMutableMap().apply {
                put(packageName, pending)
            }))
            AdmissionWorkScheduler.enqueue(context, packageName)
            return false
        }
        return true
    }

    @Synchronized
    fun getRecord(context: Context, packageName: String): AdmissionGateRecord? =
        loadState(context).records[packageName]

    @Synchronized
    fun getAllRecords(context: Context): List<AdmissionGateRecord> =
        loadState(context).records.values.sortedBy { it.appLabel.lowercase() }

    /** Records with audit/decision information or requiring local attention. */
    @Synchronized
    fun getRecordsForUi(context: Context): List<AdmissionGateRecord> =
        getAllRecords(context).filter {
            it.status != AdmissionStatus.APPROVED || it.externalResult != null
        }

    @Synchronized
    fun getPendingRecords(context: Context): List<AdmissionGateRecord> =
        getAllRecords(context).filter { it.status == AdmissionStatus.PENDING }

    /** Refreshes and persists local evidence immediately before network work. */
    @Synchronized
    fun preparePendingAudit(context: Context, packageName: String): AppAuditResult? {
        val state = loadState(context)
        val record = state.records[packageName] ?: return null
        if (record.status != AdmissionStatus.PENDING) return null
        if (isHardBlocked(context, packageName) || record.hardBlocked) {
            onHardBlockedPackage(context, packageName)
            return null
        }
        val packageInfo = AdmissionPackageQueries.getPackageInfo(context.packageManager, packageName)
            ?: return null
        if (shouldExemptPackage(context, packageInfo)) return null

        suspendPackageIfNeeded(context, packageName, true)
        val audit = AppAuditCollector.collect(context, packageInfo)
        val refreshed = mergeCurrentIdentity(
            existing = record,
            identity = audit.identity,
            audit = audit,
            now = System.currentTimeMillis(),
            status = AdmissionStatus.PENDING,
            externalResult = null,
            enforcementMessage = "PENDING_AUDIT_REFRESHED"
        )
        val saved = saveState(context, state.copy(records = state.records.toMutableMap().apply {
            put(packageName, refreshed)
        }))
        return audit.takeIf { saved }
    }

    private fun transitionToUnknownAfterIdentityFailure(
        context: Context,
        state: AdmissionGateState,
        record: AdmissionGateRecord,
        originalResult: AdmissionExternalResult,
        reason: String,
        currentAudit: AppAuditResult? = null
    ): Boolean {
        if (reason.contains("HARD_BLOCKED_AUTHORITATIVE")) {
            onHardBlockedPackage(context, record.packageName)
            return false
        }
        suspendPackageIfNeeded(context, record.packageName, true)
        val unknown = originalResult.copy(
            decision = ClassificationDecision.UNKNOWN,
            reasonCodes = (originalResult.reasonCodes + reason).distinct(),
            summary = "External admission result was not applied because package identity changed or could not be verified.",
            errorCode = "IDENTITY_RECHECK_FAILED",
            retryable = true
        )
        val pending = if (currentAudit != null) {
            mergeCurrentIdentity(
                existing = record,
                identity = currentAudit.identity,
                audit = currentAudit,
                now = System.currentTimeMillis(),
                status = AdmissionStatus.PENDING,
                externalResult = unknown,
                enforcementMessage = reason
            )
        } else {
            record.copy(
                status = AdmissionStatus.PENDING,
                externalResult = unknown,
                finalDecision = ClassificationDecision.UNKNOWN,
                statusUpdatedAtEpochMs = System.currentTimeMillis(),
                enforcementMessage = reason
            )
        }
        val saved = saveState(context, state.copy(records = state.records.toMutableMap().apply {
            put(record.packageName, pending)
        }))
        if (saved) AdmissionWorkScheduler.enqueue(context, record.packageName)
        return false
    }

    private fun recordFromAudit(
        audit: AppAuditResult,
        status: AdmissionStatus,
        now: Long,
        installedAtEpochMs: Long = now,
        hardBlocked: Boolean,
        externalResult: AdmissionExternalResult?,
        enforcementMessage: String
    ): AdmissionGateRecord = AdmissionGateRecord(
        packageName = audit.identity.packageName,
        status = status,
        signingCertDigest = audit.identity.canonicalSigningIdentity,
        installerPackageName = audit.identity.installSource.installingPackageName,
        versionCode = audit.identity.versionCode,
        versionName = audit.identity.versionName,
        appLabel = audit.appLabel,
        installedAtEpochMs = installedAtEpochMs,
        statusUpdatedAtEpochMs = now,
        signingIdentitySha256 = audit.identity.signingIdentitySha256,
        initiatingPackageName = audit.identity.installSource.initiatingPackageName,
        originatingPackageName = audit.identity.installSource.originatingPackageName,
        packageSource = audit.identity.installSource.packageSource,
        installSourceReliable = audit.identity.installSource.sourceReliable,
        auditResult = audit,
        externalResult = externalResult,
        finalDecision = when (status) {
            AdmissionStatus.APPROVED -> ClassificationDecision.ALLOW
            AdmissionStatus.DENIED -> ClassificationDecision.DENY
            AdmissionStatus.PENDING -> ClassificationDecision.UNKNOWN
        },
        hardBlocked = hardBlocked,
        reviewQueuedAtEpochMs = if (status == AdmissionStatus.PENDING) now else null,
        enforcementMessage = enforcementMessage
    )

    private fun isCleanApprovalAudit(audit: AppAuditResult): Boolean =
        audit.collectionErrors.isEmpty() && !audit.componentsTruncated && !audit.capabilitiesTruncated &&
            !audit.hasVpnService && !audit.hasBrowserCapability && !audit.hasBrowsableActivity &&
            !audit.hasAccessibilityService && !audit.hasDeviceAdminReceiver && !audit.requestsInstallPackages &&
            !audit.requestsSystemAlertWindow && !audit.queriesAllPackages && !audit.requestsUsageAccess &&
            !audit.requestsWriteSettings && !audit.requestsManageExternalStorage && !audit.requestsDeletePackages &&
            !audit.hasNotificationListenerService && !audit.hasInputMethodService

    private fun sameInstallInstance(previous: AppAuditResult?, current: AppAuditResult): Boolean {
        if (previous == null) return false
        // firstInstallTime is the PackageManager's install-instance marker.
        // A same-version reinstall can otherwise look identical by package,
        // signer, source, and version alone.
        return previous.firstInstallTimeEpochMs > 0L &&
            current.firstInstallTimeEpochMs > 0L &&
            previous.firstInstallTimeEpochMs == current.firstInstallTimeEpochMs
    }

    private fun mergeCurrentIdentity(
        existing: AdmissionGateRecord,
        identity: PackageIdentity,
        audit: AppAuditResult?,
        now: Long,
        status: AdmissionStatus,
        externalResult: AdmissionExternalResult?,
        enforcementMessage: String
    ): AdmissionGateRecord = existing.copy(
        status = status,
        signingCertDigest = identity.canonicalSigningIdentity,
        installerPackageName = identity.installSource.installingPackageName,
        versionCode = identity.versionCode,
        versionName = identity.versionName,
        appLabel = audit?.appLabel ?: existing.appLabel,
        statusUpdatedAtEpochMs = now,
        signingIdentitySha256 = identity.signingIdentitySha256,
        initiatingPackageName = identity.installSource.initiatingPackageName,
        originatingPackageName = identity.installSource.originatingPackageName,
        packageSource = identity.installSource.packageSource,
        installSourceReliable = identity.installSource.sourceReliable,
        auditResult = audit,
        externalResult = externalResult,
        finalDecision = when (status) {
            AdmissionStatus.APPROVED -> ClassificationDecision.ALLOW
            AdmissionStatus.DENIED -> ClassificationDecision.DENY
            AdmissionStatus.PENDING -> ClassificationDecision.UNKNOWN
        },
        reviewQueuedAtEpochMs = if (status == AdmissionStatus.PENDING) {
            existing.reviewQueuedAtEpochMs ?: now
        } else {
            null
        },
        enforcementMessage = enforcementMessage
    )

    private fun isHardBlocked(context: Context, packageName: String): Boolean =
        try {
            AppBlocklistManager.isPackageBlocked(context, packageName)
        } catch (e: Exception) {
            Log.w(GATE_TAG, "Hard-block lookup failed for $packageName: ${e.message}")
            // A failed hard-block lookup must not create an ALLOW path.
            true
        }

    private fun enforceHardBlock(context: Context, packageName: String) {
        try {
            AppBlocklistManager.blockPackageIfNeeded(context, packageName)
        } catch (e: Exception) {
            Log.e(GATE_TAG, "Hard-block enforcement failed for $packageName", e)
        }
    }

    private fun shouldExemptPackage(context: Context, packageInfo: PackageInfo): Boolean {
        val packageName = packageInfo.packageName
        if (packageName == context.packageName) return true
        if (packageName == "com.android.vending" ||
            packageName == "com.google.android.gms" ||
            packageName == "com.google.android.gsf"
        ) return true
        val appInfo = packageInfo.applicationInfo ?: return false
        return appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
            appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }

    private data class InstalledPackagesSnapshot(
        val packages: List<PackageInfo>,
        val succeeded: Boolean
    )

    private fun getInstalledPackages(pm: PackageManager): InstalledPackagesSnapshot = try {
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(AdmissionPackageQueries.packageInfoFlags().toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(AdmissionPackageQueries.packageInfoFlags())
        }
        InstalledPackagesSnapshot(packages, succeeded = true)
    } catch (e: Exception) {
        Log.e(GATE_TAG, "Unable to enumerate installed packages", e)
        InstalledPackagesSnapshot(emptyList(), succeeded = false)
    }

    private fun suspendPackageIfNeeded(context: Context, packageName: String, suspend: Boolean): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(GATE_TAG, "Not Device Owner; cannot set suspension for $packageName")
            return false
        }
        return try {
            val unsuspendable = dpm.setPackagesSuspended(
                LockInAdminReceiver.getComponentName(context),
                arrayOf(packageName),
                suspend
            )
            val succeeded = !unsuspendable.contains(packageName)
            if (!succeeded) {
                Log.e(GATE_TAG, "Package $packageName was not changed to suspend=$suspend")
            }
            succeeded
        } catch (e: Exception) {
            Log.e(GATE_TAG, "Suspension change failed for $packageName (suspend=$suspend)", e)
            false
        }
    }

    private fun loadState(context: Context): AdmissionGateState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_STATE_SNAPSHOT, null)
        if (!raw.isNullOrBlank()) {
            try {
                val json = JSONObject(raw)
                val parsed = stateFromJson(json)
                val token = json.optString(STATE_COMMIT_TOKEN, "")
                val marker = prefs.getString(KEY_STATE_COMMIT_MARKER, null)
                if (token.isBlank()) {
                    // Adopt a pre-marker snapshot only after the new marker is
                    // durably written. A failed adoption is treated exactly
                    // like any other untrusted approval snapshot.
                    if (saveState(context, parsed)) {
                        return normalizeHardBlockedRecords(context, parsed)
                    }
                    return failClosedUntrustedState(context, parsed)
                }
                if (token == untrustedSnapshotToken || !isAdmissionSnapshotCommitTrusted(token, marker)) {
                    Log.e(GATE_TAG, "Admission snapshot commit marker mismatch; failing closed")
                    return failClosedUntrustedState(context, parsed)
                }
                return normalizeHardBlockedRecords(context, parsed)
            } catch (e: Exception) {
                Log.e(GATE_TAG, "Admission snapshot is malformed; quarantining state", e)
                // Never fall through to the legacy marker or an uninitialized
                // state after a canonical snapshot has been corrupted: that
                // would allow a fresh baseline and re-grandfather packages.
                val quarantined = quarantinedState()
                saveState(context, quarantined)
                return quarantined
            }
        }

        // Migrate V1's two-key state once. The new state is committed as one
        // envelope plus its marker; hard-blocked legacy approvals are discarded.
        val migrated = migrateLegacyState(context, prefs)
        if (migrated != null) {
            if (saveState(context, migrated)) return normalizeHardBlockedRecords(context, migrated)
            return failClosedUntrustedState(context, migrated)
        }
        return emptyState()
    }

    private fun failClosedUntrustedState(
        context: Context,
        state: AdmissionGateState
    ): AdmissionGateState {
        state.records.values.filter { it.status == AdmissionStatus.APPROVED }.forEach {
            suspendPackageIfNeeded(context, it.packageName, true)
        }
        val safe = failClosedAdmissionStateAfterPersistenceFailure(state)
        // Best effort: if this commit also fails, the returned state is still
        // safe in memory and the marker mismatch keeps the preference cache
        // from being trusted as an approval on subsequent reads.
        saveState(context, safe)
        return safe
    }

    private fun normalizeHardBlockedRecords(
        context: Context,
        state: AdmissionGateState
    ): AdmissionGateState {
        var changed = false
        val normalized = state.records.toMutableMap()
        normalized.values.toList().forEach { record ->
            if (record.hardBlocked || isHardBlocked(context, record.packageName)) {
                enforceHardBlock(context, record.packageName)
                if (record.status != AdmissionStatus.DENIED || !record.hardBlocked ||
                    record.finalDecision != ClassificationDecision.DENY
                ) {
                    normalized[record.packageName] = record.copy(
                        status = AdmissionStatus.DENIED,
                        hardBlocked = true,
                        finalDecision = ClassificationDecision.DENY,
                        enforcementMessage = "HARD_BLOCKED_AUTHORITATIVE",
                        statusUpdatedAtEpochMs = System.currentTimeMillis()
                    )
                    changed = true
                }
            }
        }
        if (!changed) return state
        val normalizedState = state.copy(records = normalized)
        saveState(context, normalizedState)
        return normalizedState
    }

    private fun migrateLegacyState(
        context: Context,
        prefs: android.content.SharedPreferences
    ): AdmissionGateState? {
        if (!prefs.contains(LEGACY_KEY_IS_GRANDFATHERED) && !prefs.contains(LEGACY_KEY_RECORDS_JSON)) {
            return null
        }
        val records = mutableMapOf<String, AdmissionGateRecord>()
        val rawRecords = prefs.getString(LEGACY_KEY_RECORDS_JSON, null)
        if (!rawRecords.isNullOrBlank()) {
            try {
                val array = JSONArray(rawRecords)
                for (index in 0 until array.length()) {
                    val json = array.getJSONObject(index)
                    val packageName = json.getString("packageName")
                    if (isHardBlocked(context, packageName)) {
                        enforceHardBlock(context, packageName)
                        continue
                    }
                    val status = runCatching {
                        AdmissionStatus.valueOf(json.optString("status", AdmissionStatus.PENDING.name))
                    }.getOrDefault(AdmissionStatus.PENDING)
                    val digest = json.optString("signingCertDigest", "")
                    val record = AdmissionGateRecord(
                        packageName = packageName,
                        status = status,
                        signingCertDigest = digest,
                        installerPackageName = json.nullableString("installerPackageName"),
                        versionCode = json.optLong("versionCode", 0L),
                        versionName = json.nullableString("versionName"),
                        appLabel = json.optString("appLabel", packageName),
                        installedAtEpochMs = json.optLong("installedAtEpochMs", 0L),
                        statusUpdatedAtEpochMs = json.optLong("statusUpdatedAtEpochMs", 0L),
                        signingIdentitySha256 = digest.split(',').map { it.trim() }.filter { it.isNotBlank() },
                        installSourceReliable = !json.nullableString("installerPackageName").isNullOrBlank(),
                        finalDecision = when (status) {
                            AdmissionStatus.APPROVED -> ClassificationDecision.ALLOW
                            AdmissionStatus.DENIED -> ClassificationDecision.DENY
                            AdmissionStatus.PENDING -> ClassificationDecision.UNKNOWN
                        },
                        enforcementMessage = "MIGRATED_V1_STATE"
                    )
                    records[packageName] = record
                }
            } catch (e: Exception) {
                Log.e(GATE_TAG, "Unable to migrate V1 admission records", e)
            }
        }
        val state = AdmissionGateState(
            schemaVersion = STATE_SCHEMA_VERSION,
            snapshotId = if (prefs.getBoolean(LEGACY_KEY_IS_GRANDFATHERED, false)) {
                UUID.randomUUID().toString()
            } else {
                ""
            },
            grandfatheringInitialized = prefs.getBoolean(LEGACY_KEY_IS_GRANDFATHERED, false),
            grandfatheredAtEpochMs = if (prefs.getBoolean(LEGACY_KEY_IS_GRANDFATHERED, false)) {
                System.currentTimeMillis()
            } else {
                null
            },
            records = records
        )
        return state
    }

    private fun emptyState() = AdmissionGateState(
        schemaVersion = STATE_SCHEMA_VERSION,
        snapshotId = "",
        grandfatheringInitialized = false,
        grandfatheredAtEpochMs = null,
        records = mutableMapOf()
    )

    private fun quarantinedState() = AdmissionGateState(
        schemaVersion = STATE_SCHEMA_VERSION,
        snapshotId = "quarantined-${UUID.randomUUID()}",
        grandfatheringInitialized = true,
        grandfatheredAtEpochMs = null,
        records = mutableMapOf()
    )

    private fun stateFromJson(json: JSONObject): AdmissionGateState {
        val records = mutableMapOf<String, AdmissionGateRecord>()
        val snapshotId = json.optString("snapshotId", "")
        val grandfatheringInitialized = json.optBoolean("grandfatheringInitialized", false)
        if (grandfatheringInitialized && snapshotId.isBlank()) {
            throw IllegalStateException("initialized admission state has no snapshot marker")
        }
        if (!grandfatheringInitialized && snapshotId.isNotBlank()) {
            throw IllegalStateException("uninitialized admission state has a snapshot marker")
        }
        val array = json.optJSONArray("records") ?: JSONArray()
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let { recordFromJson(it)?.let { record -> records[record.packageName] = record } }
        }
        return AdmissionGateState(
            schemaVersion = json.optInt("schemaVersion", STATE_SCHEMA_VERSION),
            snapshotId = snapshotId,
            grandfatheringInitialized = grandfatheringInitialized,
            grandfatheredAtEpochMs = json.nullableLong("grandfatheredAtEpochMs"),
            records = records
        )
    }

    private fun recordFromJson(json: JSONObject): AdmissionGateRecord? = try {
        val status = AdmissionStatus.valueOf(json.getString("status"))
        val identityArray = json.optJSONArray("signingIdentitySha256")
        val legacyDigest = json.optString("signingCertDigest", "")
        val signers = identityArray?.toStringList().orEmpty().ifEmpty {
            legacyDigest.split(',').map { it.trim() }.filter { it.isNotBlank() }
        }
        AdmissionGateRecord(
            packageName = json.getString("packageName"),
            status = status,
            signingCertDigest = json.optString("signingCertDigest", signers.joinToString(",")),
            installerPackageName = json.nullableString("installerPackageName"),
            versionCode = json.optLong("versionCode", 0L),
            versionName = json.nullableString("versionName"),
            appLabel = json.optString("appLabel", json.getString("packageName")),
            installedAtEpochMs = json.optLong("installedAtEpochMs", 0L),
            statusUpdatedAtEpochMs = json.optLong("statusUpdatedAtEpochMs", 0L),
            signingIdentitySha256 = signers,
            initiatingPackageName = json.nullableString("initiatingPackageName"),
            originatingPackageName = json.nullableString("originatingPackageName"),
            packageSource = json.nullableInt("packageSource"),
            installSourceReliable = json.optBoolean("installSourceReliable", !json.nullableString("installerPackageName").isNullOrBlank()),
            auditResult = json.optJSONObject("auditResult")?.let(::appAuditResultFromJson),
            externalResult = json.optJSONObject("externalResult")?.let(::admissionExternalResultFromJson),
            finalDecision = runCatching {
                ClassificationDecision.valueOf(json.optString("finalDecision", when (status) {
                    AdmissionStatus.APPROVED -> ClassificationDecision.ALLOW.name
                    AdmissionStatus.DENIED -> ClassificationDecision.DENY.name
                    AdmissionStatus.PENDING -> ClassificationDecision.UNKNOWN.name
                }))
            }.getOrDefault(ClassificationDecision.UNKNOWN),
            hardBlocked = json.optBoolean("hardBlocked", false),
            reviewQueuedAtEpochMs = json.nullableLong("reviewQueuedAtEpochMs"),
            enforcementMessage = json.nullableString("enforcementMessage")
        )
    } catch (e: Exception) {
        Log.e(GATE_TAG, "Skipping malformed admission record", e)
        null
    }

    private fun recordToJson(record: AdmissionGateRecord): JSONObject = JSONObject().apply {
        put("packageName", record.packageName)
        put("status", record.status.name)
        put("signingCertDigest", record.signingCertDigest)
        putNullable("installerPackageName", record.installerPackageName)
        put("versionCode", record.versionCode)
        putNullable("versionName", record.versionName)
        put("appLabel", record.appLabel)
        put("installedAtEpochMs", record.installedAtEpochMs)
        put("statusUpdatedAtEpochMs", record.statusUpdatedAtEpochMs)
        put("signingIdentitySha256", record.signingIdentitySha256.toJsonArray())
        putNullable("initiatingPackageName", record.initiatingPackageName)
        putNullable("originatingPackageName", record.originatingPackageName)
        putNullable("packageSource", record.packageSource)
        put("installSourceReliable", record.installSourceReliable)
        putNullable("auditResult", record.auditResult?.toJson())
        putNullable("externalResult", record.externalResult?.toJson())
        put("finalDecision", record.finalDecision.name)
        put("hardBlocked", record.hardBlocked)
        putNullable("reviewQueuedAtEpochMs", record.reviewQueuedAtEpochMs)
        putNullable("enforcementMessage", record.enforcementMessage)
    }

    private fun saveState(context: Context, state: AdmissionGateState): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val previousMarker = prefs.getString(KEY_STATE_COMMIT_MARKER, null)
        val commitToken = UUID.randomUUID().toString()
        val json = JSONObject().apply {
            put("schemaVersion", STATE_SCHEMA_VERSION)
            put("snapshotId", state.snapshotId)
            put("grandfatheringInitialized", state.grandfatheringInitialized)
            putNullable("grandfatheredAtEpochMs", state.grandfatheredAtEpochMs)
            put(STATE_COMMIT_TOKEN, commitToken)
            put("records", JSONArray().apply {
                state.records.values.sortedBy { it.packageName }.forEach { put(recordToJson(it)) }
            })
        }.toString()

        // SharedPreferences may update its in-memory map before commit()
        // returns false. The snapshot is therefore untrusted until the
        // separate marker commit succeeds and matches the exact snapshot.
        if (!prefs.edit().putString(KEY_STATE_SNAPSHOT, json).commit()) {
            untrustedSnapshotToken = commitToken
            Log.e(GATE_TAG, "Admission snapshot commit failed; approval remains untrusted")
            return false
        }
        if (!prefs.edit().putString(KEY_STATE_COMMIT_MARKER, commitToken).commit()) {
            // commit() may also leave the failed marker in memory. Restore the
            // prior marker; the process-local rejected token also blocks trust.
            prefs.edit().apply {
                if (previousMarker == null) remove(KEY_STATE_COMMIT_MARKER)
                else putString(KEY_STATE_COMMIT_MARKER, previousMarker)
            }.commit()
            untrustedSnapshotToken = commitToken
            Log.e(GATE_TAG, "Admission snapshot commit marker failed; approval remains untrusted")
            return false
        }
        untrustedSnapshotToken = null
        return true
    }
}
