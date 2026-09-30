package com.app.lockin

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class AdmissionStatus {
    PENDING,
    APPROVED,
    DENIED
}

enum class ClassificationDecision {
    ALLOW,
    DENY,
    UNKNOWN
}

data class InstallSourceIdentity(
    val installingPackageName: String?,
    val initiatingPackageName: String?,
    val originatingPackageName: String?,
    val packageSource: Int?,
    val sourceReliable: Boolean
)

data class PackageIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signingIdentitySha256: List<String>,
    val installSource: InstallSourceIdentity
) {
    val canonicalSigningIdentity: String
        get() = signingIdentitySha256
            .map { it.lowercase(Locale.ROOT) }
            .distinct()
            .sorted()
            .joinToString(",")
}

data class AuditedPermission(
    val name: String,
    val granted: Boolean,
    val flags: Int
)

data class AuditedComponent(
    val type: String,
    val className: String,
    val exported: Boolean,
    val enabled: Boolean,
    val directBootAware: Boolean,
    val permission: String?,
    val authorities: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val dataSchemes: List<String> = emptyList()
)

data class IntentCapability(
    val capability: String,
    val componentType: String,
    val componentName: String,
    val actions: List<String>,
    val categories: List<String>,
    val dataSchemes: List<String>
)

/**
 * Local, package-manager-derived evidence captured before external review.
 * All fields are persisted so an external response can be tied to the exact
 * package identity that was suspended.
 */
data class AppAuditResult(
    val schemaVersion: Int = 1,
    val identity: PackageIdentity,
    val appLabel: String,
    val uid: Int,
    val firstInstallTimeEpochMs: Long,
    val lastUpdateTimeEpochMs: Long,
    val rawApplicationFlags: Int,
    val rawPrivateFlags: Int?,
    val isSystemApp: Boolean,
    val isUpdatedSystemApp: Boolean,
    val isDebuggable: Boolean,
    val isTestOnly: Boolean,
    val isPersistent: Boolean,
    val allowsBackup: Boolean,
    val usesCleartextTraffic: Boolean,
    val systemFlags: List<String>,
    val requestedPermissions: List<AuditedPermission>,
    val exportedComponents: List<AuditedComponent>,
    val exportedComponentCount: Int,
    val componentsTruncated: Boolean,
    val intentCapabilities: List<IntentCapability>,
    val capabilitiesTruncated: Boolean,
    val hasBrowsableActivity: Boolean,
    val hasBrowserCapability: Boolean,
    val hasVpnService: Boolean,
    val hasAccessibilityService: Boolean,
    val hasDeviceAdminReceiver: Boolean,
    val requestsInstallPackages: Boolean,
    val requestsSystemAlertWindow: Boolean,
    val overlayAppOpAllowed: Boolean?,
    val queriesAllPackages: Boolean,
    val requestsUsageAccess: Boolean,
    val requestsWriteSettings: Boolean,
    val requestsManageExternalStorage: Boolean,
    val requestsDeletePackages: Boolean,
    val hasNotificationListenerService: Boolean,
    val hasInputMethodService: Boolean,
    val hasBootReceiver: Boolean,
    val bypassSignals: List<String>,
    val collectionErrors: List<String>,
    val collectedAtEpochMs: Long
)

data class WebReputation(
    val verdict: String?,
    val score: Double?,
    val categories: List<String>,
    val source: String?,
    val checkedAtEpochMs: Long?
)

data class ExternalEvidence(
    val type: String,
    val source: String?,
    val detail: String,
    val reference: String?
)

data class AdmissionExternalResult(
    val identity: PackageIdentity?,
    val webReputation: WebReputation?,
    val decision: ClassificationDecision,
    val reasonCodes: List<String>,
    val summary: String,
    val evidence: List<ExternalEvidence>,
    val evaluatedAtEpochMs: Long,
    val httpStatus: Int?,
    val errorCode: String?,
    val retryable: Boolean
)

