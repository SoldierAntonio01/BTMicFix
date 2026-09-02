package com.btmicfix.automation

import android.Manifest
import android.app.Notification
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.util.Logger
import com.btmicfix.util.Preferences

class VoiceAccessMonitorService : NotificationListenerService() {

    private lateinit var routingManager: AudioRoutingManager

    private val handler = Handler(Looper.getMainLooper())

    private var voiceAccessListening = false

    private val stateLoop = object : Runnable {
        override fun run() {
            refreshVoiceAccessState()
            applyDesiredRouting()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()

        routingManager =
            AudioRoutingManager(applicationContext)

        // Stop old "route whenever Buds connect" behavior.
        Preferences(applicationContext).autoRouteEnabled = false
    }

    override fun onListenerConnected() {
        super.onListenerConnected()

        Logger.i("Voice Access automation connected")

        try {
            routingManager.startMonitoring()
        } catch (_: Exception) {
        }

        refreshVoiceAccessState()

        handler.removeCallbacks(stateLoop)
        handler.post(stateLoop)
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(stateLoop)

        routingManager.clearRouting()

        try {
            routingManager.stopMonitoring()
        } catch (_: Exception) {
        }

        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification
    ) {
        if (sbn.packageName == VOICE_ACCESS_PACKAGE) {
            refreshVoiceAccessState()
            applyDesiredRouting()
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification
    ) {
        if (sbn.packageName == VOICE_ACCESS_PACKAGE) {
            refreshVoiceAccessState()
            applyDesiredRouting()
        }
    }

    private fun refreshVoiceAccessState() {

        val notifications =
            try {
                activeNotifications
                    ?.filter {
                        it.packageName ==
                            VOICE_ACCESS_PACKAGE
                    }
                    ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

        if (notifications.isEmpty()) {
            voiceAccessListening = false
            return
        }

        var detectedState: Boolean? = null

        for (sbn in notifications) {

            val notification = sbn.notification

            val actionText =
                notification.actions
                    ?.mapNotNull {
                        it.title
                            ?.toString()
                            ?.lowercase()
                    }
                    ?.joinToString(" ")
                    .orEmpty()

            val extras = notification.extras

            val title =
                extras
                    .getCharSequence(
                        Notification.EXTRA_TITLE
                    )
                    ?.toString()
                    ?.lowercase()
                    .orEmpty()

            val text =
                extras
                    .getCharSequence(
                        Notification.EXTRA_TEXT
                    )
                    ?.toString()
                    ?.lowercase()
                    .orEmpty()

            val bigText =
                extras
                    .getCharSequence(
                        Notification.EXTRA_BIG_TEXT
                    )
                    ?.toString()
                    ?.lowercase()
                    .orEmpty()

            val combined =
                "$actionText $title $text $bigText"

            // Voice Access notification normally says
            // "Tap to pause" while listening.
            if (
                combined.contains("pause") ||
                combined.contains("stop listening") ||
                combined.contains("listening")
            ) {
                detectedState = true
                break
            }

            // When paused it normally offers
            // "Touch/Tap to start".
            if (
                combined.contains("start") ||
                combined.contains("resume") ||
                combined.contains("paused")
            ) {
                detectedState = false
            }
        }

        if (detectedState != null) {

            if (
                voiceAccessListening !=
                detectedState
            ) {
                Logger.i(
                    "Voice Access listening = $detectedState"
                )
            }

            voiceAccessListening =
                detectedState
        }
    }

    private fun applyDesiredRouting() {

        val callActive = isPhoneCallActive()

        val shouldRoute =
            voiceAccessListening &&
                !callActive

        if (shouldRoute) {

            if (!routingManager.isBluetoothRouted()) {

                val device =
                    routingManager
                        .findFirstBluetoothCommunicationDevice()

                if (device != null) {

                    Logger.i(
                        "Voice Access listening → BT mic ON"
                    )

                    routingManager
                        .routeToBluetooth(device)
                }
            }

        } else {

            if (routingManager.isBluetoothRouted()) {

                if (callActive) {
                    Logger.i(
                        "Call active → releasing BT mic routing"
                    )
                } else {
                    Logger.i(
                        "Voice Access paused → BT mic OFF"
                    )
                }

                routingManager.clearRouting()
            }
        }
    }

    private fun isPhoneCallActive(): Boolean {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        return try {

            val telecom =
                getSystemService(
                    TelecomManager::class.java
                )

            telecom?.isInCall == true

        } catch (_: Exception) {
            false
        }
    }

    override fun onDestroy() {

        handler.removeCallbacks(stateLoop)

        routingManager.clearRouting()

        try {
            routingManager.stopMonitoring()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    companion object {

        private const val VOICE_ACCESS_PACKAGE =
            "com.google.android.apps.accessibility.voiceaccess"

        private const val CHECK_INTERVAL_MS =
            750L
    }
}
