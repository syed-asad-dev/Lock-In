package com.app.lockin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "BootCompletedReceiver"

/**
 * Receiver responsible for re-asserting Always-On VPN policy at both boot
 * phases. Admission Gate reconciliation runs only after credential-protected
 * storage is available at normal ACTION_BOOT_COMPLETED.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val success = VpnSetupManager.establishAlwaysOnVpn(context)
        if (success) {
            Log.i(TAG, "Successfully re-asserted Always-On VPN policy.")
        } else {
            Log.w(TAG, "Failed to re-assert Always-On VPN policy (app may not be Device Owner).")
        }

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "ACTION_BOOT_COMPLETED received; reconciling Admission Gate.")
            try {
                AdmissionGateManager.reconcileState(context)
                AdmissionWorkScheduler.enqueue(context)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during Admission Gate boot reconciliation: ${e.message}", e)
            }
        } else {
            Log.i(TAG, "ACTION_LOCKED_BOOT_COMPLETED received; Admission Gate reconciliation deferred until normal boot.")
        }
    }
}
