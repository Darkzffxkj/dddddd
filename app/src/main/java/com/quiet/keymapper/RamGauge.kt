package com.quiet.keymapper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Lightweight circular RAM indicator. Reads real ActivityManager.MemoryInfo; no polling. */
class RamGauge(context: Context) : View(context) {
    var usedPercent: Int = 0
        set(value) { field = value.coerceIn(0, 100); invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.line; style = Paint.Style.STROKE; strokeWidth = context.dp(8).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.primary; style = Paint.Style.STROKE; strokeWidth = context.dp(8).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.white; textAlign = Paint.Align.CENTER
        textSize = context.dp(26).toFloat(); isFakeBoldText = true
    }
    private val desc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.violetSoft; textAlign = Paint.Align.CENTER
        textSize = context.dp(11).toFloat()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val diameter = minOf(width, height).toFloat()
        val pad = context.dp(14).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = diameter / 2f - pad
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, 135f, 270f, false, track)
        canvas.drawArc(rect, 135f, 270f * usedPercent / 100f, false, active)
        canvas.drawText("$usedPercent%", cx, cy + context.dp(4), number)
        canvas.drawText("RAM", cx, cy + context.dp(26), desc)
    }
}
