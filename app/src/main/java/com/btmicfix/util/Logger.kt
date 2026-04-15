package com.btmicfix.util

import android.util.Log
import com.btmicfix.BTMicFixApp

/**
 * Simple logging utility that wraps Android's Log class.
 * All log messages are tagged with the app tag for easy filtering.
 *
 * Usage: Logger.i("Routing activated for ${device.name}")
 * Filter in logcat: adb logcat -s BTMicFix
 */
object Logger {
    private const val TAG = BTMicFixApp.TAG

    fun d(message: String) = Log.d(TAG, message)
    fun i(message: String) = Log.i(TAG, message)
    fun w(message: String) = Log.w(TAG, message)
    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}