data class AdmissionGateRecord(
    val packageName: String,
    val status: AdmissionStatus,
    val signingCertDigest: String,
    val installerPackageName: String?,
    val versionCode: Long,
    val versionName: String?,
    val appLabel: String,
    val installedAtEpochMs: Long,
    val statusUpdatedAtEpochMs: Long,
    val signingIdentitySha256: List<String> = signingCertDigest
        .split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() },
    val initiatingPackageName: String? = null,
    val originatingPackageName: String? = null,
    val packageSource: Int? = null,
    val installSourceReliable: Boolean = installerPackageName != null,
    val auditResult: AppAuditResult? = null,
    val externalResult: AdmissionExternalResult? = null,
    val finalDecision: ClassificationDecision = when (status) {
        AdmissionStatus.APPROVED -> ClassificationDecision.ALLOW
        AdmissionStatus.DENIED -> ClassificationDecision.DENY
        AdmissionStatus.PENDING -> ClassificationDecision.UNKNOWN
    },
    val hardBlocked: Boolean = false,
    val reviewQueuedAtEpochMs: Long? = null,
    val enforcementMessage: String? = null
) {
    fun storedIdentity(): PackageIdentity = PackageIdentity(
        packageName = packageName,
        versionCode = versionCode,
        versionName = versionName,
        signingIdentitySha256 = signingIdentitySha256.ifEmpty {
            signingCertDigest.split(',').map { it.trim() }.filter { it.isNotBlank() }
        },
        installSource = InstallSourceIdentity(
            installingPackageName = installerPackageName,
            initiatingPackageName = initiatingPackageName,
            originatingPackageName = originatingPackageName,
            packageSource = packageSource,
            sourceReliable = installSourceReliable
        )
    )
}

data class IdentityValidation(
    val isMatch: Boolean,
    val reasonCodes: List<String>
)

/** Pure identity verification shared by reconciliation and backend ALLOW handling. */
object AdmissionIdentityVerifier {
    fun validate(
        expected: PackageIdentity,
        actual: PackageIdentity,
        requireVersionMatch: Boolean
    ): IdentityValidation {
        val reasons = mutableListOf<String>()

        if (expected.packageName != actual.packageName) {
            reasons += "IDENTITY_PACKAGE_MISMATCH"
        }

        val expectedSigners = normalizeSigners(expected.signingIdentitySha256)
        val actualSigners = normalizeSigners(actual.signingIdentitySha256)
        if (expectedSigners.isEmpty() || actualSigners.isEmpty()) {
            reasons += "IDENTITY_SIGNING_UNAVAILABLE"
        } else if (expectedSigners != actualSigners) {
            reasons += "IDENTITY_SIGNING_MISMATCH"
        }

        if (requireVersionMatch && expected.versionCode != actual.versionCode) {
            reasons += "IDENTITY_VERSION_MISMATCH"
        }

        val expectedSource = expected.installSource
        val actualSource = actual.installSource
        if (expectedSource.sourceReliable) {
            if (!actualSource.sourceReliable) {
                reasons += "IDENTITY_INSTALL_SOURCE_UNAVAILABLE"
            } else {
                compareReliableSourceField(
                    expectedSource.installingPackageName,
                    actualSource.installingPackageName,
                    "IDENTITY_INSTALLER_MISMATCH",
                    reasons
                )
                compareReliableSourceField(
                    expectedSource.initiatingPackageName,
                    actualSource.initiatingPackageName,
                    "IDENTITY_INITIATOR_MISMATCH",
                    reasons
                )
                compareReliableSourceField(
                    expectedSource.originatingPackageName,
                    actualSource.originatingPackageName,
                    "IDENTITY_ORIGINATOR_MISMATCH",
                    reasons
                )
                if (expectedSource.packageSource != null &&
                    expectedSource.packageSource > 0 &&
                    expectedSource.packageSource != actualSource.packageSource
                ) {
                    reasons += "IDENTITY_PACKAGE_SOURCE_MISMATCH"
                }
            }
        }

        return IdentityValidation(reasons.isEmpty(), reasons.distinct())
    }

    private fun compareReliableSourceField(
        expected: String?,
        actual: String?,
        mismatchCode: String,
        reasons: MutableList<String>
    ) {
        if (!expected.isNullOrBlank() && expected != actual) {
            reasons += mismatchCode
        }
    }

    private fun normalizeSigners(signers: List<String>): Set<String> = signers
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotBlank() }
        .toSet()
}

internal fun PackageIdentity.toJson(): JSONObject = JSONObject().apply {
    put("packageName", packageName)
    put("versionCode", versionCode)
    putNullable("versionName", versionName)
    put("signingIdentitySha256", signingIdentitySha256.toJsonArray())
    put("installSource", installSource.toJson())
}

