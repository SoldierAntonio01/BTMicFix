package com.btmicfix

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log

/**
 * Application class for BTMicFix.
 * Handles one-time initialization: notification channel creation.
 */
class BTMicFixApp : Application() {

    companion object {
        const val TAG = "BTMicFix"
        const val NOTIFICATION_CHANNEL_ID = "btmicfix_routing"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "BTMicFix initialized")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
