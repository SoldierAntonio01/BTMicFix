package com.btmicfix.audio

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.btmicfix.BuildConfig
import com.btmicfix.util.Logger
import java.lang.ref.WeakReference

/**
 * Broadcast receiver that listens for Bluetooth ACL connection state changes.
 *
 * When a Bluetooth device connects, this receiver checks if auto-routing is
 * enabled and triggers the audio routing fix. When a device disconnects, it
 * clears the routing.
 *
 * Registered dynamically in MainActivity for foreground reliability.
 * Background operation is handled by BTCompanionService via CDM.
 */
class BluetoothStateReceiver : BroadcastReceiver() {

    companion object {
        /**
         * Listener interface for components that want to react to BT state changes.
         * Uses WeakReference to prevent Activity leaks if onDestroy doesn't fire.
         */
        private var listenerRef: WeakReference<BluetoothConnectionListener>? = null

        var listener: BluetoothConnectionListener?
            get() = listenerRef?.get()
            set(value) {
                listenerRef = value?.let { WeakReference(it) }
            }

        fun getIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        }
    }

    interface BluetoothConnectionListener {
        fun onBluetoothDeviceConnected(device: BluetoothDevice)
        fun onBluetoothDeviceDisconnected(device: BluetoothDevice)
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Check BLUETOOTH_CONNECT permission (required on Android 12+)
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Logger.w("BLUETOOTH_CONNECT permission not granted, ignoring broadcast")
            return
        }

        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        if (device == null) {
            Logger.w("BT broadcast received but no device in extras")
            return
        }

        val deviceName = try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }

        // Redact MAC address in release builds (PII)
        val safeAddress = if (BuildConfig.DEBUG) {
            device.address
        } else {
            "XX:XX:XX:XX:" + device.address.takeLast(5)
        }

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Logger.i("BT device connected: $deviceName ($safeAddress)")
                listener?.onBluetoothDeviceConnected(device)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Logger.i("BT device disconnected: $deviceName ($safeAddress)")
                listener?.onBluetoothDeviceDisconnected(device)
            }
        }
    }
}
