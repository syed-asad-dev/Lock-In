package com.app.lockin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

private const val TAG = "AccessibilityLockManager"
private const val STAY_FOCUSED_PACKAGE = "com.stayfocused"

/**
 * Manages DPM permitted accessibility service restrictions for Lock-In and Stay Focused:
 * - Restricts permitted non-system accessibility services to Stay Focused and Lock-In ONLY.
 * - Non-whitelisted 3rd-party accessibility services are prevented from being enabled.
 *
 * Safety requirements strictly enforced:
 * - Does NOT apply policy if Stay Focused is not installed.
 * - Does NOT pass an empty list to setPermittedAccessibilityServices.
 * - Preserves system accessibility services (TalkBack, etc.).
 */
object AccessibilityLockManager {

    fun applyAccessibilityLock(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null) {
            Log.w(TAG, "DevicePolicyManager is not available on this device.")
            return false
        }

        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "App is not Device Owner. Cannot enforce accessibility restrictions.")
            return false
        }

        if (!isPackageInstalled(context, STAY_FOCUSED_PACKAGE)) {
            Log.w(
                TAG,
                "Stay Focused ($STAY_FOCUSED_PACKAGE) is not installed. Skipping accessibility policy enforcement."
            )
            return false
        }

        val adminComponent = LockInAdminReceiver.getComponentName(context)

        return try {
            // Allow com.stayfocused and com.app.lockin as non-system accessibility services
            val permittedServices = listOf(STAY_FOCUSED_PACKAGE, context.packageName)
            val permittedSetSuccess = dpm.setPermittedAccessibilityServices(
                adminComponent,
                permittedServices
            )
            Log.i(
                TAG,
                "setPermittedAccessibilityServices result for $permittedServices: $permittedSetSuccess"
            )
            permittedSetSuccess
        } catch (e: SecurityException) {
            Log.e(
                TAG,
                "SecurityException while applying accessibility lock: ${e.message}",
                e
            )
            false
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Exception while applying accessibility lock: ${e.message}",
                e
            )
            false
        }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking package installation for $packageName: ${e.message}")
            false
        }
    }
}
