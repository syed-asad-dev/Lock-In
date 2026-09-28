package com.app.lockin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "BootCompletedReceiver"

/**
 * Receiver responsible for re-asserting the Always-On VPN policy
 * and reconciling Admission Gate states after a device reboot.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "ACTION_BOOT_COMPLETED received. Re-asserting Always-On VPN policy and reconciling Admission Gate...")
            
            // 1. Re-trigger the established DPM Always-On configuration
            val success = VpnSetupManager.establishAlwaysOnVpn(context)
            if (success) {
                Log.i(TAG, "Successfully re-asserted Always-On VPN policy.")
            } else {
                Log.w(TAG, "Failed to re-assert Always-On VPN policy (app may not be Device Owner).")
            }

            // 2. Reconcile Admission Gate states (re-suspend pending/denied apps)
            try {
                AdmissionGateManager.reconcileState(context)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during Admission Gate boot reconciliation: ${e.message}", e)
            }
        }
    }
}
