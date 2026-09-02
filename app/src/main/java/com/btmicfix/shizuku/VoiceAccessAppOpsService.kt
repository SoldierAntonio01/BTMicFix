package com.btmicfix.shizuku

import android.app.AppOpsManager
import android.content.Context
import androidx.annotation.Keep
import com.btmicfix.IVoiceAccessOpCallback
import com.btmicfix.IVoiceAccessWatcher
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Privileged Voice Access microphone-state watcher.
 *
 * Runs as a Shizuku UserService.
 *
 * It watches Voice Access's RECORD_AUDIO AppOp.
 *
 * Voice Access starts listening:
 *      RECORD_AUDIO = ACTIVE
 *
 * Voice Access stops listening:
 *      RECORD_AUDIO = INACTIVE
 *
 * This is event-driven.
 * There is NO repeating polling loop.
 */
@Keep
class VoiceAccessAppOpsService() :
    IVoiceAccessWatcher.Stub() {

    /*
     * ============================================================
     * CONTEXT
     * ============================================================
     */

    private var serviceContext:
        Context? =
        null

    /*
     * Shizuku can construct the UserService with a Context.
     */
    @Keep
    constructor(
        context: Context
    ) : this() {

        serviceContext =
            context
    }

    /*
     * ============================================================
     * STATE
     * ============================================================
     */

    private val lock =
        Any()

    private var appOpsManager:
        AppOpsManager? =
        null

    private var listener:
        Any? =
        null

    private var clientCallback:
        IVoiceAccessOpCallback? =
        null

    private var targetUid =
        -1

    private var targetPackage =
        ""

    @Volatile
    private var targetActive =
        false

    /*
     * Run the tiny AppOps callback immediately.
     */
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
        callback: IVoiceAccessOpCallback
    ): String {

        synchronized(lock) {

            /*
             * Remove any older listener.
             */
            stopWatchLocked()

            this.targetUid =
                targetUid

            this.targetPackage =
                targetPackage

            this.clientCallback =
                callback

            /*
             * Get AppOpsManager from the Shizuku process.
             */
            val manager =
                getAppOpsManager()
                    ?: return """
                        ===== BTMicFix VOICE ACCESS APPOPS =====
                        FAILED

                        AppOpsManager unavailable inside
                        the Shizuku UserService.

                        ========================================
                    """.trimIndent()

            /*
             * ====================================================
             * HIDDEN LISTENER INTERFACE
             * ====================================================
             *
             * Compile SDK 34 doesn't expose this interface,
             * so load it by class name.
             */

            val listenerClass =
                try {

                    Class.forName(
                        "android.app.AppOpsManager" +
                            "\$OnOpActiveChangedListener"
                    )

                } catch (e: Throwable) {

                    return failure(
                        "OnOpActiveChangedListener unavailable.",
                        e
                    )
                }

            /*
             * ====================================================
             * CREATE LISTENER PROXY
             * ====================================================
             */

            val proxy =
                try {

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

                        when (method.name) {

                            "hashCode" -> {

                                System.identityHashCode(
                                    proxyObject
                                )
                            }

                            "equals" -> {

                                proxyObject ===
                                    args?.getOrNull(0)
                            }

                            "toString" -> {

                                "BTMicFixVoiceAccessAppOpsListener"
                            }

                            "onOpActiveChanged" -> {

                                handleActiveCallback(
                                    args
                                )

                                null
                            }

                            else -> {

                                null
                            }
                        }
                    }

                } catch (e: Throwable) {

                    return failure(
                        "Could not create AppOps listener.",
                        e
                    )
                }

            listener =
                proxy

            /*
             * ====================================================
             * FIND startWatchingActive()
             * ====================================================
             *
             * Expected Android form:
             *
             * startWatchingActive(
             *     String[] ops,
             *     Executor executor,
             *     OnOpActiveChangedListener listener
             * )
             */

            val startMethod =
                manager
                    .javaClass
                    .methods
                    .firstOrNull {
                            method ->

                        val types =
                            method.parameterTypes

                        method.name ==
                            "startWatchingActive" &&

                            types.size ==
                            3 &&

                            types[0].isArray &&

                            types[0].componentType ==
                            String::class.java &&

                            Executor::class.java
                                .isAssignableFrom(
                                    types[1]
                                ) &&

                            types[2].name ==
                            listenerClass.name
                    }

            if (startMethod == null) {

                listener =
                    null

                clientCallback =
                    null

                return """
                    ===== BTMicFix VOICE ACCESS APPOPS =====
                    FAILED

                    Samsung did not expose the expected:

                    startWatchingActive(
                        String[],
                        Executor,
                        OnOpActiveChangedListener
                    )

                    ========================================
                """.trimIndent()
            }

            /*
             * ====================================================
             * REGISTER WATCHER
             * ====================================================
             */

            try {

                startMethod.isAccessible =
                    true

                startMethod.invoke(
                    manager,
                    arrayOf(
                        RECORD_AUDIO_OP
                    ),
                    directExecutor,
                    proxy
                )

            } catch (e: Throwable) {

                listener =
                    null

                clientCallback =
                    null

                return failure(
                    "startWatchingActive() failed.",
                    unwrap(e)
                )
            }

            /*
             * ====================================================
             * CURRENT STATE
             * ====================================================
             *
             * Voice Access might already be listening when this
             * service starts.
             */

            val initialState =
                queryActive(
                    manager
                )
                    ?: false

            /*
             * Force initial state to the normal app process.
             */
            sendState(
                initialState,
                force = true
            )

            return """
                ===== BTMicFix VOICE ACCESS APPOPS =====
                WATCH ACTIVE

                Package:
                $targetPackage

                UID:
                $targetUid

                Watching:
                RECORD_AUDIO

                Current:
                ${
                    if (initialState) {
                        "ACTIVE"
                    } else {
                        "INACTIVE"
                    }
                }

                Polling:
                NONE

                ========================================
            """.trimIndent()
        }
    }

    /*
     * ============================================================
     * APPOPS CALLBACK
     * ============================================================
     */

    private fun handleActiveCallback(
        args:
            Array<out Any?>?
    ) {

        if (
            args == null ||
            args.size < 3
        ) {

            return
        }

        /*
         * Modern AppOps callback begins with:
         *
         * String op
         * int uid
         * String packageName
         */

        val op =
            args.getOrNull(0)
                as? String
                ?: return

        val uid =
            (
                args.getOrNull(1)
                    as? Number
                )
                ?.toInt()
                ?: return

        val packageName =
            args.getOrNull(2)
                as? String
                ?: return

        /*
         * Ignore every operation except RECORD_AUDIO.
         */

        if (
            op !=
            RECORD_AUDIO_OP
        ) {

            return
        }

        /*
         * Ignore every UID except Voice Access.
         */

        if (
            uid !=
            targetUid
        ) {

            return
        }

        /*
         * Ignore every package except Voice Access.
         */

        if (
            packageName !=
            targetPackage
        ) {

            return
        }

        /*
         * ========================================================
         * FIND ACTUAL ACTIVE STATE
         * ========================================================
         *
         * Prefer the aggregate AppOps state.
         */

        val aggregate =
            appOpsManager
                ?.let {

                    queryActive(it)
                }

        /*
         * Fallback:
         *
         * Samsung's callback should include a Boolean somewhere
         * representing active/inactive.
         */

        val callbackValue =
            args
                .firstOrNull {

                    it is Boolean
                } as? Boolean

        val active =
            aggregate
                ?: callbackValue
                ?: return

        sendState(
            active
        )
    }

    /*
     * ============================================================
     * QUERY CURRENT STATE
     * ============================================================
     */

    override fun isTargetActive():
        Boolean {

        synchronized(lock) {

            val manager =
                appOpsManager
                    ?: getAppOpsManager()
                    ?: return targetActive

            val active =
                queryActive(
                    manager
                )
                    ?: targetActive

            targetActive =
                active

            return active
        }
    }

    /*
     * ============================================================
     * HIDDEN isOpActive()
     * ============================================================
     */

    private fun queryActive(
        manager:
            AppOpsManager
    ): Boolean? {

        if (
            targetUid < 0 ||
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
                RECORD_AUDIO_OP,
                targetUid,
                targetPackage
            ) as? Boolean

        } catch (_: Throwable) {

            null
        }
    }

    /*
     * ============================================================
     * SEND STATE TO BTMICFIX
     * ============================================================
     */

    private fun sendState(
        active: Boolean,
        force: Boolean =
            false
    ) {

        /*
         * Avoid duplicate callbacks.
         */

        if (
            !force &&
            active ==
            targetActive
        ) {

            return
        }

        targetActive =
            active

        try {

            clientCallback
                ?.onRecordAudioActiveChanged(
                    active
                )

        } catch (_: Throwable) {

            /*
             * Main BTMicFix process may have died.
             */

            clientCallback =
                null
        }
    }

    /*
     * ============================================================
     * STOP WATCH
     * ============================================================
     */

    override fun stopWatch() {

        synchronized(lock) {

            stopWatchLocked()
        }
    }

    private fun stopWatchLocked() {

        val manager =
            appOpsManager

        val oldListener =
            listener

        if (
            manager != null &&
            oldListener != null
        ) {

            try {

                val stopMethod =
                    manager
                        .javaClass
                        .methods
                        .firstOrNull {
                                method ->

                            method.name ==
                                "stopWatchingActive" &&

                            method
                                .parameterTypes
                                .size ==
                                1
                        }

                if (
                    stopMethod != null
                ) {

                    stopMethod.isAccessible =
                        true

                    stopMethod.invoke(
                        manager,
                        oldListener
                    )
                }

            } catch (_: Throwable) {
            }
        }

        listener =
            null

        clientCallback =
            null

        targetUid =
            -1

        targetPackage =
            ""

        targetActive =
            false
    }

    /*
     * ============================================================
     * GET APPOPS MANAGER
     * ============================================================
     */

    private fun getAppOpsManager():
        AppOpsManager? {

        appOpsManager
            ?.let {

                return it
            }

        val context =
            serviceContext
                ?: return null

        val manager =
            try {

                context.getSystemService(
                    AppOpsManager::class.java
                )

            } catch (_: Throwable) {

                null
            }

        appOpsManager =
            manager

        return manager
    }

    /*
     * ============================================================
     * SHIZUKU DESTROY
     * ============================================================
     */

    override fun destroy() {

        synchronized(lock) {

            stopWatchLocked()
        }

        System.exit(0)
    }

    /*
     * ============================================================
     * ERROR HELPERS
     * ============================================================
     */

    private fun failure(
        title: String,
        throwable: Throwable
    ): String {

        val actual =
            unwrap(
                throwable
            )

        return """
            ===== BTMicFix VOICE ACCESS APPOPS =====
            FAILED

            $title

            ${actual.javaClass.name}

            ${actual.message}

            ========================================
        """.trimIndent()
    }

    private fun unwrap(
        throwable: Throwable
    ): Throwable {

        return if (
            throwable is
            InvocationTargetException
        ) {

            throwable.targetException
                ?: throwable

        } else {

            throwable
        }
    }

    /*
     * ============================================================
     * CONSTANT
     * ============================================================
     */

    companion object {

        private const val
            RECORD_AUDIO_OP =
            "android:record_audio"
    }
    }
