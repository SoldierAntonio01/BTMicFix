package com.btmicfix.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Simple SharedPreferences wrapper for persisting user settings.
 */
class Preferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("btmicfix_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PAIRED_DEVICE_ADDRESS = "paired_device_address"
        private const val KEY_PAIRED_DEVICE_NAME = "paired_device_name"
        private const val KEY_AUTO_ROUTE_ENABLED = "auto_route_enabled"
        private const val KEY_SHIZUKU_FALLBACK_ENABLED = "shizuku_fallback_enabled"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
    }

    var pairedDeviceAddress: String?
        get() = prefs.getString(KEY_PAIRED_DEVICE_ADDRESS, null)
        set(value) = prefs.edit { putString(KEY_PAIRED_DEVICE_ADDRESS, value) }

    var pairedDeviceName: String?
        get() = prefs.getString(KEY_PAIRED_DEVICE_NAME, null)
        set(value) = prefs.edit { putString(KEY_PAIRED_DEVICE_NAME, value) }

    var autoRouteEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ROUTE_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_ROUTE_ENABLED, value) }

    var shizukuFallbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHIZUKU_FALLBACK_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_SHIZUKU_FALLBACK_ENABLED, value) }

    var setupCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
        set(value) = prefs.edit { putBoolean(KEY_SETUP_COMPLETED, value) }

    fun clearPairedDevice() {
        prefs.edit {
            remove(KEY_PAIRED_DEVICE_ADDRESS)
            remove(KEY_PAIRED_DEVICE_NAME)
        }
    }
}
