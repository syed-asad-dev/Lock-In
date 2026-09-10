package com.app.lockin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log

private const val TAG = "DnsLockManager"
private const val NEXT_DNS_HOST = "f1969c.dns.nextdns.io"

/**
 * Applies global Private DNS pointing to NextDNS and locks user configuration.
 * Requires the app to be active Device Owner on Android 11+ (API 30+).
 */
fun applyDnsLock(context: Context): Boolean {
  val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
  if (dpm == null) {
    Log.w(TAG, "DevicePolicyManager is not available on this device.")
    return false
  }

  val adminComponent = LockInAdminReceiver.getComponentName(context)

  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
    Log.w(
      TAG,
      "Private DNS lock requires Android 11 (API 30) or higher. Current SDK version: ${Build.VERSION.SDK_INT}"
    )
    return false
  }

  if (!dpm.isDeviceOwnerApp(context.packageName)) {
    Log.w(TAG, "App is not the Device Owner. Cannot configure global Private DNS.")
    return false
  }

  return try {
    val setResult = dpm.setGlobalPrivateDnsModeSpecifiedHost(adminComponent, NEXT_DNS_HOST)
    if (setResult != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
      val errorDescription = when (setResult) {
        DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING -> "HOST_NOT_SERVING (the DNS host does not serve Private DNS queries)"
        DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING -> "FAILURE_SETTING (failed to set the DNS mode)"
        else -> "UNKNOWN_ERROR (code $setResult)"
      }
      Log.e(TAG, "Failed to set global Private DNS: $errorDescription")
      return false
    }

    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
    Log.i(TAG, "Successfully configured Private DNS to $NEXT_DNS_HOST and added DISALLOW_CONFIG_PRIVATE_DNS restriction.")
    true
  } catch (e: SecurityException) {
    Log.e(TAG, "SecurityException encountered while applying DNS lock: ${e.message}", e)
    false
  } catch (e: Exception) {
    Log.e(TAG, "Exception encountered while applying DNS lock: ${e.message}", e)
    false
  }
}
