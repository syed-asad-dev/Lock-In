package com.app.lockin

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

object AppBlocklistManager {
  private const val TAG = "AppBlocklistManager"

  // Blocklist of restricted packages.
  // Verified:
  // - "com.innersloth.spacemafia" -> Among Us
  // - "com.nordvpn.android" -> NordVPN
  // - "ch.protonvpn.android" -> Proton VPN
  // - "com.expressvpn.vpn" -> ExpressVPN
  // - "com.surfshark.vpnclient.android" -> Surfshark
  // - "com.tunnelbear.android" -> TunnelBear
  private val blockedPackages = mutableSetOf(
    "com.innersloth.spacemafia",
    // VPNs
    "com.nordvpn.android",
    "ch.protonvpn.android",
    "com.expressvpn.vpn",
    "com.surfshark.vpnclient.android",
    "com.tunnelbear.android",
    "com.windscribe.vpn",
    "com.hotspotshield.android.vpn",
    "com.cyberghost.vpn",
    "com.ppure.vpn.proxy.puffin",
    "com.fast.free.unblock.secure.vpn",
    "com.free.vpn.unblock.proxy.turbovpn",
    // Browsers
    "com.brave.browser",
    "org.mozilla.firefox",
    "org.mozilla.firefox_beta",
    "org.mozilla.fenix", // Firefox Nightly
    "org.mozilla.focus", // Firefox Focus
    "com.microsoft.emmx", // Edge
    "com.opera.browser",
    "com.opera.mini.native",
    "com.opera.gx",
    "com.duckduckgo.mobile.android",
    "com.puffinbrowser.android.puffin",
    "com.sec.android.app.sbrowser", // Samsung Internet
    "com.sec.android.app.sbrowser.beta",
    "com.vivaldi.browser",
    "com.UCMobile.intl",
    "com.cloudmosa.puffinFree",
    "com.mbrowser.maxton",
    "com.android.browser", // AOSP Browser
    "com.google.android.browser",
  )

  /**
   * Checks if the package is in the blocked package list OR if it has browser capabilities.
   */
  fun isPackageBlocked(context: Context, packageName: String): Boolean {
    if (blockedPackages.contains(packageName)) return true
    
    // Skip checking for Chrome and our own app
    if (packageName == "com.android.chrome" || packageName == context.packageName) return false
    if (packageName == "com.google.android.browser") return false

    // Check if the app is a "hidden" browser by looking for web-view intent filters
    return try {
      val intent = Intent(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        data = Uri.parse("https://")
      }
      val resolveInfo = context.packageManager.queryIntentActivities(intent, 0)
      resolveInfo.any { it.activityInfo.packageName == packageName }
    } catch (e: Exception) {
      false
    }
  }

  /**
   * If the package is blocked and this app is Device Owner, silently uninstalls the package.
   */
  fun blockPackageIfNeeded(context: Context, packageName: String) {
    if (!isPackageBlocked(context, packageName)) {
      Log.d(TAG, "Package $packageName is safe. No action taken.")
      return
    }

    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    if (dpm == null) {
      Log.w(TAG, "DevicePolicyManager not available. Cannot block $packageName.")
      return
    }

    if (!dpm.isDeviceOwnerApp(context.packageName)) {
      Log.w(TAG, "App is not Device Owner. Cannot silently uninstall $packageName.")
      return
    }

    val adminComponent = LockInAdminReceiver.getComponentName(context)

    try {
      Log.i(TAG, "Blocked package detected: $packageName. Initiating silent uninstallation as Device Owner.")

      // 1. Immediately Suspend the package so it cannot be launched while waiting for uninstall
      try {
        dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), true)
        Log.i(TAG, "Package $packageName has been suspended (grayed out).")
      } catch (e: Exception) {
        Log.w(TAG, "Failed to suspend $packageName: ${e.message}")
      }

      // 2. Ensure uninstall is not blocked by admin restrictions
      try {
        dpm.setUninstallBlocked(adminComponent, packageName, false)
      } catch (e: Exception) {
        Log.w(TAG, "Note on setUninstallBlocked for $packageName: ${e.message}")
      }

      // 3. PackageInstaller.uninstall() invoked by Device Owner executes silently
      val packageInstaller = context.packageManager.packageInstaller
      val callbackIntent = Intent(context, PackageInstallReceiver::class.java).apply {
        action = "com.app.lockin.ACTION_UNINSTALL_RESULT"
        putExtra("extra_package_name", packageName)
      }
      val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
      val pendingIntent = PendingIntent.getBroadcast(
        context,
        packageName.hashCode(),
        callbackIntent,
        flags
      )

      packageInstaller.uninstall(packageName, pendingIntent.intentSender)
      Log.i(TAG, "Silent uninstall request dispatched to PackageInstaller for $packageName.")
    } catch (e: SecurityException) {
      Log.e(TAG, "SecurityException during silent uninstall of $packageName: ${e.message}", e)
    } catch (e: Exception) {
      Log.e(TAG, "Exception during silent uninstall of $packageName: ${e.message}", e)
    }
  }
}
