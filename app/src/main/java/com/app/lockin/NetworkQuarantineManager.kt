package com.app.lockin

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.util.Log

object NetworkQuarantineManager {
    private const val TAG = "NetworkQuarantineManager"

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
        "com.app.lockin"
    )

    fun isPackageTrusted(packageInfo: PackageInfo): Boolean {
        val appInfo = packageInfo.applicationInfo ?: return false
        
        // Trust system apps
        if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) || 
            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)) {
            return true
        }

        // Trust user-allowed apps
        return trustedPackages.contains(packageInfo.packageName)
    }

    fun logQuarantineDecision(packageInfo: PackageInfo) {
        val packageName = packageInfo.packageName
        if (isPackageTrusted(packageInfo)) {
            Log.i(TAG, "Decision: TRUSTED (bypass) for package: $packageName")
        } else {
            Log.w(TAG, "Decision: QUARANTINED (blocked) for package: $packageName")
        }
    }
}
