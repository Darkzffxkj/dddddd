package com.quiet.keymapper

import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Shell-side, parameter-validated display controls. 45s undo timer while bound.
 * An unexpected process death may prevent rollback; the interface documents
 * manual adb recovery. Never accepts arbitrary shell command strings.
 */
class DisplayTuningUserService : IDisplayTuningService.Stub() {
    private val lock=Any()
    private val timer=Executors.newSingleThreadScheduledExecutor()
    private var rollback:ScheduledFuture<*>?=null
    private var baseline:Original?=null

    private data class Original(val sizeOverride:String?, val densityOverride:Int?)
    private data class Device(
        val physicalW:Int,val physicalH:Int,val physicalDpi:Int,
        val activeW:Int,val activeH:Int,val activeDpi:Int,
        val sizeOverride:String?,val densityOverride:Int?
    )
    private fun authorized()=Process.myUid()==2000 || Process.myUid()==0
    private fun cmd(vararg args:String):Pair<Int,String> {
        val p=ProcessBuilder(*args).redirectErrorStream(true).start()
        val done=p.waitFor(6,TimeUnit.SECONDS)
        if(!done) { p.destroyForcibly();return -1 to "timeout" }
        return p.exitValue() to p.inputStream.bufferedReader().use { it.readText() }
    }
    private val sizeRegex=Regex("(\\d{2,5})x(\\d{2,5})")
    private val number=Regex("(\\d{2,4})")

    private fun probe():Device? {
        val size=cmd("/system/bin/wm","size")
        val density=cmd("/system/bin/wm","density")
        if(size.first!=0 || density.first!=0) return null
        val physicalSize=sizeRegex.find(
            size.second.lineSequence().firstOrNull { it.contains("Physical size:") } ?: ""
        )?.destructured ?: return null
        val w=physicalSize.component1().toIntOrNull() ?: return null
        val h=physicalSize.component2().toIntOrNull() ?: return null
        val physicalDpi=number.find(
            density.second.lineSequence().firstOrNull { it.contains("Physical density:") } ?: ""
        )?.value?.toIntOrNull() ?: return null
        val overrideSize=sizeRegex.find(
            size.second.lineSequence().firstOrNull { it.contains("Override size:") } ?: ""
        )?.value
        val overrideDpi=number.find(
            density.second.lineSequence().firstOrNull { it.contains("Override density:") } ?: ""
        )?.value?.toIntOrNull()
        val effective=overrideSize?.let { sizeRegex.matchEntire(it)?.destructured }
        return Device(
            w,h,physicalDpi,
            effective?.component1()?.toIntOrNull() ?: w,
            effective?.component2()?.toIntOrNull() ?: h,
            overrideDpi ?: physicalDpi,overrideSize,overrideDpi
        )
    }
    private fun restore(previous:Original):Boolean {
        val a=cmd("/system/bin/wm","size",previous.sizeOverride ?: "reset")
        val b=cmd("/system/bin/wm","density",previous.densityOverride?.toString() ?: "reset")
        return a.first==0 && b.first==0
    }
    override fun getDisplayInfo():String = synchronized(lock) {
        if(!authorized()) return@synchronized "ERROR|sem permissão Shizuku"
        try {
            val d=probe() ?: return@synchronized "ERROR|não foi possível consultar wm"
            listOf(d.physicalW,d.physicalH,d.physicalDpi,d.activeW,
                d.activeH,d.activeDpi,if(rollback!=null)1 else 0).joinToString("|")
        } catch (_:Exception) { "ERROR|falha ao consultar resolução" }
    }
    override fun applyTemporary(width:Int,height:Int,dpi:Int):Int = synchronized(lock) {
        if(!authorized()) return@synchronized -10
        if(rollback!=null) return@synchronized -7
        val d=try { probe() } catch (_:Exception) { null } ?: return@synchronized -1
        val minW=max(320,(d.physicalW*.60).toInt())
        val minH=max(480,(d.physicalH*.60).toInt())
        val minDpi=max(160,(d.physicalDpi*.75).toInt())
        val maxDpi=min(640,(d.physicalDpi*1.25).toInt())
        if(width !in minW..d.physicalW || height !in minH..d.physicalH ||
            abs(width.toDouble()/height.coerceAtLeast(1)-d.physicalW.toDouble()/d.physicalH)>.045 ||
            dpi !in minDpi..maxDpi) return@synchronized -2
        val previous=Original(d.sizeOverride,d.densityOverride)
        try {
            val a=cmd("/system/bin/wm","size",width.toString()+"x"+height)
            if(a.first!=0) return@synchronized -3
            val b=cmd("/system/bin/wm","density",dpi.toString())
            if(b.first!=0) { restore(previous);return@synchronized -4 }
            baseline=previous
            rollback=timer.schedule({
                synchronized(lock) {
                    baseline?.let { restore(it) }
                    baseline=null
                    rollback=null
                }
            },45,TimeUnit.SECONDS)
            0
        } catch (_:Exception) {
            restore(previous)
            baseline=null
            -5
        }
    }
    override fun confirmTemporary():Int = synchronized(lock) {
        if(!authorized()) return@synchronized -10
        if(rollback==null) return@synchronized -7
        rollback?.cancel(false)
        rollback=null
        baseline=null
        0
    }
    override fun restoreDefault():Int = synchronized(lock) {
        if(!authorized()) return@synchronized -10
        rollback?.cancel(false)
        rollback=null
        baseline=null
        val a=try { cmd("/system/bin/wm","size","reset") } catch (_:Exception) {-1 to ""}
        val b=try { cmd("/system/bin/wm","density","reset") } catch (_:Exception) {-1 to ""}
        if(a.first==0 && b.first==0)0 else -4
    }
    override fun destroy() {
        synchronized(lock) {
            if(rollback!=null) baseline?.let { restore(it) }
            rollback?.cancel(false)
            baseline=null
            rollback=null
            timer.shutdownNow()
        }
        System.exit(0)
    }
}
