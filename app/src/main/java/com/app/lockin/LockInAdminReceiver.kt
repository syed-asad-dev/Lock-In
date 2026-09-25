package com.app.lockin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class LockInAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "LockInAdminReceiver onEnabled: Device Admin / Device Owner enabled")
        AccessibilityLockManager.applyAccessibilityLock(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "LockInAdminReceiver onDisabled: Device Admin / Device Owner disabled")
    }

    companion object {
        private const val TAG = "LockInAdminReceiver"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, LockInAdminReceiver::class.java)
        }
    }
}
