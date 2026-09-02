package com.btmicfix.automation

import android.app.Notification
import android.media.AudioDeviceInfo
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.bluetooth.LeAudioCacheRefresher
import com.btmicfix.shizuku.LeAudioShizukuBridge
import com.btmicfix.util.Logger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Automatically controls BTMicFix from Voice Access.
 *
 * Voice Access LISTENING:
 *
 *      Buds LE Audio mic route ON
 *
 * Voice Access PAUSED / STOPPED:
 *
 *      communication route OFF
 *      AudioManager returns to normal mode
 *
 * IMPORTANT:
 *
 * The LE Audio PROFILE itself is deliberately left connected
 * when Voice Access pauses.
 *
 * That avoids doing a full HFP/A2DP <-> LE Audio profile handover
 * every time Voice Access starts/stops, which would likely bring
 * back the 2-3 second audio interruptions.
 *
 * This service is event-driven.
 *
 * NO repeating watchdog.
 * NO notification polling loop.
 */
class VoiceAccessMonitorService :
    NotificationListenerService() {

    /*
     * ============================================================
     * AUDIO ROUTING
     * ============================================================
     */

    private lateinit var routingManager:
        AudioRoutingManager

    /*
     * ============================================================
     * BACKGROUND WORK
     * ============================================================
     */

    private val worker =
        Executors.newSingleThreadExecutor()

    private val activationRunning =
        AtomicBoolean(
            false
        )

    /*
     * ============================================================
     * VOICE ACCESS STATE
     * ============================================================
     */

    @Volatile
    private var voiceAccessListening =
        false

    private var lastDetectedState:
        Boolean? =
        null

    /*
     * ============================================================
     * CREATE
     * ============================================================
     */

    override fun onCreate() {

        super.onCreate()

        routingManager =
            AudioRoutingManager(
                applicationContext
            )

        routingManager
            .startMonitoring()

        Logger.i(
            "Voice Access automation service created"
        )
    }

    /*
     * ============================================================
     * LISTENER CONNECTED
     * ============================================================
     */

    override fun onListenerConnected() {

        super.onListenerConnected()

        Logger.i(
            "Notification listener connected"
        )

        refreshVoiceAccessState()
    }

    /*
     * ============================================================
     * NOTIFICATION POSTED / UPDATED
     * ============================================================
     */

    override fun onNotificationPosted(
        sbn: StatusBarNotification
    ) {

        if (
            sbn.packageName ==
            VOICE_ACCESS_PACKAGE
        ) {

            refreshVoiceAccessState()
        }
    }

    /*
     * ============================================================
     * NOTIFICATION REMOVED
     * ============================================================
     */

    override fun onNotificationRemoved(
        sbn: StatusBarNotification
    ) {

        if (
            sbn.packageName ==
            VOICE_ACCESS_PACKAGE
        ) {

            refreshVoiceAccessState()
        }
    }

    /*
     * ============================================================
     * DETECT CURRENT VOICE ACCESS STATE
     * ============================================================
     */

    private fun refreshVoiceAccessState() {

        val voiceNotifications =
            try {

                activeNotifications
                    ?.filter {

                        it.packageName ==
                            VOICE_ACCESS_PACKAGE
                    }
                    ?: emptyList()

            } catch (_: Throwable) {

                emptyList()
            }

        /*
         * No Voice Access notification:
         *
         * Treat Voice Access as not listening.
         */

        if (
            voiceNotifications.isEmpty()
        ) {

            applyVoiceAccessState(
                false
            )

            return
        }

        /*
         * Inspect each Voice Access notification.
         */

        var detected:
            Boolean? =
            null

        for (
            sbn in voiceNotifications
        ) {

            val notification =
                sbn.notification

            val actionTexts =
                notification
                    .actions
                    ?.mapNotNull {

                        it.title
                            ?.toString()
                            ?.trim()
                            ?.lowercase()
                    }
                    ?: emptyList()

            /*
             * ----------------------------------------------------
             * ACTION BUTTONS
             * ----------------------------------------------------
             *
             * If Voice Access offers PAUSE, it is currently
             * listening.
             *
             * If it offers START / RESUME, it is currently paused.
             */

            if (
                actionTexts.any {

                    it ==
                        "start" ||

                    it.contains(
                        "start listening"
                    ) ||

                    it.contains(
                        "resume"
                    )
                }
            ) {

                detected =
                    false

                break
            }

            if (
                actionTexts.any {

                    it ==
                        "pause" ||

                    it.contains(
                        "stop listening"
                    )
                }
            ) {

                detected =
                    true

                break
            }

            /*
             * ----------------------------------------------------
             * NOTIFICATION TEXT
             * ----------------------------------------------------
             */

            val extras =
                notification.extras

            val title =
                extras
                    .getCharSequence(
                        Notification.EXTRA_TITLE
                    )
                    ?.toString()
                    .orEmpty()

            val text =
                extras
                    .getCharSequence(
                        Notification.EXTRA_TEXT
                    )
                    ?.toString()
                    .orEmpty()

            val bigText =
                extras
                    .getCharSequence(
                        Notification.EXTRA_BIG_TEXT
                    )
                    ?.toString()
                    .orEmpty()

            val subText =
                extras
                    .getCharSequence(
                        Notification.EXTRA_SUB_TEXT
                    )
                    ?.toString()
                    .orEmpty()

            val combined =
                "$title $text $bigText $subText"
                    .lowercase()

            /*
             * Paused states FIRST so "not listening"
             * cannot accidentally match "listening".
             */

            if (
                combined.contains(
                    "tap to start"
                ) ||

                combined.contains(
                    "touch to start"
                ) ||

                combined.contains(
                    "start listening"
                ) ||

                combined.contains(
                    "not listening"
                ) ||

                combined.contains(
                    "paused"
                )
            ) {

                detected =
                    false

                break
            }

            if (
                combined.contains(
                    "tap to pause"
                ) ||

                combined.contains(
                    "touch to pause"
                ) ||

                combined.contains(
                    "stop listening"
                ) ||

                combined.contains(
                    "is listening"
                )
            ) {

                detected =
                    true

                break
            }
        }

        /*
         * If Samsung/Google changes the wording and we cannot
         * confidently determine the state, preserve the current
         * state rather than randomly switching the audio route.
         */

        if (
            detected !=
            null
        ) {

            applyVoiceAccessState(
                detected
            )
        }
    }

    /*
     * ============================================================
     * APPLY STATE
     * ============================================================
     */

    private fun applyVoiceAccessState(
        listening: Boolean
    ) {

        if (
            lastDetectedState ==
            listening
        ) {

            return
        }

        lastDetectedState =
            listening

        voiceAccessListening =
            listening

        if (listening) {

            Logger.i(
                "Voice Access LISTENING -> Buds mic ON"
            )

            requestActivation()

        } else {

            Logger.i(
                "Voice Access PAUSED -> Buds mic OFF"
            )

            /*
             * Release immediately.
             *
             * Do not wait behind Bluetooth connection work.
             */

            try {

                routingManager
                    .clearRouting()

            } catch (e: Throwable) {

                Logger.e(
                    "Could not release Voice Access route",
                    e
                )
            }
        }
    }

    /*
     * ============================================================
     * START BACKGROUND ACTIVATION
     * ============================================================
     */

    private fun requestActivation() {

        if (
            !activationRunning
                .compareAndSet(
                    false,
                    true
                )
        ) {

            return
        }

        worker.execute {

            try {

                activateBudsMic()

            } finally {

                activationRunning.set(
                    false
                )
            }
        }
    }

    /*
     * ============================================================
     * ACTIVATE BUDS MIC
     * ============================================================
     */

    private fun activateBudsMic() {

        if (!voiceAccessListening) {

            return
        }

        /*
         * ========================================================
         * FAST PATH
         * ========================================================
         *
         * If TYPE_BLE_HEADSET already exists, do not touch
         * Shizuku, GATT, cache, or LE profile state.
         *
         * Just route it.
         */

        val existingBle =
            routingManager
                .findFirstBluetoothCommunicationDevice()

        if (
            existingBle !=
            null
        ) {

            if (voiceAccessListening) {

                routingManager
                    .routeToBluetooth(
                        existingBle
                    )
            }

            return
        }

        /*
         * ========================================================
         * FIND BUDS NAME
         * ========================================================
         */

        val devices =
            routingManager
                .availableDevices
                .value

        val buds =
            devices
                .firstOrNull {

                    it.deviceInfo.type ==
                        AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                ?: devices
                    .firstOrNull {

                        it.deviceInfo.type ==
                            AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
                ?: devices
                    .firstOrNull {

                        it.name.contains(
                            "Buds",
                            ignoreCase =
                                true
                        )
                    }

        if (
            buds ==
            null
        ) {

            Logger.w(
                "Voice Access automation: Buds not found"
            )

            return
        }

        val preferredName =
            buds.name

        /*
         * ========================================================
         * CACHE
         * ========================================================
         */

        val refresh =
            LeAudioCacheRefresher
                .refresh(
                    context =
                        applicationContext,

                    preferredDeviceName =
                        preferredName
                )

        if (
            !voiceAccessListening
        ) {

            return
        }

        if (
            !refresh.cacheUpdated ||
            !refresh.hasAscs
        ) {

            Logger.w(
                "Voice Access automation: LE cache not ready"
            )

            return
        }

        /*
         * ========================================================
         * LE AUDIO PROFILE
         * ========================================================
         */

        val connect =
            LeAudioShizukuBridge
                .forceLeAudio(
                    context =
                        applicationContext,

                    preferredDeviceName =
                        preferredName
                )

        if (
            !voiceAccessListening
        ) {

            return
        }

        val connected =
            connect.contains(
                "ACCEPTED - LE AUDIO CONNECTED"
            ) ||
                connect.contains(
                    "ACCEPTED - ALREADY CONNECTED"
                )

        if (!connected) {

            Logger.w(
                "Voice Access automation: LE Audio did not connect"
            )

            return
        }

        /*
         * Small settling delay.
         */

        try {

            Thread.sleep(
                500L
            )

        } catch (_: InterruptedException) {
        }

        if (
            !voiceAccessListening
        ) {

            return
        }

        /*
         * ========================================================
         * FINAL ROUTE
         * ========================================================
         */

        routingManager
            .routeToFirstAvailableBluetooth()
    }

    /*
     * ============================================================
     * DISCONNECTED
     * ============================================================
     */

    override fun onListenerDisconnected() {

        voiceAccessListening =
            false

        lastDetectedState =
            false

        try {

            routingManager
                .clearRouting()

        } catch (_: Throwable) {
        }

        super.onListenerDisconnected()
    }

    /*
     * ============================================================
     * DESTROY
     * ============================================================
     */

    override fun onDestroy() {

        voiceAccessListening =
            false

        try {

            routingManager
                .clearRouting()

        } catch (_: Throwable) {
        }

        try {

            routingManager
                .stopMonitoring()

        } catch (_: Throwable) {
        }

        worker.shutdownNow()

        super.onDestroy()
    }

    companion object {

        private const val
            VOICE_ACCESS_PACKAGE =
            "com.google.android.apps.accessibility.voiceaccess"
    }
    }
