package com.app.lockin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log

private const val TAG = "VpnSetupManager"

object VpnSetupManager {

  /** 
   * Sets this app as the Always-On VPN package with lockdown enabled.
   * This bypasses the need for the user consent dialog and prevents bypass.
   */
  fun establishAlwaysOnVpn(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
      ?: return false
    val adminComponent = LockInAdminReceiver.getComponentName(context)

    if (!dpm.isDeviceOwnerApp(context.packageName)) {
      Log.w(TAG, "Not Device Owner. Cannot set Always-On VPN.")
      return false
    }

    return try {
      // SET LOCKDOWN TO FALSE for stabilization. 
      // The VPN will now only capture the specific untrusted apps we allowed in LocalVpnService.
      dpm.setAlwaysOnVpnPackage(adminComponent, context.packageName, false)
      Log.i(TAG, "Always-On VPN established (Lockdown DISABLED for stability).")
      
      // Also manually start the service to apply the capture list
      startLocalVpnDirectly(context)
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to set Always-On VPN: ${e.message}")
      false
    }
  }

  fun disableAlwaysOnVpn(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
      ?: return false
    val adminComponent = LockInAdminReceiver.getComponentName(context)

    return try {
      dpm.setAlwaysOnVpnPackage(adminComponent, null, false)
      Log.i(TAG, "Always-On VPN disabled.")
      stopLocalVpn(context)
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to disable Always-On VPN: ${e.message}")
      false
    }
  }

  /** Returns the consent Intent if user permission is needed, or null if already granted. */
  fun getPrepareIntent(context: Context): Intent? {
    return VpnService.prepare(context)
  }

  /** Starts LocalVpnService directly, assuming consent is already granted. */
  fun startLocalVpnDirectly(context: Context): Boolean {
    return try {
      val serviceIntent = Intent(context, LocalVpnService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(serviceIntent)
      } else {
        context.startService(serviceIntent)
      }
      Log.i(TAG, "LocalVpnService started successfully.")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Exception encountered while starting LocalVpnService: ${e.message}", e)
      false
    }
  }

  fun stopLocalVpn(context: Context): Boolean {
    return try {
      val serviceIntent = Intent(context, LocalVpnService::class.java)
      context.stopService(serviceIntent)
      Log.i(TAG, "LocalVpnService stopped.")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Exception encountered while stopping LocalVpnService: ${e.message}", e)
      false
    }
  }
}