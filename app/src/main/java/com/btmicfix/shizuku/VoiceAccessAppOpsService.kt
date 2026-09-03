package com.btmicfix.shizuku

import android.app.AppOpsManager
import android.content.Context
import android.os.IBinder
import android.os.Process
import androidx.annotation.Keep
import com.btmicfix.IVoiceAccessOpCallback
import com.btmicfix.IVoiceAccessWatcher
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.Executor

/**
 * Privileged Voice Access microphone watcher.
 *
 * Runs as a Shizuku UserService (shell UID when Shizuku is started by ADB).
 *
 * It does two separate jobs:
 *
 * 1. Watches Voice Access RECORD_AUDIO:
 *      - hidden STARTED watcher = early hint
 *      - ACTIVE watcher = authoritative on/off state
 *
 * 2. While Voice Access is listening, temporarily tells Android audio policy
 *    to prefer the BLE Headset INPUT for the capture preset Voice Access uses.
 *
 * This is what targets the actual microphone path. Selecting only the
 * communication OUTPUT after Voice Access has already opened AudioRecord can
 * leave Voice Access on the phone mic.
 *
 * There is no repeating polling loop.
 */
@Keep
class VoiceAccessAppOpsService() :
    IVoiceAccessWatcher.Stub() {

    private var suppliedContext:
        Context? =
        null

    @Keep
    constructor(
        context: Context
    ) : this() {
        suppliedContext =
            context
    }

    private val lock =
        Any()

    /*
     * ============================================================
     * APPOPS
     * ============================================================
     */

    private var appOpsManager:
        AppOpsManager? =
        null

    private var activeListener:
        AppOpsManager.OnOpActiveChangedListener? =
        null

    /*
     * OnOpStartedListener is hidden from the normal SDK.
     * Keep it as Any and create it with a dynamic proxy.
     */
    private var startedListener:
        Any? =
        null

    private var startedListenerClass:
        Class<*>? =
        null

    private var recordAudioOpCode:
        Int =
        FALLBACK_RECORD_AUDIO_OP

    /*
     * ============================================================
     * TARGET APP
     * ============================================================
     */

    private var targetUid:
        Int =
        -1

    private var targetPackage:
        String =
        ""

    /*
     * ============================================================
     * BLE INPUT TARGET
     * ============================================================
     *
     * Public Android device type + address supplied by the normal
     * BTMicFix process.
     *
     * Address is never included in status text.
     */

    private var bleInputDeviceType:
        Int =
        -1

    private var bleInputAddress:
        String =
        ""

    /*
     * ============================================================
     * CLIENT CALLBACK
     * ============================================================
     */

    private var clientCallback:
        IVoiceAccessOpCallback? =
        null

    /*
     * ============================================================
     * STATUS
     * ============================================================
     */

    @Volatile
    private var currentActive:
        Boolean =
        false

    @Volatile
    private var activeWatcherRegistered:
        Boolean =
        false

    @Volatile
    private var startedWatcherRegistered:
        Boolean =
        false

    @Volatile
    private var capturePreferenceApplied:
        Boolean =
        false

    @Volatile
    private var lastAudioSource:
        Int =
        -1

    @Volatile
    private var lastActualInputType:
        Int =
        -1

    private var lastError:
        String =
        "NONE"

    /*
     * Every capture preset BTMicFix has temporarily overridden.
     * They are cleared when Voice Access stops.
     */
    private val appliedCapturePresets =
        linkedSetOf<Int>()

    /*
     * ============================================================
     * AUDIO SERVICE
     * ============================================================
     */

    private var audioService:
        Any? =
        null

    private var audioServiceInterface:
        Class<*>? =
        null

    private val directExecutor =
        Executor { runnable ->
            runnable.run()
        }

    /*
     * ============================================================
     * START WATCH
     * ============================================================
     */

    override fun startWatch(
        targetUid: Int,
        targetPackage: String,
        bleInputDeviceType: Int,
        bleInputAddress: String,
        callback: IVoiceAccessOpCallback
    ): String {

        synchronized(lock) {

            stopWatchLocked()

            this.targetUid =
                targetUid

            this.targetPackage =
                targetPackage

            this.bleInputDeviceType =
                bleInputDeviceType

            this.bleInputAddress =
                bleInputAddress

            this.clientCallback =
                callback

            lastError =
                "NONE"

            recordAudioOpCode =
                resolveRecordAudioOpCode()

            val manager =
                obtainAppOpsManager()

            if (
                manager ==
                null
            ) {
                lastError =
                    "Could not obtain AppOpsManager."

                return buildStatus(
                    "FAILED"
                )
            }

            /*
             * ====================================================
             * AUTHORITATIVE ACTIVE/INACTIVE WATCHER
             * ====================================================
             */

            val newActiveListener =
                object :
                    AppOpsManager.OnOpActiveChangedListener {

                    override fun onOpActiveChanged(
                        op: String,
                        uid: Int,
                        packageName: String,
                        active: Boolean
                    ) {

                        if (
                            op !=
                            AppOpsManager.OPSTR_RECORD_AUDIO
                        ) {
                            return
                        }

                        if (
                            uid !=
                            this@VoiceAccessAppOpsService.targetUid
                        ) {
                            return
                        }

                        if (
                            packageName !=
                            this@VoiceAccessAppOpsService.targetPackage
                        ) {
                            return
                        }

                        if (active) {

                            /*
                             * At this point the recorder exists.
                             *
                             * Ask privileged AudioService which AudioSource
                             * Voice Access actually opened, then prefer the
                             * BLE input for that exact capture preset.
                             *
                             * Fall back to VOICE_RECOGNITION because Voice
                             * Access is an accessibility voice recognizer.
                             */
                            val detectedSource =
                                discoverTargetRecordingSource()
                                    ?: AUDIO_SOURCE_VOICE_RECOGNITION

                            applyBleCapturePreference(
                                detectedSource
                            )

                            /*
                             * If the exact source was not VOICE_RECOGNITION,
                             * leave the early VOICE_RECOGNITION preference
                             * installed too until Voice Access turns off.
                             */
                            sendCaptureRoutingState(
                                capturePreferenceApplied,
                                detectedSource
                            )

                        } else {

                            /*
                             * Return Android capture policy to normal.
                             */
                            clearBleCapturePreferenceLocked()

                            sendCaptureRoutingState(
                                false,
                                lastAudioSource
                            )
                        }

                        val aggregateState =
                            queryCurrentState()

                        sendActiveState(
                            aggregateState
                                ?: active
                        )
                    }
                }

            try {

                manager.startWatchingActive(
                    arrayOf(
                        AppOpsManager.OPSTR_RECORD_AUDIO
                    ),
                    directExecutor,
                    newActiveListener
                )

                activeListener =
                    newActiveListener

                activeWatcherRegistered =
                    true

            } catch (e: Throwable) {

                activeListener =
                    null

                activeWatcherRegistered =
                    false

                lastError =
                    "startWatchingActive failed: " +
                        describeThrowable(
                            e
                        )

                return buildStatus(
                    "ACTIVE WATCH REGISTRATION FAILED"
                )
            }

            /*
             * ====================================================
             * EARLY START ATTEMPT WATCHER
             * ====================================================
             *
             * This hidden callback is useful as an earlier hint,
             * but Android dispatches it asynchronously. Therefore
             * BTMicFix does NOT rely on it alone.
             *
             * It primes:
             *      VOICE_RECOGNITION -> BLE Headset input
             *
             * and tells the normal process to select the BLE
             * communication device as early as possible.
             */

            startedWatcherRegistered =
                registerStartedWatcher(
                    manager
                )

            /*
             * ====================================================
             * INITIAL STATE
             * ====================================================
             */

            val initialState =
                queryCurrentState()
                    ?: false

            currentActive =
                initialState

            if (
                initialState
            ) {

                val detectedSource =
                    discoverTargetRecordingSource()
                        ?: AUDIO_SOURCE_VOICE_RECOGNITION

                applyBleCapturePreference(
                    detectedSource
                )

                sendCaptureRoutingState(
                    capturePreferenceApplied,
                    detectedSource
                )
            }

            sendActiveState(
                initialState,
                force =
                    true
            )

            return buildStatus(
                "WATCH ACTIVE"
            )
        }
    }

    /*
     * ============================================================
     * HIDDEN START-WATCHER REGISTRATION
     * ============================================================
     */

    private fun registerStartedWatcher(
        manager: AppOpsManager
    ): Boolean {

        return try {

            val listenerClass =
                Class.forName(
                    "android.app.AppOpsManager" +
                        "\$OnOpStartedListener"
                )

            startedListenerClass =
                listenerClass

            val proxy =
                Proxy.newProxyInstance(
                    listenerClass.classLoader
                        ?: javaClass.classLoader,
                    arrayOf(
                        listenerClass
                    )
                ) {
                        proxyObject,
                        method,
                        args ->

                    when (
                        method.name
                    ) {

                        "hashCode" -> {

                            System.identityHashCode(
                                proxyObject
                            )
                        }

                        "equals" -> {

                            proxyObject ===
                                args?.getOrNull(
                                    0
                                )
                        }

                        "toString" -> {

                            "BTMicFixVoiceAccessStartedListener"
                        }

                        "onOpStarted" -> {

                            handleStartedCallback(
                                args
                            )

                            null
                        }

                        else -> {

                            null
                        }
                    }
                }

            val startMethod =
                manager
                    .javaClass
                    .methods
                    .firstOrNull {
                            candidate ->

                        val types =
                            candidate.parameterTypes

                        candidate.name ==
                            "startWatchingStarted" &&

                            types.size ==
                            2 &&

                            types[0].isArray &&

                            types[0].componentType ==
                            Int::class.javaPrimitiveType &&

                            types[1].name ==
                            listenerClass.name
                    }

            if (
                startMethod ==
                null
            ) {

                lastError =
                    "Hidden startWatchingStarted() method not found."

                false

            } else {

                startMethod.isAccessible =
                    true

                startMethod.invoke(
                    manager,
                    intArrayOf(
                        recordAudioOpCode
                    ),
                    proxy
                )

                startedListener =
                    proxy

                true
            }

        } catch (e: Throwable) {

            lastError =
                "startWatchingStarted failed: " +
                    describeThrowable(
                        e
                    )

            false
        }
    }

    /*
     * ============================================================
     * STARTED CALLBACK
     * ============================================================
     *
     * Android has changed the tail of OnOpStartedListener's
     * parameter list across releases.
     *
     * The first three values remain:
     *      int op
     *      int uid
     *      String packageName
     *
     * so only those are required here.
     */

    private fun handleStartedCallback(
        args:
            Array<out Any?>?
    ) {

        if (
            args ==
            null ||
            args.size <
            3
        ) {
            return
        }

        val op =
            (
                args.getOrNull(
                    0
                ) as? Number
                )
                ?.toInt()
                ?: return

        val uid =
            (
                args.getOrNull(
                    1
                ) as? Number
                )
                ?.toInt()
                ?: return

        val packageName =
            args.getOrNull(
                2
            ) as? String
                ?: return

        if (
            op !=
            recordAudioOpCode
        ) {
            return
        }

        if (
            uid !=
            targetUid
        ) {
            return
        }

        if (
            packageName !=
            targetPackage
        ) {
            return
        }

        /*
         * Prime the likely Voice Access capture preset BEFORE the
         * normal app process receives the "starting" callback.
         *
         * If Voice Access actually uses another source, the ACTIVE
         * callback discovers it and applies that source too.
         */
        applyBleCapturePreference(
            AUDIO_SOURCE_VOICE_RECOGNITION
        )

        sendCaptureRoutingState(
            capturePreferenceApplied,
            AUDIO_SOURCE_VOICE_RECOGNITION
        )

        try {

            clientCallback
                ?.onRecordAudioStarting()

        } catch (e: Throwable) {

            lastError =
                "Starting callback failed: " +
                    describeThrowable(
                        e
                    )

            clientCallback =
                null
        }
    }

    /*
     * ============================================================
     * CURRENT APPOPS STATE
     * ============================================================
     */

    private fun queryCurrentState():
        Boolean? {

        val manager =
            appOpsManager
                ?: return null

        if (
            targetUid <
            0 ||
            targetPackage.isBlank()
        ) {
            return false
        }

        return try {

            val method =
                manager
                    .javaClass
                    .methods
                    .firstOrNull {
                            candidate ->

                        val types =
                            candidate.parameterTypes

                        candidate.name ==
                            "isOpActive" &&

                            types.size ==
                            3 &&

                            types[0] ==
                            String::class.java &&

                            types[1] ==
                            Int::class.javaPrimitiveType &&

                            types[2] ==
                            String::class.java
                    }
                    ?: return null

            method.isAccessible =
                true

            method.invoke(
                manager,
                AppOpsManager.OPSTR_RECORD_AUDIO,
                targetUid,
                targetPackage
            ) as? Boolean

        } catch (e: Throwable) {

            lastError =
                "isOpActive unavailable: " +
                    describeThrowable(
                        e
                    )

            null
        }
    }

    /*
     * ============================================================
     * ACTIVE CALLBACK TO MAIN PROCESS
     * ============================================================
     */

    private fun sendActiveState(
        active: Boolean,
        force: Boolean =
            false
    ) {

        if (
            !force &&
            active ==
            currentActive
        ) {
            return
        }

        currentActive =
            active

        try {

            clientCallback
                ?.onRecordAudioActiveChanged(
                    active
                )

        } catch (e: Throwable) {

            lastError =
                "Active callback failed: " +
                    describeThrowable(
                        e
                    )

            clientCallback =
                null
        }
    }

    /*
     * ============================================================
     * CAPTURE ROUTING CALLBACK TO MAIN PROCESS
     * ============================================================
     */

    private fun sendCaptureRoutingState(
        applied: Boolean,
        audioSource: Int
    ) {

        try {

            clientCallback
                ?.onCaptureRoutingChanged(
                    applied,
                    audioSource
                )

        } catch (e: Throwable) {

            lastError =
                "Capture callback failed: " +
                    describeThrowable(
                        e
                    )

            clientCallback =
                null
        }
    }

    /*
     * ============================================================
     * FIND VOICE ACCESS'S REAL CAPTURE PRESET
     * ============================================================
     *
     * The shell process has MODIFY_AUDIO_ROUTING, so AudioService
     * returns non-anonymized AudioRecordingConfiguration objects.
     */

    private fun discoverTargetRecordingSource():
        Int? {

        val service =
            obtainAudioService()
                ?: return null

        val iface =
            audioServiceInterface
                ?: return null

        return try {

            val method =
                iface
                    .methods
                    .firstOrNull {
                        it.name ==
                            "getActiveRecordingConfigurations" &&
                            it.parameterTypes.isEmpty()
                    }
                    ?: return null

            val configs =
                method.invoke(
                    service
                ) as? List<*>
                    ?: return null

            for (
                config in
                configs
            ) {

                if (
                    config ==
                    null
                ) {
                    continue
                }

                val configClass =
                    config.javaClass

                val uidMethod =
                    configClass
                        .methods
                        .firstOrNull {
                            it.name ==
                                "getClientUid" &&
                                it.parameterTypes.isEmpty()
                        }
                        ?: continue

                uidMethod.isAccessible =
                    true

                val uid =
                    (
                        uidMethod.invoke(
                            config
                        ) as? Number
                        )
                        ?.toInt()
                        ?: continue

                if (
                    uid !=
                    targetUid
                ) {
                    continue
                }

                val sourceMethod =
                    configClass
                        .methods
                        .firstOrNull {
                            it.name ==
                                "getClientAudioSource" &&
                                it.parameterTypes.isEmpty()
                        }
                        ?: continue

                val source =
                    (
                        sourceMethod.invoke(
                            config
                        ) as? Number
                        )
                        ?.toInt()
                        ?: continue

                lastAudioSource =
                    source

                /*
                 * Optional diagnostic: which input Android currently
                 * reports for Voice Access.
                 */
                try {

                    val deviceMethod =
                        configClass
                            .methods
                            .firstOrNull {
                                it.name ==
                                    "getAudioDevice" &&
                                    it.parameterTypes.isEmpty()
                            }

                    val device =
                        deviceMethod
                            ?.invoke(
                                config
                            )

                    if (
                        device !=
                        null
                    ) {

                        val typeMethod =
                            device
                                .javaClass
                                .methods
                                .firstOrNull {
                                    it.name ==
                                        "getType" &&
                                        it.parameterTypes.isEmpty()
                                }

                        lastActualInputType =
                            (
                                typeMethod
                                    ?.invoke(
                                        device
                                    ) as? Number
                                )
                                ?.toInt()
                                ?: -1
                    }

                } catch (_: Throwable) {
                }

                return source
            }

            null

        } catch (e: Throwable) {

            lastError =
                "Recording config query failed: " +
                    describeThrowable(
                        e
                    )

            null
        }
    }

    /*
     * ============================================================
     * APPLY BLE INPUT TO CAPTURE PRESET
     * ============================================================
     *
     * This is the important microphone-routing piece.
     *
     * It calls privileged AudioService:
     *
     * setPreferredDevicesForCapturePreset(
     *     audioSource,
     *     [BLE_HEADSET_INPUT]
     * )
     *
     * AudioService requires MODIFY_AUDIO_ROUTING; Android grants
     * that permission to the shell UID used by Shizuku.
     */

    private fun applyBleCapturePreference(
        audioSource: Int
    ): Boolean {

        if (
            bleInputDeviceType <=
            0
        ) {

            lastError =
                "BLE input device type was not supplied."

            capturePreferenceApplied =
                false

            return false
        }

        val service =
            obtainAudioService()
                ?: run {

                    capturePreferenceApplied =
                        false

                    return false
                }

        val iface =
            audioServiceInterface
                ?: run {

                    capturePreferenceApplied =
                        false

                    return false
                }

        val attributes =
            createBleInputAttributes()
                ?: run {

                    capturePreferenceApplied =
                        false

                    return false
                }

        return try {

            val method =
                iface
                    .methods
                    .firstOrNull {
                            candidate ->

                        candidate.name ==
                            "setPreferredDevicesForCapturePreset" &&

                            candidate
                                .parameterTypes
                                .size ==
                            2
                    }
                    ?: run {

                        lastError =
                            "IAudioService.setPreferredDevicesForCapturePreset missing."

                        capturePreferenceApplied =
                            false

                        return false
                    }

            method.isAccessible =
                true

            val result =
                method.invoke(
                    service,
                    audioSource,
                    Collections.singletonList(
                        attributes
                    )
                )

            val status =
                (
                    result as? Number
                    )
                    ?.toInt()
                    ?: -1

            if (
                status ==
                AUDIO_STATUS_SUCCESS
            ) {

                appliedCapturePresets.add(
                    audioSource
                )

                capturePreferenceApplied =
                    true

                lastAudioSource =
                    audioSource

                true

            } else {

                lastError =
                    "setPreferredDevicesForCapturePreset returned $status for source $audioSource."

                capturePreferenceApplied =
                    appliedCapturePresets
                        .isNotEmpty()

                false
            }

        } catch (e: Throwable) {

            lastError =
                "BLE capture preference failed: " +
                    describeThrowable(
                        e
                    )

            capturePreferenceApplied =
                appliedCapturePresets
                    .isNotEmpty()

            false
        }
    }

    /*
     * ============================================================
     * BUILD BLE INPUT ATTRIBUTES
     * ============================================================
     */

    private fun createBleInputAttributes():
        Any? {

        return try {

            val attributesClass =
                Class.forName(
                    "android.media.AudioDeviceAttributes"
                )

            val roleInput =
                attributesClass
                    .getField(
                        "ROLE_INPUT"
                    )
                    .getInt(
                        null
                    )

            val constructor =
                attributesClass
                    .constructors
                    .firstOrNull {
                            ctor ->

                        val types =
                            ctor.parameterTypes

                        types.size ==
                            3 &&

                            types[0] ==
                            Int::class.javaPrimitiveType &&

                            types[1] ==
                            Int::class.javaPrimitiveType &&

                            types[2] ==
                            String::class.java
                    }
                    ?: run {

                        lastError =
                            "AudioDeviceAttributes(ROLE,type,address) constructor missing."

                        return null
                    }

            constructor.isAccessible =
                true

            constructor.newInstance(
                roleInput,
                bleInputDeviceType,
                bleInputAddress
            )

        } catch (e: Throwable) {

            lastError =
                "Could not build BLE input AudioDeviceAttributes: " +
                    describeThrowable(
                        e
                    )

            null
        }
    }

    /*
     * ============================================================
     * CLEAR TEMPORARY CAPTURE PREFERENCE
     * ============================================================
     */

    /*
     * ============================================================
     * UPDATE REAL BLE INPUT TARGET
     * ============================================================
     *
     * Called by the normal BTMicFix process after it enumerates
     * AudioManager.GET_DEVICES_INPUTS and finds the real
     * TYPE_BLE_HEADSET source.
     */

    override fun updateBleInputTarget(
        bleInputDeviceType: Int,
        bleInputAddress: String
    ): String {

        synchronized(lock) {

            this.bleInputDeviceType =
                bleInputDeviceType

            this.bleInputAddress =
                bleInputAddress

            lastError =
                "NONE"

            return buildStatus(
                "BLE INPUT TARGET UPDATED"
            )
        }
    }

    /*
     * ============================================================
     * RE-APPLY CAPTURE PREFERENCE NOW
     * ============================================================
     *
     * Useful after Samsung exposes the BLE input a moment after
     * setCommunicationDevice() is accepted.
     */

    override fun refreshCapturePreference():
        Boolean {

        synchronized(lock) {

            val source =
                discoverTargetRecordingSource()
                    ?: AUDIO_SOURCE_VOICE_RECOGNITION

            val applied =
                applyBleCapturePreference(
                    source
                )

            sendCaptureRoutingState(
                applied,
                source
            )

            return applied
        }
    }

    override fun clearCapturePreference() {

        synchronized(lock) {

            clearBleCapturePreferenceLocked()

            sendCaptureRoutingState(
                false,
                lastAudioSource
            )
        }
    }

    private fun clearBleCapturePreferenceLocked() {

        val service =
            obtainAudioService()

        val iface =
            audioServiceInterface

        if (
            service ==
            null ||
            iface ==
            null
        ) {

            appliedCapturePresets.clear()

            capturePreferenceApplied =
                false

            return
        }

        val presets =
            appliedCapturePresets
                .toList()

        try {

            val clearMethod =
                iface
                    .methods
                    .firstOrNull {
                            candidate ->

                        candidate.name ==
                            "clearPreferredDevicesForCapturePreset" &&

                            candidate
                                .parameterTypes
                                .size ==
                            1
                    }

            if (
                clearMethod !=
                null
            ) {

                clearMethod.isAccessible =
                    true

                for (
                    preset in
                    presets
                ) {

                    try {

                        clearMethod.invoke(
                            service,
                            preset
                        )

                    } catch (_: Throwable) {
                    }
                }
            }

        } finally {

            appliedCapturePresets.clear()

            capturePreferenceApplied =
                false
        }
    }

    /*
     * ============================================================
     * RAW AUDIO SERVICE
     * ============================================================
     */

    private fun obtainAudioService():
        Any? {

        audioService
            ?.let {

                return it
            }

        return try {

            val binder:
                IBinder =
                SystemServiceHelper
                    .getSystemService(
                        Context.AUDIO_SERVICE
                    )
                    ?: run {

                        lastError =
                            "Android audio Binder is NULL."

                        return null
                    }

            val stubClass =
                Class.forName(
                    "android.media.IAudioService\$Stub"
                )

            val ifaceClass =
                Class.forName(
                    "android.media.IAudioService"
                )

            val asInterface =
                stubClass
                    .methods
                    .firstOrNull {
                            method ->

                        method.name ==
                            "asInterface" &&

                            method
                                .parameterTypes
                                .size ==
                            1
                    }
                    ?: run {

                        lastError =
                            "IAudioService.Stub.asInterface missing."

                        return null
                    }

            asInterface.isAccessible =
                true

            val service =
                asInterface.invoke(
                    null,
                    binder
                )
                    ?: run {

                        lastError =
                            "IAudioService interface is NULL."

                        return null
                    }

            audioService =
                service

            audioServiceInterface =
                ifaceClass

            service

        } catch (e: Throwable) {

            lastError =
                "Could not obtain IAudioService: " +
                    describeThrowable(
                        e
                    )

            null
        }
    }

    /*
     * ============================================================
     * GET APPOPS MANAGER
     * ============================================================
     */

    private fun obtainAppOpsManager():
        AppOpsManager? {

        appOpsManager
            ?.let {

                return it
            }

        val context =
            obtainUsableContext()
                ?: run {

                    lastError =
                        "No usable Context in Shizuku UserService."

                    return null
                }

        try {

            val manager =
                context.getSystemService(
                    AppOpsManager::class.java
                )

            if (
                manager !=
                null
            ) {

                appOpsManager =
                    manager

                return manager
            }

        } catch (e: Throwable) {

            lastError =
                "Context AppOpsManager failed: " +
                    describeThrowable(
                        e
                    )
        }

        /*
         * Fallback: construct AppOpsManager around the raw appops Binder.
         */

        return try {

            val binder =
                SystemServiceHelper
                    .getSystemService(
                        Context.APP_OPS_SERVICE
                    )
                    ?: run {

                        lastError =
                            "Raw appops Binder is NULL."

                        return null
                    }

            val stubClass =
                Class.forName(
                    "com.android.internal.app.IAppOpsService\$Stub"
                )

            val asInterface =
                stubClass
                    .methods
                    .firstOrNull {
                            method ->

                        method.name ==
                            "asInterface" &&

                            method
                                .parameterTypes
                                .size ==
                            1
                    }
                    ?: run {

                        lastError =
                            "IAppOpsService.Stub.asInterface missing."

                        return null
                    }

            asInterface.isAccessible =
                true

            val internalService =
                asInterface.invoke(
                    null,
                    binder
                )
                    ?: run {

                        lastError =
                            "IAppOpsService interface is NULL."

                        return null
                    }

            val constructor =
                AppOpsManager::class.java
                    .declaredConstructors
                    .firstOrNull {
                            ctor ->

                        val types =
                            ctor.parameterTypes

                        types.size ==
                            2 &&

                            Context::class.java
                                .isAssignableFrom(
                                    types[0]
                                ) &&

                            types[1].name ==
                            "com.android.internal.app.IAppOpsService"
                    }
                    ?: run {

                        lastError =
                            "AppOpsManager(Context,IAppOpsService) constructor missing."

                        return null
                    }

            constructor.isAccessible =
                true

            val manager =
                constructor.newInstance(
                    context,
                    internalService
                ) as? AppOpsManager
                    ?: run {

                        lastError =
                            "Constructed AppOpsManager is NULL."

                        return null
                    }

            appOpsManager =
                manager

            manager

        } catch (e: Throwable) {

            lastError =
                "Raw AppOpsManager creation failed: " +
                    describeThrowable(
                        e
                    )

            null
        }
    }

    /*
     * ============================================================
     * CONTEXT
     * ============================================================
     */

    private fun obtainUsableContext():
        Context? {

        suppliedContext
            ?.let {

                return it
            }

        return try {

            val activityThreadClass =
                Class.forName(
                    "android.app.ActivityThread"
                )

            val currentMethod =
                activityThreadClass
                    .getDeclaredMethod(
                        "currentActivityThread"
                    )

            currentMethod.isAccessible =
                true

            val thread =
                currentMethod.invoke(
                    null
                )
                    ?: return null

            val systemContextMethod =
                activityThreadClass
                    .getDeclaredMethod(
                        "getSystemContext"
                    )

            systemContextMethod.isAccessible =
                true

            val context =
                systemContextMethod.invoke(
                    thread
                ) as? Context

            suppliedContext =
                context

            context

        } catch (e: Throwable) {

            lastError =
                "Context recovery failed: " +
                    describeThrowable(
                        e
                    )

            null
        }
    }

    /*
     * ============================================================
     * RECORD_AUDIO INTEGER OP CODE
     * ============================================================
     */

    private fun resolveRecordAudioOpCode():
        Int {

        return try {

            val field =
                AppOpsManager::class.java
                    .getDeclaredField(
                        "OP_RECORD_AUDIO"
                    )

            field.isAccessible =
                true

            field.getInt(
                null
            )

        } catch (_: Throwable) {

            FALLBACK_RECORD_AUDIO_OP
        }
    }

    /*
     * ============================================================
     * PUBLIC STATE / STATUS
     * ============================================================
     */

    override fun isTargetActive():
        Boolean {

        synchronized(lock) {

            val actual =
                queryCurrentState()

            if (
                actual !=
                null
            ) {

                currentActive =
                    actual
            }

            return currentActive
        }
    }

    override fun getStatus():
        String {

        synchronized(lock) {

            return buildStatus(
                if (
                    activeWatcherRegistered
                ) {
                    "WATCH ACTIVE"
                } else {
                    "WATCH NOT ACTIVE"
                }
            )
        }
    }

    private fun buildStatus(
        title: String
    ): String {

        return """
            ===== BTMicFix VOICE ACCESS APPOPS =====
            $title

            UserService UID:
            ${Process.myUid()}

            Active watcher:
            ${yesNo(activeWatcherRegistered)}

            Started watcher:
            ${yesNo(startedWatcherRegistered)}

            BLE input target supplied:
            ${yesNo(bleInputDeviceType > 0)}

            RECORD_AUDIO:
            ${if (currentActive) "ACTIVE" else "INACTIVE"}

            Capture preference:
            ${if (capturePreferenceApplied) "BLE INPUT PREFERRED" else "DEFAULT"}

            Detected capture source:
            $lastAudioSource

            Android-reported input type:
            $lastActualInputType

            Last error:
            $lastError

            =========================================
        """.trimIndent()
    }

    /*
     * ============================================================
     * STOP
     * ============================================================
     */

    override fun stopWatch() {

        synchronized(lock) {

            stopWatchLocked()
        }
    }

    private fun stopWatchLocked() {

        clearBleCapturePreferenceLocked()

        val manager =
            appOpsManager

        val oldActiveListener =
            activeListener

        if (
            manager !=
            null &&
            oldActiveListener !=
            null
        ) {

            try {

                manager.stopWatchingActive(
                    oldActiveListener
                )

            } catch (_: Throwable) {
            }
        }

        val oldStartedListener =
            startedListener

        val oldStartedClass =
            startedListenerClass

        if (
            manager !=
            null &&
            oldStartedListener !=
            null &&
            oldStartedClass !=
            null
        ) {

            try {

                val stopMethod =
                    manager
                        .javaClass
                        .methods
                        .firstOrNull {
                                candidate ->

                            candidate.name ==
                                "stopWatchingStarted" &&

                                candidate
                                    .parameterTypes
                                    .size ==
                                1 &&

                                candidate
                                    .parameterTypes[0]
                                    .name ==
                                oldStartedClass.name
                        }

                if (
                    stopMethod !=
                    null
                ) {

                    stopMethod.isAccessible =
                        true

                    stopMethod.invoke(
                        manager,
                        oldStartedListener
                    )
                }

            } catch (_: Throwable) {
            }
        }

        activeListener =
            null

        startedListener =
            null

        startedListenerClass =
            null

        activeWatcherRegistered =
            false

        startedWatcherRegistered =
            false

        clientCallback =
            null

        targetUid =
            -1

        targetPackage =
            ""

        bleInputDeviceType =
            -1

        bleInputAddress =
            ""

        currentActive =
            false

        capturePreferenceApplied =
            false

        lastAudioSource =
            -1

        lastActualInputType =
            -1
    }

    override fun destroy() {

        synchronized(lock) {

            stopWatchLocked()
        }

        System.exit(
            0
        )
    }

    /*
     * ============================================================
     * HELPERS
     * ============================================================
     */

    private fun yesNo(
        value: Boolean
    ): String {

        return if (
            value
        ) {
            "YES"
        } else {
            "NO"
        }
    }

    private fun describeThrowable(
        throwable: Throwable
    ): String {

        val actual =
            if (
                throwable is
                InvocationTargetException
            ) {

                throwable.targetException
                    ?: throwable

            } else {

                throwable
            }

        return (
            actual.javaClass.simpleName +
                ": " +
                (
                    actual.message
                        ?: "no message"
                    )
            )
    }

    companion object {

        /*
         * OP_RECORD_AUDIO has been 27 for Android's app-op table.
         * Reflection is attempted first; this is only a fallback.
         */
        private const val FALLBACK_RECORD_AUDIO_OP =
            27

        /*
         * MediaRecorder.AudioSource constants.
         *
         * Avoid references to hidden/system source annotations in
         * this privileged reflection layer.
         */
        private const val AUDIO_SOURCE_VOICE_RECOGNITION =
            6

        private const val AUDIO_STATUS_SUCCESS =
            0
    }
}
