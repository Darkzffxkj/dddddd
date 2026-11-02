package com.quiet.keymapper

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku

data class DisplayState(
    val physicalW:Int,val physicalH:Int,val physicalDpi:Int,
    val activeW:Int,val activeH:Int,val activeDpi:Int,val pending:Boolean
) {
    companion object {
        fun parse(s:String):DisplayState? {
            val p=s.split("|")
            if(p.size!=7) return null
            val n=p.map { it.toIntOrNull() ?: return null }
            return DisplayState(n[0],n[1],n[2],n[3],n[4],n[5],n[6]!=0)
        }
    }
}

/** UI-process Shizuku IPC client. All remote calls run off the main thread. */
class DisplayTuningClient(private val context:Context) {
    private val ui=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val pendingReady=mutableListOf<(Boolean)->Unit>()
    private var bound=false
    private var closed=false
    @Volatile private var remote:IDisplayTuningService?=null
    private val args=Shizuku.UserServiceArgs(
        ComponentName(context.packageName,DisplayTuningUserService::class.java.name)
    ).daemon(false).processNameSuffix("display-tuner").version(1)
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {
            remote=IDisplayTuningService.Stub.asInterface(binder)
            ui.post {
                if(closed) return@post
                val list=pendingReady.toList()
                pendingReady.clear()
                list.forEach { it(true) }
            }
        }
        override fun onServiceDisconnected(name:ComponentName) {
            remote=null
            bound=false
            ui.post {
                val list=pendingReady.toList()
                pendingReady.clear()
                list.forEach { it(false) }
            }
        }
    }
    fun connect(callback:(Boolean)->Unit) {
        if(closed){ callback(false);return }
        if(remote!=null){ callback(true);return }
        pendingReady.add(callback)
        if(bound) return
        try {
            if(!Shizuku.pingBinder() ||
                Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) {
                val list=pendingReady.toList()
                pendingReady.clear()
                list.forEach { it(false) }
                return
            }
            Shizuku.bindUserService(args,connection)
            bound=true
        } catch (_:Exception) {
            val list=pendingReady.toList()
            pendingReady.clear()
            list.forEach { it(false) }
        }
    }
    fun query(callback:(DisplayState?,String?)->Unit) {
        worker.execute {
            val raw=try { remote?.getDisplayInfo() ?: "ERROR|Shizuku desconectado" }
                catch (_:Exception) { "ERROR|consulta indisponível" }
            val result=DisplayState.parse(raw)
            ui.post { if(!closed) callback(result,if(result==null)raw.substringAfter("|",raw) else null) }
        }
    }
    fun apply(w:Int,h:Int,dpi:Int,callback:(Int)->Unit) = operation({it.applyTemporary(w,h,dpi)},callback)
    fun confirm(callback:(Int)->Unit) = operation({it.confirmTemporary()},callback)
    fun reset(callback:(Int)->Unit) = operation({it.restoreDefault()},callback)
    private fun operation(fn:(IDisplayTuningService)->Int,callback:(Int)->Unit) {
        if(closed){callback(-10);return}
        worker.execute {
            val code=try { remote?.let(fn) ?: -10 } catch (_:Exception) { -10 }
            ui.post { if(!closed) callback(code) }
        }
    }
    fun close() {
        closed=true
        pendingReady.clear()
        try {
            if(bound) Shizuku.unbindUserService(args,connection,false)
        } catch (_:Exception) {}
        remote=null
        worker.shutdownNow()
    }
}
