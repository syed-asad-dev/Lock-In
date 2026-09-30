package com.app.lockin

import android.accessibilityservice.AccessibilityService
import android.app.AppOpsManager
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.util.Log
import java.security.MessageDigest
import java.util.Locale

private const val AUDIT_TAG = "AppAuditCollector"
private const val MAX_AUDITED_COMPONENTS = 256
private const val MAX_INTENT_CAPABILITIES = 128

/** Package-manager flag set used for both install handling and local auditing. */
internal object AdmissionPackageQueries {
    fun packageInfoFlags(): Int {
        var flags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_META_DATA
        flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            flags or PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            flags or PackageManager.GET_SIGNATURES
        }
        return flags
    }

    fun getPackageInfo(pm: PackageManager, packageName: String): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(packageInfoFlags().toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, packageInfoFlags())
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            Log.w(AUDIT_TAG, "Unable to inspect package $packageName: ${e.message}")
            null
        }
    }
}

internal object PackageIdentityReader {
    fun fromPackageInfo(context: Context, packageInfo: PackageInfo): PackageIdentity {
        val packageName = packageInfo.packageName
        return PackageIdentity(
            packageName = packageName,
            versionCode = versionCode(packageInfo),
            versionName = packageInfo.versionName,
            signingIdentitySha256 = signingIdentitySha256(packageInfo),
            installSource = installSource(context.packageManager, packageName)
        )
    }

    fun signingIdentitySha256(packageInfo: PackageInfo): List<String> {
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = packageInfo.signingInfo ?: return emptyList()
                // apkContentsSigners represents the currently installed signer set.
                signingInfo.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            signatures.orEmpty()
                .map { signature ->
                    MessageDigest.getInstance("SHA-256")
                        .digest(signature.toByteArray())
                        .toHex()
                }
                .distinct()
                .sorted()
        } catch (e: Exception) {
            Log.w(AUDIT_TAG, "Unable to read signing identity for ${packageInfo.packageName}: ${e.message}")
            emptyList()
        }
    }

    fun installSource(pm: PackageManager, packageName: String): InstallSourceIdentity {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val installer = try {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(packageName)
            } catch (_: Exception) {
                null
            }
            return InstallSourceIdentity(
                installingPackageName = installer,
                initiatingPackageName = null,
                originatingPackageName = null,
                packageSource = null,
                sourceReliable = !installer.isNullOrBlank()
            )
        }

        return try {
            val source = pm.getInstallSourceInfo(packageName)
            val packageSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                source.packageSource
            } else {
                null
            }
            val installing = source.installingPackageName
            val initiating = source.initiatingPackageName
            val originating = source.originatingPackageName
            InstallSourceIdentity(
                installingPackageName = installing,
                initiatingPackageName = initiating,
                originatingPackageName = originating,
                packageSource = packageSource,
                sourceReliable = !installing.isNullOrBlank() ||
                    !initiating.isNullOrBlank() ||
                    !originating.isNullOrBlank() ||
                    (packageSource != null && packageSource > 0)
            )
        } catch (e: Exception) {
            Log.w(AUDIT_TAG, "Unable to read install source for $packageName: ${e.message}")
            InstallSourceIdentity(null, null, null, null, false)
        }
    }

    fun versionCode(packageInfo: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
}

object AppAuditCollector {
    /** Collects a complete local audit without making any network calls. */
    fun collect(context: Context, packageName: String): AppAuditResult? {
        val packageInfo = AdmissionPackageQueries.getPackageInfo(context.packageManager, packageName)
            ?: return null
        return collect(context, packageInfo)
    }

