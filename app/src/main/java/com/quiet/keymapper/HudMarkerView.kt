package com.quiet.keymapper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.KeyEvent
import kotlin.math.min

/** Circular keymap widgets: double-ring joystick, circular touch button, and aim/suspend. */
class HudMarkerView(context:Context,private val control:HudControl):View(context) {
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
    private val border=0xFFD4A8FF.toInt()
    private val purple=0xFF8F50F4.toInt()
    private fun text(c:Canvas,value:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean=true) {
        ink.reset();ink.isAntiAlias=true
        ink.color=color;ink.textAlign=Paint.Align.CENTER
        ink.textSize=size;ink.typeface=if(bold)android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.DEFAULT
        c.drawText(value,x,y-(ink.ascent()+ink.descent())/2,ink)
    }
    private fun circle(c:Canvas,x:Float,y:Float,r:Float,color:Int,stroke:Float=0f) {
        ink.reset();ink.isAntiAlias=true;ink.color=color
        ink.style=if(stroke>0)Paint.Style.STROKE else Paint.Style.FILL
        ink.strokeWidth=stroke
        c.drawCircle(x,y,r,ink)
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val k=min(width,height).toFloat();val cx=width/2f;val cy=height/2f
        val outer=k*.46f
        circle(canvas,cx,cy,outer,0xDA170F2B.toInt())
        circle(canvas,cx,cy,outer,border,k*.028f)
        if(control.kind=="JOYSTICK") {
            circle(canvas,cx,cy,k*.34f,0x773D2773)
            circle(canvas,cx,cy,k*.34f,0xFF7043C8.toInt(),k*.026f)
            circle(canvas,cx,cy,k*.21f,purple)
            circle(canvas,cx,cy,k*.16f,0xFF231733.toInt())
            circle(canvas,cx,cy,k*.06f,0xFFFFFFFF.toInt())
            text(canvas,"W",cx,cy-k*.365f,k*.115f,Color.WHITE)
            text(canvas,"S",cx,cy+k*.365f,k*.115f,Color.WHITE)
            text(canvas,"A",cx-k*.365f,cy,k*.115f,Color.WHITE)
            text(canvas,"D",cx+k*.365f,cy,k*.115f,Color.WHITE)
            ink.reset();ink.isAntiAlias=true;ink.style=Paint.Style.STROKE
            ink.strokeWidth=k*.012f;ink.color=0xFFBC9EFF.toInt()
            val ring=Path().apply {
                addArc(cx-outer*.87f,cy-outer*.87f,cx+outer*.87f,cy+outer*.87f,205f,122f)
            }
            canvas.drawPath(ring,ink)
        } else {
            circle(canvas,cx,cy,k*.34f,0xE35C298D.toInt())
            circle(canvas,cx,cy,k*.34f,0xFF9A72F4.toInt(),k*.02f)
            if(control.kind=="SUSPEND") {
                text(canvas,"⌖",cx,cy-k*.06f,k*.37f,Color.WHITE)
                text(canvas,"SENSI",cx,cy+k*.24f,k*.13f,0xFFE3D4FF.toInt())
            } else {
                val label=KeyEvent.keyCodeToString(control.keyCode).removePrefix("KEYCODE_")
                val shown=if(label.length>5)label.take(4)+"…" else label
                text(canvas,shown,cx,cy,k*(if(shown.length>3).18f else .3f),Color.WHITE)
            }
        }
    }
}
