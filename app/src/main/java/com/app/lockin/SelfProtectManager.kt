package com.app.lockin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import android.util.Log

private const val TAG = "SelfProtectManager"

/**
 * Manages self-protection policies for the Lock-In application:
 * - Prevents uninstallation of this app
 * - Disallows apps control (disables force-stopping, clearing data/cache)
 */
object SelfProtectManager {

  fun applySelfProtect(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    if (dpm == null) {
      Log.w(TAG, "DevicePolicyManager is not available on this device.")
      return false
    }

    val adminComponent = LockInAdminReceiver.getComponentName(context)

    if (!dpm.isDeviceOwnerApp(context.packageName)) {
      Log.w(TAG, "App is not the Device Owner. Cannot apply self-protection.")
      return false
    }

    return try {
      dpm.setUninstallBlocked(adminComponent, context.packageName, true)
      dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
      Log.i(TAG, "Successfully applied self-protection (uninstall blocked and DISALLOW_APPS_CONTROL).")
      true
    } catch (e: SecurityException) {
      Log.e(TAG, "SecurityException encountered while applying self-protection: ${e.message}", e)
      false
    } catch (e: Exception) {
      Log.e(TAG, "Exception encountered while applying self-protection: ${e.message}", e)
      false
    }
  }

  fun removeSelfProtect(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    if (dpm == null) {
      Log.w(TAG, "DevicePolicyManager is not available on this device.")
      return false
    }

    val adminComponent = LockInAdminReceiver.getComponentName(context)

    if (!dpm.isDeviceOwnerApp(context.packageName)) {
      Log.w(TAG, "App is not the Device Owner. Cannot remove self-protection.")
      return false
    }

    return try {
      dpm.setUninstallBlocked(adminComponent, context.packageName, false)
      dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
      Log.i(TAG, "Successfully removed self-protection.")
      true
    } catch (e: SecurityException) {
      Log.e(TAG, "SecurityException encountered while removing self-protection: ${e.message}", e)
      false
    } catch (e: Exception) {
      Log.e(TAG, "Exception encountered while removing self-protection: ${e.message}", e)
      false
    }
  }
}
