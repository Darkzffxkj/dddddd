package com.quiet.keymapper

import android.os.Process

/**
 * Shizuku UserService: executes with shell/root identity, never in the main UI.
 * No batch blind kill; each exact package must be checked by the caller.
 */
class OptimizerUserService : IOptimizerService.Stub() {
    private val allowed = Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")
    private fun valid(packageName: String): Boolean =
        packageName.length <= 180 && allowed.matches(packageName) &&
            packageName != "android" && !packageName.startsWith("com.android.systemui") &&
            !packageName.startsWith("com.google.android.gms") &&
            !packageName.startsWith("com.android.providers") &&
            !packageName.startsWith("com.android.permissioncontroller") &&
            !packageName.startsWith("com.android.phone")

    override fun isRunning(packageName: String): Boolean {
        if (!valid(packageName)) return false
        return try {
            val process = ProcessBuilder("/system/bin/pidof", packageName)
                .redirectErrorStream(true).start()
            process.inputStream.close()
            process.waitFor() == 0
        } catch (_: Exception) { false }
    }

    /** 0=success; negative codes indicate refusal/error. */
    override fun forceStop(packageName: String): Int {
        if (!valid(packageName)) return -2
        if (Process.myUid() != 2000 && Process.myUid() != 0) return -3
        return try {
            val process = ProcessBuilder("/system/bin/am", "force-stop",
                "--user", "current", packageName).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() == 0) 0 else -4
        } catch (_: Exception) { -5 }
    }

    @Suppress("unused")
    fun destroy() { System.exit(0) }
}