internal fun packageIdentityFromJson(json: JSONObject): PackageIdentity = PackageIdentity(
    packageName = json.getString("packageName"),
    versionCode = json.optLong("versionCode", 0L),
    versionName = json.nullableString("versionName"),
    signingIdentitySha256 = json.optJSONArray("signingIdentitySha256").toStringList(),
    installSource = json.optJSONObject("installSource")?.let(::installSourceFromJson)
        ?: InstallSourceIdentity(null, null, null, null, false)
)

internal fun InstallSourceIdentity.toJson(): JSONObject = JSONObject().apply {
    putNullable("installingPackageName", installingPackageName)
    putNullable("initiatingPackageName", initiatingPackageName)
    putNullable("originatingPackageName", originatingPackageName)
    putNullable("packageSource", packageSource)
    put("sourceReliable", sourceReliable)
}

internal fun installSourceFromJson(json: JSONObject): InstallSourceIdentity = InstallSourceIdentity(
    installingPackageName = json.nullableString("installingPackageName"),
    initiatingPackageName = json.nullableString("initiatingPackageName"),
    originatingPackageName = json.nullableString("originatingPackageName"),
    packageSource = json.nullableInt("packageSource"),
    sourceReliable = json.optBoolean("sourceReliable", false)
)

internal fun AppAuditResult.toJson(): JSONObject = JSONObject().apply {
    put("schemaVersion", schemaVersion)
    put("identity", identity.toJson())
    put("appLabel", appLabel)
    put("uid", uid)
    put("firstInstallTimeEpochMs", firstInstallTimeEpochMs)
    put("lastUpdateTimeEpochMs", lastUpdateTimeEpochMs)
    put("rawApplicationFlags", rawApplicationFlags)
    putNullable("rawPrivateFlags", rawPrivateFlags)
    put("isSystemApp", isSystemApp)
    put("isUpdatedSystemApp", isUpdatedSystemApp)
    put("isDebuggable", isDebuggable)
    put("isTestOnly", isTestOnly)
    put("isPersistent", isPersistent)
    put("allowsBackup", allowsBackup)
    put("usesCleartextTraffic", usesCleartextTraffic)
    put("systemFlags", systemFlags.toJsonArray())
    put("requestedPermissions", JSONArray().apply {
        requestedPermissions.forEach { permission ->
            put(JSONObject().apply {
                put("name", permission.name)
                put("granted", permission.granted)
                put("flags", permission.flags)
            })
        }
    })
    put("exportedComponents", JSONArray().apply {
        exportedComponents.forEach { component ->
            put(component.toJson())
        }
    })
    put("exportedComponentCount", exportedComponentCount)
    put("componentsTruncated", componentsTruncated)
    put("intentCapabilities", JSONArray().apply {
        intentCapabilities.forEach { capability ->
            put(capability.toJson())
        }
    })
    put("capabilitiesTruncated", capabilitiesTruncated)
    put("hasBrowsableActivity", hasBrowsableActivity)
    put("hasBrowserCapability", hasBrowserCapability)
    put("hasVpnService", hasVpnService)
    put("hasAccessibilityService", hasAccessibilityService)
    put("hasDeviceAdminReceiver", hasDeviceAdminReceiver)
    put("requestsInstallPackages", requestsInstallPackages)
    put("requestsSystemAlertWindow", requestsSystemAlertWindow)
    putNullable("overlayAppOpAllowed", overlayAppOpAllowed)
    put("queriesAllPackages", queriesAllPackages)
    put("requestsUsageAccess", requestsUsageAccess)
    put("requestsWriteSettings", requestsWriteSettings)
    put("requestsManageExternalStorage", requestsManageExternalStorage)
    put("requestsDeletePackages", requestsDeletePackages)
    put("hasNotificationListenerService", hasNotificationListenerService)
    put("hasInputMethodService", hasInputMethodService)
    put("hasBootReceiver", hasBootReceiver)
    put("bypassSignals", bypassSignals.toJsonArray())
    put("collectionErrors", collectionErrors.toJsonArray())
    put("collectedAtEpochMs", collectedAtEpochMs)
}

