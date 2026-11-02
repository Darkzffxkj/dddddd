package com.quiet.keymapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.app.ActivityManager
import android.os.Build
import android.os.SystemClock
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Path
import android.graphics.Point
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A transparent overlay over the original game. Public accessibility APIs dispatch
 * discrete taps. Continuous WASD/mouse-look requires a separate gesture engine.
 */
class HudAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var current: HudAccessibilityService? = null
            private set
    }
    private val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val store by lazy { HudProfileStore(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var game = ""
    private var foreground = ""
    private var profile = HudProfile()
    private var typing = false
    private var suspended = false
    private val virtualCursor by lazy { VirtualGameCursor(this,wm) }
    private var cursorRequested = false
    private var lastPointerTapAt = 0L
    private var editing = false
    private var bubbleHiddenThisGame = false
    private var bubble: View? = null
    private var menu: View? = null
    private var panel: View? = null
    private var monitor: TextView? = null
    private var pendingExit: Runnable? = null
    private var pendingExitPackage: String? = null
    private val screenOffReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if(intent?.action == Intent.ACTION_SCREEN_OFF) leaveGame()
        }
    }
    private var bindKey: ((Int) -> Unit)? = null
    private var consumedBindingUp = -1
    private val held = mutableSetOf<Int>()
    private val pendingPresses = mutableMapOf<Int, Runnable>()
    private var alive = false
    private val keepHud = object:Runnable {
        override fun run() {
            if(!alive) return
            if(game.isNotEmpty() && foreground == game && !editing && panel == null) {
                if(bubble != null && bubble?.isAttachedToWindow == false) bubble=null
                if(bubble == null) showBubble()
                ensureMonitor()
            }
            handler.postDelayed(this, 2200L)
        }
    }
    private var windowGeneration = 0
    private var transientWindow = false
    private var seenGameOnce = false
    private var firstGameWindowAt = 0L
    private var lastGameWindowAt = 0L
    private var armedAt = 0L
    private val cursors by lazy { CursorFileStore(this) }
    private var displayClient:DisplayTuningClient?=null
    private fun displayTuning():DisplayTuningClient {
        return displayClient ?: DisplayTuningClient(this).also { displayClient=it }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        alive=true
        handler.postDelayed(keepHud,2200L)
        try { registerReceiver(screenOffReceiver,IntentFilter(Intent.ACTION_SCREEN_OFF)) } catch (_:Exception) {}
        serviceInfo = serviceInfo.apply {
            flags=flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        updateMouseCapture()
        // Recover the last launched game when Android restarts an enabled service.
        val previous = getSharedPreferences("referencia_hud_profiles_v7",Context.MODE_PRIVATE)
            .getString("active_game",null)
        if (!previous.isNullOrBlank()) {
            game = previous
            profile = store.load(game)
            armedAt = SystemClock.uptimeMillis()
            diagnostic("SERVICE_RESTORED " + game)
        } else diagnostic("SERVICE_CONNECTED")

    }
    fun prepareGame(packageName: String) {
        handler.post {
            // Opening the same game again is a NEW session. Keep this before
            // startActivity(): stale transitions from our launcher must not end it.
            cancelPendingExit()
            closeAll()
            game=packageName
            foreground=""
            profile=store.load(game)
            typing=false
            suspended=false
            cursorRequested=true
            bubbleHiddenThisGame=false
            seenGameOnce=false
            transientWindow=false
            firstGameWindowAt=0L
            lastGameWindowAt=0L
            armedAt=SystemClock.uptimeMillis()
            windowGeneration++
            getSharedPreferences("referencia_hud_profiles_v7",Context.MODE_PRIVATE)
                .edit().putString("active_game",packageName).apply()
            diagnostic("ARMED " + packageName)
            updateVirtualCursor()
            // Do not show a bubble on the launcher. First wait for the game's
            // TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOWS_CHANGED event.
        }
    }
    /** SDK 34+: mouse motion delivered by AccessibilityService is exclusive while enabled.
     * Older Android versions keep their native mouse pointer; a global replacement
     * cannot be implemented safely through public accessibility APIs.
     */
    private fun updateMouseCapture() {
        if(Build.VERSION.SDK_INT<34)return
        try {
            val enabled=game.isNotBlank() && foreground==game &&
                cursorRequested && !suspended && !typing && !editing
            val info=serviceInfo
            info.setMotionEventSources(
                if(enabled) InputDevice.SOURCE_MOUSE else 0
            )
            serviceInfo=info
        } catch(error:Exception) {
            diagnostic("MOUSE_CAPTURE_UNAVAILABLE "+error.javaClass.simpleName)
        }
    }
    private fun updateVirtualCursor() {
        val visible=game.isNotBlank() && foreground==game &&
            cursorRequested && !editing && !typing
        if(visible && !virtualCursor.active) virtualCursor.show(game)
        if(!visible && virtualCursor.active) virtualCursor.hide()
        updateMouseCapture()
    }
    private fun toggleSuspend() {
        suspended=!suspended
        cancelAllPresses()
        if(suspended) {
            virtualCursor.center()
            diagnostic("SUSPENDED_CURSOR_CENTER")
        } else virtualCursor.center()
        updateVirtualCursor()
        toast(if(suspended)"Controles suspensos • cursor centralizado"
              else "Controles retomados")
    }
    override fun onMotionEvent(event:MotionEvent) {
        super.onMotionEvent(event)
        if(Build.VERSION.SDK_INT<34 || game.isBlank() ||
            foreground!=game || !cursorRequested ||
            suspended || typing || editing)return
        if(event.source and InputDevice.SOURCE_MOUSE != InputDevice.SOURCE_MOUSE)return
        when(event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE,MotionEvent.ACTION_MOVE -> {
                // Android supplies absolute mouse coordinates for SOURCE_MOUSE.
                // Relative devices are handled using their movement deltas.
                if(event.source and InputDevice.SOURCE_MOUSE_RELATIVE ==
                    InputDevice.SOURCE_MOUSE_RELATIVE) {
                    virtualCursor.moveBy(event.x,event.y)
                } else virtualCursor.moveTo(event.x,event.y)
            }
            MotionEvent.ACTION_BUTTON_PRESS,MotionEvent.ACTION_DOWN -> {
                if(event.buttonState and MotionEvent.BUTTON_PRIMARY == 0)return
                val now=SystemClock.uptimeMillis()
                if(now-lastPointerTapAt>110L) {
                    lastPointerTapAt=now
                    val (tx,ty)=virtualCursor.normalized()
                    tap(tx,ty)
                }
            }
        }
    }

    fun onMainAppForeground() {
        // Called only when MainActivity is RESUMED, not at game launch.
        if(game.isNotBlank() && seenGameOnce) leaveGame("RETURNED_TO_MAPPER")
    }
    private fun diagnostic(message:String) {
        try {
            val pref=getSharedPreferences("referencia_overlay_diagnostics",Context.MODE_PRIVATE)
            val stamp=java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.getDefault())
                .format(java.util.Date())
            val existing=pref.getString("events","").orEmpty()
            val updated=(existing.lines().filter { it.isNotBlank() }.takeLast(18)+
                ("$stamp  "+message)).joinToString("\n")
            pref.edit().putString("events",updated).apply()
        } catch (_:Exception) {}
    }
    /** The current game's window is the only source of permission to show our overlay.
     * A non-game foreground activity schedules removal. System transient windows,
     * game boosters and keyboards do not count as a confirmed exit.
     */
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val type=e?.eventType ?: return
        if(type!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type!=AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        val incoming=e.packageName?.toString().orEmpty()
        if(incoming.isBlank() || game.isBlank()) return

        if(incoming==game) {
            cancelPendingExit()
            foreground=game
            val now=SystemClock.uptimeMillis()
            if(!seenGameOnce) {
                firstGameWindowAt=now
                diagnostic("GAME_VISIBLE " + game)
            }
            lastGameWindowAt=now
            seenGameOnce=true
            transientWindow=false
            if(!editing && bubble==null) showBubble()
            updateVirtualCursor()
            ensureMonitor()
            return
        }

        // While we launch a game, the launcher, Google Play splash, and login
        // activities may emit events. None of them can cancel the ARMED state.
        if(!seenGameOnce || type!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val cls=e.className?.toString().orEmpty()
        if(incoming==packageName) {
            // Explicitly returning to the Referência app is an exit. Events
            // from our AccessibilityService overlays are not MainActivity.
            if(cls.contains("MainActivity")) {
                diagnostic("EXIT_MAPPER_WINDOW")
                scheduleExit("MAPPER",800L)
            }
            return
        }

        val home=try {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME),0)?.activityInfo?.packageName
        } catch (_:Exception) { null }
        if(home!=null && incoming==home) {
            diagnostic("EXIT_HOME_WINDOW")
            scheduleExit("HOME",1200L)
            return
        }

        // Exclude Android overlays, vendor game tools, notification sheets
        // and the currently enabled input method. Those are not game exits.
        val ime=try {
            android.provider.Settings.Secure.getString(contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
        } catch (_:Exception) { null }
        val transient=incoming=="android" || incoming=="com.android.systemui" ||
            incoming=="com.google.android.gms" || incoming==ime ||
            incoming.contains("permissioncontroller",true) ||
            incoming.contains("inputmethod",true) ||
            incoming.contains("keyboard",true) ||
            incoming.contains("honeyboard",true) ||
            incoming.contains("gamebooster",true) ||
            incoming.startsWith("com.samsung.android.game.")
        if(transient) return

        // During startup/loading, other package windows are not reliable
        // evidence that Free Fire exited. Prevent the V10 false-positive.
        val now=SystemClock.uptimeMillis()
        if(firstGameWindowAt==0L || now-firstGameWindowAt<30_000L) return

        // After startup, a stable unrelated app window with NO intervening
        // game event can close the HUD. The delay gives games using external
        // authentication/ads time to regain focus.
        if(pendingExitPackage!=incoming) {
            diagnostic("CHECK_OTHER " + incoming)
            scheduleExit(incoming,8000L)
        }
    }
    private fun cancelPendingExit() {
        pendingExit?.let(handler::removeCallbacks)
        pendingExit=null
        pendingExitPackage=null
    }
    private fun scheduleExit(otherPackage:String,delay:Long) {
        if(pendingExitPackage==otherPackage) return
        cancelPendingExit()
        pendingExitPackage=otherPackage
        val pending=Runnable {
            pendingExit=null
            pendingExitPackage=null
            if(game.isNotBlank() && foreground==game && seenGameOnce)
                leaveGame("EXIT_CONFIRMED " + otherPackage)
        }
        pendingExit=pending
        handler.postDelayed(pending,delay)
    }
    private fun leaveGame(reason:String="EXIT_SCREEN_OFF") {
        diagnostic(reason)
        DisplaySessionReset.restoreIfModified(applicationContext)
        cancelPendingExit()
        foreground=""
        seenGameOnce=false
        transientWindow=false
        firstGameWindowAt=0L
        lastGameWindowAt=0L
        armedAt=0L
        cancelAllPresses()
        cursorRequested=false
        updateVirtualCursor()
        closeAll()
        game=""
        getSharedPreferences("referencia_hud_profiles_v7",Context.MODE_PRIVATE)
            .edit().remove("active_game").apply()
    }
    override fun onInterrupt() {}
    override fun onDestroy() {
        alive=false
        cancelPendingExit()
        handler.removeCallbacks(keepHud)
        DisplaySessionReset.restoreIfModified(applicationContext)
        displayClient?.close()
        displayClient=null
        try { unregisterReceiver(screenOffReceiver) } catch (_:Exception) {}
        cancelAllPresses()
        cursorRequested=false
        virtualCursor.hide()
        updateMouseCapture()
        closeAll()
        if (current === this) current = null
        super.onDestroy()
    }
    private fun cancelAllPresses() {
        held.clear()
        pendingPresses.values.forEach { handler.removeCallbacks(it) }
        pendingPresses.clear()
    }
    override fun onKeyEvent(e:KeyEvent):Boolean {
        if(game.isBlank() || foreground!=game) return false
        if(e.action==KeyEvent.ACTION_UP && e.keyCode==consumedBindingUp) {
            consumedBindingUp=-1
            return true
        }
        if(bindKey!=null && e.action==KeyEvent.ACTION_DOWN && e.repeatCount==0) {
            consumedBindingUp=e.keyCode
            val fn=bindKey; bindKey=null
            fn?.invoke(e.keyCode)
            return true
        }
        if(editing) return false
        if(e.keyCode==profile.shortcutKey) {
            if(e.action==KeyEvent.ACTION_DOWN && e.repeatCount==0) {
                typing=!typing
                if(typing) cancelAllPresses()
                updateVirtualCursor()
                toast(if(typing) "Modo digitação" else "Modo controle")
            }
            return true
        }
        val suspend=profile.controls.firstOrNull {
            it.kind=="SUSPEND" && it.keyCode==e.keyCode
        }
        if(suspend!=null) {
            if(e.action==KeyEvent.ACTION_DOWN && e.repeatCount==0)
                toggleSuspend()
            return true
        }
        if(profile.resetViewKey!=KeyEvent.KEYCODE_UNKNOWN &&
            profile.resetViewKey==e.keyCode) {
            if(e.action==KeyEvent.ACTION_DOWN && e.repeatCount==0) {
                virtualCursor.center()
                toast("Cursor virtual centralizado")
            }
            return true
        }
        if(typing || suspended) return false
        val mapped=profile.controls.firstOrNull {
            (it.kind=="TOUCH" || it.kind=="FIRE") && it.keyCode==e.keyCode
        } ?: return false
        val code=e.keyCode
        if(e.action==KeyEvent.ACTION_DOWN && e.repeatCount==0) {
            if(held.add(code)) {
                val command=object:Runnable {
                    override fun run() {
                        if(code !in held || foreground!=game || typing || suspended || editing) return
                        tap(mapped.x,mapped.y)
                        if(mapped.kind=="FIRE")
                            handler.postDelayed(this,mapped.repeatMs.coerceIn(50,500).toLong())
                    }
                }
                pendingPresses[code]=command
                handler.postDelayed(command,mapped.delayMs.coerceIn(0,300).toLong())
            }
        } else if(e.action==KeyEvent.ACTION_UP) {
            held.remove(code)
            pendingPresses.remove(code)?.let { handler.removeCallbacks(it) }
        }
        return true
    }
    private fun size():Point = Point().also {
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(it)
    }
    private fun tap(x: Float,y:Float) {
        if (foreground != game || editing || transientWindow) return
        val p=size()
        val path=Path().apply { moveTo(x.coerceIn(0f,1f)*p.x,y.coerceIn(0f,1f)*p.y) }
        dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path,0,40)).build(),null,null)
    }
    private fun dp(v:Int)=(resources.displayMetrics.density*v+.5f).toInt()
    private fun toast(s:String) = handler.post { Toast.makeText(this,s,Toast.LENGTH_SHORT).show() }
    private fun bg(color:Int,stroke:Int=0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color);cornerRadius=dp(13).toFloat()
            if(stroke!=0)setStroke(dp(1),stroke)
        }
    private fun txt(s:String,sp:Float=14f):TextView=TextView(this).apply {
        text=s;textSize=sp;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL
    }
    private fun btn(s:String,task:()->Unit):TextView=txt(s,13f).apply {
        gravity=Gravity.CENTER
        setPadding(dp(10),dp(8),dp(10),dp(8))
        background=bg(0xFF6E2CB4.toInt(),0xFFB77AFF.toInt())
        setOnClickListener { task() }
    }
    private fun col():LinearLayout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row():LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
    }
    private fun lp(w:Int,h:Int)=WindowManager.LayoutParams(w,h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.START
        }
    private fun add(v:View,p:WindowManager.LayoutParams):Boolean =
        try { wm.addView(v,p);true } catch (error:Exception) {
            diagnostic("OVERLAY_ADD_FAILED "+error.javaClass.simpleName)
            false
        }
    private fun remove(v:View?) { if(v!=null)try { wm.removeView(v) }catch(_:Exception){} }
    private fun ensureMonitor() {
        if(game.isBlank() || foreground!=game || !profile.showDeviceMonitor) {
            remove(monitor);monitor=null;return
        }
        val activity=getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory=ActivityManager.MemoryInfo()
        val line=try {
            activity.getMemoryInfo(memory)
            val free=memory.availMem.toDouble()/1_073_741_824.0
            val total=memory.totalMem.toDouble()/1_073_741_824.0
            String.format(java.util.Locale.getDefault(),
                "%s  |  RAM %.1f / %.1f GB livre", Build.MODEL,free,total)
        } catch (_:Exception) { Build.MODEL }
        if(monitor==null) {
            val v=txt(line,11f).apply {
                setPadding(dp(10),dp(5),dp(10),dp(5))
                background=bg(0xE31C132A.toInt(),0xFF8849D0.toInt())
            }
            val params=lp(-2,-2).apply {
                flags=flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x=0;y=dp(50)
            }
            if(add(v,params)) monitor=v
        } else monitor?.text=line
    }
    private fun closeMenu(){remove(menu);menu=null}
    private fun closePanel(){
        remove(panel);panel=null;editing=false;bindKey=null
        showBubble()
        updateVirtualCursor()
    }
    private fun closeAll(){
        remove(menu);menu=null;remove(panel);panel=null;remove(bubble);bubble=null
        remove(monitor);monitor=null
        editing=false;bindKey=null;cancelAllPresses()
    }
    private fun save(){ if(game.isNotEmpty()) store.save(game,profile) }
    private fun cornerPosition(p:WindowManager.LayoutParams,c:Int) {
        val sz=size()
        val side=dp(profile.bubbleSize.coerceIn(40,82))
        p.x=if(c%2==0) dp(10) else sz.x-side-dp(10)
        p.y=if(c<2) dp(60) else sz.y-side-dp(56)
    }
    private fun showBubble(){
        if(bubbleHiddenThisGame || bubble!=null || panel!=null || game.isEmpty() || foreground!=game)return
        val v=ImageView(this).apply {
            setImageResource(R.drawable.brand_logo)
            scaleType=ImageView.ScaleType.FIT_CENTER
            setPadding(dp(5),dp(5),dp(5),dp(5))
            background=GradientDrawable().apply {
                shape=GradientDrawable.OVAL
                setColor(0xEE160B26.toInt())
                setStroke(dp(2),0xFFD7A9FF.toInt())
            }
            contentDescription="Referência — abrir menu"
        }
        val side=dp(profile.bubbleSize.coerceIn(40,82))
        val params=lp(side,side)
        cornerPosition(params,store.corner())
        var downX=0f;var downY=0f;var wasX=0;var wasY=0;var dragged=false
        v.setOnTouchListener { _,e ->
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{downX=e.rawX;downY=e.rawY;wasX=params.x;wasY=params.y;dragged=false;true}
                MotionEvent.ACTION_MOVE->{
                    if(hypot(e.rawX-downX,e.rawY-downY)>dp(8))dragged=true
                    if(dragged){
                        params.x=wasX+(e.rawX-downX).toInt()
                        params.y=wasY+(e.rawY-downY).toInt()
                        try{wm.updateViewLayout(v,params)}catch(_:Exception){}
                    };true
                }
                MotionEvent.ACTION_UP->{
                    if(dragged){
                        val sz=size()
                        val c=(if(params.y>sz.y/2)2 else 0)+(if(params.x>sz.x/2)1 else 0)
                        store.saveCorner(c);cornerPosition(params,c)
                        try{wm.updateViewLayout(v,params)}catch(_:Exception){}
                    }else toggleMenu()
                    true
                }
                else->false
            }
        }
        if(add(v,params)) {
            bubble=v
            diagnostic("BUBBLE_VISIBLE "+game)
        }
    }
    private fun toggleMenu(){
        if(menu!=null){closeMenu();return}
        val container=col().apply {
            background=bg(0xF3191323.toInt(),0xFF964AE0.toInt())
            setPadding(dp(12),dp(12),dp(12),dp(12))
        }
        container.addView(txt("Referência • HUD",15f))
        val options=listOf<Pair<String,()->Unit>>(
            "Editar HUD" to {openEditor()},
            "Mouse / Suspender + Sensi" to {openMouseSettings()},
            "Resolução / DPI" to {openSettingsPage(4)},
            (if(cursorRequested)"Ocultar cursor virtual" else "Mostrar cursor virtual") to {
                cursorRequested=!cursorRequested
                closeMenu()
                updateVirtualCursor()
                toast(if(cursorRequested)"Cursor virtual ativado" else "Cursor virtual oculto")
            },
            (if(typing)"Voltar ao controle" else "Digitar no teclado") to {
                typing=!typing;closeMenu()
                updateVirtualCursor()
                toast(if(typing)"Toque no chat para digitar" else "Controles retomados")
            },
            "✕ Ocultar botão flutuante" to {
                bubbleHiddenThisGame=true
                closeMenu()
                remove(bubble);bubble=null
                toast("Botão ocultado. Reabra o jogo pela Referência para mostrar novamente.")
            }
        )
        options.forEach { (title,task)->
            container.addView(btn(title,task),LinearLayout.LayoutParams(-1,dp(45)).apply {topMargin=dp(7)})
        }
        val sz=size();val width=min(dp(262),sz.x-dp(20))
        val p=lp(width,-2);val c=store.corner()
        p.x=if(c%2==0)dp(10)else sz.x-width-dp(10)
        p.y=if(c<2)dp(121)else max(dp(12),sz.y-dp(313))
        if(add(container,p))menu=container
    }
    private fun fullScreen(edit:Boolean,allowKeyboard:Boolean=false):FrameLayout{
        closeMenu();remove(bubble);bubble=null;remove(panel);panel=null
        editing=edit
        updateVirtualCursor()
        val root=FrameLayout(this).apply { setBackgroundColor(0x90090514.toInt()) }
        val overlayParams=lp(-1,-1)
        if(allowKeyboard) {
            // Android Accessibility overlays default to NOT_FOCUSABLE in this app.
            // A focusable window is required for typed resolution/DPI values.
            overlayParams.flags=overlayParams.flags and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            overlayParams.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        if(add(root,overlayParams))panel=root
        return root
    }
    private fun openEditor(showCatalog:Boolean=true) {
        val root=fullScreen(true)
        val bar=row().apply {
            setPadding(dp(8),dp(5),dp(8),dp(5))
            background=bg(0xF4110E24.toInt(),0xFF686BEE.toInt())
        }
        val title=txt("REFERÊNCIA  /  EDITOR DE HUD",15f).apply{
            setTextColor(0xFFE6DBFF.toInt())
        }
        bar.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(btn("＋ Componente"){showComponentCatalog(root)},
            LinearLayout.LayoutParams(-2,dp(42)).apply{rightMargin=dp(6)})
        bar.addView(btn("Salvar") {
            save()
            closePanel()
            toast("HUD salvo")
        },LinearLayout.LayoutParams(-2,dp(42)).apply{rightMargin=dp(6)})
        bar.addView(btn("✕"){closePanel()},LinearLayout.LayoutParams(dp(48),dp(42)))
        root.addView(bar,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val hint=txt("Arraste as bolinhas • ⚙ configura • × exclui",11f).apply{
            setPadding(dp(12),0,dp(12),0)
            setTextColor(0xFFE2D5FF.toInt())
            background=bg(0xE91B142B.toInt(),0xFF7861B3.toInt())
        }
        root.addView(hint,FrameLayout.LayoutParams(-1,dp(31),Gravity.BOTTOM))
        profile.controls.toList().forEach { makeMarker(root,it) }
        if(showCatalog) showComponentCatalog(root)
    }

    private fun showComponentCatalog(root:FrameLayout) {
        root.findViewWithTag<View>("componentCatalog")?.let {root.removeView(it)}
        val scroller=ScrollView(this).apply {
            tag="componentCatalog"
            isFillViewport=false
            isVerticalScrollBarEnabled=true
            overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLL
            background=bg(0xF513122E.toInt(),0xFF6871FF.toInt())
        }
        val sheet=col().apply {setPadding(dp(13),dp(9),dp(13),dp(12))}
        val top=row()
        top.addView(txt("ADICIONAR COMPONENTE",20f).apply {
            setTextColor(0xFFF9F4FF.toInt())
        },LinearLayout.LayoutParams(0,-2,1f))
        top.addView(btn("✕"){root.removeView(scroller)},
            LinearLayout.LayoutParams(dp(47),dp(42)))
        sheet.addView(top)
        sheet.addView(txt("Selecione um componente para colocar no keymap",12f).apply {
            setTextColor(0xFFC7C1E5.toInt())
        },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(5);bottomMargin=dp(10)})
        val grid=android.widget.GridLayout(this).apply {
            columnCount=4
        }
        val sz=size()
        val catalogWidth=min(sz.x-dp(18),dp(855))
        val cellWidth=max(dp(75),(catalogWidth-dp(35))/4)
        val cards=listOf(
            Triple("TOUCH","☝","Toque"),
            Triple("SWIPE","↗","Deslizar"),
            Triple("SCROLL","◉","Rolar com o mouse"),
            Triple("MACRO","⇄","Macro"),
            Triple("JOYSTICK","✥","Joystick"),
            Triple("VISION","⌖","Visão FPS"),
            Triple("LOOK","◎","Olhar Livre"),
            Triple("MOUSE_SELECTION","⊕","Seleção do Mouse"),
            Triple("SUSPEND","Ⅱ","Suspender + Sensi"),
            Triple("FIRE","◉","Atirar")
        )
        cards.forEachIndexed {i,(kind,icon,label)->
            val available=kind in setOf("TOUCH","JOYSTICK","SUSPEND","FIRE")
            val tile=col().apply {
                gravity=Gravity.CENTER
                setPadding(dp(6),dp(5),dp(6),dp(5))
                background=GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    if(available) intArrayOf(0xFF262655.toInt(),
                        0xFF1B1B3E.toInt(),0xFF171630.toInt())
                    else intArrayOf(0xFF18182A.toInt(),0xFF131326.toInt())
                ).apply {
                    cornerRadius=dp(11).toFloat()
                    setStroke(dp(2),if(available)0xFF656AFF.toInt() else 0xFF393A72.toInt())
                }
                isClickable=true
                setOnClickListener {
                    if(available) newControl(kind)
                    else toast(label+" • motor de entrada ainda não implementado")
                }
            }
            val glyph=txt(icon,28f).apply {
                gravity=Gravity.CENTER
                setTextColor(if(available)Color.WHITE else 0xFF8885AA.toInt())
            }
            tile.addView(glyph,LinearLayout.LayoutParams(-1,dp(49)))
            val name=txt(label,12f).apply{
                gravity=Gravity.CENTER
                setTextColor(if(available)Color.WHITE else 0xFFA9A3C9.toInt())
                maxLines=2
            }
            tile.addView(name,LinearLayout.LayoutParams(-1,-2))
            if(!available) tile.addView(txt("EM BREVE",8f).apply {
                gravity=Gravity.CENTER
                setTextColor(0xFFAEA9FF.toInt())
            },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(3)})
            grid.addView(tile,android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(i/4),
                android.widget.GridLayout.spec(i%4)
            ).apply {
                width=cellWidth-dp(5)
                height=dp(112)
                setMargins(dp(2),dp(2),dp(3),dp(4))
            })
        }
        sheet.addView(grid)
        sheet.addView(txt(
            "Toque, Atirar e Suspender estão disponíveis. Joystick registra o perfil, "+
            "mas o movimento WASD contínuo ainda precisa do motor de gestos. "+
            "As funções marcadas EM BREVE não geram comandos falsos.",10f
        ).apply{
            setTextColor(0xFFD3CCE7.toInt())
        },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        scroller.addView(sheet)
        val p=FrameLayout.LayoutParams(catalogWidth,
            min(sz.y-dp(85),dp(530)),Gravity.CENTER)
        root.addView(scroller,p)
    }

    private fun newControl(kind:String){
        if(profile.controls.size>=90){toast("Limite de controles");return}
        val c=HudControl(kind,
            when(kind) { "SUSPEND"->KeyEvent.KEYCODE_F9;"FIRE"->KeyEvent.KEYCODE_F;else->KeyEvent.KEYCODE_SPACE },.5f,.49f)
        profile.controls.add(c);save();openEditor(false);openControlSettings(c)
    }
    private fun makeMarker(root:FrameLayout,c:HudControl){
        val sz=size()
        val defaultSide=if(c.kind=="JOYSTICK")108 else 74
        val side=dp(defaultSide*profile.hudScale.coerceIn(50,160)/100)
        val v=FrameLayout(this)
        v.addView(HudMarkerView(this,c),FrameLayout.LayoutParams(-1,-1))
        val gear=txt("⚙",21f).apply {
            gravity=Gravity.CENTER
            background=bg(0xEE24152D.toInt(),0xFFC5A0FF.toInt())
            contentDescription="Configurar controle"
            setOnClickListener { openControlSettings(c) }
        }
        v.addView(gear,FrameLayout.LayoutParams(dp(28),dp(28),Gravity.TOP or Gravity.RIGHT))
        val delete=txt("×",22f).apply {
            gravity=Gravity.CENTER
            setTextColor(0xFFFFDAED.toInt())
            background=bg(0xF05D1C42.toInt(),0xFFFF86B5.toInt())
            contentDescription="Excluir controle do HUD"
            setOnClickListener {
                profile.controls.remove(c)
                save()
                openEditor()
                toast("Controle excluído do HUD")
            }
        }
        v.addView(delete,FrameLayout.LayoutParams(dp(28),dp(28),
            Gravity.BOTTOM or Gravity.RIGHT))
        val params=FrameLayout.LayoutParams(side,side).apply {
            leftMargin=(c.x*sz.x-side/2).toInt().coerceIn(0,max(0,sz.x-side))
            topMargin=(c.y*sz.y-side/2).toInt().coerceIn(dp(54),max(dp(54),sz.y-side-dp(43)))
        }
        root.addView(v,params)
        var dx=0f;var dy=0f;var px=0;var py=0;var moved=false;var start=0L
        v.setOnTouchListener { _,e ->
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN -> {
                    dx=e.rawX;dy=e.rawY;px=params.leftMargin;py=params.topMargin
                    moved=false;start=android.os.SystemClock.uptimeMillis();true
                }
                MotionEvent.ACTION_MOVE -> {
                    if(hypot(e.rawX-dx,e.rawY-dy)>dp(7))moved=true
                    if(moved) {
                        params.leftMargin=(px+(e.rawX-dx).toInt()).coerceIn(0,max(0,sz.x-side))
                        params.topMargin=(py+(e.rawY-dy).toInt()).coerceIn(dp(54),max(dp(54),sz.y-side-dp(43)))
                        v.layoutParams=params
                    };true
                }
                MotionEvent.ACTION_UP -> {
                    if(moved) {
                        c.x=(params.leftMargin+side/2f)/sz.x
                        c.y=(params.topMargin+side/2f)/sz.y
                        save()
                    } else if(android.os.SystemClock.uptimeMillis()-start>650) {
                        profile.controls.remove(c);save();openEditor(false)
                    } else openControlSettings(c)
                    true
                }
                else -> false
            }
        }
    }
    private fun seek(parent:LinearLayout,label:String,value:Int,minVal:Int,maxVal:Int,set:(Int)->Unit){
        val title=txt(label+" · "+value,13f)
        parent.addView(title,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        val s=SeekBar(this).apply{
            max=maxVal-minVal;progress=(value-minVal).coerceIn(0,max)
            progressTintList=ColorStateList.valueOf(0xFFB47AFF.toInt())
            thumbTintList=ColorStateList.valueOf(0xFFE7CCFF.toInt())
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(bar:SeekBar,n:Int,user:Boolean){
                    if(user){title.text=label+" · "+(n+minVal);set(n+minVal);save()}
                }
                override fun onStartTrackingTouch(bar:SeekBar){}
                override fun onStopTrackingTouch(bar:SeekBar){}
            })
        }
        parent.addView(s)
    }
    private fun openControlSettings(c:HudControl){
        val root=panel as? FrameLayout?:return
        root.findViewWithTag<View>("controlSheet")?.let{root.removeView(it)}
        val box=col().apply{
            tag="controlSheet";setPadding(dp(14),dp(12),dp(14),dp(14))
            background=bg(0xFA1D1429.toInt(),0xFFBD88FF.toInt())
        }
        box.addView(txt(when(c.kind){"JOYSTICK"->"Analógico / WASD"
            "SUSPEND"->"Suspender + Sensi";"FIRE"->"◉ Atirar";else->"Tocar / Tecla"},17f))
        box.addView(btn("Vincular: "+KeyEvent.keyCodeToString(c.keyCode).removePrefix("KEYCODE_")){
            bindKey={key->c.keyCode=key;save();openControlSettings(c)}
            toast("Pressione a tecla física")
        },LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(9)})
        if(c.kind=="TOUCH" || c.kind=="FIRE") {
            seek(box,"Delay para ativar (ms)",c.delayMs,0,300){ c.delayMs=it;save() }
            if(c.kind=="FIRE") {
                seek(box,"Intervalo entre disparos (ms)",c.repeatMs,50,500){ c.repeatMs=it;save() }
                box.addView(txt("Enquanto segurar a tecla, o Android enviará toques repetidos. Não é um toque contínuo.",11f))
            }
        }
        if(c.kind=="JOYSTICK"){
            seek(box,"Raio do analógico",(c.radius*1000).toInt(),20,300){c.radius=it/1000f}
            seek(box,"Caminhar devagar / Shift (%)",(c.slowRatio*100).toInt(),5,100){c.slowRatio=it/100f}
            box.addView(txt("WASD e Shift: perfil salvo; movimento contínuo requer motor multitouch.",11f))
        }
        if(c.kind=="SUSPEND"){
            box.addView(btn("Abrir configuração de Mouse / Visão") {
                save()
                openMouseSettings()
            },LinearLayout.LayoutParams(-1,dp(43)).apply{topMargin=dp(6)})
            box.addView(btn("Preset estilo MSI App Player 5.9"){
                profile.sensitivityX=350;profile.sensitivityY=350
                profile.aimAcceleration=0;profile.aimSmoothing=10
                profile.msiStyle=true;save();openControlSettings(c)
            },LinearLayout.LayoutParams(-1,dp(42)).apply{topMargin=dp(8)})
            seek(box,"Sensi X",profile.sensitivityX,1,1000){profile.sensitivityX=it;save()}
            seek(box,"Sensi Y",profile.sensitivityY,1,1000){profile.sensitivityY=it;save()}
            seek(box,"Aceleração (%)",profile.aimAcceleration,0,100){profile.aimAcceleration=it;save()}
            seek(box,"Suavização (%)",profile.aimSmoothing,0,100){profile.aimSmoothing=it;save()}
            box.addView(txt("Escala inspirada no MSI 5.9: não reproduz o motor de mouse do emulador.",11f))
        }
        box.addView(btn("Concluído"){root.removeView(box);save()},
            LinearLayout.LayoutParams(-1,dp(42)).apply{topMargin=dp(9)})
        val sz=size();root.addView(box,FrameLayout.LayoutParams(min(sz.x-dp(20),dp(410)),-2,Gravity.CENTER))
    }
    // Main menu follows the five categories in the supplied reference, adapted
    // to the project's existing dark-purple Android visual language.
    /** Mouse / vision configuration inspired by the supplied reference screenshot.
     * Settings which depend on a full relative-aim engine are clearly labeled.
     */
    private fun openMouseSettings() {
        val root=fullScreen(false)
        val scroll=ScrollView(this)
        val layout=col().apply {
            background=bg(0xF5110D1C.toInt(),0xFF6C63FB.toInt())
            setPadding(dp(16),dp(10),dp(16),dp(16))
        }
        val header=row()
        header.addView(txt("CONFIGURAÇÃO  •  MOUSE E VISÃO",17f).apply {
            setTextColor(0xFFF4ECFF.toInt())
        },LinearLayout.LayoutParams(0,-2,1f))
        header.addView(btn("✕"){closePanel()},LinearLayout.LayoutParams(dp(46),dp(41)))
        layout.addView(header)
        layout.addView(txt("SUSPENDER  /  CURSOR VIRTUAL  /  SENSIBILIDADE",10f).apply {
            setTextColor(0xFFA9A4FF.toInt())
        },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(7);bottomMargin=dp(10)})
        var suspendControl=profile.controls.firstOrNull {it.kind=="SUSPEND"}
        fun rowButton(label:String,code:Int,onTap:()->Unit) {
            val line=row()
            line.addView(txt(label,12f),LinearLayout.LayoutParams(0,-2,1f))
            line.addView(txt(KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_"),12f).apply {
                setTextColor(0xFFDAD3FF.toInt())
            },LinearLayout.LayoutParams(-2,-2).apply{rightMargin=dp(8)})
            line.addView(btn("ALTERAR",onTap),LinearLayout.LayoutParams(dp(95),dp(39)))
            layout.addView(line,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        }
        if(suspendControl==null) {
            val add=btn("＋  Adicionar Suspender ao HUD") {
                if(profile.controls.none {it.kind=="SUSPEND"}) {
                    profile.controls.add(HudControl("SUSPEND",KeyEvent.KEYCODE_F9,.5f,.5f))
                }
                profile.suspendEnabled=true
                save()
                openMouseSettings()
            }
            layout.addView(add,LinearLayout.LayoutParams(-1,dp(43)))
        } else {
            rowButton("Tecla para Suspender / Soltar mouse",suspendControl.keyCode) {
                bindKey={code ->
                    suspendControl?.let { it.keyCode=code }
                    profile.suspendEnabled=true
                    save()
                    openMouseSettings()
                }
                toast("Pressione uma tecla física para Suspender")
            }
        }
        rowButton("Tecla para centralizar o cursor",profile.resetViewKey) {
            bindKey={code ->
                profile.resetViewKey=code
                save()
                openMouseSettings()
            }
            toast("Pressione uma tecla física para centralizar")
        }
        val center=btn("⌖  Centralizar cursor agora") {
            virtualCursor.center()
            toast("Cursor virtual centralizado")
        }
        layout.addView(center,LinearLayout.LayoutParams(-1,dp(43)).apply{topMargin=dp(10)})
        val status=txt("Estado: "+if(suspended)"Suspenso" else "Controles ativos",12f)
        status.setTextColor(if(suspended)0xFFFFCE9C.toInt() else 0xFFBEECDA.toInt())
        layout.addView(status,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        val toggleButton=btn(if(suspended)"▶  Retomar controles" else "Ⅱ  Suspender controles") {
            toggleSuspend()
            openMouseSettings()
        }
        layout.addView(toggleButton,LinearLayout.LayoutParams(-1,dp(43)).apply{topMargin=dp(9)})
        val divider=View(this).apply {setBackgroundColor(0xFF5B4387.toInt())}
        layout.addView(divider,LinearLayout.LayoutParams(-1,dp(1)).apply{topMargin=dp(13);bottomMargin=dp(9)})
        val pointer=txt("Cursor virtual no jogo: "+if(cursorRequested)"Ativado" else "Desativado",12f)
        layout.addView(pointer)
        val showCursor=btn(if(cursorRequested)"Ocultar seta virtual" else "Mostrar seta virtual") {
            cursorRequested=!cursorRequested
            updateVirtualCursor()
            openMouseSettings()
        }
        layout.addView(showCursor,LinearLayout.LayoutParams(-1,dp(41)).apply{topMargin=dp(5)})
        val selected=cursors.filename(game)
        layout.addView(txt("Seta: "+(selected ?: "Roxa padrão")+"  •  "+cursors.size(game)+" px",11f),
            LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(7)})
        val notice=txt(
            "A seta .cur é importada na tela inicial. Android 14+: captura experimental de mouse. "+
            "A seta nativa pode continuar visível em alguns dispositivos; "+
            "Android 10–13 não disponibiliza captura global do mouse por essa API.",11f
        )
        notice.setTextColor(0xFFD9C2F1.toInt())
        layout.addView(notice,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(7)})
        val divider2=View(this).apply{setBackgroundColor(0xFF5B4387.toInt())}
        layout.addView(divider2,LinearLayout.LayoutParams(-1,dp(1)).apply{topMargin=dp(12);bottomMargin=dp(9)})
        layout.addView(txt("SENSIBILIDADE  •  PERFIL DE MIRA",13f).apply{
            setTextColor(0xFFDBBBFF.toInt())
        })
        seek(layout,"Sensibilidade Horizontal (X)",profile.sensitivityX,1,1000) {
            profile.sensitivityX=it
        }
        seek(layout,"Sensibilidade Vertical (Y)",profile.sensitivityY,1,1000) {
            profile.sensitivityY=it
        }
        seek(layout,"Aceleração (%)",profile.aimAcceleration,0,100){
            profile.aimAcceleration=it
        }
        seek(layout,"Suavização (%)",profile.aimSmoothing,0,100){
            profile.aimSmoothing=it
        }
        layout.addView(btn("Preset visual estilo MSI App Player 5.9"){
            profile.sensitivityX=350;profile.sensitivityY=350
            profile.aimAcceleration=0;profile.aimSmoothing=10
            profile.msiStyle=true
            save()
            openMouseSettings()
        },LinearLayout.LayoutParams(-1,dp(43)).apply{topMargin=dp(9)})
        val foot=txt(
            "X/Y, aceleração e suavização são parâmetros de perfil. "+
            "O motor de mira contínua não está implementado nesta versão. "+
            "Ao suspender, a posição do cursor virtual volta ao centro da tela.",11f
        )
        foot.setTextColor(0xFFFFD6AD.toInt())
        layout.addView(foot,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        layout.addView(btn("SALVAR E FECHAR"){save();closePanel()},
            LinearLayout.LayoutParams(-1,dp(43)).apply{topMargin=dp(10)})
        scroll.addView(layout)
        val sz=size()
        root.addView(scroll,FrameLayout.LayoutParams(
            min(sz.x-dp(16),dp(740)),min(sz.y-dp(16),dp(650)),Gravity.CENTER))
    }

    private fun openSettings() {
        val root=fullScreen(false)
        val scroll=ScrollView(this)
        val container=col().apply {
            background=bg(0xF3181025.toInt(),0xFFB784F5.toInt())
            setPadding(dp(15),dp(14),dp(15),dp(16))
        }
        val header=row()
        header.addView(txt("Configurações",19f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(btn("✕"){closePanel()},LinearLayout.LayoutParams(dp(49),dp(42)))
        container.addView(header)
        container.addView(txt("REFERÊNCIA  •  AJUSTES POR JOGO",10f).apply {
            setTextColor(0xFFC8A4F6.toInt())
        },LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(4);bottomMargin=dp(11)})
        fun category(label:String,action:()->Unit) {
            val item=row().apply {
                setPadding(dp(10),dp(8),dp(8),dp(8))
                background=bg(0xD82C1A40.toInt(),0xFF5C3379.toInt())
                setOnClickListener { action() }
            }
            item.addView(txt(label,14f),LinearLayout.LayoutParams(0,-2,1f))
            item.addView(txt("❯",23f).apply {
                gravity=Gravity.CENTER
                setTextColor(0xFFDDC1FF.toInt())
            },LinearLayout.LayoutParams(dp(34),dp(43)))
            container.addView(item,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(9)})
        }
        category("Configurações Básicas"){openSettingsPage(0)}
        category("Configurações do Monitor de Informações do Dispositivo"){openSettingsPage(1)}
        category("Configurações Gerais de Controle de Recuo"){openSettingsPage(2)}
        category("Configurações da mira"){openSettingsPage(3)}
        category("Configurações da ferramenta de ajuste de tamanho de tela"){openSettingsPage(4)}
        container.addView(txt("Alguns controles são apenas configurações do perfil, pois o motor de mouse relativo e a compensação de recuo ainda não estão implementados.",11f).apply {
            setTextColor(0xFFD4B9E8.toInt())
        },LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(14)})
        scroll.addView(container)
        val sz=size()
        root.addView(scroll,FrameLayout.LayoutParams(
            min(sz.x-dp(18),dp(600)),min(sz.y-dp(24),dp(670)),Gravity.CENTER))
    }

    private fun toggle(box:LinearLayout,label:String,checked:Boolean,changed:(Boolean)->Unit) {
        val r=row().apply { setPadding(0,dp(8),0,dp(8)) }
        r.addView(txt(label,13f),LinearLayout.LayoutParams(0,-2,1f))
        val sw=Switch(this).apply {
            isChecked=checked
            thumbTintList=ColorStateList.valueOf(0xFFD3A7FF.toInt())
            setOnCheckedChangeListener { _,value -> changed(value);save() }
        }
        r.addView(sw)
        box.addView(r)
    }
    private fun createDisplaySettings(box:LinearLayout) {
        box.addView(txt("Resolução personalizada e Density (DPI)",15f),
            LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(17)})
        val notice=txt(
            "Digite a largura, a altura e o DPI que preferir. Exemplo: 1656 × 1080 / 500 DPI. " +
            "Você não precisa manter a proporção original da tela. A mudança afeta todo o Android.",
            11f
        )
        notice.setTextColor(0xFFFFD0A2.toInt())
        box.addView(notice,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(7)})
        val status=txt("Verificando resolução atual pelo Shizuku...",12f)
        box.addView(status,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(10)})
        val choices=col()
        box.addView(choices)
        val client=displayTuning()

        fun render(display:DisplayState) {
            if(!box.isAttachedToWindow)return
            choices.removeAllViews()
            status.text="Resolução atual: "+display.activeW+" × "+display.activeH+
                " / "+display.activeDpi+" DPI"+
                "\nTela física: "+display.physicalW+" × "+display.physicalH+
                " / "+display.physicalDpi+" DPI"+
                if(display.pending)"\nTESTE ATIVO: confirme antes de 45 segundos." else ""

            fun entry(label:String,initial:Int):android.widget.EditText {
                val section=col()
                val title=txt(label,13f)
                title.setTextColor(0xFFE8D1FF.toInt())
                section.addView(title)
                val input=android.widget.EditText(this).apply {
                    setText(initial.toString())
                    inputType=android.text.InputType.TYPE_CLASS_NUMBER
                    isSingleLine=true
                    textSize=18f
                    setTextColor(android.graphics.Color.WHITE)
                    setSelectAllOnFocus(true)
                    background=bg(0xFF271634.toInt(),0xFF9F65E4.toInt())
                    setPadding(dp(10),dp(7),dp(10),dp(7))
                }
                section.addView(input,LinearLayout.LayoutParams(-1,dp(48)).apply {
                    topMargin=dp(5)
                })
                choices.addView(section,LinearLayout.LayoutParams(-1,-2).apply {
                    topMargin=dp(10)
                })
                return input
            }
            val width=entry("Largura (px)",display.activeW)
            val height=entry("Altura (px)",display.activeH)
            val density=entry("Density / DPI",display.activeDpi)

            val feedback=txt("",12f)
            choices.addView(feedback,LinearLayout.LayoutParams(-1,-2).apply {
                topMargin=dp(10)
            })

            fun numbers():Triple<Int,Int,Int>? {
                val w=width.text.toString().trim().toIntOrNull() ?: return null
                val h=height.text.toString().trim().toIntOrNull() ?: return null
                val dpi=density.text.toString().trim().toIntOrNull() ?: return null
                return Triple(w,h,dpi)
            }
            fun valid(n:Triple<Int,Int,Int>):Boolean {
                val (w,h,dpi)=n
                return w in 320..4096 && h in 320..4096 &&
                    w.toLong()*h.toLong()<=9_000_000L && dpi in 120..1000
            }
            fun updatePreview() {
                val n=numbers()
                when {
                    n==null -> {
                        feedback.text="Preencha os três campos com números inteiros."
                        feedback.setTextColor(0xFFFFB68D.toInt())
                    }
                    !valid(n) -> {
                        feedback.text="Limites: largura/altura de 320 a 4096 px, "+
                            "até 9 milhões de pixels, DPI de 120 a 1000."
                        feedback.setTextColor(0xFFFFB68D.toInt())
                    }
                    else -> {
                        feedback.text="Sua escolha: "+n.first+" × "+n.second+
                            " px, "+n.third+" DPI"
                        feedback.setTextColor(0xFFC6A9FA.toInt())
                    }
                }
            }
            val watcher=object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                    updatePreview()
                }
                override fun afterTextChanged(s:android.text.Editable?) {}
            }
            listOf(width,height,density).forEach {it.addTextChangedListener(watcher)}
            updatePreview()

            choices.addView(txt("Os valores são livres dentro dos limites de segurança; "+
                "uma resolução diferente da proporção física pode deformar a imagem. "+
                "A tela poderá girar, reiniciar sua interface ou mudar a posição do HUD.",
                11f),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})

            if(display.pending) {
                choices.addView(btn("Confirmar e manter a configuração") {
                    status.text="Confirmando a configuração..."
                    client.confirm { code ->
                        if(code==0) {
                            toast("Resolução e DPI confirmados")
                            client.query { fresh,error ->
                                if(fresh!=null)render(fresh)
                                else status.text="Confirmado, mas não foi possível consultar: "+error
                            }
                        } else status.text="Não foi possível confirmar ("+code+
                            "). Aguarde a restauração."
                    }
                },LinearLayout.LayoutParams(-1,dp(48)).apply {topMargin=dp(12)})
            } else {
                choices.addView(btn("Testar minha resolução e DPI (45 s)") {
                    val n=numbers()
                    if(n==null || !valid(n)) {
                        status.text="Preencha valores válidos antes de aplicar."
                    } else {
                        // Close soft keyboard before wm reconfigures the screen.
                        val keyboard=getSystemService(Context.INPUT_METHOD_SERVICE)
                            as android.view.inputmethod.InputMethodManager
                        keyboard.hideSoftInputFromWindow(width.windowToken,0)
                        status.text="Aplicando "+n.first+" × "+n.second+
                            " e "+n.third+" DPI..."
                        client.apply(n.first,n.second,n.third) { code ->
                            if(code==0) {
                                DisplaySessionReset.markApplied(applicationContext)
                                toast("Teste ativo! Confirme antes de 45 segundos.")
                                client.query { fresh,error ->
                                    if(fresh!=null)render(fresh)
                                    else status.text="Teste aplicado; consulta indisponível: "+error
                                }
                            } else {
                                status.text="Falha ao aplicar ("+code+
                                    "). Verifique os valores e a ativação Shizuku."
                            }
                        }
                    }
                },LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(12)})
            }

            var resetArmed=false
            choices.addView(btn("Restaurar resolução e DPI originais") {
                if(!resetArmed) {
                    resetArmed=true
                    status.text="Toque novamente em RESTAURAR para remover "+
                        "as configurações personalizadas do display."
                } else {
                    status.text="Restaurando valores padrão..."
                    client.reset { code ->
                        if(code==0) {
                            DisplaySessionReset.clear(applicationContext)
                            toast("Tela restaurada")
                            client.query { fresh,error ->
                                if(fresh!=null)render(fresh)
                                else status.text="Comando enviado. Consulta: "+error
                            }
                        } else status.text="Restauração falhou: "+code
                    }
                }
            },LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)})

            choices.addView(btn("Copiar comandos de recuperação") {
                val clip=getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText(
                    "Recuperar resolução Android","wm size reset\nwm density reset"
                ))
                toast("Comandos copiados")
            },LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(8)})
            choices.addView(txt("Sem confirmar, o serviço tenta desfazer as "+
                "alterações após 45 segundos. Se o processo Shizuku for encerrado, "+
                "a recuperação automática não é garantida. Nesse caso, execute "+
                "wm size reset e wm density reset por ADB.",
                11f),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        }
        client.connect { ok ->
            if(!box.isAttachedToWindow)return@connect
            if(!ok)status.text="Shizuku inativo ou sem autorização. "+
                "Ative pela aba Ativação da Referência."
            else client.query { d,error ->
                if(!box.isAttachedToWindow)return@query
                if(d!=null)render(d)
                else status.text="Não foi possível consultar a tela: "+error
            }
        }
    }

    private fun openSettingsPage(which:Int) {
        val root=fullScreen(false,allowKeyboard=(which==4))
        val titles=arrayOf(
            "Configurações Básicas",
            "Monitor de Informações do Dispositivo",
            "Controle de Recuo",
            "Configurações da mira",
            "Ajuste de tamanho de tela"
        )
        val scroll=ScrollView(this)
        val box=col().apply {
            background=bg(0xF21B1228.toInt(),0xFFAD73F1.toInt())
            setPadding(dp(15),dp(13),dp(15),dp(16))
        }
        val head=row()
        head.addView(txt(titles[which],17f),LinearLayout.LayoutParams(0,-2,1f))
        head.addView(btn("✕"){closePanel()},LinearLayout.LayoutParams(dp(48),dp(42)))
        box.addView(head)
        when(which) {
            0 -> {
                box.addView(txt("Perfil do jogo • Teclado e botão flutuante",12f))
                box.addView(btn("Atalho de digitação: "+KeyEvent.keyCodeToString(profile.shortcutKey).removePrefix("KEYCODE_")) {
                    bindKey={ key -> profile.shortcutKey=key;save();openSettingsPage(0) }
                    toast("Pressione uma tecla do teclado físico")
                },LinearLayout.LayoutParams(-1,dp(42)).apply {topMargin=dp(12)})
                box.addView(txt("Posição do botão flutuante",14f),
                    LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(14)})
                val corners=row()
                listOf("↖","↗","↙","↘").forEachIndexed { index,label ->
                    corners.addView(btn(label){
                        store.saveCorner(index)
                        toast("Novo canto aplicado ao fechar o menu")
                    },LinearLayout.LayoutParams(0,dp(44),1f).apply {rightMargin=dp(4)})
                }
                box.addView(corners)
                box.addView(txt("Cursor (.cur)",14f),
                    LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(13)})
                box.addView(txt(cursors.filename(game) ?: "Selecione o arquivo .cur na tela inicial",11f))
                val cursor=cursors.load(game)
                if(cursor!=null) {
                    val image=ImageView(this).apply {
                        setImageBitmap(cursor.bitmap)
                        scaleType=ImageView.ScaleType.FIT_CENTER
                    }
                    box.addView(image,LinearLayout.LayoutParams(dp(56),dp(56)))
                }
                seek(box,"Tamanho da seta (.cur)",cursors.size(game),12,128){cursors.setSize(game,it)}
            }
            1 -> {
                toggle(box,"Mostrar monitor durante o jogo",profile.showDeviceMonitor) {
                    profile.showDeviceMonitor=it
                    ensureMonitor()
                }
                val memory=ActivityManager.MemoryInfo()
                try {
                    (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
                    box.addView(txt(String.format(java.util.Locale.getDefault(),
                        "Aparelho: %s  •  RAM disponível: %.2f GB / %.2f GB",
                        Build.MODEL,memory.availMem/1_073_741_824.0,memory.totalMem/1_073_741_824.0),12f))
                } catch (_:Exception) {
                    box.addView(txt("Memória indisponível",12f))
                }
                box.addView(txt("O monitor exibe modelo e memória livre. FPS por aplicativo não pode ser medido com precisão por essa permissão.",11f))
            }
            2 -> {
                toggle(box,"Preparar perfil de controle de recuo",profile.recoilControlEnabled) {
                    profile.recoilControlEnabled=it
                }
                seek(box,"Intensidade de compensação (%)",profile.recoilStrength,0,100) {
                    profile.recoilStrength=it
                }
                seek(box,"Suavização da compensação (%)",profile.recoilSmooth,0,100) {
                    profile.recoilSmooth=it
                }
                box.addView(txt("Esses valores são armazenados, mas NÃO reduzem o recuo atualmente: o motor de compensação não está implementado.",11f).apply {
                    setTextColor(0xFFFFC98B.toInt())
                },LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
            }
            3 -> {
                seek(box,"Sensibilidade Horizontal X",profile.sensitivityX,1,1000){
                    profile.sensitivityX=it
                }
                seek(box,"Sensibilidade Vertical Y",profile.sensitivityY,1,1000){
                    profile.sensitivityY=it
                }
                seek(box,"Aceleração",profile.aimAcceleration,0,100){
                    profile.aimAcceleration=it
                }
                seek(box,"Suavização",profile.aimSmoothing,0,100){
                    profile.aimSmoothing=it
                }
                box.addView(btn("Carregar preset estilo MSI 5.9"){
                    profile.sensitivityX=350;profile.sensitivityY=350
                    profile.aimAcceleration=0;profile.aimSmoothing=10
                    profile.msiStyle=true
                    save()
                    openSettingsPage(3)
                },LinearLayout.LayoutParams(-1,dp(43)).apply {topMargin=dp(10)})
                box.addView(txt("O preset organiza os parâmetros no estilo do MSI. Não altera a mira até existir captura relativa do mouse.",11f),
                    LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
            }
            4 -> {
                seek(box,"Tamanho dos botões do HUD (%)",profile.hudScale,50,160){
                    profile.hudScale=it
                }
                seek(box,"Tamanho do botão flutuante (dp)",profile.bubbleSize,40,82){
                    profile.bubbleSize=it
                }
                box.addView(btn("Restaurar tamanho padrão"){
                    profile.hudScale=100
                    profile.bubbleSize=52
                    save()
                    openSettingsPage(4)
                },LinearLayout.LayoutParams(-1,dp(43)).apply {topMargin=dp(9)})
                box.addView(txt("Os novos tamanhos entram em vigor ao reabrir o editor ou o botão flutuante.",11f))
                createDisplaySettings(box)
            }
        }
        box.addView(btn("‹ Voltar às Configurações"){save();openSettings()},
            LinearLayout.LayoutParams(-1,dp(43)).apply {topMargin=dp(15)})
        box.addView(btn("Salvar e fechar"){save();closePanel()},
            LinearLayout.LayoutParams(-1,dp(43)).apply {topMargin=dp(7)})
        scroll.addView(box)
        val sz=size()
        root.addView(scroll,FrameLayout.LayoutParams(
            min(sz.x-dp(16),dp(575)),min(sz.y-dp(22),dp(670)),Gravity.CENTER))
    }
}
