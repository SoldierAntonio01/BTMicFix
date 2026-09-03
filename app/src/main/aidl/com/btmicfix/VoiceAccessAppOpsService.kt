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
 * Runs inside a Shizuku UserService.
 *
 * Watches:
 *
 * com.google.android.apps.accessibility.voiceaccess
 *
 * for:
 *
 * android:record_audio
 *
 *
 * Voice Access listening:
 * RECORD_AUDIO ACTIVE
 *
 * Voice Access stopped:
 * RECORD_AUDIO INACTIVE
 *
 *
 * NO POLLING.
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
     * Shizuku API 13+ tries this constructor first.
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
     * STATE
     * ============================================================
     */

    private val lock =
        Any()

    private var appOpsManager:
        AppOpsManager? =
        null

    private var targetUid =
        -1

    private var targetPackage =
        ""

    private var callback:
        IVoiceAccessOpCallback? =
        null

    private var listener:
        AppOpsManager.OnOpActiveChangedListener? =
        null

    @Volatile
    private var currentActive =
        false

    @Volatile
    private var watcherRegistered =
        false

    private var contextSource =
        "NONE"

    private var managerSource =
        "NONE"

    private var lastError =
        "NONE"

    /*
     * Tiny callbacks can execute inline.
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

            stopWatchLocked()

            this.targetUid =
                targetUid

            this.targetPackage =
                targetPackage

            this.callback =
                callback

            lastError =
                "NONE"

            /*
             * Get a usable AppOpsManager.
             */
            val manager =
                obtainAppOpsManager()

            if (
                manager == null
            ) {

                lastError =
                    "Could not obtain AppOpsManager"

                return buildStatus(
                    title = "FAILED"
                )
            }

            /*
             * ====================================================
             * APPOPS LISTENER
             * ====================================================
             */

            val newListener =
                object :
                    AppOpsManager.OnOpActiveChangedListener {

                    override fun onOpActiveChanged(
                        op: String,
                        uid: Int,
                        packageName: String,
                        active: Boolean
                    ) {

                        /*
                         * Ignore everything except Voice Access.
                         */

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

                        /*
                         * Prefer Android's aggregate current state.
                         *
                         * This protects against attribution-level
                         * callbacks briefly toggling individually.
                         */

                        val actual =
                            queryCurrentState()
                                ?: active

                        sendState(
                            actual
                        )
                    }
                }

            /*
             * ====================================================
             * REGISTER
             * ====================================================
             */

            try {

                manager.startWatchingActive(
                    arrayOf(
                        AppOpsManager.OPSTR_RECORD_AUDIO
                    ),
                    directExecutor,
                    newListener
                )

                listener =
                    newListener

                watcherRegistered =
                    true

            } catch (e: Throwable) {

                watcherRegistered =
                    false

                listener =
                    null

                lastError =
                    describeThrowable(
                        e
                    )

                return buildStatus(
                    title = "WATCH REGISTRATION FAILED"
                )
            }

            /*
             * Voice Access may already be listening.
             */

            currentActive =
                queryCurrentState()
                    ?: false

            /*
             * Synchronize main BTMicFix process immediately.
             */

            sendState(
                currentActive,
                force = true
            )

            return buildStatus(
                title = "WATCH ACTIVE"
            )
        }
    }

    /*
     * ============================================================
     * QUERY STATE
     * ============================================================
     */

    private fun queryCurrentState():
        Boolean? {

        val manager =
            appOpsManager
                ?: return null

        if (
            targetUid < 0 ||
            targetPackage.isBlank()
        ) {

            return false
        }

        return try {

            manager.isOpActive(
                AppOpsManager.OPSTR_RECORD_AUDIO,
                targetUid,
                targetPackage
            )

        } catch (e: Throwable) {

            lastError =
                describeThrowable(
                    e
                )

            null
        }
    }

    /*
     * ============================================================
     * CALLBACK MAIN APP
     * ============================================================
     */

    private fun sendState(
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

            callback
                ?.onRecordAudioActiveChanged(
                    active
                )

        } catch (e: Throwable) {

            lastError =
                "Callback failed: " +
                    describeThrowable(
                        e
                    )

            callback =
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
     * PUBLIC STATUS
     * ============================================================
     */

    override fun getStatus():
        String {

        synchronized(lock) {

            return buildStatus(
                title =
                    if (watcherRegistered) {
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

                manager.stopWatchingActive(
                    oldListener
                )

            } catch (_: Throwable) {
            }
        }

        listener =
            null

        watcherRegistered =
            false

        callback =
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
            obtainUsableContext()
                ?: run {

                    lastError =
                        "No usable Context"

                    return null
                }

        /*
         * ========================================================
         * METHOD 1
         *
         * Normal Context system service.
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
                "Context AppOps failed: " +
                    describeThrowable(
                        e
                    )
        }

        /*
         * ========================================================
         * METHOD 2
         *
         * Build AppOpsManager directly around Android's
         * appops system Binder.
         *
         * This avoids relying on a fully normal app Context.
         * ========================================================
         */

        try {

            val binder =
                SystemServiceHelper
                    .getSystemService(
                        Context.APP_OPS_SERVICE
                    )
                    ?: run {

                        lastError =
                            "appops Binder is NULL"

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
                            method.parameterTypes.size ==
                            1
                    }
                    ?: run {

                        lastError =
                            "IAppOpsService.Stub.asInterface missing"

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
                            "IAppOpsService interface is NULL"

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
                            "AppOpsManager(Context,IAppOpsService) constructor missing"

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
                            "Constructed AppOpsManager is NULL"

                        return null
                    }

            appOpsManager =
                manager

            managerSource =
                "Raw appops Binder"

            return manager

        } catch (e: Throwable) {

            lastError =
                "Raw AppOps failed: " +
                    describeThrowable(
                        e
                    )

            return null
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

                contextSource =
                    "Shizuku v13 Context"

                return it
            }

        /*
         * Shizuku's UserService process is built around
         * ActivityThread.systemMain().
         *
         * Recover the system Context if for any reason our
         * Context constructor wasn't used.
         */

        try {

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

            if (
                thread != null
            ) {

                val getSystemContext =
                    activityThreadClass
                        .getDeclaredMethod(
                            "getSystemContext"
                        )

                getSystemContext.isAccessible =
                    true

                val context =
                    getSystemContext.invoke(
                        thread
                    ) as? Context

                if (
                    context != null
                ) {

                    suppliedContext =
                        context

                    contextSource =
                        "ActivityThread system Context"

                    return context
                }
            }

        } catch (e: Throwable) {

            lastError =
                "System Context failed: " +
                    describeThrowable(
                        e
                    )
        }

        return null
    }

    /*
     * ============================================================
     * STATUS
     * ============================================================
     */

    private fun buildStatus(
        title: String
    ): String {

        return """
            ===== BTMicFix VOICE ACCESS APPOPS =====
            $title

            Process UID:
            ${Process.myUid()}

            Context:
            $contextSource

            AppOpsManager:
            $managerSource

            Target package:
            ${
                if (targetPackage.isBlank()) {
                    "NONE"
                } else {
                    targetPackage
                }
            }

            Target UID:
            $targetUid

            Watcher registered:
            ${yesNo(watcherRegistered)}

            RECORD_AUDIO:
            ${
                if (currentActive) {
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

    private fun yesNo(
        value: Boolean
    ): String {

        return if (value) {
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
                (actual.message ?: "no message")
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
