package com.app.lockin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import android.util.Log

private const val TAG = "SideloadBlockManager"

/**
 * Manages sideloading and VPN configuration blocking policies for the Lock-In application:
 * - Allows or disallows installing apps from unknown sources (sideloading)
 * - Disallows user configuration or changes to VPN connections
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

  fun allowSideloading(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    if (dpm == null) {
      Log.w(TAG, "DevicePolicyManager is not available on this device.")
      return false
    }

    val adminComponent = LockInAdminReceiver.getComponentName(context)

    if (!dpm.isDeviceOwnerApp(context.packageName)) {
      Log.w(TAG, "App is not the Device Owner. Cannot clear sideload restriction.")
      return false
    }

    return try {
      dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
      dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
      Log.i(TAG, "Successfully cleared DISALLOW_INSTALL_UNKNOWN_SOURCES and DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY.")
      true
    } catch (e: SecurityException) {
      Log.e(TAG, "SecurityException encountered while clearing sideload restriction: ${e.message}", e)
      false
    } catch (e: Exception) {
      Log.e(TAG, "Exception encountered while clearing sideload restriction: ${e.message}", e)
      false
    }
  }

  fun checkIsSideloadBlocked(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
    val adminComponent = LockInAdminReceiver.getComponentName(context)
    return try {
      if (dpm.isDeviceOwnerApp(context.packageName)) {
        val restrictions = dpm.getUserRestrictions(adminComponent)
        restrictions.getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, false) ||
          restrictions.getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY, false)
      } else false
    } catch (e: Exception) {
      false
    }
  }
}
