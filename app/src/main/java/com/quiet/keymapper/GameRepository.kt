package com.quiet.keymapper

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/** Only launcher-visible apps, without broad QUERY_ALL_PACKAGES permission. */
data class InstalledApp(val packageName: String, val label: String, val isGame: Boolean)

class GameRepository(private val context: Context) {
    private val packageManager = context.packageManager
    private val prefs = context.getSharedPreferences("keymapper_games", Context.MODE_PRIVATE)
    private val knownGames = setOf(
        "com.dts.freefireth", "com.dts.freefiremax", "com.axlebolt.standoff2", "com.tencent.ig",
        "com.pubg.imobile", "com.activision.callofduty.shooter", "com.mojang.minecraftpe",
        "com.roblox.client", "com.supercell.clashofclans", "com.supercell.clashroyale",
        "com.epicgames.fortnite", "com.mobile.legends", "com.riotgames.league.wildrift"
    )
    private fun manualPackages(): Set<String> = prefs.getStringSet("manually_added", emptySet())?.toSet() ?: emptySet()

    fun addManually(packageName: String) {
        val next = manualPackages().toMutableSet().apply { add(packageName) }
        prefs.edit().putStringSet("manually_added", next).apply()
    }

    fun removeManually(packageName: String) {
        val next=manualPackages().toMutableSet().apply { remove(packageName) }
        prefs.edit().putStringSet("manually_added",next).apply()
    }

    fun selectedPackages():Set<String> = manualPackages()

    fun allLaunchable(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list: List<ResolveInfo> = packageManager.queryIntentActivities(intent, 0)
        val manual = manualPackages()
        return list.mapNotNull { candidate ->
            val info = candidate.activityInfo ?: return@mapNotNull null
            val app = info.applicationInfo ?: return@mapNotNull null
            if (info.packageName == context.packageName) return@mapNotNull null
            val name = candidate.loadLabel(packageManager)?.toString() ?: info.packageName
            @Suppress("DEPRECATION")
            val classified = app.category == ApplicationInfo.CATEGORY_GAME ||
                (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            InstalledApp(info.packageName, name, classified || info.packageName in knownGames || info.packageName in manual)
        }.distinctBy { it.packageName }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /** The home screen NEVER auto-populates classified games. Only user selections appear. */
    fun detectedGames(installed: List<InstalledApp>): List<InstalledApp> =
        installed.filter { it.packageName in manualPackages() }

    fun launchIntent(packageName: String): Intent? = packageManager.getLaunchIntentForPackage(packageName)
}
