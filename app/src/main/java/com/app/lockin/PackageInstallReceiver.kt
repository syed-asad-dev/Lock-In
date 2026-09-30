package com.app.lockin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class PackageInstallReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    Log.d(TAG, "PackageInstallReceiver received action: $action")

    when (action) {
      Intent.ACTION_PACKAGE_ADDED -> {
        val packageName = intent.data?.schemeSpecificPart
        if (!packageName.isNullOrBlank()) {
          val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
          Log.i(TAG, "Package installed event received for: $packageName (replacing=$replacing)")

          // 1. Hard-block policy is authoritative and runs before any
          // grandfathering, audit, or external review path.
          AppBlocklistManager.blockPackageIfNeeded(context, packageName)
          if (AppBlocklistManager.isPackageBlocked(context, packageName)) {
            AdmissionGateManager.onHardBlockedPackage(context, packageName)
            return
          }

          // 2. This performs only local PackageManager/DPM work. It suspends,
          // persists PENDING plus AppAuditResult, and enqueues JobScheduler;
          // no network operation is allowed on this receiver thread.
          AdmissionGateManager.onPackageInstalled(context, packageName, replacing)
        }
      }
      Intent.ACTION_PACKAGE_REMOVED -> {
        val packageName = intent.data?.schemeSpecificPart
        if (!packageName.isNullOrBlank()) {
          val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
          Log.i(TAG, "Package removed event received for: $packageName (replacing=$replacing)")
          AdmissionGateManager.onPackageRemoved(context, packageName, replacing)
        }
      }
      "com.app.lockin.ACTION_UNINSTALL_RESULT" -> {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val pkg = intent.getStringExtra("extra_package_name") ?: intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        Log.i(TAG, "Uninstall result callback for $pkg: status=$status, message=$message")
      }
    }
  }

  companion object {
    private const val TAG = "PackageInstallReceiver"
  }
}
