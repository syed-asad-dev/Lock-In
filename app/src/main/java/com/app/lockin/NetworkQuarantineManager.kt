package com.app.lockin

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.util.Log

object NetworkQuarantineManager {
    private const val TAG = "NetworkQuarantineManager"

    // Explicit list of apps that require network quarantine (Black-Hole)
    private val quarantinedPackages = setOf(
        "hub.browser.video.downloader.saver" // Browser Hub
    )

    // Legacy list for reference/logging only
    private val trustedPackages = setOf(
        "com.whatsapp",
        "com.snapchat.android",
        "com.instagram.android",
        "com.facebook.katana",
        "com.openai.chatgpt",
        "com.anthropic.claude",
        "com.duolingo",
        "com.indeed.android.jobsearch",
        "com.google.android.keep",
        "com.google.android.inputmethod.latin",
        "com.techlogix.mobilinkcustomer",
        "com.sadapay.app",
        "com.google.android.apps.bard",
        "app.redantotsu.asr",
        "dantotsu.torrentAddon",
        "com.community.oneroom",
        "com.nikhil.yt",
        "com.samsung.sree",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.chrome",
        "com.app.lockin",
        "com.spotify.music",
        "com.linkedin.android"
    )

    /**
     * Rules for Network Quarantine:
     * 1. System apps -> NO (Always Bypass)
     * 2. Chrome or Lock-In -> NO (Always Bypass)
     * 3. Explicit Risk Package -> YES (Capture)
     * 4. Default -> NO (Bypass)
     */
    fun shouldQuarantine(packageInfo: PackageInfo): Boolean {
        val packageName = packageInfo.packageName
        val appInfo = packageInfo.applicationInfo ?: return false
        
        // Rule 1: Trust system apps
        if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) || 
            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)) {
            return false
        }

        // Rule 2: Trust Chrome and Lock-In core
        if (packageName == "com.android.chrome" || packageName == "com.app.lockin") {
            return false
        }

        // Rule 3: Explicit Risk Check
        return quarantinedPackages.contains(packageName)
    }

    fun logQuarantineDecision(packageInfo: PackageInfo) {
        val packageName = packageInfo.packageName
        if (shouldQuarantine(packageInfo)) {
            Log.w(TAG, "Decision: QUARANTINED (black-holed) for package: $packageName")
        } else {
            val type = if (trustedPackages.contains(packageName)) "USER-ALLOWED" else "DEFAULT-SAFE"
            Log.i(TAG, "Decision: TRUSTED ($type) for package: $packageName")
        }
    }
}
