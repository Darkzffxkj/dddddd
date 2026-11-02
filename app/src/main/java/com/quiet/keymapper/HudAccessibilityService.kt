package com.quiet.keymapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
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
            if (foreground == game) showBubble()
        }
    }
    override fun onAccessibilityEvent(e:AccessibilityEvent?) {
        val t=e?.eventType ?: return
        if(t!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            t!=AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        val incoming=e.packageName?.toString().orEmpty()
        if(incoming.isBlank() || incoming==packageName || game.isBlank()) return
        if(incoming==game) {
            foreground=game
            seenGameOnce=true
            windowGeneration++
            transientWindow=false
            if(!editing && bubble==null) showBubble()
            return
        }
        // The game's loading screen, Android keyboard, permission sheets and game
        // booster may use other packages. They do not justify removing the HUD.
        // Only a stable transition to the HOME launcher hides it.
        if(!seenGameOnce || t!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val home=try {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME),0)?.activityInfo?.packageName
        } catch (_:Exception) { null }
        if(incoming==home) {
            val generation=++windowGeneration
            handler.postDelayed({
                if(windowGeneration==generation && foreground==game) {
                    foreground=""
                    cancelAllPresses()
                    closeAll()
                }
            },15000L)
        }
    }
    override fun onInterrupt() {}
    override fun onDestroy() {
        alive=false
        handler.removeCallbacks(keepHud)
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
    private fun closeMenu(){remove(menu);menu=null}
    private fun closePanel(){remove(panel);panel=null;editing=false;bindKey=null;showBubble()}
    private fun closeAll(){
        remove(menu);menu=null;remove(panel);panel=null;remove(bubble);bubble=null
        editing=false;bindKey=null;cancelAllPresses()
    }
    private fun save(){ if(game.isNotEmpty()) store.save(game,profile) }
    private fun cornerPosition(p:WindowManager.LayoutParams,c:Int) {
        val sz=size()
        p.x=if(c%2==0) dp(10) else sz.x-dp(62)
        p.y=if(c<2) dp(60) else sz.y-dp(108)
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
        val params=lp(dp(52),dp(52))
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
        val side=dp(if(c.kind=="JOYSTICK") 108 else 74)
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
    private fun openSettings(){
        val root=fullScreen(false)
        val scroll=ScrollView(this)
        val box=col().apply {
            background=bg(0xFA1D1429.toInt(),0xFFB57AFF.toInt())
            setPadding(dp(16),dp(14),dp(16),dp(14))
        }
        box.addView(txt("Configurações · Referência",19f))
        box.addView(txt("Mira e movimento",15f),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
        seek(box,"Sensibilidade horizontal (X)",profile.sensitivityX,1,1000){
            profile.sensitivityX=it
        }
        seek(box,"Sensibilidade vertical (Y)",profile.sensitivityY,1,1000){
            profile.sensitivityY=it
        }
        seek(box,"Aceleração (%)",profile.aimAcceleration,0,100){profile.aimAcceleration=it}
        seek(box,"Suavização (%)",profile.aimSmoothing,0,100){profile.aimSmoothing=it}
        box.addView(btn("Preset estilo MSI 5.9"){
            profile.sensitivityX=350;profile.sensitivityY=350
            profile.aimAcceleration=0;profile.aimSmoothing=10
            profile.msiStyle=true;save();openSettings()
        },LinearLayout.LayoutParams(-1,dp(42)).apply{topMargin=dp(7)})
        box.addView(txt("Ajustes salvos para uso futuro pelo motor de mira; mouse relativo ainda não está implementado.",11f))
        box.addView(txt("Cursor personalizado (.cur)",15f),LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(16)})
        val filename=cursors.filename(game)
        box.addView(txt(if(filename.isNullOrBlank()) "Nenhum arquivo selecionado. Use Cursor do mouse na tela inicial."
            else "Arquivo selecionado: "+filename,11f))
        val decoded=cursors.load(game)
        if(decoded!=null) {
            val preview=ImageView(this).apply {
                setImageBitmap(decoded.bitmap)
                scaleType=ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0x442E1A42)
            }
            val initial=dp(profile.cursorSize.coerceIn(12,128))
            box.addView(preview,LinearLayout.LayoutParams(initial,initial).apply {topMargin=dp(7)})
            seek(box,"Tamanho da seta",cursors.size(game),12,128) {
                cursors.setSize(game,it)
                val n=dp(it)
                preview.layoutParams=LinearLayout.LayoutParams(n,n).apply {topMargin=dp(7)}
            }
        } else {
            seek(box,"Tamanho da seta",cursors.size(game),12,128){
                cursors.setSize(game,it)
            }
        }
        box.addView(txt("A prévia do .cur e o tamanho são salvos por jogo. A substituição do ponteiro nativo durante a partida depende do futuro motor de captura de mouse.",11f),
            LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(9)})
        box.addView(btn("Atalho de digitação: "+KeyEvent.keyCodeToString(profile.shortcutKey).removePrefix("KEYCODE_")){
            bindKey={key->profile.shortcutKey=key;save();openSettings()}
            toast("Pressione uma tecla física")
        },LinearLayout.LayoutParams(-1,dp(43)).apply {topMargin=dp(9)})
        box.addView(btn("Salvar e fechar"){save();closePanel()},
            LinearLayout.LayoutParams(-1,dp(45)).apply {topMargin=dp(9)})
        scroll.addView(box)
        val sz=size()
        root.addView(scroll,FrameLayout.LayoutParams(min(sz.x-dp(18),dp(490)),
            min(sz.y-dp(28),dp(620)),Gravity.CENTER))
    }
}
