package com.quiet.keymapper

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Dark-violet visual system. Native views keep the APK small and fast. */
object Palette {
    val bg = Color.rgb(16, 16, 20)
    val panel = Color.rgb(27, 25, 35)
    val panelAlt = Color.rgb(39, 35, 50)
    val primary = Color.rgb(155, 77, 255)
    val primaryDark = Color.rgb(83, 33, 150)
    val violetSoft = Color.rgb(194, 154, 255)
    val white = Color.rgb(247, 244, 255)
    val muted = Color.rgb(166, 162, 178)
    val success = Color.rgb(40, 210, 174)
    val warning = Color.rgb(255, 189, 94)
    val danger = Color.rgb(255, 106, 124)
    val line = Color.rgb(53, 48, 63)
}

fun Context.dp(value: Int): Int = (resources.displayMetrics.density * value + .5f).toInt()

fun rounded(color: Int, radius: Float, stroke: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        stroke?.let { setStroke(1, it) }
    }

fun gradientHeader(context: Context): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.TL_BR,
    intArrayOf(Color.rgb(124, 42, 221), Color.rgb(77, 21, 140), Color.rgb(40, 20, 66))
).apply { cornerRadius = context.dp(20).toFloat() }

fun Context.txt(value: String, size: Float = 14f, color: Int = Palette.white, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

fun Context.action(value: String, filled: Boolean, callback: () -> Unit): TextView =
    txt(value, 14f, if (filled) Color.WHITE else Palette.violetSoft, true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(15), dp(9), dp(15), dp(9))
        val base = rounded(if (filled) Palette.primary else Palette.panelAlt, dp(12).toFloat(),
            if (filled) null else Palette.primary)
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null)
        isClickable = true
        isFocusable = true
        setOnClickListener { callback() }
    }

fun Context.column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun View.withMargin(bottom: Int = 0, top: Int = 0, left: Int = 0, right: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        setMargins(context.dp(left), context.dp(top), context.dp(right), context.dp(bottom))
    }
