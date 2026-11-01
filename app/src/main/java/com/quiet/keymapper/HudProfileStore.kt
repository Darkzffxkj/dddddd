package com.quiet.keymapper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** Coordinates are normalized to the active display: profiles survive resolution changes.
 * The game package is always the owner; profiles never modify the target APK.
 */
data class HudControl(
    val kind: String,
    var keyCode: Int,
    var x: Float,
    var y: Float,
    var radius: Float = 0.09f,
    var slowRatio: Float = 0.35f
) {
    fun toJson() = JSONObject().put("kind", kind).put("key", keyCode)
        .put("x", x.toDouble()).put("y", y.toDouble())
        .put("radius", radius.toDouble()).put("slow", slowRatio.toDouble())
    companion object {
        fun read(o: JSONObject) = HudControl(
            o.optString("kind", "TOUCH"),
            o.optInt("key", android.view.KeyEvent.KEYCODE_SPACE),
            o.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
            o.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
            o.optDouble("radius", 0.09).toFloat().coerceIn(.01f, .3f),
            o.optDouble("slow", .35).toFloat().coerceIn(.05f, 1f)
        )
    }
}
data class HudProfile(
    val controls: MutableList<HudControl> = mutableListOf(),
    var sensitivityX: Int = 350,
    var sensitivityY: Int = 350,
    var cursorColor: Int = 0xFFAE69FF.toInt(),
    var cursorSize: Int = 26,
    var cursorAlpha: Int = 95,
    var cursorStyle: Int = 0,
    var cursorSpeed: Int = 100,
    var shortcutKey: Int = android.view.KeyEvent.KEYCODE_F8,
    var resetViewKey: Int = android.view.KeyEvent.KEYCODE_UNKNOWN,
    var slowKey: Int = android.view.KeyEvent.KEYCODE_SHIFT_LEFT,
    var suspendEnabled: Boolean = false,
    var reducedLatency: Boolean = true
) {
    fun toJson(): String = JSONObject().apply {
        put("controls", JSONArray().apply { controls.forEach { put(it.toJson()) } })
        put("sensX", sensitivityX); put("sensY", sensitivityY)
        put("cursorColor", cursorColor); put("cursorSize", cursorSize)
        put("cursorAlpha", cursorAlpha); put("cursorStyle", cursorStyle)
        put("cursorSpeed", cursorSpeed); put("shortcutKey", shortcutKey)
        put("resetViewKey", resetViewKey); put("slowKey", slowKey)
        put("suspendEnabled", suspendEnabled); put("reducedLatency", reducedLatency)
    }.toString()
    companion object {
        fun read(raw: String): HudProfile {
            if (raw.isBlank()) return HudProfile()
            return try {
                val o = JSONObject(raw); val arr = o.optJSONArray("controls") ?: JSONArray()
                val result = HudProfile()
                repeat(min(120, arr.length())) { i -> arr.optJSONObject(i)?.let { result.controls.add(HudControl.read(it)) } }
                result.sensitivityX = o.optInt("sensX", 350).coerceIn(1, 1000)
                result.sensitivityY = o.optInt("sensY", 350).coerceIn(1, 1000)
                result.cursorColor = o.optInt("cursorColor", 0xFFAE69FF.toInt())
                result.cursorSize = o.optInt("cursorSize", 26).coerceIn(8, 80)
                result.cursorAlpha = o.optInt("cursorAlpha", 95).coerceIn(10, 100)
                result.cursorStyle = o.optInt("cursorStyle", 0).coerceIn(0, 3)
                result.cursorSpeed = o.optInt("cursorSpeed", 100).coerceIn(20, 300)
                result.shortcutKey = o.optInt("shortcutKey", android.view.KeyEvent.KEYCODE_F8)
                result.resetViewKey = o.optInt("resetViewKey", android.view.KeyEvent.KEYCODE_UNKNOWN)
                result.slowKey = o.optInt("slowKey", android.view.KeyEvent.KEYCODE_SHIFT_LEFT)
                result.suspendEnabled = o.optBoolean("suspendEnabled", false)
                result.reducedLatency = o.optBoolean("reducedLatency", true)
                result
            } catch (_: Exception) { HudProfile() }
        }
    }
}
class HudProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences("referencia_hud_profiles_v7", Context.MODE_PRIVATE)
    fun load(pkg: String): HudProfile = HudProfile.read(preferences.getString(pkg, "") ?: "")
    fun save(pkg: String, profile: HudProfile) {
        // Persist before service unbind or game exit. Per-game atomic SharedPreferences commit.
        preferences.edit().putString(pkg, profile.toJson()).apply()
    }
    fun saveCorner(value: Int) = preferences.edit().putInt("bubble_corner", value.coerceIn(0, 3)).apply()
    fun corner(): Int = preferences.getInt("bubble_corner", 1).coerceIn(0, 3)
}
