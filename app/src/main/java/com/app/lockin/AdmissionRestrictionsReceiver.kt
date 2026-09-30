package com.app.lockin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Re-queues suspended reviews when managed backend configuration changes. */
class AdmissionRestrictionsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED) {
            Log.i(TAG, "Managed admission backend restrictions changed; queuing review work.")
            AdmissionWorkScheduler.enqueue(context)
        }
    }

    companion object {
        private const val TAG = "AdmissionRestrictionsReceiver"
    }
}
