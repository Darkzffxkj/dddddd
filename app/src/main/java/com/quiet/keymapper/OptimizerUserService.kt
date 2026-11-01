package com.quiet.keymapper

import android.os.Process
import java.io.File
import java.util.concurrent.TimeUnit

/** Shell-side service invoked over Shizuku's bound UserService IPC.
 * Never reflects into private Shizuku APIs, and never invokes arbitrary shell strings.
 */
class OptimizerUserService : IOptimizerService.Stub() {
    private val packagePattern = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
    private val optionalRestore = setOf(
        "com.google.android.youtube",
        "com.android.chrome",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.docs",
        "com.google.android.gm",
        "com.instagram.android",
        "com.google.android.apps.photos",
        "com.google.android.apps.maps",
        "com.google.android.apps.tachyon",
        "com.google.android.videos"
    )
    private val protected = setOf(
        "android", "com.quiet.keymapper", "com.android.systemui",
        "com.android.settings", "com.android.phone", "com.android.dialer",
        "com.android.providers.settings", "com.google.android.gms",
        "com.google.android.gsf", "moe.shizuku.privileged.api",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller"
    )
    private fun authorizedUid() = Process.myUid() == 2000 || Process.myUid() == 0

    private fun exec(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val output = try { p.inputStream.bufferedReader().use { it.readText() } }
            catch (_: Exception) { "" }
        val exited = p.waitFor(8, TimeUnit.SECONDS)
        if (!exited) { p.destroyForcibly(); return -1 to output }
        return p.exitValue() to output
    }

    override fun listRunningProcesses(): Array<String> {
        if (!authorizedUid()) return arrayOf("!ERROR_PS")
        return try {
            var data = exec("/system/bin/ps", "-A", "-o", "NAME")
            if (data.first != 0) {
                data = exec("/system/bin/ps", "-A")
                if (data.first != 0) return arrayOf("!ERROR_PS")
                return data.second.lineSequence().drop(1)
                    .mapNotNull { it.trim().split(Regex("\\s+")).lastOrNull() }
                    .filter { it.isNotBlank() }.distinct().toList().toTypedArray()
            }
            data.second.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && it != "NAME" }
                .distinct().toList().toTypedArray()
        } catch (_: Exception) { arrayOf("!ERROR_PS") }
    }

    override fun forceStopUserApp(packageName: String): Int {
        if (!authorizedUid()) return -10
        if (!packagePattern.matches(packageName) || packageName.length > 180 ||
            packageName in protected || packageName.startsWith("com.android.") ||
            packageName.startsWith("com.google.android.gms")) return -2
        return try {
            // Shell-side allowlist: only packages installed as third-party apps.
            val list = exec("/system/bin/pm", "list", "packages", "-3", "--user", "0")
            if (list.first != 0 || "package:$packageName" !in list.second.lineSequence().toSet()) return -3
            val r = exec("/system/bin/am", "force-stop", "--user", "0", packageName)
            if (r.first == 0) 0 else -4
        } catch (_: Exception) { -5 }
    }

    override fun enableOptionalApp(packageName: String): Int {
        if (!authorizedUid()) return -10
        if (packageName !in optionalRestore) return -2
        return try {
            val r = exec("/system/bin/pm", "enable", "--user", "0", packageName)
            if (r.first == 0) 0 else -4
        } catch (_: Exception) { -5 }
    }

    override fun destroy() {
        // Shizuku owns this auxiliary process; never terminate the Android app process.
        System.exit(0)
    }
}
