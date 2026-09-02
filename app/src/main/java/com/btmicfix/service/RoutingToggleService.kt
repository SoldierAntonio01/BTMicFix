package com.btmicfix.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.btmicfix.BTMicFixApp
import com.btmicfix.MainActivity
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.util.Logger

class RoutingToggleService : Service() {

    private lateinit var routingManager: AudioRoutingManager
    private var routingEnabled = false

    override fun onCreate() {
        super.onCreate()

        routingManager = AudioRoutingManager(applicationContext)
        routingManager.startMonitoring()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_TOGGLE) {
            if (routingEnabled) {
                disableRouting()
            } else {
                enableRouting()
            }
        }

        return START_NOT_STICKY
    }

    private fun enableRouting() {

        startForegroundCompat(
            buildNotification("Turning Bluetooth mic on…")
        )

        val result =
            routingManager.routeToFirstAvailableBluetooth()

        when (result) {

            is AudioRoutingManager.RoutingState.Active -> {
                routingEnabled = true

                updateNotification(
                    "BT mic ON • ${result.deviceName}"
                )

                Logger.i("Pinch routing enabled")
            }

            is AudioRoutingManager.RoutingState.Failed -> {
                routingEnabled = false

                Logger.e(
                    "Pinch routing failed: ${result.reason}"
                )

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> Unit
        }
    }

    private fun disableRouting() {

        routingEnabled = false

        routingManager.clearRouting()

        stopForeground(STOP_FOREGROUND_REMOVE)

        stopSelf()
    }

    private fun startForegroundCompat(
        notification: Notification
    ) {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun updateNotification(text: String) {

        val manager =
            getSystemService(
                NOTIFICATION_SERVICE
            ) as android.app.NotificationManager

        manager.notify(
            NOTIFICATION_ID,
            buildNotification(text)
        )
    }

    private fun buildNotification(
        text: String
    ): Notification {

        val appIntent =
            Intent(
                this,
                MainActivity::class.java
            )

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat.Builder(
            this,
            BTMicFixApp.NOTIFICATION_CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.stat_sys_data_bluetooth
            )
            .setContentTitle("BTMicFix")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .build()
    }

    override fun onDestroy() {

        if (routingEnabled) {
            routingManager.clearRouting()
        }

        try {
            routingManager.stopMonitoring()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    companion object {

        const val ACTION_TOGGLE =
            "com.btmicfix.action.TOGGLE_ROUTING"

        private const val NOTIFICATION_ID =
            1002
    }
}
