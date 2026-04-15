package com.btmicfix

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.audio.BluetoothStateReceiver
import com.btmicfix.companion.DeviceCompanionManager
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.ui.screens.HomeScreen
import com.btmicfix.ui.screens.SetupScreen
import com.btmicfix.ui.theme.BTMicFixTheme
import com.btmicfix.util.Logger
import com.btmicfix.util.Preferences

/**
 * Main (and only) Activity for BTMicFix.
 *
 * Manages the lifecycle of core managers and provides them to the Compose UI.
 * Uses simple screen-state navigation (no Jetpack Navigation — overkill for 2 screens).
 */
class MainActivity : ComponentActivity(), BluetoothStateReceiver.BluetoothConnectionListener {

    private lateinit var audioRoutingManager: AudioRoutingManager
    private lateinit var shizukuManager: ShizukuManager
    private lateinit var companionManager: DeviceCompanionManager
    private lateinit var preferences: Preferences
    private val btReceiver = BluetoothStateReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize managers
        audioRoutingManager = AudioRoutingManager(this)
        shizukuManager = ShizukuManager()
        companionManager = DeviceCompanionManager(this)
        preferences = Preferences(this)

        // Start monitoring
        audioRoutingManager.startMonitoring()
        shizukuManager.initialize()
        companionManager.resumeObservingAllAssociations()

        // Register BT state listener
        BluetoothStateReceiver.listener = this
        registerReceiver(
            btReceiver,
            BluetoothStateReceiver.getIntentFilter(),
            Context.RECEIVER_EXPORTED,
        )

        Logger.i("MainActivity created, all managers initialized")

        setContent {
            BTMicFixTheme {
                var currentScreen by remember {
                    mutableStateOf(
                        if (preferences.setupCompleted) Screen.Home else Screen.Setup
                    )
                }

                when (currentScreen) {
                    Screen.Home -> {
                        HomeScreen(
                            audioRoutingManager = audioRoutingManager,
                            shizukuManager = shizukuManager,
                            onSetupClick = { currentScreen = Screen.Setup },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Screen.Setup -> {
                        SetupScreen(
                            audioRoutingManager = audioRoutingManager,
                            shizukuManager = shizukuManager,
                            companionManager = companionManager,
                            preferences = preferences,
                            onBackClick = {
                                preferences.setupCompleted = true
                                currentScreen = Screen.Home
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh states when returning to the app
        shizukuManager.refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        BluetoothStateReceiver.listener = null
        try {
            unregisterReceiver(btReceiver)
        } catch (e: Exception) {
            // Receiver may not be registered
        }
        audioRoutingManager.stopMonitoring()
        shizukuManager.cleanup()
        Logger.i("MainActivity destroyed")
    }

    // -- BluetoothConnectionListener --

    override fun onBluetoothDeviceConnected(device: BluetoothDevice) {
        if (preferences.autoRouteEnabled) {
            Logger.i("Auto-routing triggered by BT connect")
            audioRoutingManager.routeToFirstAvailableBluetooth()
        }
    }

    override fun onBluetoothDeviceDisconnected(device: BluetoothDevice) {
        Logger.i("BT device disconnected, clearing routing")
        audioRoutingManager.clearRouting()
    }

    /**
     * Simple screen enum — no need for Jetpack Navigation with only 2 screens.
     */
    private enum class Screen {
        Home,
        Setup,
    }
}