    fun collect(context: Context, packageInfo: PackageInfo): AppAuditResult {
        val pm = context.packageManager
        val packageName = packageInfo.packageName
        val appInfo = packageInfo.applicationInfo ?: ApplicationInfo().apply {
            this.packageName = packageName
        }
        val identity = PackageIdentityReader.fromPackageInfo(context, packageInfo)
        val errors = mutableListOf<String>()

        val requestedPermissions = packageInfo.requestedPermissions.orEmpty()
            .mapIndexed { index, permission ->
                val flags = packageInfo.requestedPermissionsFlags?.getOrNull(index) ?: 0
                AuditedPermission(
                    name = permission,
                    granted = try {
                        pm.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED
                    } catch (e: Exception) {
                        errors += "PERMISSION_CHECK_FAILED:$permission"
                        false
                    },
                    flags = flags
                )
            }
            .sortedBy { it.name }

        val allComponents = mutableListOf<AuditedComponent>()
        packageInfo.activities.orEmpty().forEach {
            allComponents += componentToAudit("activity", it)
        }
        packageInfo.services.orEmpty().forEach {
            allComponents += componentToAudit("service", it)
        }
        packageInfo.receivers.orEmpty().forEach {
            allComponents += componentToAudit("receiver", it)
        }
        packageInfo.providers.orEmpty().forEach {
            allComponents += componentToAudit("provider", it)
        }
        val componentsTruncated = allComponents.size > MAX_AUDITED_COMPONENTS
        val auditedComponents = allComponents
            .sortedWith(compareBy<AuditedComponent> { it.type }.thenBy { it.className })
            .take(MAX_AUDITED_COMPONENTS)

        val capabilities = mutableListOf<IntentCapability>()
        fun collectCapability(spec: CapabilitySpec) {
            try {
                val results = when (spec.queryType) {
                    QueryType.ACTIVITY -> queryActivities(pm, spec.intent)
                    QueryType.SERVICE -> queryServices(pm, spec.intent)
                    QueryType.RECEIVER -> queryReceivers(pm, spec.intent)
                }
                results.filter { resolvePackageName(it) == packageName }.forEach { resolveInfo ->
                    val componentName = resolveComponentName(resolveInfo, spec.queryType)
                    if (!componentName.isNullOrBlank()) {
                        val filter = resolveInfo.filter
                        capabilities += IntentCapability(
                            capability = spec.name,
                            componentType = spec.queryType.name.lowercase(Locale.ROOT),
                            componentName = componentName,
                            actions = filterActions(filter),
                            categories = filterCategories(filter),
                            dataSchemes = filterDataSchemes(filter)
                        )
                    }
                }
            } catch (e: Exception) {
                errors += "CAPABILITY_QUERY_FAILED:${spec.name}"
            }
        }

        capabilitySpecs().forEach(::collectCapability)
        val distinctCapabilities = capabilities.distinctBy {
            listOf(it.capability, it.componentType, it.componentName, it.actions, it.categories, it.dataSchemes)
        }
        val capabilitiesTruncated = distinctCapabilities.size > MAX_INTENT_CAPABILITIES
        val auditedCapabilities = distinctCapabilities.take(MAX_INTENT_CAPABILITIES)

        val hasBrowsableActivity = auditedCapabilities.any { it.capability == "BROWSER_BROWSABLE" }
        val hasBrowserCapability = auditedCapabilities.any { it.capability == "BROWSER_HTTP" }
        val hasVpnService = auditedCapabilities.any { it.capability == "VPN_SERVICE" } ||
            packageInfo.services.orEmpty().any { it.permission == android.Manifest.permission.BIND_VPN_SERVICE }
        val hasAccessibilityService = auditedCapabilities.any { it.capability == "ACCESSIBILITY_SERVICE" } ||
            packageInfo.services.orEmpty().any {
                it.permission == android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE
            }
        val hasDeviceAdminReceiver = auditedCapabilities.any { it.capability == "DEVICE_ADMIN" } ||
            packageInfo.receivers.orEmpty().any { it.permission == android.Manifest.permission.BIND_DEVICE_ADMIN }
        val hasNotificationListenerService = auditedCapabilities.any {
            it.capability == "NOTIFICATION_LISTENER_SERVICE"
        } || packageInfo.services.orEmpty().any {
            it.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
        }
        val hasInputMethodService = auditedCapabilities.any { it.capability == "INPUT_METHOD_SERVICE" } ||
            packageInfo.services.orEmpty().any {
                it.permission == "android.permission.BIND_INPUT_METHOD"
            }
        val hasBootReceiver = auditedCapabilities.any { it.capability == "BOOT_RECEIVER" }

        val permissionNames = requestedPermissions.map { it.name }.toSet()
        val requestsInstallPackages = "android.permission.REQUEST_INSTALL_PACKAGES" in permissionNames
        val requestsSystemAlertWindow = android.Manifest.permission.SYSTEM_ALERT_WINDOW in permissionNames
        val queriesAllPackages = android.Manifest.permission.QUERY_ALL_PACKAGES in permissionNames
        val requestsUsageAccess = "android.permission.PACKAGE_USAGE_STATS" in permissionNames
        val requestsWriteSettings = android.Manifest.permission.WRITE_SETTINGS in permissionNames
        val requestsManageExternalStorage = "android.permission.MANAGE_EXTERNAL_STORAGE" in permissionNames
        val requestsDeletePackages = "android.permission.REQUEST_DELETE_PACKAGES" in permissionNames
        val overlayAppOpAllowed = if (requestsSystemAlertWindow) {
            checkOverlayAppOp(context, appInfo.uid, packageName)
        } else {
            null
        }

        val systemFlags = buildSystemFlags(appInfo)
        val usesCleartextTraffic = appInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0
        val isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val isUpdatedSystemApp = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        val isDebuggable = appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val isTestOnly = appInfo.flags and ApplicationInfo.FLAG_TEST_ONLY != 0
        val isPersistent = appInfo.flags and ApplicationInfo.FLAG_PERSISTENT != 0
        val allowsBackup = appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0

        val bypassSignals = buildBypassSignals(
            isSystemApp = isSystemApp,
            isUpdatedSystemApp = isUpdatedSystemApp,
            isDebuggable = isDebuggable,
            isTestOnly = isTestOnly,
            hasBrowserCapability = hasBrowserCapability,
            hasVpnService = hasVpnService,
            hasAccessibilityService = hasAccessibilityService,
            hasDeviceAdminReceiver = hasDeviceAdminReceiver,
            requestsInstallPackages = requestsInstallPackages,
            requestsSystemAlertWindow = requestsSystemAlertWindow,
            queriesAllPackages = queriesAllPackages,
            requestsUsageAccess = requestsUsageAccess,
            requestsWriteSettings = requestsWriteSettings,
            requestsManageExternalStorage = requestsManageExternalStorage,
            requestsDeletePackages = requestsDeletePackages,
            hasNotificationListenerService = hasNotificationListenerService,
            hasInputMethodService = hasInputMethodService,
            hasBootReceiver = hasBootReceiver,
            usesCleartextTraffic = usesCleartextTraffic,
            exportedComponentCount = allComponents.count { it.exported }
        )

        return AppAuditResult(
            identity = identity,
            appLabel = try {
                appInfo.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
            } catch (e: Exception) {
                errors += "LABEL_READ_FAILED"
                packageName
            },
            uid = appInfo.uid,
            firstInstallTimeEpochMs = packageInfo.firstInstallTime,
            lastUpdateTimeEpochMs = packageInfo.lastUpdateTime,
            rawApplicationFlags = appInfo.flags,
            // ApplicationInfo.privateFlags is hidden from the public SDK;
            // rawApplicationFlags plus the named systemFlags above are the
            // portable, policy-relevant representation.
            rawPrivateFlags = null,
            isSystemApp = isSystemApp,
            isUpdatedSystemApp = isUpdatedSystemApp,
            isDebuggable = isDebuggable,
            isTestOnly = isTestOnly,
            isPersistent = isPersistent,
            allowsBackup = allowsBackup,
            usesCleartextTraffic = usesCleartextTraffic,
            systemFlags = systemFlags,
            requestedPermissions = requestedPermissions,
            exportedComponents = auditedComponents,
            exportedComponentCount = allComponents.count { it.exported },
            componentsTruncated = componentsTruncated,
            intentCapabilities = auditedCapabilities,
            capabilitiesTruncated = capabilitiesTruncated,
            hasBrowsableActivity = hasBrowsableActivity,
            hasBrowserCapability = hasBrowserCapability,
            hasVpnService = hasVpnService,
            hasAccessibilityService = hasAccessibilityService,
            hasDeviceAdminReceiver = hasDeviceAdminReceiver,
            requestsInstallPackages = requestsInstallPackages,
            requestsSystemAlertWindow = requestsSystemAlertWindow,
            overlayAppOpAllowed = overlayAppOpAllowed,
            queriesAllPackages = queriesAllPackages,
            requestsUsageAccess = requestsUsageAccess,
            requestsWriteSettings = requestsWriteSettings,
            requestsManageExternalStorage = requestsManageExternalStorage,
            requestsDeletePackages = requestsDeletePackages,
            hasNotificationListenerService = hasNotificationListenerService,
            hasInputMethodService = hasInputMethodService,
            hasBootReceiver = hasBootReceiver,
            bypassSignals = bypassSignals,
            collectionErrors = errors.distinct(),
            collectedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun componentToAudit(type: String, component: ComponentInfo): AuditedComponent {
        val permission = when (component) {
            is ActivityInfo -> component.permission
            is ServiceInfo -> component.permission
            is ProviderInfo -> component.readPermission ?: component.writePermission
            else -> null
        }
        val authorities = if (component is ProviderInfo) {
            component.authority.orEmpty().split(';').filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        return AuditedComponent(
            type = type,
            className = component.name,
            exported = component.exported,
            enabled = component.enabled,
            directBootAware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) component.directBootAware else false,
            permission = permission,
            authorities = authorities
        )
    }

    private fun buildSystemFlags(appInfo: ApplicationInfo): List<String> = buildList {
        fun addIf(condition: Boolean, name: String) {
            if (condition) add(name)
        }
        addIf(appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0, "SYSTEM")
        addIf(appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0, "UPDATED_SYSTEM")
        addIf(appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0, "DEBUGGABLE")
        addIf(appInfo.flags and ApplicationInfo.FLAG_TEST_ONLY != 0, "TEST_ONLY")
        addIf(appInfo.flags and ApplicationInfo.FLAG_PERSISTENT != 0, "PERSISTENT")
        addIf(appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0, "ALLOW_BACKUP")
        addIf(appInfo.flags and ApplicationInfo.FLAG_EXTERNAL_STORAGE != 0, "EXTERNAL_STORAGE")
        addIf(appInfo.flags and ApplicationInfo.FLAG_HAS_CODE != 0, "HAS_CODE")
        addIf(appInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP != 0, "LARGE_HEAP")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            addIf(appInfo.flags and ApplicationInfo.FLAG_SUSPENDED != 0, "SUSPENDED")
        }
    }

    private fun buildBypassSignals(
        isSystemApp: Boolean,
        isUpdatedSystemApp: Boolean,
        isDebuggable: Boolean,
        isTestOnly: Boolean,
        hasBrowserCapability: Boolean,
        hasVpnService: Boolean,
        hasAccessibilityService: Boolean,
        hasDeviceAdminReceiver: Boolean,
        requestsInstallPackages: Boolean,
        requestsSystemAlertWindow: Boolean,
        queriesAllPackages: Boolean,
        requestsUsageAccess: Boolean,
        requestsWriteSettings: Boolean,
        requestsManageExternalStorage: Boolean,
        requestsDeletePackages: Boolean,
        hasNotificationListenerService: Boolean,
        hasInputMethodService: Boolean,
        hasBootReceiver: Boolean,
        usesCleartextTraffic: Boolean,
        exportedComponentCount: Int
    ): List<String> = buildList {
        fun addIf(condition: Boolean, signal: String) {
            if (condition) add(signal)
        }
        addIf(isSystemApp, "SYSTEM_APP")
        addIf(isUpdatedSystemApp, "UPDATED_SYSTEM_APP")
        addIf(isDebuggable, "DEBUGGABLE")
        addIf(isTestOnly, "TEST_ONLY")
        addIf(hasBrowserCapability, "BROWSER_BROWSABLE")
        addIf(hasVpnService, "VPN_SERVICE")
        addIf(hasAccessibilityService, "ACCESSIBILITY_SERVICE")
        addIf(hasDeviceAdminReceiver, "DEVICE_ADMIN_RECEIVER")
        addIf(requestsInstallPackages, "REQUEST_INSTALL_PACKAGES")
        addIf(requestsSystemAlertWindow, "SYSTEM_ALERT_WINDOW")
        addIf(queriesAllPackages, "QUERY_ALL_PACKAGES")
        addIf(requestsUsageAccess, "PACKAGE_USAGE_STATS")
        addIf(requestsWriteSettings, "WRITE_SETTINGS")
        addIf(requestsManageExternalStorage, "MANAGE_EXTERNAL_STORAGE")
        addIf(requestsDeletePackages, "REQUEST_DELETE_PACKAGES")
        addIf(hasNotificationListenerService, "NOTIFICATION_LISTENER")
        addIf(hasInputMethodService, "INPUT_METHOD_SERVICE")
        addIf(hasBootReceiver, "BOOT_RECEIVER")
        addIf(usesCleartextTraffic, "USES_CLEARTEXT_TRAFFIC")
        if (exportedComponentCount > 0) add("EXPORTED_COMPONENTS:$exportedComponentCount")
    }.distinct()

    private fun checkOverlayAppOp(context: Context, uid: Int, packageName: String): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return null
            when (appOps.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, packageName)) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_IGNORED,
                AppOpsManager.MODE_ERRORED -> false
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private enum class QueryType { ACTIVITY, SERVICE, RECEIVER }

