package com.app.lockin

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

private const val TAG = "LockInA11yService"

/**
 * Narrow, lightweight AccessibilityService responsible for protecting:
 * 1. Stay Focused and Lock-In accessibility service detail/toggle screens from being turned OFF.
 * 2. Accessibility shortcut configuration screens (to prevent assigning Stay Focused/Lock-In to hardware shortcuts).
 * 3. Samsung's "Reset accessibility settings" screen.
 *
 * Listens ONLY for TYPE_WINDOW_STATE_CHANGED inside com.android.settings and com.android.settings.intelligence.
 */
class LockInAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val pkgName = event.packageName?.toString() ?: return
        if (pkgName != "com.android.settings" && pkgName != "com.android.settings.intelligence") {
            return
        }

        val rootNode = rootInActiveWindow ?: return

        try {
            val className = event.className?.toString() ?: ""

            // 1. Check for Reset Accessibility Settings screen
            if (isResetAccessibilityScreen(rootNode, className)) {
                Log.i(TAG, "Intercepted Reset Accessibility Settings screen. Executing GLOBAL_ACTION_BACK.")
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }

            // 2. Check for Accessibility Shortcut Configuration screens
            if (isShortcutConfigScreen(rootNode, className)) {
                Log.i(TAG, "Intercepted Accessibility Shortcut Configuration screen ($className). Executing GLOBAL_ACTION_BACK.")
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }

            // 3. Check for Service Detail/Toggle screen for Stay Focused or Lock-In
            val protectedService = getProtectedServiceInDetailScreen(rootNode)
            if (protectedService != null) {
                Log.i(TAG, "Intercepted service detail toggle screen for '$protectedService'. Executing GLOBAL_ACTION_BACK.")
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
        } finally {
            rootNode.recycle()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "LockInAccessibilityService interrupted.")
    }

    /**
     * Detects if the current window is a Service Detail screen for Stay Focused or Lock-In.
     * To avoid false positives on the Installed Apps / Services LIST screen,
     * requires BOTH target identity ("Stay Focused" or "Lock-In") AND a main toggle switch widget.
     */
    private fun getProtectedServiceInDetailScreen(rootNode: AccessibilityNodeInfo): String? {
        val hasSwitchWidget = hasDetailSwitchWidget(rootNode)
        if (!hasSwitchWidget) {
            return null
        }

        val containsStayFocused = hasNodeWithText(rootNode, "Stay Focused")
        val containsLockIn = hasNodeWithText(rootNode, "Lock-In")

        return when {
            containsStayFocused -> "Stay Focused"
            containsLockIn -> "Lock-In"
            else -> null
        }
    }

    /**
     * Checks if the window contains a main detail switch bar or toggle widget.
     * Evaluates widget class names, resource IDs, and structure.
     */
    private fun hasDetailSwitchWidget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        // Check for known switch classes
        if (className.contains("Switch", ignoreCase = true) ||
            className.contains("Toggle", ignoreCase = true)
        ) {
            return true
        }

        // Check for known switch resource IDs
        if (viewId.contains("switch_widget", ignoreCase = true) ||
            viewId.contains("sesl_switchbar", ignoreCase = true) ||
            viewId.contains("switch_bar", ignoreCase = true)
        ) {
            return true
        }

        // Traverse child nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = hasDetailSwitchWidget(child)
            child.recycle()
            if (found) return true
        }

        return false
    }

    /**
     * Detects if the current window is an Accessibility Shortcut Configuration screen.
     */
    private fun isShortcutConfigScreen(rootNode: AccessibilityNodeInfo, className: String): Boolean {
        // Class name checks
        if (className.contains("AccessibilityEditShortcutsActivity", ignoreCase = true) ||
            className.contains("SideAndVolumeUpPreferenceActivity", ignoreCase = true) ||
            className.contains("VolumeUpAndDownPreferenceActivity", ignoreCase = true) ||
            className.contains("AccessibilityButtonPreferenceActivity", ignoreCase = true) ||
            className.contains("AccessibilityDialogActivity", ignoreCase = true)
        ) {
            return true
        }

        // Title/node fallback checks for shortcut configuration
        val hasShortcutTitle = hasNodeWithText(rootNode, "Accessibility button") ||
                hasNodeWithText(rootNode, "Side and Volume up keys") ||
                hasNodeWithText(rootNode, "Volume up and down keys") ||
                hasNodeWithText(rootNode, "Edit shortcuts")

        if (hasShortcutTitle && (hasNodeWithText(rootNode, "Stay Focused") || hasNodeWithText(rootNode, "Lock-In"))) {
            return true
        }

        return false
    }

    /**
     * Detects if the current window is Samsung's "Reset accessibility settings" screen.
     */
    private fun isResetAccessibilityScreen(rootNode: AccessibilityNodeInfo, className: String): Boolean {
        if (className.contains("ResetAccessibilitySettings", ignoreCase = true)) {
            return true
        }

        return hasNodeWithText(rootNode, "Reset accessibility settings") ||
                hasNodeWithText(rootNode, "Reset Accessibility Settings")
    }

    /**
     * Recursively checks if any node in the hierarchy contains the target text string.
     */
    private fun hasNodeWithText(node: AccessibilityNodeInfo, targetText: String): Boolean {
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""

        if (text.contains(targetText, ignoreCase = true) ||
            contentDesc.contains(targetText, ignoreCase = true)
        ) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = hasNodeWithText(child, targetText)
            child.recycle()
            if (found) return true
        }

        return false
    }
}
