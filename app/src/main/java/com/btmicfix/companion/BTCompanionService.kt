package com.btmicfix.companion

import android.app.Notification
import android.app.PendingIntent
import android.companion.CompanionDeviceService
import android.companion.AssociationInfo
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.btmicfix.BTMicFixApp
import com.btmicfix.MainActivity
import com.btmicfix.R
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.util.Logger

/**
 * Background service that is automatically bound by the system when the
 * associated Bluetooth device connects or disconnects.
 *
 * This is the core of the "zero-touch" experience: the user pairs their
 * earbuds once, and from then on, the audio routing fix is applied
 * automatically whenever the earbuds connect.
 *
 * The system manages this service's lifecycle via CompanionDeviceManager.
 * We do NOT need to manage wakelocks or background scanning ourselves.
 */
class BTCompanionService : CompanionDeviceService() {

    private lateinit var audioRoutingManager: AudioRoutingManager

    override fun onCreate() {
        super.onCreate()
        Logger.i("BTCompanionService created")
        audioRoutingManager = AudioRoutingManager(applicationContext)
    }

    /**
     * Called when the associated device appears (connects).
     * This is our trigger to apply the audio routing fix.
     */
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        super.onDeviceAppeared(associationInfo)
        Logger.i("🎧 Device appeared: association=${associationInfo.id}")

        // Start a foreground service to keep the routing active
        startForegroundWithNotification("Connecting…")

        // Apply the audio routing fix
        audioRoutingManager.startMonitoring()
        val result = audioRoutingManager.routeToFirstAvailableBluetooth()

        when (result) {
            is AudioRoutingManager.RoutingState.Active -> {
                Logger.i("✓ Background routing activated: ${result.deviceName}")
                updateNotification("Mic routed to ${result.deviceName}")
            }
            is AudioRoutingManager.RoutingState.Failed -> {
                Logger.e("✗ Background routing failed: ${result.reason}")
                updateNotification("Routing failed — tap to retry")
            }
            else -> {
                Logger.w("Unexpected routing state: $result")
            }
        }
    }

    /**
     * Called when the associated device disappears (disconnects).
     * Clear the audio routing and stop the foreground service.
     */
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        super.onDeviceDisappeared(associationInfo)
        Logger.i("🎧 Device disappeared: association=${associationInfo.id}")

        audioRoutingManager.clearRouting()
        audioRoutingManager.stopMonitoring()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRoutingManager.clearRouting()
        audioRoutingManager.stopMonitoring()
        Logger.i("BTCompanionService destroyed")
    }

    /**
     * Start as a foreground service with a persistent notification.
     * Android requires foreground services to show a notification.
     */
    private fun startForegroundWithNotification(statusText: String) {
        val notification = buildNotification(statusText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                BTMicFixApp.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(BTMicFixApp.NOTIFICATION_ID, notification)
        }
    }

    /**
     * Update the foreground notification text.
     */
    private fun updateNotification(statusText: String) {
        val notification = buildNotification(statusText)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(BTMicFixApp.NOTIFICATION_ID, notification)
    }

    /**
     * Build the foreground service notification.
     */
    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, BTMicFixApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BTMicFix")
            .setContentText(statusText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
