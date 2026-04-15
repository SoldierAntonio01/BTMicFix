package com.btmicfix.companion

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.getSystemService
import com.btmicfix.util.Logger
import com.btmicfix.util.Preferences

/**
 * Manages Companion Device Manager (CDM) associations.
 *
 * CDM allows the app to pair with specific Bluetooth earbuds and receive
 * system callbacks when those earbuds connect/disconnect, even when the
 * app is not running. This is much more battery-efficient than constantly
 * scanning for Bluetooth devices.
 */
class DeviceCompanionManager(private val context: Context) {

    private val companionDeviceManager: CompanionDeviceManager? =
        context.getSystemService<CompanionDeviceManager>()

    private val preferences = Preferences(context)

    /**
     * Check if CDM is available on this device.
     */
    fun isAvailable(): Boolean = companionDeviceManager != null

    /**
     * Start the device association flow.
     * This opens a system dialog where the user selects their Bluetooth earbuds.
     *
     * @param launcher The ActivityResultLauncher to handle the association result.
     */
    fun startAssociation(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val cdm = companionDeviceManager ?: run {
            Logger.e("CompanionDeviceManager not available on this device")
            return
        }

        // Filter for Bluetooth devices only
        val deviceFilter = BluetoothDeviceFilter.Builder().build()

        val associationRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false) // Show all matching devices
            .build()

        Logger.i("Starting CDM association flow")

        cdm.associate(
            associationRequest,
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    Logger.i("CDM association pending, launching picker")
                    val request = IntentSenderRequest.Builder(intentSender).build()
                    launcher.launch(request)
                }

                @Deprecated("Deprecated in API 33+", ReplaceWith("onAssociationCreated"))
                override fun onDeviceFound(intentSender: IntentSender) {
                    // Legacy path for API 31-32
                    Logger.i("CDM device found (legacy), launching picker")
                    val request = IntentSenderRequest.Builder(intentSender).build()
                    launcher.launch(request)
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    Logger.i("CDM association created: ${associationInfo.id}")
                    // Start observing presence for this association
                    startObservingPresence(associationInfo.id)
                }

                override fun onFailure(error: CharSequence?) {
                    Logger.e("CDM association failed: $error")
                }
            },
            null // Handler (null = main thread)
        )
    }

    /**
     * Start observing device presence for a given association.
     * When the device appears, the system will bind our BTCompanionService.
     */
    fun startObservingPresence(associationId: Int) {
        val cdm = companionDeviceManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm.startObservingDevicePresence(associationId.toString())
            }
            Logger.i("Started observing presence for association $associationId")
        } catch (e: Exception) {
            Logger.e("Failed to start observing presence", e)
        }
    }

    /**
     * Get all current associations.
     */
    fun getAssociations(): List<AssociationInfo> {
        val cdm = companionDeviceManager ?: return emptyList()
        return try {
            cdm.myAssociations
        } catch (e: Exception) {
            Logger.e("Error getting associations", e)
            emptyList()
        }
    }

    /**
     * Remove an association (unpair from CDM — does not affect Bluetooth pairing).
     */
    fun removeAssociation(associationId: Int) {
        val cdm = companionDeviceManager ?: return
        try {
            cdm.disassociate(associationId)
            preferences.clearPairedDevice()
            Logger.i("Removed CDM association $associationId")
        } catch (e: Exception) {
            Logger.e("Error removing association", e)
        }
    }

    /**
     * Resume observing presence for all existing associations.
     * Call this on app startup to re-register background detection.
     */
    fun resumeObservingAllAssociations() {
        val associations = getAssociations()
        for (association in associations) {
            startObservingPresence(association.id)
        }
        if (associations.isNotEmpty()) {
            Logger.i("Resumed observing ${associations.size} CDM association(s)")
        }
    }
}
