package com.btmicfix.shizuku

import com.btmicfix.IPrivilegedService
import com.btmicfix.util.Logger

/**
 * Shizuku UserService implementation that runs in a privileged process (UID 2000).
 *
 * This service is spawned by Shizuku and can execute commands as the ADB shell user.
 * It provides fallback audio routing capabilities for cases where the standard
 * setCommunicationDevice() API fails.
 *
 * NOTE: This runs in a separate process, NOT the app's main process.
 * Standard Android Context APIs may not work here.
 */
class PrivilegedServiceImpl : IPrivilegedService.Stub() {

    companion object {
        // Only these command prefixes are allowed to execute.
        // This prevents shell injection via the AIDL interface.
        private val ALLOWED_PREFIXES = listOf(
            "dumpsys audio",
            "cmd audio",
            "settings get",
        )
    }

    override fun destroy() {
        Logger.i("PrivilegedService: destroy() called, shutting down")
        System.exit(0)
    }

    override fun executeAudioCommand(command: String): String? {
        // Validate against allowlist to prevent shell injection
        if (ALLOWED_PREFIXES.none { command.startsWith(it) }) {
            Logger.e("PrivilegedService: blocked disallowed command: $command")
            return "ERROR: Command not in allowlist"
        }

        return try {
            Logger.d("PrivilegedService: executing audio command: $command")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                output.trim()
            } else {
                Logger.e("PrivilegedService: command failed (exit=$exitCode): $error")
                "ERROR: $error"
            }
        } catch (e: Exception) {
            Logger.e("PrivilegedService: command exception", e)
            "EXCEPTION: ${e.message}"
        }
    }

    override fun getAudioDump(): String? {
        return executeAudioCommand("dumpsys audio")
    }

    /**
     * Attempt to force audio strategy via shell command.
     *
     * This is a last-resort fallback. The `cmd audio` interface is not stable
     * and varies across Android versions and OEM builds.
     *
     * @param strategy Audio strategy constant (e.g., 0 = STRATEGY_MEDIA, 1 = STRATEGY_PHONE)
     * @param deviceType AudioDeviceInfo type constant
     * @return true if the command appeared to succeed
     */
    override fun forceAudioStrategy(strategy: Int, deviceType: Int): Boolean {
        // Validate integer ranges to prevent misuse
        if (strategy !in 0..15 || deviceType !in 0..30) {
            Logger.e("PrivilegedService: invalid strategy=$strategy or deviceType=$deviceType")
            return false
        }

        return try {
            // Try the set-force-use approach (varies by Android version)
            val result = executeAudioCommand(
                "cmd audio set-force-use $strategy $deviceType"
            )
            Logger.i("PrivilegedService: forceAudioStrategy result: $result")
            result != null && !result.startsWith("ERROR")
        } catch (e: Exception) {
            Logger.e("PrivilegedService: forceAudioStrategy failed", e)
            false
        }
    }
}
