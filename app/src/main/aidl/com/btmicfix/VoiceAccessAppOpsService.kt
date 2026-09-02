package com.btmicfix.shizuku

import android.app.AppOpsManager
import android.content.Context
import android.os.IBinder
import androidx.annotation.Keep
import com.btmicfix.IVoiceAccessOpCallback
import com.btmicfix.IVoiceAccessWatcher
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Privileged Voice Access microphone watcher.
 *
 * This class runs inside a Shizuku UserService process.
 *
 * With normal ADB-started Shizuku that process runs as:
 *
 * UID 2000 = shell
 *
 * Android grants shell WATCH_APPOPS, allowing us to watch
 * RECORD_AUDIO activity belonging to Voice Access.
 *
 *
 * NO POLLING.
 *
 * Android itself sends us an AppOps callback when:
 *
 * Voice Access RECORD_AUDIO:
 *
 * inactive -> active
 *
 * or:
 *
 * active -> inactive
 */
@Keep
class VoiceAccessAppOpsService() :
    IVoiceAccessWatcher.Stub() {

    /*
     * ============================================================
     * SHIZUKU CONTEXT
     * ============================================================
     */

    private var providedContext:
        Context? =
        null

    /*
     * Shizuku v13+ will prefer this constructor.
     */
    @Keep
    constructor(
        context: Context
    ) : this() {

        providedContext =
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

    private var listenerProxy:
        Any? =
        null

    private var remoteCallback:
        IVoiceAccessOpCallback? =
        null

    private var watchedUid =
        -1

    private var watchedPackage =
        ""

    @Volatile
    private var currentActive =
        false

    /*
     * Run AppOps callback work immediately on the Binder callback
     * thread. The operation is extremely small.
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

        synchronized(
            lock
        ) {

            /*
             * Remove an older listener first.
             */
            stopWatchLocked()

            watchedUid =
                targetUid

            watchedPackage =
                targetPackage

            remoteCallback =
                callback

            val manager =
                obtainAppOpsManager()
                    ?: return """
                        ===== BTMicFix VOICE ACCESS APPOPS =====
                        FAILED

                        Could not obtain AppOpsManager
                        inside the Shizuku UserService.

                        ========================================
                    """.trimIndent()

            appOpsManager =
                manager

            /*
             * ====================================================
             * LOAD HIDDEN LISTENER INTERFACE
             * ====================================================
             */

            val listenerClass =
                try {

                    Class.forName(
                        "android.app.AppOpsManager" +
                            "\$OnOpActiveChangedListener"
                    )

                } catch (
                    e: Throwable
                ) {

                    return failureText(
                        "Could not load " +
                            "OnOpActiveChangedListener.",
                        e
                    )
                }

            /*
             * ====================================================
             * DYNAMIC LISTENER
             * ====================================================
             *
             * We use a Java Proxy so compileSdk 34 does not need
             * Android's hidden listener interface in its SDK stubs.
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

                        when (
                            method.name
                        ) {

                            /*
                             * Dynamic proxies need sane Object
                             * implementations because AppOpsManager
                             * stores listeners in an internal map.
                             */

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

                                "BTMicFixVoiceAccessAppOpsListener"
                            }

                            "onOpActiveChanged" -> {

                                handleAppOpsCallback(
                                    args
                                )

                                null
                            }

                            else -> {

                                null
                            }
                        }
                    }

                } catch (
                    e: Throwable
                ) {

                    return failureText(
                        "Could not create AppOps listener.",
                        e
                    )
                }

            listenerProxy =
                proxy

            /*
             * ====================================================
             * FIND startWatchingActive()
             * ====================================================
             *
             * Android 16 form:
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

                            types[1] ==
                            Executor::class.java &&

                            types[2].name ==
                            listenerClass.name
                    }

            if (
                startMethod ==
                null
            ) {

                listenerProxy =
                    null

                remoteCallback =
                    null

                return """
                    ===== BTMicFix VOICE ACCESS APPOPS =====
                    FAILED

                    Samsung's AppOpsManager did not expose
                    the expected startWatchingActive(
                        String[],
                        Executor,
                        OnOpActiveChangedListener
                    ) method.

                    ========================================
                """.trimIndent()
            }

            /*
             * ====================================================
             * REGISTER
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

            } catch (
                e: Throwable
            ) {

                listenerProxy =
                    null

                remoteCallback =
                    null

                val actual =
                    rootCause(
                        e
                    )

                return failureText(
                    "Android rejected startWatchingActive().",
                    actual
                )
            }

            /*
             * ====================================================
             * GET CURRENT STATE
             * ====================================================
             *
             * Important if Voice Access was already listening
             * before this watcher started.
             */

            val initialActive =
                queryTargetActive(
                    manager
                )
                    ?: false

            currentActive =
                initialActive

            /*
             * Immediately synchronize the normal app process.
             */

            notifyClient(
                initialActive,
                force =
                    true
            )

            return """
                ===== BTMicFix VOICE ACCESS APPOPS =====
                WATCH ACTIVE

                Target:
                $watchedPackage

                UID:
                $watchedUid

                Watching:
                $RECORD_AUDIO_OP

                Current RECORD_AUDIO state:
                ${
                    if (initialActive) {
                        "ACTIVE"
                    } else {
                        "INACTIVE"
                    }
                }

                Detection:
                Android AppOps callback

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

    private fun handleAppOpsCallback(
        args:
            Array<out Any?>?
    ) {

        if (
            args ==
            null ||
            args.size <
            4
        ) {

            return
        }

        /*
         * All modern callback forms begin with:
         *
         * String op
         * int uid
         * String packageName
         */

        val op =
            args.getOrNull(
                0
            ) as? String
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

        /*
         * Only care about the exact Voice Access app-op.
         */

        if (
            op !=
            RECORD_AUDIO_OP
        ) {

            return
        }

        if (
            uid !=
            watchedUid
        ) {

            return
        }

        if (
            packageName !=
            watchedPackage
        ) {

            return
        }

        /*
         * ========================================================
         * AGGREGATE STATE
         * ========================================================
         *
         * Do not blindly trust one attribution callback.
         *
         * Voice Access could have more than one active attribution.
         *
         * Ask AppOpsManager for the package's overall active state.
         */

        val manager =
            appOpsManager

        val actualState =
            if (
                manager !=
                null
            ) {

                queryTargetActive(
                    manager
                )

            } else {

                null
            }

        /*
         * Fallback if isOpActive itself is unavailable.
         *
         * There is only one Boolean argument in each callback form.
         */

        val callbackState =
            args
                .firstOrNull {

                    it is Boolean
                } as? Boolean

        val active =
            actualState
                ?: callbackState
                ?: return

        notifyClient(
            active
        )
    }

    /*
     * ============================================================
     * QUERY CURRENT ACTIVE STATE
     * ============================================================
     */

    private fun queryTargetActive(
        manager: AppOpsManager
    ): Boolean? {

        if (
            watchedUid <
            0 ||
            watchedPackage.isBlank()
        ) {

            return false
        }

        return try {

            val method =
                manager
                    .javaClass
                    .methods
                    .firstOrNull {
                            method ->

                        val types =
                            method.parameterTypes

                        method.name ==
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
                watchedUid,
                watchedPackage
            ) as? Boolean

        } catch (
            _: Throwable
        ) {

            null
        }
    }

    /*
     * ============================================================
     * NOTIFY APP
     * ============================================================
     */

    private fun notifyClient(
        active: Boolean,
        force: Boolean =
            false
    ) {

        if (
            !force &&
            currentActive ==
            active
        ) {

            return
        }

        currentActive =
            active

        val callback =
            remoteCallback
                ?: return

        try {

            callback
                .onRecordAudioActiveChanged(
                    active
                )

        } catch (
            _: Throwable
        ) {

            /*
             * Normal if the app process was killed.
             */
            remoteCallback =
                null
        }
    }

    /*
     * ============================================================
     * PUBLIC QUERY
     * ============================================================
     */

    override fun isTargetActive():
        Boolean {

        synchronized(
            lock
        ) {

            val manager =
                appOpsManager
                    ?: obtainAppOpsManager()
                    ?: return currentActive

            val active =
                queryTargetActive(
                    manager
                )
                    ?: currentActive

            currentActive =
                active

            return active
        }
    }

    /*
     * ============================================================
     * STOP WATCH
     * ============================================================
     */

    override fun stopWatch() {

        synchronized(
            lock
        ) {

            stopWatchLocked()
        }
    }

    private fun stopWatchLocked() {

        val manager =
            appOpsManager

        val listener =
            listenerProxy

        if (
            manager !=
            null &&
            listener !=
            null
        ) {

            try {

                val method =
                    manager
                        .javaClass
                        .methods
                        .firstOrNull {
                                candidate ->

                            candidate.name ==
                                "stopWatchingActive" &&

                                candidate
                                    .parameterTypes
                                    .size ==
                                1
                        }

                if (
                    method !=
                    null
                ) {

                    method.isAccessible =
                        true

                    method.invoke(
                        manager,
                        listener
                    )
                }

            } catch (
                _: Throwable
            ) {
            }
        }

        listenerProxy =
            null

        remoteCallback =
            null

        watchedUid =
            -1

        watchedPackage =
            ""

        currentActive =
            false
    }

    /*
     * ============================================================
     * APPOPS MANAGER
     * ============================================================
     */

    private fun obtainAppOpsManager():
        AppOpsManager? {

        appOpsManager
            ?.let {

                return it
            }

        val context =
            obtainContext()
                ?: return null

        /*
         * ========================================================
         * METHOD 1
         * NORMAL SYSTEM SERVICE
         * ========================================================
         */

        try {

            val normal =
                context.getSystemService(
                    Context.APP_OPS_SERVICE
                ) as? AppOpsManager

            if (
                normal !=
                null
            ) {

                appOpsManager =
                    normal

                return normal
            }

        } catch (
            _: Throwable
        ) {
        }

        /*
         * ========================================================
         * METHOD 2
         * CONSTRUCT APPOPSMANAGER FROM RAW SYSTEM BINDER
         * ========================================================
         *
         * UserService is not a normal Android application process,
         * so provide a low-level fallback.
         */

        return try {

            val binder:
                IBinder =
                SystemServiceHelper
                    .getSystemService(
                        Context.APP_OPS_SERVICE
                    )
                    ?: return null

            val stubClass =
                Class.forName(
                    "com.android.internal.app" +
                        ".IAppOpsService\$Stub"
                )

            val asInterface =
                stubClass
                    .methods
                    .firstOrNull {
                            method ->

                        method.name ==
                            "asInterface" &&

                            method.parameterTypes.size ==
                            1 &&

                            method.parameterTypes[0] ==
                            IBinder::class.java
                    }
                    ?: return null

            val internalService =
                asInterface.invoke(
                    null,
                    binder
                )
                    ?: return null

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
                    ?: return null

            constructor.isAccessible =
                true

            val manager =
                constructor.newInstance(
                    context,
                    internalService
                ) as? AppOpsManager

            appOpsManager =
                manager

            manager

        } catch (
            _: Throwable
        ) {

            null
        }
    }

    /*
     * ============================================================
     * CONTEXT
     * ============================================================
     */

    private fun obtainContext():
        Context? {

        providedContext
            ?.let {

                return it
            }

        /*
         * Shizuku's UserService process is created through
         * ActivityThread.systemMain().
         *
         * Obtain its system context if the v13 constructor was
         * unavailable for any reason.
         */

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

            providedContext =
                context

            context

        } catch (
            _: Throwable
        ) {

            null
        }
    }

    /*
     * ============================================================
     * DESTROY
     * ============================================================
     */

    override fun destroy() {

        synchronized(
            lock
        ) {

            stopWatchLocked()
        }

        System.exit(
            0
        )
    }

    /*
     * ============================================================
     * ERROR HELPERS
     * ============================================================
     */

    private fun failureText(
        title: String,
        throwable: Throwable
    ): String {

        val actual =
            rootCause(
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

    private fun rootCause(
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

    companion object {

        /*
         * Public AppOps string for RECORD_AUDIO.
         */
        private const val
            RECORD_AUDIO_OP =
            "android:record_audio"
    }
    }