internal fun appAuditResultFromJson(json: JSONObject): AppAuditResult = AppAuditResult(
    schemaVersion = json.optInt("schemaVersion", 1),
    identity = packageIdentityFromJson(json.getJSONObject("identity")),
    appLabel = json.optString("appLabel", json.getJSONObject("identity").getString("packageName")),
    uid = json.optInt("uid", -1),
    firstInstallTimeEpochMs = json.optLong("firstInstallTimeEpochMs", 0L),
    lastUpdateTimeEpochMs = json.optLong("lastUpdateTimeEpochMs", 0L),
    rawApplicationFlags = json.optInt("rawApplicationFlags", 0),
    rawPrivateFlags = json.nullableInt("rawPrivateFlags"),
    isSystemApp = json.optBoolean("isSystemApp", false),
    isUpdatedSystemApp = json.optBoolean("isUpdatedSystemApp", false),
    isDebuggable = json.optBoolean("isDebuggable", false),
    isTestOnly = json.optBoolean("isTestOnly", false),
    isPersistent = json.optBoolean("isPersistent", false),
    allowsBackup = json.optBoolean("allowsBackup", false),
    usesCleartextTraffic = json.optBoolean("usesCleartextTraffic", false),
    systemFlags = json.optJSONArray("systemFlags").toStringList(),
    requestedPermissions = json.optJSONArray("requestedPermissions").toObjectList { item ->
        AuditedPermission(
            name = item.getString("name"),
            granted = item.optBoolean("granted", false),
            flags = item.optInt("flags", 0)
        )
    },
    exportedComponents = json.optJSONArray("exportedComponents").toObjectList(::auditedComponentFromJson),
    exportedComponentCount = json.optInt("exportedComponentCount", 0),
    componentsTruncated = json.optBoolean("componentsTruncated", false),
    intentCapabilities = json.optJSONArray("intentCapabilities").toObjectList(::intentCapabilityFromJson),
    capabilitiesTruncated = json.optBoolean("capabilitiesTruncated", false),
    hasBrowsableActivity = json.optBoolean("hasBrowsableActivity", false),
    hasBrowserCapability = json.optBoolean("hasBrowserCapability", false),
    hasVpnService = json.optBoolean("hasVpnService", false),
    hasAccessibilityService = json.optBoolean("hasAccessibilityService", false),
    hasDeviceAdminReceiver = json.optBoolean("hasDeviceAdminReceiver", false),
    requestsInstallPackages = json.optBoolean("requestsInstallPackages", false),
    requestsSystemAlertWindow = json.optBoolean("requestsSystemAlertWindow", false),
    overlayAppOpAllowed = json.nullableBoolean("overlayAppOpAllowed"),
    queriesAllPackages = json.optBoolean("queriesAllPackages", false),
    requestsUsageAccess = json.optBoolean("requestsUsageAccess", false),
    requestsWriteSettings = json.optBoolean("requestsWriteSettings", false),
    requestsManageExternalStorage = json.optBoolean("requestsManageExternalStorage", false),
    requestsDeletePackages = json.optBoolean("requestsDeletePackages", false),
    hasNotificationListenerService = json.optBoolean("hasNotificationListenerService", false),
    hasInputMethodService = json.optBoolean("hasInputMethodService", false),
    hasBootReceiver = json.optBoolean("hasBootReceiver", false),
    bypassSignals = json.optJSONArray("bypassSignals").toStringList(),
    collectionErrors = json.optJSONArray("collectionErrors").toStringList(),
    collectedAtEpochMs = json.optLong("collectedAtEpochMs", 0L)
)

internal fun AdmissionExternalResult.toJson(): JSONObject = JSONObject().apply {
    putNullable("identity", identity?.toJson())
    putNullable("webReputation", webReputation?.toJson())
    put("decision", decision.name)
    put("reasonCodes", reasonCodes.toJsonArray())
    put("summary", summary)
    put("evidence", JSONArray().apply { evidence.forEach { put(it.toJson()) } })
    put("evaluatedAtEpochMs", evaluatedAtEpochMs)
    putNullable("httpStatus", httpStatus)
    putNullable("errorCode", errorCode)
    put("retryable", retryable)
}

