package com.app.lockin

import android.content.Context

object CooldownManager {
  private const val PREFS_NAME = "lockin_prefs"
  private const val COOLDOWN_DURATION_MILLIS = 7 * 24 * 60 * 60 * 1000L // 7 days in milliseconds

  private fun getPrefKey(key: String): String = "${key}_change_requested_at"

  /**
   * Saves the current timestamp as the request time for the given key.
   */
  fun requestChange(context: Context, key: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putLong(getPrefKey(key), System.currentTimeMillis()).apply()
  }

  /**
   * Returns 0 if no request is pending, otherwise returns remaining milliseconds clamped to min 0.
   */
  fun getRemainingCooldownMillis(context: Context, key: String): Long {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val requestedAt = prefs.getLong(getPrefKey(key), 0L)
    if (requestedAt <= 0L) {
      return 0L
    }
    val elapsed = System.currentTimeMillis() - requestedAt
    val remaining = COOLDOWN_DURATION_MILLIS - elapsed
    return if (remaining > 0L) remaining else 0L
  }

  /**
   * Returns true only if a request exists AND remaining cooldown is 0.
   */
  fun isChangeReady(context: Context, key: String): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val requestedAt = prefs.getLong(getPrefKey(key), 0L)
    if (requestedAt <= 0L) {
      return false
    }
    return getRemainingCooldownMillis(context, key) == 0L
  }

  /**
   * Clears the stored timestamp for the given key.
   */
  fun clearRequest(context: Context, key: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().remove(getPrefKey(key)).apply()
  }
}
