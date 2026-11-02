package com.quiet.keymapper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.max

/** Touch-through cursor overlay. The service owns mouse events on Android 14+.
 * Does not change the global Android pointer icon or touch the target game's APK.
 */
class VirtualGameCursor(private val context:Context, private val wm:WindowManager) {
    private val prefs=CursorFileStore(context)
    private var view:ImageView?=null
    private var params:WindowManager.LayoutParams?=null
    private var cursorW=36
    private var cursorH=36
    private var x=0f
    private var y=0f
    var active:Boolean=false
        private set

    private fun dimensions():Pair<Int,Int> {
        val dm=context.resources.displayMetrics
        // Use physical display pixels, not dp, so coordinates match MotionEvent.
        val point=android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        return max(1,point.x) to max(1,point.y)
    }
    private fun defaultArrow():Bitmap {
        val bmp=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bmp)
        val arrow=Path().apply {
            moveTo(7f,4f);lineTo(7f,68f);lineTo(23f,51f)
            lineTo(35f,76f);lineTo(47f,70f);lineTo(35f,46f)
            lineTo(57f,46f);close()
        }
        val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.STROKE;strokeWidth=5f;color=Color.WHITE
            strokeJoin=Paint.Join.ROUND
        }
        canvas.drawPath(arrow,edge)
        val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.FILL;color=0xFFB77AF8.toInt()
        }
        canvas.drawPath(arrow,fill)
        return bmp
    }
    fun show(pkg:String) {
        if(active)return
        val decoded=try { prefs.load(pkg) } catch (_:Exception) { null }
        val bitmap=decoded?.bitmap ?: defaultArrow()
        val nominal=prefs.size(pkg).coerceIn(12,128)
        val w=(nominal*context.resources.displayMetrics.density).toInt().coerceAtLeast(16)
        cursorW=w
        cursorH=(w*bitmap.height.toFloat()/bitmap.width).toInt().coerceAtLeast(16)
        val overlay=ImageView(context).apply {
            scaleType=ImageView.ScaleType.FIT_XY
            setImageBitmap(bitmap)
            contentDescription="Cursor virtual da Referência"
        }
        val p=WindowManager.LayoutParams(cursorW,cursorH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity=Gravity.TOP or Gravity.START }
        view=overlay
        params=p
        val sz=dimensions()
        x=sz.first/2f
        y=sz.second/2f
        applyPosition()
        try {
            wm.addView(overlay,p)
            active=true
        } catch (_:Exception) {
            view=null;params=null;active=false
        }
    }
    private fun applyPosition() {
        val p=params ?: return
        val sz=dimensions()
        x=x.coerceIn(0f,sz.first.toFloat()-1f)
        y=y.coerceIn(0f,sz.second.toFloat()-1f)
        p.x=x.toInt().coerceIn(0,max(0,sz.first-cursorW))
        p.y=y.toInt().coerceIn(0,max(0,sz.second-cursorH))
        if(active) try {wm.updateViewLayout(view,p)}catch (_:Exception){}
    }
    fun center() {
        val sz=dimensions()
        x=sz.first/2f;y=sz.second/2f
        applyPosition()
    }
    fun moveTo(px:Float,py:Float) {
        x=px;y=py;applyPosition()
    }
    fun moveBy(dx:Float,dy:Float) {
        x+=dx;y+=dy;applyPosition()
    }
    fun normalized():Pair<Float,Float> {
        val sz=dimensions()
        return (x/sz.first).coerceIn(0f,1f) to
            (y/sz.second).coerceIn(0f,1f)
    }
    fun hide() {
        view?.let { try { wm.removeView(it) }catch (_:Exception){} }
        view=null;params=null;active=false
    }
}