internal fun admissionExternalResultFromJson(json: JSONObject): AdmissionExternalResult = AdmissionExternalResult(
    identity = json.optJSONObject("identity")?.let(::packageIdentityFromJson),
    webReputation = json.optJSONObject("webReputation")?.let(::webReputationFromJson),
    decision = runCatching {
        ClassificationDecision.valueOf(json.optString("decision", ClassificationDecision.UNKNOWN.name))
    }.getOrDefault(ClassificationDecision.UNKNOWN),
    reasonCodes = json.optJSONArray("reasonCodes").toStringList(),
    summary = json.optString("summary", ""),
    evidence = json.optJSONArray("evidence").toObjectList(::externalEvidenceFromJson),
    evaluatedAtEpochMs = json.optLong("evaluatedAtEpochMs", 0L),
    httpStatus = json.nullableInt("httpStatus"),
    errorCode = json.nullableString("errorCode"),
    retryable = json.optBoolean("retryable", false)
)

private fun AuditedComponent.toJson(): JSONObject = JSONObject().apply {
    put("type", type)
    put("className", className)
    put("exported", exported)
    put("enabled", enabled)
    put("directBootAware", directBootAware)
    putNullable("permission", permission)
    put("authorities", authorities.toJsonArray())
    put("actions", actions.toJsonArray())
    put("categories", categories.toJsonArray())
    put("dataSchemes", dataSchemes.toJsonArray())
}

private fun auditedComponentFromJson(json: JSONObject): AuditedComponent = AuditedComponent(
    type = json.optString("type", "unknown"),
    className = json.optString("className", ""),
    exported = json.optBoolean("exported", false),
    enabled = json.optBoolean("enabled", true),
    directBootAware = json.optBoolean("directBootAware", false),
    permission = json.nullableString("permission"),
    authorities = json.optJSONArray("authorities").toStringList(),
    actions = json.optJSONArray("actions").toStringList(),
    categories = json.optJSONArray("categories").toStringList(),
    dataSchemes = json.optJSONArray("dataSchemes").toStringList()
)

private fun IntentCapability.toJson(): JSONObject = JSONObject().apply {
    put("capability", capability)
    put("componentType", componentType)
    put("componentName", componentName)
    put("actions", actions.toJsonArray())
    put("categories", categories.toJsonArray())
    put("dataSchemes", dataSchemes.toJsonArray())
}

private fun intentCapabilityFromJson(json: JSONObject): IntentCapability = IntentCapability(
    capability = json.optString("capability", "unknown"),
    componentType = json.optString("componentType", "unknown"),
    componentName = json.optString("componentName", ""),
    actions = json.optJSONArray("actions").toStringList(),
    categories = json.optJSONArray("categories").toStringList(),
    dataSchemes = json.optJSONArray("dataSchemes").toStringList()
)

private fun WebReputation.toJson(): JSONObject = JSONObject().apply {
    putNullable("verdict", verdict)
    putNullable("score", score)
    put("categories", categories.toJsonArray())
    putNullable("source", source)
    putNullable("checkedAtEpochMs", checkedAtEpochMs)
}

internal fun webReputationFromJson(json: JSONObject): WebReputation = WebReputation(
    verdict = json.nullableString("verdict"),
    score = json.nullableDouble("score"),
    categories = json.optJSONArray("categories").toStringList(),
    source = json.nullableString("source"),
    checkedAtEpochMs = json.nullableLong("checkedAtEpochMs")
)

private fun ExternalEvidence.toJson(): JSONObject = JSONObject().apply {
    put("type", type)
    putNullable("source", source)
    put("detail", detail)
    putNullable("reference", reference)
}

internal fun externalEvidenceFromJson(json: JSONObject): ExternalEvidence = ExternalEvidence(
    type = json.optString("type", "unknown"),
    source = json.nullableString("source"),
    detail = json.optString("detail", ""),
    reference = json.nullableString("reference")
)

internal fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

internal fun JSONObject.nullableString(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

internal fun JSONObject.nullableInt(key: String): Int? =
    if (has(key) && !isNull(key)) getInt(key) else null

internal fun JSONObject.nullableLong(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

internal fun JSONObject.nullableDouble(key: String): Double? =
    if (has(key) && !isNull(key)) getDouble(key) else null

internal fun JSONObject.nullableBoolean(key: String): Boolean? =
    if (has(key) && !isNull(key)) getBoolean(key) else null

internal fun List<String>.toJsonArray(): JSONArray = JSONArray().also { array ->
    forEach(array::put)
}

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val value = optString(index, "").trim()
            if (value.isNotEmpty()) add(value)
        }
    }
}

internal inline fun <T> JSONArray?.toObjectList(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let { add(transform(it)) }
        }
    }
}
