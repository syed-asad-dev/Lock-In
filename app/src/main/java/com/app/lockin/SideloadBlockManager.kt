package com.app.lockin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import android.util.Log

private const val TAG = "SideloadBlockManager"

/**
 * Manages sideloading and VPN configuration blocking policies for the Lock-In application:
 * - Disallows installing apps from unknown sources (per-user and globally)
 * - Disallows user configuration or changes to VPN connections
 *
 * This restriction is permanent/one-way and has no removal mechanism.
 */
object SideloadBlockManager {

  fun applySideloadBlock(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    if (dpm == null) {
      Log.w(TAG, "DevicePolicyManager is not available on this device.")
      return false
    }

    val adminComponent = LockInAdminReceiver.getComponentName(context)

    if (!dpm.isDeviceOwnerApp(context.packageName)) {
      Log.w(TAG, "App is not the Device Owner. Cannot apply sideload and VPN restrictions.")
      return false
    }

    return try {
      dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
      dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
      dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_VPN)
      Log.i(
        TAG,
        "Successfully applied DISALLOW_INSTALL_UNKNOWN_SOURCES and DISALLOW_CONFIG_VPN."
      )
      true
    } catch (e: SecurityException) {
      Log.e(TAG, "SecurityException encountered while applying sideload/VPN restrictions: ${e.message}", e)
      false
    } catch (e: Exception) {
      Log.e(TAG, "Exception encountered while applying sideload/VPN restrictions: ${e.message}", e)
      false
    }
  }
}
