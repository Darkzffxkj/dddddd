package com.quiet.keymapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.app.ActivityManager
import android.os.Build
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
    private var editing = false
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
    private val cursors by lazy { CursorFileStore(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        alive=true
        handler.postDelayed(keepHud,2200L)
        try { registerReceiver(screenOffReceiver,IntentFilter(Intent.ACTION_SCREEN_OFF)) } catch (_:Exception) {}
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        // Recover the last launched game when Android restarts an enabled service.
        val previous = getSharedPreferences("referencia_hud_profiles_v7",Context.MODE_PRIVATE)
            .getString("active_game",null)
        if (!previous.isNullOrBlank()) {
            game = previous
            profile = store.load(game)
        }
    }
    fun prepareGame(packageName: String) {
        handler.post {
            if (game != packageName) {
                closeAll()
                game = packageName
                profile = store.load(game)
                typing = false
                suspended = false
                seenGameOnce = false
                transientWindow = false
                windowGeneration++
            }
            getSharedPreferences("referencia_hud_profiles_v7",Context.MODE_PRIVATE)
                .edit().putString("active_game",packageName).apply()
            if (foreground == game) { showBubble(); ensureMonitor() }
        }
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
            seenGameOnce=true
            transientWindow=false
            if(!editing && bubble==null) showBubble()
            ensureMonitor()
            return
        }
        if(type!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !seenGameOnce) return
        // The app's MainActivity is a definite game exit; accessibility overlays
        // may generate events from this package but never masquerade as MainActivity.
        if(incoming==packageName) {
            val cls=e.className?.toString().orEmpty()
            if(cls.contains("MainActivity")) leaveGame()
            return
        }
        val home=try {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME),0)?.activityInfo?.packageName
        } catch (_:Exception) { null }
        if(incoming==home) {
            scheduleExit(incoming,450L)
            return
        }
        val transient=incoming=="android" || incoming=="com.android.systemui" ||
            incoming=="com.google.android.gms" || incoming.contains("permissioncontroller") ||
            incoming.contains("inputmethod") || incoming.contains("keyboard") ||
            incoming.contains("gamebooster",true) ||
            incoming.startsWith("com.samsung.android.game.")
        if(transient) return
        // A genuine transition to another application is a game exit.
        // Debounce briefly so a transient login/loading activity does not hide the HUD.
        scheduleExit(incoming,1750L)
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
            if(game.isNotEmpty() && foreground==game) leaveGame()
        }
        pendingExit=pending
        handler.postDelayed(pending,delay)
    }
    private fun leaveGame() {
        cancelPendingExit()
        foreground=""
        seenGameOnce=false
        transientWindow=false
        cancelAllPresses()
        closeAll()
        // Keep the per-game profile saved; only the active UI session is closed.
        game=""
        getSharedPreferences("referencia_hud_profiles_v7",Context.MODE_PRIVATE)
            .edit().remove("active_game").apply()
    }
    override fun onInterrupt() {}
    override fun onDestroy() {
        alive=false
        cancelPendingExit()
        handler.removeCallbacks(keepHud)
        try { unregisterReceiver(screenOffReceiver) } catch (_:Exception) {}
        cancelAllPresses()
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
                toast(if(typing) "Modo digitação" else "Modo controle")
            }
            return true
        }
        val suspend=profile.controls.firstOrNull {
            it.kind=="SUSPEND" && it.keyCode==e.keyCode
        }
        if(suspend!=null) {
            if(e.action==KeyEvent.ACTION_DOWN && e.repeatCount==0) {
                suspended=!suspended
                if(suspended) cancelAllPresses()
                toast(if(suspended) "Controles suspensos" else "Controles retomados")
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
        try { wm.addView(v,p);true } catch (_:Exception) { false }
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
    private fun closePanel(){remove(panel);panel=null;editing=false;bindKey=null;showBubble()}
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
        if(bubble!=null || panel!=null || game.isEmpty() || foreground!=game)return
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
        if(add(v,params))bubble=v
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
            "Configurações" to {openSettings()},
            (if(typing)"Voltar ao controle" else "Digitar no teclado") to {
                typing=!typing;closeMenu()
                toast(if(typing)"Toque no chat para digitar" else "Controles retomados")
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
    private fun fullScreen(edit:Boolean):FrameLayout{
        closeMenu();remove(bubble);bubble=null;remove(panel);panel=null
        editing=edit
        val root=FrameLayout(this).apply{setBackgroundColor(0x90090514.toInt())}
        if(add(root,lp(-1,-1)))panel=root
        return root
    }
    private fun openEditor(){
        val root=fullScreen(true)
        val bar=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false}
        val line=row().apply{setPadding(dp(6),dp(6),dp(6),dp(6));background=bg(0xF51A1126.toInt())}
        fun tool(s:String,fn:()->Unit){
            line.addView(btn(s,fn),LinearLayout.LayoutParams(-2,dp(42)).apply{rightMargin=dp(5)})
        }
        tool("⊙ Tocar"){newControl("TOUCH")}
        tool("◉ Atirar"){newControl("FIRE")}
        tool("◎ Analógico"){newControl("JOYSTICK")}
        tool("⌖ Suspender + Sensi"){newControl("SUSPEND")}
        tool("Salvar"){save();closePanel();toast("Perfil do jogo salvo")}
        tool("Fechar"){closePanel()}
        bar.addView(line)
        root.addView(bar,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val hint=txt("Arraste para posicionar • toque para configurar • segure para remover",11f)
        root.addView(hint,FrameLayout.LayoutParams(-1,dp(35),Gravity.BOTTOM))
        profile.controls.toList().forEach{makeMarker(root,it)}
    }
    private fun newControl(kind:String){
        if(profile.controls.size>=90){toast("Limite de controles");return}
        val c=HudControl(kind,
            when(kind) { "SUSPEND"->KeyEvent.KEYCODE_F9;"FIRE"->KeyEvent.KEYCODE_F;else->KeyEvent.KEYCODE_SPACE },.5f,.49f)
        profile.controls.add(c);save();openEditor();openControlSettings(c)
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
            setOnClickListener { openControlSettings(c) }
        }
        v.addView(gear,FrameLayout.LayoutParams(dp(28),dp(28),Gravity.TOP or Gravity.RIGHT))
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
                        profile.controls.remove(c);save();openEditor()
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
    private fun openSettingsPage(which:Int) {
        val root=fullScreen(false)
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
                box.addView(txt("Os novos tamanhos entram em vigor ao reabrir o editor ou o botão flutuante. Não modifica a resolução do jogo.",11f))
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