    private data class CapabilitySpec(
        val name: String,
        val intent: Intent,
        val queryType: QueryType
    )

    private fun capabilitySpecs(): List<CapabilitySpec> = listOf(
        CapabilitySpec(
            name = "BROWSER_BROWSABLE",
            intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            },
            queryType = QueryType.ACTIVITY
        ),
        CapabilitySpec(
            name = "BROWSER_HTTP",
            intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            },
            queryType = QueryType.ACTIVITY
        ),
        CapabilitySpec(VpnService.SERVICE_INTERFACE, Intent(VpnService.SERVICE_INTERFACE), QueryType.SERVICE)
            .copy(name = "VPN_SERVICE"),
        CapabilitySpec(
            name = "ACCESSIBILITY_SERVICE",
            intent = Intent(AccessibilityService.SERVICE_INTERFACE),
            queryType = QueryType.SERVICE
        ),
        CapabilitySpec(
            name = "DEVICE_ADMIN",
            intent = Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED),
            queryType = QueryType.RECEIVER
        ),
        CapabilitySpec(
            name = "NOTIFICATION_LISTENER_SERVICE",
            intent = Intent("android.service.notification.NotificationListenerService"),
            queryType = QueryType.SERVICE
        ),
        CapabilitySpec(
            name = "INPUT_METHOD_SERVICE",
            intent = Intent("android.view.InputMethod"),
            queryType = QueryType.SERVICE
        ),
        CapabilitySpec(
            name = "BOOT_RECEIVER",
            intent = Intent(Intent.ACTION_BOOT_COMPLETED),
            queryType = QueryType.RECEIVER
        )
    )

    private fun queryActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }

    private fun queryServices(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
        }

    private fun queryReceivers(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryBroadcastReceivers(intent, PackageManager.MATCH_ALL)
        }

    private fun resolvePackageName(resolveInfo: ResolveInfo): String? =
        resolveInfo.activityInfo?.packageName
            ?: resolveInfo.serviceInfo?.packageName
            ?: resolveInfo.providerInfo?.packageName

    private fun resolveComponentName(resolveInfo: ResolveInfo, queryType: QueryType): String? = when (queryType) {
        QueryType.ACTIVITY -> resolveInfo.activityInfo?.name
        QueryType.SERVICE -> resolveInfo.serviceInfo?.name
        QueryType.RECEIVER -> resolveInfo.activityInfo?.name
    }

    private fun filterActions(filter: IntentFilter?): List<String> = if (filter == null) {
        emptyList()
    } else {
        buildList { for (index in 0 until filter.countActions()) add(filter.getAction(index)) }
    }

    private fun filterCategories(filter: IntentFilter?): List<String> = if (filter == null) {
        emptyList()
    } else {
        buildList { for (index in 0 until filter.countCategories()) add(filter.getCategory(index)) }
    }

    private fun filterDataSchemes(filter: IntentFilter?): List<String> = if (filter == null) {
        emptyList()
    } else {
        buildList { for (index in 0 until filter.countDataSchemes()) add(filter.getDataScheme(index)) }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
