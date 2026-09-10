package com.app.lockin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import android.util.Log

private const val TAG = "ChromeDnsManager"
private const val CHROME_PACKAGE = "com.android.chrome"

/**
 * Forces Chrome's Secure DNS off AND blocks the NextDNS dashboard
 * specifically within Chrome on this device (does not touch the
 * shared NextDNS Denylist, which also affects the laptop).
 */
fun applyChromeDnsLock(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    if (dpm == null) {
        Log.w(TAG, "DevicePolicyManager is not available on this device.")
        return false
    }

    val adminComponent = LockInAdminReceiver.getComponentName(context)

    if (!dpm.isDeviceOwnerApp(context.packageName)) {
        Log.w(TAG, "App is not the Device Owner. Cannot apply Chrome restrictions.")
        return false
    }

    return try {
        val restrictions = Bundle().apply {
            putString("DnsOverHttpsMode", "off")
            putStringArray("URLBlocklist", arrayOf(
                "nextdns.io", "my.nextdns.io",
                "pornhub.com", "chaturbate.com", "xvideos.com", "xnxx.com",
                "x.com", "reddit.com", "quora.com", "xhamster.com", "redtube.com"
            ))
            putBoolean("ForceGoogleSafeSearch", true)
            putInt("ForceYouTubeRestrict", 2)
        }
        dpm.setApplicationRestrictions(adminComponent, CHROME_PACKAGE, restrictions)
        Log.i(TAG, "Applied Chrome restrictions: DnsOverHttpsMode=off, URLBlocklist, SafeSearch=true, YouTubeRestrict=Strict(2)")
        true
    } catch (e: SecurityException) {
        Log.e(TAG, "SecurityException while applying Chrome restrictions: ${e.message}", e)
        false
    } catch (e: Exception) {
        Log.e(TAG, "Exception while applying Chrome restrictions: ${e.message}", e)
        false
    }
}