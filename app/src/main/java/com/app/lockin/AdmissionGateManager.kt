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
import java.security.MessageDigest

private const val TAG = "AdmissionGateManager"
private const val PREFS_NAME = "admission_gate_prefs"
private const val KEY_IS_GRANDFATHERED = "is_grandfathered_initialized"
private const val KEY_RECORDS_JSON = "admission_gate_records_json"

enum class AdmissionStatus {
    PENDING,
    APPROVED,
    DENIED
}

data class AdmissionGateRecord(
    val packageName: String,
    val status: AdmissionStatus,
    val signingCertDigest: String,
    val installerPackageName: String?,
    val versionCode: Long,
    val versionName: String?,
    val appLabel: String,
    val installedAtEpochMs: Long,
    val statusUpdatedAtEpochMs: Long
)

object AdmissionGateManager {

    @Synchronized
    fun isGrandfatheredInitialized(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_GRANDFATHERED, false)
    }

    @Synchronized
    fun initializeGrandfatheringIfNeeded(context: Context, excludePackageName: String? = null) {
        if (isGrandfatheredInitialized(context)) return

        Log.i(TAG, "Initializing Admission Gate baseline grandfathering (excluding: $excludePackageName)...")
        val pm = context.packageManager
        val recordsMap = loadRecordsInternal(context)
        val now = System.currentTimeMillis()

        val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_SIGNATURES)
        }

        var grandfatheredCount = 0
        installedPackages.forEach { packageInfo ->
            val pkgName = packageInfo.packageName
            if (shouldExemptPackage(context, packageInfo, excludePackageName)) {
                return@forEach
            }

            val appInfo = packageInfo.applicationInfo
            val label = appInfo?.loadLabel(pm)?.toString() ?: pkgName
            val vCode = getVersionCode(packageInfo)
            val vName = packageInfo.versionName
            val certDigest = getSigningCertDigest(packageInfo)
            val installer = getInstallerPackageName(pm, pkgName)

            val record = AdmissionGateRecord(
                packageName = pkgName,
                status = AdmissionStatus.APPROVED,
                signingCertDigest = certDigest,
                installerPackageName = installer,
                versionCode = vCode,
                versionName = vName,
                appLabel = label,
                installedAtEpochMs = now,
                statusUpdatedAtEpochMs = now
            )
            recordsMap[pkgName] = record
            grandfatheredCount++
        }

        saveRecordsInternal(context, recordsMap)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_GRANDFATHERED, true)
            .apply()

        Log.i(TAG, "Baseline grandfathering initialized. Grandfathered $grandfatheredCount existing apps as APPROVED.")
    }

    @Synchronized
    fun onPackageInstalled(context: Context, packageName: String, isReplacing: Boolean) {
        if (!isGrandfatheredInitialized(context)) {
            initializeGrandfatheringIfNeeded(context, excludePackageName = packageName)
        }

        val pm = context.packageManager
        val packageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package $packageName not found during onPackageInstalled.")
            return
        }

        if (shouldExemptPackage(context, packageInfo, excludePackageName = null)) {
            Log.d(TAG, "Package $packageName is exempt from Admission Gate.")
            return
        }

        val recordsMap = loadRecordsInternal(context)
        val existingRecord = recordsMap[packageName]
        val now = System.currentTimeMillis()

        val certDigest = getSigningCertDigest(packageInfo)
        val installer = getInstallerPackageName(pm, packageName)
        val appInfo = packageInfo.applicationInfo
        val label = appInfo?.loadLabel(pm)?.toString() ?: packageName
        val vCode = getVersionCode(packageInfo)
        val vName = packageInfo.versionName

        if (isReplacing && existingRecord != null) {
            val isDigestMatch = existingRecord.signingCertDigest.isNotBlank() && existingRecord.signingCertDigest == certDigest
            val isInstallerMatch = existingRecord.installerPackageName == installer

            if (isDigestMatch && isInstallerMatch) {
                Log.i(TAG, "Package $packageName updated with matching identity. Remaining in status: ${existingRecord.status}.")
                val updatedRecord = existingRecord.copy(
                    versionCode = vCode,
                    versionName = vName,
                    statusUpdatedAtEpochMs = now
                )
                recordsMap[packageName] = updatedRecord
                saveRecordsInternal(context, recordsMap)

                // Preserve existing suspension for PENDING or DENIED
                if (existingRecord.status == AdmissionStatus.PENDING || existingRecord.status == AdmissionStatus.DENIED) {
                    suspendPackageIfNeeded(context, packageName, true)
                }
                return
            } else {
                Log.w(TAG, "Package $packageName update mismatch (digestMatch=$isDigestMatch, installerMatch=$isInstallerMatch). Transitioning to PENDING and suspending.")
            }
        }

        // Fresh install OR update identity mismatch -> 1. Suspend 2. Persist PENDING
        suspendPackageIfNeeded(context, packageName, true)

        val newRecord = AdmissionGateRecord(
            packageName = packageName,
            status = AdmissionStatus.PENDING,
            signingCertDigest = certDigest,
            installerPackageName = installer,
            versionCode = vCode,
            versionName = vName,
            appLabel = label,
            installedAtEpochMs = existingRecord?.installedAtEpochMs ?: now,
            statusUpdatedAtEpochMs = now
        )
        recordsMap[packageName] = newRecord
        saveRecordsInternal(context, recordsMap)
    }

    @Synchronized
    fun onPackageRemoved(context: Context, packageName: String, isReplacing: Boolean) {
        if (isReplacing) {
            Log.d(TAG, "Package $packageName removed for replacement. Keeping admission record.")
            return
        }

        val recordsMap = loadRecordsInternal(context)
        if (recordsMap.remove(packageName) != null) {
            saveRecordsInternal(context, recordsMap)
            Log.i(TAG, "Package $packageName uninstalled. Removed admission record.")
        }
    }

    @Synchronized
    fun reconcileState(context: Context) {
        if (!isGrandfatheredInitialized(context)) {
            initializeGrandfatheringIfNeeded(context)
            return
        }

        Log.i(TAG, "Running Admission Gate state reconciliation...")
        val pm = context.packageManager
        val recordsMap = loadRecordsInternal(context)
        val now = System.currentTimeMillis()

        val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_SIGNATURES)
        }

        val installedMap = installedPackages.associateBy { it.packageName }

        // 1. Re-evaluate recorded apps
        recordsMap.values.toList().forEach { record ->
            val pkgName = record.packageName
            val installedInfo = installedMap[pkgName]

            if (installedInfo == null) {
                // Uninstalled while inactive -> remove record
                Log.i(TAG, "Reconciliation: $pkgName no longer installed. Removing record.")
                recordsMap.remove(pkgName)
                return@forEach
            }

            if (shouldExemptPackage(context, installedInfo, excludePackageName = null)) {
                recordsMap.remove(pkgName)
                return@forEach
            }

            when (record.status) {
                AdmissionStatus.PENDING, AdmissionStatus.DENIED -> {
                    suspendPackageIfNeeded(context, pkgName, true)
                }
                AdmissionStatus.APPROVED -> {
                    val currentCertDigest = getSigningCertDigest(installedInfo)
                    if (record.signingCertDigest.isNotBlank() && record.signingCertDigest != currentCertDigest) {
                        Log.w(TAG, "Reconciliation: Signature mismatch for $pkgName! Reverting to PENDING and suspending.")
                        suspendPackageIfNeeded(context, pkgName, true)
                        val updatedRecord = record.copy(
                            status = AdmissionStatus.PENDING,
                            statusUpdatedAtEpochMs = now
                        )
                        recordsMap[pkgName] = updatedRecord
                    } else {
                        // Ensure approved app is NOT suspended
                        suspendPackageIfNeeded(context, pkgName, false)
                    }
                }
            }
        }

        // 2. Discover unrecorded non-system apps installed while inactive
        installedPackages.forEach { packageInfo ->
            val pkgName = packageInfo.packageName
            if (shouldExemptPackage(context, packageInfo, excludePackageName = null)) return@forEach

            if (!recordsMap.containsKey(pkgName)) {
                Log.w(TAG, "Reconciliation: Discovered unrecorded non-system app $pkgName. Marking PENDING and suspending.")
                val appInfo = packageInfo.applicationInfo
                val label = appInfo?.loadLabel(pm)?.toString() ?: pkgName
                val vCode = getVersionCode(packageInfo)
                val vName = packageInfo.versionName
                val certDigest = getSigningCertDigest(packageInfo)
                val installer = getInstallerPackageName(pm, pkgName)

                suspendPackageIfNeeded(context, pkgName, true)

                val record = AdmissionGateRecord(
                    packageName = pkgName,
                    status = AdmissionStatus.PENDING,
                    signingCertDigest = certDigest,
                    installerPackageName = installer,
                    versionCode = vCode,
                    versionName = vName,
                    appLabel = label,
                    installedAtEpochMs = now,
                    statusUpdatedAtEpochMs = now
                )
                recordsMap[pkgName] = record
            }
        }

        saveRecordsInternal(context, recordsMap)
        Log.i(TAG, "Admission Gate reconciliation completed. Total records: ${recordsMap.size}")
    }

    @Synchronized
    fun approvePackage(context: Context, packageName: String): Boolean {
        if (AppBlocklistManager.isPackageBlocked(context, packageName)) {
            Log.w(TAG, "Cannot approve $packageName: package is hard-blocked by AppBlocklistManager.")
            return false
        }

        val recordsMap = loadRecordsInternal(context)
        val record = recordsMap[packageName]
        if (record == null) {
            Log.w(TAG, "Cannot approve $packageName: record not found.")
            return false
        }

        val pm = context.packageManager
        val packageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot approve $packageName: package info query failed: ${e.message}")
            return false
        }

        val currentDigest = getSigningCertDigest(packageInfo)
        val currentInstaller = getInstallerPackageName(pm, packageName)
        val vCode = getVersionCode(packageInfo)
        val vName = packageInfo.versionName
        val now = System.currentTimeMillis()

        val isDigestMatch = record.signingCertDigest.isNotBlank() && record.signingCertDigest == currentDigest
        val isInstallerMatch = record.installerPackageName == currentInstaller

        if (!isDigestMatch || !isInstallerMatch) {
            Log.w(TAG, "Cannot approve $packageName: identity changed since record creation (digestMatch=$isDigestMatch, installerMatch=$isInstallerMatch).")
            suspendPackageIfNeeded(context, packageName, true)

            val refreshedRecord = record.copy(
                status = AdmissionStatus.PENDING,
                signingCertDigest = currentDigest,
                installerPackageName = currentInstaller,
                versionCode = vCode,
                versionName = vName,
                statusUpdatedAtEpochMs = now
            )
            recordsMap[packageName] = refreshedRecord
            saveRecordsInternal(context, recordsMap)
            return false
        }

        // Enforcement order for Approval:
        // 1. Unsuspend package
        suspendPackageIfNeeded(context, packageName, false)

        // 2. Persist APPROVED state
        val updatedRecord = record.copy(
            status = AdmissionStatus.APPROVED,
            signingCertDigest = currentDigest,
            installerPackageName = currentInstaller,
            versionCode = vCode,
            versionName = vName,
            statusUpdatedAtEpochMs = now
        )
        recordsMap[packageName] = updatedRecord
        saveRecordsInternal(context, recordsMap)

        Log.i(TAG, "Successfully APPROVED and unsuspended package: $packageName")
        return true
    }

    @Synchronized
    fun denyPackage(context: Context, packageName: String): Boolean {
        val recordsMap = loadRecordsInternal(context)
        val record = recordsMap[packageName]
        if (record == null) {
            Log.w(TAG, "Cannot deny $packageName: record not found.")
            return false
        }

        // Enforcement order for Denial:
        // 1. Suspend package
        suspendPackageIfNeeded(context, packageName, true)

        // 2. Persist DENIED state
        val updatedRecord = record.copy(
            status = AdmissionStatus.DENIED,
            statusUpdatedAtEpochMs = System.currentTimeMillis()
        )
        recordsMap[packageName] = updatedRecord
        saveRecordsInternal(context, recordsMap)

        Log.i(TAG, "Successfully DENIED and suspended package: $packageName")
        return true
    }

    @Synchronized
    fun getAllRecords(context: Context): List<AdmissionGateRecord> {
        return loadRecordsInternal(context).values.toList()
    }

    @Synchronized
    fun getPendingRecords(context: Context): List<AdmissionGateRecord> {
        return loadRecordsInternal(context).values.filter { it.status == AdmissionStatus.PENDING }
    }

    private fun suspendPackageIfNeeded(context: Context, packageName: String, suspend: Boolean) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Not Device Owner. Cannot change suspension for $packageName.")
            return
        }

        val adminComponent = LockInAdminReceiver.getComponentName(context)
        try {
            val unsuspendable = dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), suspend)
            if (unsuspendable != null && unsuspendable.contains(packageName)) {
                Log.e(TAG, "DevicePolicyManager reported $packageName as UNSUSPENDABLE (suspend=$suspend)!")
            } else {
                Log.i(TAG, "Successfully updated suspension for $packageName -> suspend=$suspend")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while changing suspension for $packageName (suspend=$suspend): ${e.message}", e)
        }
    }

    private fun shouldExemptPackage(context: Context, packageInfo: PackageInfo, excludePackageName: String?): Boolean {
        val pkgName = packageInfo.packageName

        // 1. Exclude triggering new package if grandfathering baseline is running
        if (excludePackageName != null && pkgName == excludePackageName) {
            return true
        }

        // 2. Exclude Lock-In itself
        if (pkgName == context.packageName) {
            return true
        }

        // 3. Exclude Play Store and Google Play Services
        if (pkgName == "com.android.vending" || pkgName == "com.google.android.gms" || pkgName == "com.google.android.gsf") {
            return true
        }

        // 4. Exclude hard-blocked packages (managed by AppBlocklistManager)
        if (AppBlocklistManager.isPackageBlocked(context, pkgName)) {
            return true
        }

        // 5. Exclude System apps
        val appInfo = packageInfo.applicationInfo ?: return false
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)

        return isSystem
    }

    private fun getSigningCertDigest(packageInfo: PackageInfo): String {
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = packageInfo.signingInfo ?: return ""
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (!signatures.isNullOrEmpty()) {
                val bytes = signatures[0].toByteArray()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                bytesToHex(digest)
            } else ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute signing cert digest for ${packageInfo.packageName}: ${e.message}")
            ""
        }
    }

    private fun getInstallerPackageName(pm: PackageManager, packageName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(packageName)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getVersionCode(packageInfo: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun loadRecordsInternal(context: Context): MutableMap<String, AdmissionGateRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_RECORDS_JSON, null) ?: return mutableMapOf()

        val map = mutableMapOf<String, AdmissionGateRecord>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val record = AdmissionGateRecord(
                    packageName = obj.getString("packageName"),
                    status = AdmissionStatus.valueOf(obj.getString("status")),
                    signingCertDigest = obj.optString("signingCertDigest", ""),
                    installerPackageName = if (obj.has("installerPackageName") && !obj.isNull("installerPackageName")) obj.getString("installerPackageName") else null,
                    versionCode = obj.optLong("versionCode", 0L),
                    versionName = if (obj.has("versionName") && !obj.isNull("versionName")) obj.getString("versionName") else null,
                    appLabel = obj.optString("appLabel", obj.getString("packageName")),
                    installedAtEpochMs = obj.optLong("installedAtEpochMs", 0L),
                    statusUpdatedAtEpochMs = obj.optLong("statusUpdatedAtEpochMs", 0L)
                )
                map[record.packageName] = record
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing admission gate records JSON: ${e.message}", e)
        }
        return map
    }

    private fun saveRecordsInternal(context: Context, recordsMap: Map<String, AdmissionGateRecord>) {
        val jsonArray = JSONArray()
        recordsMap.values.forEach { record ->
            val obj = JSONObject().apply {
                put("packageName", record.packageName)
                put("status", record.status.name)
                put("signingCertDigest", record.signingCertDigest)
                put("installerPackageName", record.installerPackageName ?: JSONObject.NULL)
                put("versionCode", record.versionCode)
                put("versionName", record.versionName ?: JSONObject.NULL)
                put("appLabel", record.appLabel)
                put("installedAtEpochMs", record.installedAtEpochMs)
                put("statusUpdatedAtEpochMs", record.statusUpdatedAtEpochMs)
            }
            jsonArray.put(obj)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS_JSON, jsonArray.toString())
            .apply()
    }
}
