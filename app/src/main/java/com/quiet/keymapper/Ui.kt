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
    // Graphite / brushed violet metallic palette.
    val bg = Color.rgb(9, 7, 15)
    val panel = Color.rgb(24, 19, 32)
    val panelAlt = Color.rgb(45, 33, 59)
    val primary = Color.rgb(157, 94, 229)
    val primaryDark = Color.rgb(79, 39, 126)
    val violetSoft = Color.rgb(215, 185, 248)
    val white = Color.rgb(249, 246, 255)
    val muted = Color.rgb(176, 162, 192)
    val success = Color.rgb(48, 214, 166)
    val warning = Color.rgb(255, 193, 105)
    val danger = Color.rgb(255, 106, 148)
    val line = Color.rgb(81, 61, 99)

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
    intArrayOf(
        Color.rgb(31, 24, 43),
        Color.rgb(95, 73, 127),
        Color.rgb(146, 117, 173),
        Color.rgb(71, 46, 106),
        Color.rgb(24, 19, 34)
    )
).apply {
    cornerRadius = context.dp(17).toFloat()
    setStroke(context.dp(1),Color.rgb(180, 147, 213))
}

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
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if(filled) intArrayOf(0xFFA978E2.toInt(),0xFF6F3DAB.toInt(),0xFF4A276E.toInt())
            else intArrayOf(0xFF41314D.toInt(),0xFF292036.toInt(),0xFF21172C.toInt())
        ).apply {
            cornerRadius=dp(12).toFloat()
            setStroke(dp(1),if(filled)0xFFCEA8FA.toInt() else 0xFF79618E.toInt())
        }
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
