package com.app.lockin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import android.util.Log

private const val TAG = "SafeBootLockManager"

/**
 * Disallows booting the device in safe mode.
 * Requires the app to be active Device Owner.
 */
fun applySafeBootLock(context: Context): Boolean {
  val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
  if (dpm == null) {
    Log.w(TAG, "DevicePolicyManager is not available on this device.")
    return false
  }

  val adminComponent = LockInAdminReceiver.getComponentName(context)

  if (!dpm.isDeviceOwnerApp(context.packageName)) {
    Log.w(TAG, "App is not the Device Owner. Cannot disallow safe boot.")
    return false
  }

  return try {
    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
    Log.i(TAG, "Successfully added DISALLOW_SAFE_BOOT user restriction.")
    true
  } catch (e: SecurityException) {
    Log.e(TAG, "SecurityException encountered while applying safe mode block: ${e.message}", e)
    false
  } catch (e: Exception) {
    Log.e(TAG, "Exception encountered while applying safe mode block: ${e.message}", e)
    false
  }
}
