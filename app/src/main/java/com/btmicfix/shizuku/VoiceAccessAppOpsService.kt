package com.btmicfix.shizuku

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import androidx.annotation.Keep
import com.btmicfix.IVoiceAccessOpCallback
import com.btmicfix.IVoiceAccessWatcher
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executor

/**
 * BTMicFix privileged Voice Access microphone watcher.
 *
 * Runs inside a Shizuku UserService.
 *
 * Watches:
 *
 * android:record_audio
 *
 * specifically for Google's Voice Access package.
 *
 *
 * Voice Access listening:
 *
 * RECORD_AUDIO = ACTIVE
 *
 *
 * Voice Access stopped:
 *
 * RECORD_AUDIO = INACTIVE
 *
 *
 * IMPORTANT:
 *
 * This is event-driven.
 *
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

    private var suppliedContext:
        Context? =
        null

    /*
     * Shizuku API 13+ may create the UserService
     * using this Context constructor.
     */
    @Keep
    constructor(
        context: Context
    ) : this() {

        suppliedContext =
            context
    }

    /*
     * ============================================================
     * LOCK
     * ============================================================
     */

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
     * ============================================================
     * TARGET
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
     * CALLBACK TO NORMAL BTMICFIX PROCESS
     * ============================================================
     */

    private var clientCallback:
        IVoiceAccessOpCallback? =
        null

    /*
     * ============================================================
     * STATE
     * ============================================================
     */

    @Volatile
    private var currentActive:
        Boolean =
        false

    @Volatile
    private var watcherRegistered:
        Boolean =
        false

    /*
     * ============================================================
     * DIAGNOSTICS
     * ============================================================
     */

    private var contextSource:
        String =
        "NONE"

    private var managerSource:
        String =
        "NONE"

    private var lastError:
        String =
        "NONE"

    /*
     * ============================================================
     * CALLBACK EXECUTOR
     * ============================================================
     *
     * AppOps callbacks are tiny.
     *
     * Run immediately on the dispatching thread rather than
     * creating another permanent worker.
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
             * Remove any old registration first.
             */

            stopWatchLocked()

            this.targetUid =
                targetUid

            this.targetPackage =
                targetPackage

            this.clientCallback =
                callback

            lastError =
                "NONE"

            /*
             * ====================================================
             * GET APPOPS MANAGER
             * ====================================================
             */

            val manager =
                obtainAppOpsManager()

            if (
                manager == null
            ) {

                lastError =
                    "Could not obtain AppOpsManager."

                return buildStatus(
                    "FAILED"
                )
            }

            /*
             * ====================================================
             * CREATE ACTIVE LISTENER
             * ====================================================
             */

            val listener =
                object :
                    AppOpsManager.OnOpActiveChangedListener {

                    override fun onOpActiveChanged(
                        op: String,
                        uid: Int,
                        packageName: String,
                        active: Boolean
                    ) {

                        /*
                         * Only RECORD_AUDIO matters.
                         */

                        if (
                            op !=
                            AppOpsManager.OPSTR_RECORD_AUDIO
                        ) {

                            return
                        }

                        /*
                         * Only our Voice Access UID matters.
                         */

                        if (
                            uid !=
                            this@VoiceAccessAppOpsService.targetUid
                        ) {

                            return
                        }

                        /*
                         * Only Google's Voice Access package matters.
                         */

                        if (
                            packageName !=
                            this@VoiceAccessAppOpsService.targetPackage
                        ) {

                            return
                        }

                        /*
                         * Prefer Android's aggregate state if its
                         * hidden isOpActive() method is accessible.
                         *
                         * Otherwise use the state Android supplied
                         * directly in this callback.
                         */

                        val aggregateState =
                            queryCurrentState()

                        val finalState =
                            aggregateState
                                ?: active

                        sendState(
                            finalState
                        )
                    }
                }

            /*
             * ====================================================
             * REGISTER APPOPS WATCH
             * ====================================================
             */

            try {

                manager.startWatchingActive(
                    arrayOf(
                        AppOpsManager.OPSTR_RECORD_AUDIO
                    ),
                    directExecutor,
                    listener
                )

                activeListener =
                    listener

                watcherRegistered =
                    true

            } catch (e: Throwable) {

                activeListener =
                    null

                watcherRegistered =
                    false

                lastError =
                    "startWatchingActive failed: " +
                        describeThrowable(
                            e
                        )

                return buildStatus(
                    "WATCH REGISTRATION FAILED"
                )
            }

            /*
             * ====================================================
             * GET INITIAL STATE
             * ====================================================
             *
             * Voice Access may already be listening before our
             * UserService starts.
             */

            val initialState =
                queryCurrentState()
                    ?: false

            /*
             * Force one initial callback into BTMicFix.
             */

            sendState(
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
     * QUERY CURRENT APPOPS STATE
     * ============================================================
     *
     * isOpActive() is not something we want to depend on at
     * compile time across Samsung/Android versions.
     *
     * Call it reflectively.
     */

    private fun queryCurrentState():
        Boolean? {

        val manager =
            appOpsManager
                ?: return null

        if (
            targetUid <
            0
        ) {

            return false
        }

        if (
            targetPackage.isBlank()
        ) {

            return false
        }

        return try {

            /*
             * Look for:
             *
             * isOpActive(
             *     String op,
             *     int uid,
             *     String packageName
             * )
             */

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

            if (
                method == null
            ) {

                return null
            }

            method.isAccessible =
                true

            method.invoke(
                manager,
                AppOpsManager.OPSTR_RECORD_AUDIO,
                targetUid,
                targetPackage
            ) as? Boolean

        } catch (e: Throwable) {

            /*
             * Not fatal.
             *
             * onOpActiveChanged() already provides the Boolean
             * active state, so this is only an extra verification.
             */

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
     * SEND STATE TO MAIN BTMICFIX PROCESS
     * ============================================================
     */

    private fun sendState(
        active: Boolean,
        force: Boolean =
            false
    ) {

        /*
         * Don't send duplicate events.
         */

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

            /*
             * Main BTMicFix process may have been killed or
             * restarted.
             */

            lastError =
                "Client callback failed: " +
                    describeThrowable(
                        e
                    )

            clientCallback =
                null
        }
    }

    /*
     * ============================================================
     * CURRENT STATE REQUESTED BY MAIN APP
     * ============================================================
     */

    override fun isTargetActive():
        Boolean {

        synchronized(lock) {

            val actual =
                queryCurrentState()

            if (
                actual != null
            ) {

                currentActive =
                    actual
            }

            return currentActive
        }
    }

    /*
     * ============================================================
     * GET STATUS
     * ============================================================
     *
     * THIS METHOD IS REQUIRED BY:
     *
     * IVoiceAccessWatcher.aidl
     *
     * String getStatus() = 4;
     *
     * This is the exact method your failed build was missing.
     */

    override fun getStatus():
        String {

        synchronized(lock) {

            /*
             * Refresh state for diagnostics if possible.
             */

            val actual =
                queryCurrentState()

            if (
                actual != null
            ) {

                currentActive =
                    actual
            }

            return buildStatus(
                if (
                    watcherRegistered
                ) {

                    "WATCH ACTIVE"

                } else {

                    "WATCH NOT ACTIVE"
                }
            )
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

    /*
     * ============================================================
     * INTERNAL STOP
     * ============================================================
     */

    private fun stopWatchLocked() {

        val manager =
            appOpsManager

        val listener =
            activeListener

        if (
            manager != null &&
            listener != null
        ) {

            try {

                manager.stopWatchingActive(
                    listener
                )

            } catch (e: Throwable) {

                lastError =
                    "stopWatchingActive failed: " +
                        describeThrowable(
                            e
                        )
            }
        }

        activeListener =
            null

        watcherRegistered =
            false

        clientCallback =
            null

        targetUid =
            -1

        targetPackage =
            ""

        currentActive =
            false
    }

    /*
     * ============================================================
     * GET APPOPS MANAGER
     * ============================================================
     */

    private fun obtainAppOpsManager():
        AppOpsManager? {

        /*
         * Already available.
         */

        appOpsManager
            ?.let {

                return it
            }

        /*
         * ========================================================
         * GET A USABLE CONTEXT
         * ========================================================
         */

        val context =
            obtainUsableContext()

        if (
            context == null
        ) {

            lastError =
                "No usable Context inside Shizuku UserService."

            return null
        }

        /*
         * ========================================================
         * METHOD 1
         *
         * Normal Context.getSystemService()
         * ========================================================
         */

        try {

            val manager =
                context.getSystemService(
                    AppOpsManager::class.java
                )

            if (
                manager != null
            ) {

                appOpsManager =
                    manager

                managerSource =
                    "Context.getSystemService"

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
         * ========================================================
         * METHOD 2
         *
         * RAW ANDROID "appops" BINDER
         * ========================================================
         *
         * Shizuku UserService is not a normal Android application
         * process.
         *
         * If Context.getSystemService() fails, obtain the raw
         * system Binder and construct AppOpsManager ourselves.
         */

        try {

            val binder =
                SystemServiceHelper
                    .getSystemService(
                        Context.APP_OPS_SERVICE
                    )

            if (
                binder == null
            ) {

                lastError =
                    "Raw appops Binder returned NULL."

                return null
            }

            /*
             * ====================================================
             * IAppOpsService.Stub.asInterface()
             * ====================================================
             */

            val stubClass =
                Class.forName(
                    "com.android.internal.app.IAppOpsService\$Stub"
                )

            val asInterfaceMethod =
                stubClass
                    .methods
                    .firstOrNull {
                            method ->

                        method.name ==
                            "asInterface" &&

                            method.parameterTypes.size ==
                            1
                    }

            if (
                asInterfaceMethod == null
            ) {

                lastError =
                    "IAppOpsService.Stub.asInterface not found."

                return null
            }

            asInterfaceMethod.isAccessible =
                true

            val internalService =
                asInterfaceMethod.invoke(
                    null,
                    binder
                )

            if (
                internalService == null
            ) {

                lastError =
                    "IAppOpsService interface returned NULL."

                return null
            }

            /*
             * ====================================================
             * HIDDEN APPOPSMANAGER CONSTRUCTOR
             * ====================================================
             *
             * AppOpsManager(
             *     Context,
             *     IAppOpsService
             * )
             */

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

            if (
                constructor == null
            ) {

                lastError =
                    "Hidden AppOpsManager(Context,IAppOpsService) " +
                        "constructor not found."

                return null
            }

            constructor.isAccessible =
                true

            val manager =
                constructor.newInstance(
                    context,
                    internalService
                ) as? AppOpsManager

            if (
                manager == null
            ) {

                lastError =
                    "Constructed AppOpsManager returned NULL."

                return null
            }

            appOpsManager =
                manager

            managerSource =
                "Raw Android appops Binder"

            return manager

        } catch (e: Throwable) {

            lastError =
                "Raw AppOpsManager creation failed: " +
                    describeThrowable(
                        e
                    )

            return null
        }
    }

    /*
     * ============================================================
     * GET USABLE CONTEXT
     * ============================================================
     */

    private fun obtainUsableContext():
        Context? {

        /*
         * ========================================================
         * METHOD 1
         *
         * Shizuku-provided Context
         * ========================================================
         */

        suppliedContext
            ?.let {

                contextSource =
                    "Shizuku Context constructor"

                return it
            }

        /*
         * ========================================================
         * METHOD 2
         *
         * ActivityThread system Context
         * ========================================================
         *
         * Shizuku UserService is initialized using Android's
         * ActivityThread system process infrastructure.
         *
         * Recover its system Context if our Context constructor
         * was not used.
         */

        try {

            val activityThreadClass =
                Class.forName(
                    "android.app.ActivityThread"
                )

            /*
             * currentActivityThread()
             */

            val currentActivityThreadMethod =
                activityThreadClass
                    .getDeclaredMethod(
                        "currentActivityThread"
                    )

            currentActivityThreadMethod.isAccessible =
                true

            val activityThread =
                currentActivityThreadMethod.invoke(
                    null
                )

            if (
                activityThread == null
            ) {

                lastError =
                    "ActivityThread.currentActivityThread() returned NULL."

                return null
            }

            /*
             * getSystemContext()
             */

            val getSystemContextMethod =
                activityThreadClass
                    .getDeclaredMethod(
                        "getSystemContext"
                    )

            getSystemContextMethod.isAccessible =
                true

            val context =
                getSystemContextMethod.invoke(
                    activityThread
                ) as? Context

            if (
                context == null
            ) {

                lastError =
                    "ActivityThread.getSystemContext() returned NULL."

                return null
            }

            suppliedContext =
                context

            contextSource =
                "ActivityThread system Context"

            return context

        } catch (e: Throwable) {

            lastError =
                "System Context recovery failed: " +
                    describeThrowable(
                        e
                    )

            return null
        }
    }

    /*
     * ============================================================
     * BUILD STATUS
     * ============================================================
     */

    private fun buildStatus(
        title: String
    ): String {

        return """
            ===== BTMicFix VOICE ACCESS APPOPS =====
            $title

            Shizuku UserService UID:
            ${Process.myUid()}

            Context source:
            $contextSource

            AppOpsManager source:
            $managerSource

            Target package:
            ${
                if (
                    targetPackage.isBlank()
                ) {
                    "NONE"
                } else {
                    targetPackage
                }
            }

            Target UID:
            $targetUid

            RECORD_AUDIO watcher registered:
            ${yesNo(watcherRegistered)}

            RECORD_AUDIO current state:
            ${
                if (
                    currentActive
                ) {
                    "ACTIVE"
                } else {
                    "INACTIVE"
                }
            }

            Last error:
            $lastError

            =========================================
        """.trimIndent()
    }

    /*
     * ============================================================
     * YES / NO
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

    /*
     * ============================================================
     * ERROR DESCRIPTION
     * ============================================================
     */

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

    /*
     * ============================================================
     * SHIZUKU DESTROY
     * ============================================================
     */

    override fun destroy() {

        synchronized(lock) {

            stopWatchLocked()
        }

        System.exit(
            0
        )
    }
}
