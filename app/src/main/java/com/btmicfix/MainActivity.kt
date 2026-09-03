package com.btmicfix

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationManagerCompat
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.audio.BluetoothStateReceiver
import com.btmicfix.automation.VoiceAccessAutomationState
import com.btmicfix.automation.VoiceAccessMonitorService
import com.btmicfix.companion.DeviceCompanionManager
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.ui.screens.HomeScreen
import com.btmicfix.ui.screens.SetupScreen
import com.btmicfix.ui.theme.BTMicFixTheme
import com.btmicfix.util.Logger
import com.btmicfix.util.Preferences

class MainActivity :
    ComponentActivity(),
    BluetoothStateReceiver.BluetoothConnectionListener {

    private lateinit var audioRoutingManager:
        AudioRoutingManager

    private lateinit var shizukuManager:
        ShizukuManager

    private lateinit var companionManager:
        DeviceCompanionManager

    private lateinit var preferences:
        Preferences

    private val btReceiver =
        BluetoothStateReceiver()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        enableEdgeToEdge()

        /*
         * ========================================================
         * MANAGERS
         * ========================================================
         */

        audioRoutingManager =
            AudioRoutingManager(
                this
            )

        shizukuManager =
            ShizukuManager()

        companionManager =
            DeviceCompanionManager(
                this
            )

        preferences =
            Preferences(
                this
            )

        /*
         * ========================================================
         * MONITORING
         * ========================================================
         */

        audioRoutingManager
            .startMonitoring()

        shizukuManager
            .initialize()

        companionManager
            .resumeObservingAllAssociations()

        /*
         * ========================================================
         * BLUETOOTH RECEIVER
         * ========================================================
         */

        BluetoothStateReceiver.listener =
            this

        registerReceiver(
            btReceiver,
            BluetoothStateReceiver
                .getIntentFilter(),
            Context.RECEIVER_EXPORTED
        )

        /*
         * ========================================================
         * VOICE ACCESS AUTOMATION
         * ========================================================
         */

        requestVoiceAccessAutomationRebind()

        /*
         * ========================================================
         * UI
         * ========================================================
         */

        setContent {

            BTMicFixTheme {

                var currentScreen by remember {

                    mutableStateOf(
                        if (
                            preferences.setupCompleted
                        ) {

                            Screen.Home

                        } else {

                            Screen.Setup
                        }
                    )
                }

                when (
                    currentScreen
                ) {

                    Screen.Home -> {

                        HomeScreen(
                            audioRoutingManager =
                                audioRoutingManager,

                            shizukuManager =
                                shizukuManager,

                            onSetupClick = {

                                currentScreen =
                                    Screen.Setup
                            },

                            modifier =
                                Modifier.fillMaxSize()
                        )
                    }

                    Screen.Setup -> {

                        SetupScreen(
                            audioRoutingManager =
                                audioRoutingManager,

                            shizukuManager =
                                shizukuManager,

                            companionManager =
                                companionManager,

                            preferences =
                                preferences,

                            onBackClick = {

                                preferences.setupCompleted =
                                    true

                                currentScreen =
                                    Screen.Home
                            },

                            modifier =
                                Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    /*
     * ============================================================
     * RESUME
     * ============================================================
     */

    override fun onResume() {

        super.onResume()

        shizukuManager
            .refreshStatus()

        /*
         * Important after returning from:
         *
         * Settings -> Notification access
         */

        requestVoiceAccessAutomationRebind()
    }

    /*
     * ============================================================
     * NOTIFICATION LISTENER REBIND
     * ============================================================
     */

    private fun requestVoiceAccessAutomationRebind() {

        val enabled =
            NotificationManagerCompat
                .getEnabledListenerPackages(
                    this
                )
                .contains(
                    packageName
                )

        VoiceAccessAutomationState.update {

            it.copy(
                notificationAccessEnabled =
                    enabled,

                lastMessage =
                    if (enabled) {
                        "Notification Access is ON. Requesting automation host rebind."
                    } else {
                        "Notification Access is OFF."
                    }
            )
        }

        if (
            !enabled
        ) {

            return
        }

        try {

            NotificationListenerService
                .requestRebind(
                    ComponentName(
                        this,
                        VoiceAccessMonitorService::class.java
                    )
                )

        } catch (e: Throwable) {

            VoiceAccessAutomationState.update {

                it.copy(
                    lastMessage =
                        "requestRebind failed: ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    /*
     * ============================================================
     * DESTROY
     * ============================================================
     */

    override fun onDestroy() {

        BluetoothStateReceiver.listener =
            null

        try {

            unregisterReceiver(
                btReceiver
            )

        } catch (_: Exception) {
        }

        audioRoutingManager
            .stopMonitoring()

        shizukuManager
            .cleanup()

        super.onDestroy()
    }

    /*
     * ============================================================
     * BLUETOOTH EVENTS
     * ============================================================
     */

    override fun onBluetoothDeviceConnected(
        device: BluetoothDevice
    ) {

        /*
         * Keep your existing normal auto-route preference.
         *
         * If you don't use that preference, Voice Access automation
         * still works independently through AppOps.
         */

        if (
            preferences.autoRouteEnabled
        ) {

            audioRoutingManager
                .routeToFirstAvailableBluetooth()
        }
    }

    override fun onBluetoothDeviceDisconnected(
        device: BluetoothDevice
    ) {

        audioRoutingManager
            .clearRouting()
    }

    private enum class Screen {
        Home,
        Setup
    }
}
