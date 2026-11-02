package com.quiet.keymapper

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.input.InputManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.content.res.Configuration
import android.Manifest
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.BaseAdapter
import android.widget.ImageView
import android.content.pm.PackageManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** V2: purple UI, games and devices, actual permission checks, Shizuku activation gate.
 * This build intentionally does NOT claim that an input mapper has been implemented.
 */
class MainActivity : Activity(), InputManager.InputDeviceListener {
    private val activation = ShizukuActivation()
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var devices: DeviceRepository
    private lateinit var games: GameRepository
    private lateinit var inputManager: InputManager
    private lateinit var deviceStrip: LinearLayout
    private lateinit var devicesSummary: TextView
    private lateinit var activationChip: TextView
    private lateinit var activationInfo: TextView
    private lateinit var playGate: LinearLayout
    private lateinit var gameCount: TextView
    private lateinit var playTab: TextView
    private lateinit var activationTab: TextView
    private lateinit var optimizerTab: TextView
    private lateinit var settingsTab: TextView
    private lateinit var settingsPage: ScrollView
    private lateinit var selectedGamesPanel: LinearLayout
    private lateinit var emptyGamesPanel: LinearLayout
    private lateinit var keyboardIndicator: TextView
    private lateinit var mouseIndicator: TextView
    private lateinit var optimizerPanel: OptimizerPanel
    private lateinit var body: FrameLayout
    private lateinit var playPage: ScrollView
    private lateinit var activationPage: ScrollView
    private lateinit var gameAdapter: GameAdapter
    private var allApps = emptyList<InstalledApp>()
    private var disposed = false
    private var launchingGame = false
    private var permissionsDialog: Dialog? = null
    private var permissionsRefresh: (() -> Unit)? = null
    private val cursorStore by lazy { CursorFileStore(this) }

    private val onShizukuArrived = Shizuku.OnBinderReceivedListener { runOnUiThread { updateActivation() } }
    private val onShizukuDead = Shizuku.OnBinderDeadListener { runOnUiThread { updateActivation() } }
    private val onShizukuPermission = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == SHIZUKU_PERMISSION_CODE) runOnUiThread { updateActivation() }
    }
    private val deviceRefresh = Runnable { if (!disposed) renderDevices() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        devices = DeviceRepository(this)
        games = GameRepository(this)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        buildUi()
        Shizuku.addBinderReceivedListenerSticky(onShizukuArrived)
        Shizuku.addBinderDeadListener(onShizukuDead)
        Shizuku.addRequestPermissionResultListener(onShizukuPermission)
        updateActivation()
        refreshGames()
    }

    override fun onStart() {
        super.onStart()
        inputManager.registerInputDeviceListener(this, handler)
        scheduleDeviceRefresh()
    }

    override fun onResume() {
        super.onResume()
        // This activity is genuinely visible again; remove the in-game overlay.
        // During the initial launch, the game has not been observed yet, so this
        // never cancels the just-armed session.
        HudAccessibilityService.current?.onMainAppForeground()
        DisplaySessionReset.restoreIfModified(applicationContext)
        if (::activationInfo.isInitialized) updateActivation()
        permissionsRefresh?.invoke()
    }

    override fun onStop() {
        // Opening a game also pauses this Activity; leave its display profile active.
        // Returning Home or minimizing Referência without launching a game resets it.
        if(!launchingGame) DisplaySessionReset.restoreIfModified(applicationContext)
        launchingGame=false
        inputManager.unregisterInputDeviceListener(this)
        handler.removeCallbacks(deviceRefresh)
        super.onStop()
    }

    override fun onDestroy() {
        disposed = true
        executor.shutdownNow()
        if (::optimizerPanel.isInitialized) optimizerPanel.close()
        handler.removeCallbacks(deviceRefresh)
        permissionsDialog?.dismiss()
        Shizuku.removeBinderReceivedListener(onShizukuArrived)
        Shizuku.removeBinderDeadListener(onShizukuDead)
        Shizuku.removeRequestPermissionResultListener(onShizukuPermission)
        super.onDestroy()
    }

    override fun onInputDeviceAdded(deviceId: Int) = scheduleDeviceRefresh()
    override fun onInputDeviceRemoved(deviceId: Int) = scheduleDeviceRefresh()
    override fun onInputDeviceChanged(deviceId: Int) = scheduleDeviceRefresh()
    private fun scheduleDeviceRefresh() {
        handler.removeCallbacks(deviceRefresh)
        handler.postDelayed(deviceRefresh, 180L)
    }


    private fun metallicSurface(raised:Boolean=true):android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            if(raised) intArrayOf(
                0xFF302342.toInt(),0xFF22172F.toInt(),
                0xFF15111F.toInt(),0xFF241735.toInt()
            ) else intArrayOf(
                0xFF1E172A.toInt(),0xFF12101B.toInt(),0xFF22152F.toInt()
            )
        ).apply {
            cornerRadius=dp(14).toFloat()
            setStroke(dp(1),if(raised)0xFF886DA9.toInt() else 0xFF554266.toInt())
        }

    private fun buildUi() {
        window.statusBarColor=0xFF0E0918.toInt()
        window.navigationBarColor=0xFF0C0814.toInt()
        val root=column().apply {
            background=android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF090810.toInt(),0xFF151024.toInt(),0xFF10091C.toInt(),
                    0xFF080710.toInt())
            )
        }
        setContentView(root)
        val header=row().apply {
            background=metallicSurface()
            setPadding(dp(12),dp(7),dp(12),dp(7))
        }
        val logo=ImageView(this).apply {
            setImageResource(R.drawable.brand_logo)
            scaleType=ImageView.ScaleType.FIT_CENTER
            contentDescription="Referência"
        }
        header.addView(logo,LinearLayout.LayoutParams(dp(44),dp(44)).apply{rightMargin=dp(9)})
        val brand=column()
        brand.addView(txt("REFERÊNCIA",19f,Palette.white,true))
        brand.addView(txt("M A P E A D O R   A N D R O I D",9f,Palette.violetSoft,true),
            LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(3)})
        activationChip=txt("VERIFICANDO",9f,Palette.warning,true)
        brand.addView(activationChip,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(3)})
        header.addView(brand,LinearLayout.LayoutParams(0,-2,1f))
        keyboardIndicator=txt("⌨  Teclado  •  ...",11f,Palette.white,true).apply {
            gravity=Gravity.CENTER
            background=rounded(0xFF1C172A.toInt(),dp(11).toFloat(),0xFF795B9C.toInt())
            setPadding(dp(11),dp(10),dp(11),dp(10))
        }
        mouseIndicator=txt("◉  Mouse  •  ...",11f,Palette.white,true).apply {
            gravity=Gravity.CENTER
            background=rounded(0xFF1C172A.toInt(),dp(11).toFloat(),0xFF795B9C.toInt())
            setPadding(dp(11),dp(10),dp(11),dp(10))
        }
        header.addView(keyboardIndicator,LinearLayout.LayoutParams(-2,-2).apply{rightMargin=dp(8)})
        header.addView(mouseIndicator)
        root.addView(header,LinearLayout.LayoutParams(-1,-2).apply {
            setMargins(dp(9),dp(7),dp(9),dp(5))
        })

        // Keep the existing real external-device diagnostics and activation state.
        deviceStrip=row()
        devicesSummary=txt("...",10f,Palette.muted)
        body=FrameLayout(this)
        root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        createPlayPage()
        createSettingsPage()
        createActivationPage()
        optimizerPanel=OptimizerPanel(this){showTab(1)}

        // Compact bottom nav; every tab switches to a real app function.
        val bar=row().apply {
            background=metallicSurface(false)
            setPadding(dp(5),dp(5),dp(5),dp(5))
        }
        playTab=tabButton("⌂  Início"){showTab(0)}
        activationTab=tabButton("✦  Ativação"){showTab(1)}
        optimizerTab=tabButton("⚡  Otimizar"){showTab(2)}
        settingsTab=tabButton("⚙  Configurações"){showTab(3)}
        listOf(playTab,activationTab,optimizerTab,settingsTab).forEachIndexed { i,v ->
            bar.addView(v,LinearLayout.LayoutParams(0,dp(42),1f).apply {
                if(i>0)leftMargin=dp(4)
            })
        }
        root.addView(bar,LinearLayout.LayoutParams(-1,-2).apply {
            setMargins(dp(8),dp(5),dp(8),dp(6))
        })
        showTab(0)
    }

    private fun tabButton(label:String,tap:()->Unit):TextView =
        txt(label,12f,Palette.white,true).apply {
            gravity=Gravity.CENTER
            isClickable=true
            isFocusable=true
            setOnClickListener { tap() }
        }

    private fun showTab(index:Int) {
        val tabs=listOf(playTab,activationTab,optimizerTab,settingsTab)
        tabs.forEachIndexed { i,tab ->
            tab.background=if(i==index)android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFAB78E6.toInt(),0xFF7041AF.toInt(),0xFF492872.toInt())
            ).apply {
                cornerRadius=dp(11).toFloat()
                setStroke(dp(1),0xFFE4C0FF.toInt())
            } else rounded(0xFF1C1626.toInt(),dp(11).toFloat())
            tab.setTextColor(if(i==index)Palette.white else Palette.muted)
        }
        body.removeAllViews()
        val page=when(index) {
            0->playPage
            1->activationPage
            2->optimizerPanel.view
            else->settingsPage
        }
        (page.parent as? ViewGroup)?.removeView(page)
        body.addView(page,FrameLayout.LayoutParams(-1,-1))
        if(index==1) updateActivation()
        if(index==2) optimizerPanel.onTabShown()
    }

    private fun createPlayPage() {
        playPage=ScrollView(this).apply {
            isFillViewport=true
            clipToPadding=false
            overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content=column().apply {
            gravity=Gravity.CENTER
            setPadding(dp(14),dp(6),dp(14),dp(8))
        }
        playPage.addView(content)
        playGate=row().apply {
            background=rounded(0xFF321C47.toInt(),dp(11).toFloat(),0xFF9366C1.toInt())
            setPadding(dp(10),dp(6),dp(10),dp(6))
        }
        playGate.addView(txt("Ativação necessária para iniciar jogos com o HUD",11f,Palette.white),
            LinearLayout.LayoutParams(0,-2,1f))
        playGate.addView(action("Ativar",true){showTab(1)},
            LinearLayout.LayoutParams(-2,dp(36)))
        content.addView(playGate,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(5)})

        val central=column().apply {
            gravity=Gravity.CENTER_HORIZONTAL
            background=metallicSurface(true)
            setPadding(dp(17),dp(13),dp(17),dp(14))
        }
        val width=(resources.displayMetrics.widthPixels*0.88f).toInt().coerceAtMost(dp(680))
        content.addView(central,LinearLayout.LayoutParams(width,-2).apply {topMargin=dp(5)})

        emptyGamesPanel=column().apply {
            gravity=Gravity.CENTER
        }
        val icon=txt("◇",39f,Palette.violetSoft,true).apply {
            gravity=Gravity.CENTER
            background=rounded(0xFF28143F.toInt(),dp(22).toFloat(),0xFFA17ACA.toInt())
        }
        emptyGamesPanel.addView(icon,LinearLayout.LayoutParams(dp(65),dp(53)).apply {
            gravity=Gravity.CENTER_HORIZONTAL
        })
        emptyGamesPanel.addView(txt("Nenhum jogo adicionado",20f,Palette.white,true).apply {
            gravity=Gravity.CENTER
        },LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
        emptyGamesPanel.addView(txt(
            "Escolha os aplicativos que deseja usar. Nenhum jogo é adicionado automaticamente.",
            11f,Palette.muted
        ).apply { gravity=Gravity.CENTER },
            LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(5)})
        emptyGamesPanel.addView(action("＋   Adicionar jogo",true){openManualPicker()},
            LinearLayout.LayoutParams(-1,dp(47)).apply {
                topMargin=dp(13)
                leftMargin=dp(35)
                rightMargin=dp(35)
            })
        central.addView(emptyGamesPanel)

        selectedGamesPanel=column().apply {gravity=Gravity.CENTER_HORIZONTAL}
        val top=row()
        top.addView(txt("Meus jogos",18f,Palette.white,true),LinearLayout.LayoutParams(0,-2,1f))
        top.addView(action("＋  Adicionar jogo",true){openManualPicker()},
            LinearLayout.LayoutParams(-2,dp(40)))
        selectedGamesPanel.addView(top)
        gameCount=txt("Nenhum jogo adicionado",11f,Palette.muted)
        selectedGamesPanel.addView(gameCount,
            LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(5)})
        gameAdapter=GameAdapter(this,{onGameSelected(it)},{openManualPicker()}) { app ->
            AlertDialog.Builder(this)
                .setTitle("Remover da biblioteca?")
                .setMessage("Remover "+app.label+" da Referência? O aplicativo não será desinstalado.")
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Remover"){_,_->
                    games.removeManually(app.packageName)
                    refreshGames()
                }.show()
        }
        val recycler=RecyclerView(this).apply {
            layoutManager=GridLayoutManager(this@MainActivity,5)
            adapter=gameAdapter
            isNestedScrollingEnabled=false
            overScrollMode=View.OVER_SCROLL_NEVER
            itemAnimator=null
        }
        selectedGamesPanel.addView(recycler,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        central.addView(selectedGamesPanel)
        selectedGamesPanel.visibility=View.GONE
    }

    private fun createSettingsPage() {
        settingsPage=ScrollView(this).apply {clipToPadding=false}
        val content=column().apply {
            setPadding(dp(18),dp(8),dp(18),dp(22))
        }
        settingsPage.addView(content)
        content.addView(txt("Configurações",18f,Palette.white,true),
            LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)})
        settingsRow(content,"Adicionar aplicativo","Escolha qualquer aplicativo instalado") {
            openManualPicker()
        }
        settingsRow(content,"Atualizar biblioteca","Recarregar aplicativos instalados") {
            refreshGames()
        }
        settingsRow(content,"Mapeamentos","Configurar HUD pelo botão flutuante no jogo") {
            info("Editor de HUD","Abra um jogo pela aba Início e selecione Editar HUD no botão flutuante. O motor de mouse relativo e analógico contínuo ainda não está completo.")
        }
        settingsRow(content,"Cursor do mouse","Selecionar arquivo .cur por jogo") {
            openCursorPicker()
        }
        settingsRow(content,"Dispositivos","Verificar mouse e teclado externos") {
            renderDevices()
            toast(devicesSummary.text.toString())
        }
        settingsRow(content,"Ativação","Shizuku e permissões do dispositivo") {showTab(1)}
        settingsRow(content,"Otimizar","Gerenciamento e hibernação de aplicativos"){showTab(2)}
        settingsRow(content,"Restaurar resolução e DPI","Voltar aos valores originais da tela") {
            DisplayRecoveryUi.show(this)
        }
        settingsRow(content,"Diagnóstico do HUD","Ver por que o botão aparece ou desaparece") {
            showHudDiagnostics()
        }
    }

    private fun settingsRow(container: LinearLayout, name: String, description: String, click: () -> Unit) {
        val line = row().apply { setPadding(0, dp(14), dp(2), dp(14)) }
        val labels = column()
        labels.addView(txt(name, 14f, Palette.white, true))
        labels.addView(txt(description, 12f, Palette.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(txt("›", 26f, Palette.violetSoft))
        line.isClickable = true
        line.isFocusable = true
        line.setOnClickListener { click() }
        container.addView(line)
        container.addView(View(this).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun createActivationPage() {
        activationPage = ScrollView(this).apply { clipToPadding = false }
        val content = column().apply { setPadding(dp(16), dp(4), dp(16), dp(28)) }
        activationPage.addView(content)
        val stateCard = column().apply {
            background = rounded(Palette.panel, dp(14).toFloat())
            setPadding(dp(15), dp(16), dp(15), dp(16))
        }
        stateCard.addView(txt("Estado da ativação", 18f, Palette.white, true))
        activationInfo = txt("Verificando Shizuku...", 13f, Palette.muted).apply {
            setPadding(0, dp(9), 0, dp(13))
        }
        stateCard.addView(activationInfo)
        val buttons = row()
        buttons.addView(action("Verificar", false) { updateActivation() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        buttons.addView(action("Autorizar", true) { requestShizukuPermission() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
        stateCard.addView(buttons)
        content.addView(stateCard, stateCard.withMargin(bottom = 20))
        content.addView(txt("MÉTODOS DE ATIVAÇÃO", 11f, Palette.violetSoft, true),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        if (Build.VERSION.SDK_INT >= 30) {
            content.addView(activationCard("Depuração Wi-Fi + Shizuku",
                "Android 11+. Inicie o Shizuku após parear pela depuração sem fio e autorize o Referência.",
                listOf("Buscar pareamento Wi-Fi" to { startPairingScan() },
                    "Opções de desenvolvedor" to { openDeveloperOptions() },
                    "Abrir Shizuku" to { openShizuku() })), cardParams())
        }
        content.addView(activationCard("Computador (USB / ADB)",
            "Android 10+. Ative a depuração USB, conecte ao computador, autorize a chave RSA e inicie o Shizuku.",
            listOf("Copiar comando ADB" to { copyAdbCommand() }, "Abrir Shizuku" to { openShizuku() })), cardParams())
        content.addView(activationCard("Outro celular (ADB host)",
            "Use outro Android com suporte a OTG/ADB host para iniciar o Shizuku no aparelho de jogo.",
            listOf("Copiar comando ADB" to { copyAdbCommand() })), cardParams())
        content.addView(txt("A autorização permite utilizar APIs compatíveis, mas não significa que o motor de toques esteja implementado.",
            12f, Palette.muted).apply { setPadding(dp(4), dp(5), dp(4), dp(5)) })
    }

    private fun activationCard(title: String, desc: String, buttons: List<Pair<String, () -> Unit>>): LinearLayout {
        val card = column().apply {
            background = rounded(Palette.panel, dp(13).toFloat())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        card.addView(txt(title, 15f, Palette.white, true))
        card.addView(txt(desc, 12f, Palette.muted).apply { setLineSpacing(dp(2).toFloat(), 1f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7); bottomMargin = dp(12) })
        buttons.forEachIndexed { i, pair ->
            card.addView(action(pair.first, i == 0, pair.second),
                LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(7) })
        }
        return card
    }
    private fun cardParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(11) }

    private fun renderDevices() {
        deviceStrip.removeAllViews()
        val found = try { devices.snapshot() } catch (_: Exception) { emptyList() }
        devicesSummary.text = if (found.isEmpty()) "Nenhum detectado" else "${found.size} dispositivo(s)"
        val keyboard=found.any { it.kind.contains("Teclado",true) }
        val mouse=found.any { it.kind.contains("Mouse",true) }
        keyboardIndicator.text="⌨  "+(if(keyboard)"Conectado" else "Teclado desconectado")
        mouseIndicator.text="◉  "+(if(mouse)"Conectado" else "Mouse desconectado")
        keyboardIndicator.setTextColor(if(keyboard)Palette.success else Palette.white)
        mouseIndicator.setTextColor(if(mouse)Palette.success else Palette.white)
        if (found.isEmpty()) {
            deviceStrip.addView(txt("Nenhum mouse ou teclado externo conectado", 12f, Palette.muted))
            return
        }
        found.forEach { device ->
            val chip = column().apply {
                background = rounded(Palette.panelAlt, dp(9).toFloat())
                setPadding(dp(9), dp(7), dp(9), dp(7))
            }
            chip.addView(txt(device.kind, 10f, Palette.violetSoft, true))
            chip.addView(txt(device.name, 12f, Palette.white).apply { maxLines = 2; maxWidth = dp(190) })
            deviceStrip.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7) })
        }
    }

    private fun refreshGames() {
        if (disposed) return
        executor.execute {
            val found = try { games.allLaunchable() } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (disposed) return@runOnUiThread
                allApps = found
                val list = games.detectedGames(found)
                gameAdapter.submit(list)
                emptyGamesPanel.visibility=if(list.isEmpty())View.VISIBLE else View.GONE
                selectedGamesPanel.visibility=if(list.isEmpty())View.GONE else View.VISIBLE
                gameCount.text=when(list.size) {
                    0->"Nenhum jogo adicionado"
                    1->"1 aplicativo na biblioteca"
                    else->"${list.size} aplicativos na biblioteca"
                }
            }
        }
    }

    /** Mostra o ícone original do APK ao adicionar jogos (sem clones). */
    private fun openManualPicker() {
        val selected=games.selectedPackages()
        val candidates=allApps.filterNot { it.packageName in selected }
        if (candidates.isEmpty()) { toast("Todos os aplicativos disponíveis já foram adicionados"); return }
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = candidates.size
            override fun getItem(position: Int): Any = candidates[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val app = candidates[position]
                val itemRow = (convertView as? LinearLayout) ?: row().apply {
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                    minimumHeight = dp(62)
                }
                itemRow.removeAllViews()
                val icon = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(try {
                        packageManager.getApplicationIcon(app.packageName)
                    } catch (_: Exception) { packageManager.defaultActivityIcon })
                }
                itemRow.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(13) })
                val labels = column()
                labels.addView(txt(app.label, 14f, Palette.white, true))
                labels.addView(txt(app.packageName, 10f, Palette.muted).apply { maxLines = 1 },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                itemRow.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                return itemRow
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Adicionar jogo")
            .setAdapter(adapter) { _, which ->
                games.addManually(candidates[which].packageName)
                refreshGames()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun onGameSelected(app: InstalledApp) {
        // Não mostrar o mesmo diálogo quando Shizuku e overlay já estão liberados.
        if (activation.state() == ShizukuActivation.State.AUTHORIZED && Settings.canDrawOverlays(this)) {
            launchSelectedGame(app)
        } else {
            showPermissionDialog(app)
        }
    }

    /** Required screen on each game tap. Real OS checks; no fabricated green check marks. */
    private fun showPermissionDialog(app: InstalledApp) {
        permissionsDialog?.dismiss()
        val dialog = Dialog(this)
        permissionsDialog = dialog
        val root = column().apply {
            background = rounded(Palette.panel, dp(18).toFloat(), Palette.primaryDark)
            setPadding(dp(19), dp(20), dp(19), dp(18))
        }
        root.addView(txt("Permissões do jogo", 18f, Palette.white, true))
        root.addView(txt("Para abrir ${app.label}, confira as permissões abaixo.", 13f, Palette.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7); bottomMargin = dp(15) })

        var skippedOverlay = false
        var skippedBattery = false
        val overlayRow = permissionRow(root, "Widgets flutuantes", "Exibir Referência sobre o jogo", "Permitir", "Pular",
            { openOverlaySettings() }, { skippedOverlay = true; permissionsRefresh?.invoke() })
        val batteryRow = permissionRow(root, "Desativar a otimização da bateria", "Ajuda a manter o serviço em execução", "Permitir", "Pular",
            { openBatterySettings() }, { skippedBattery = true; permissionsRefresh?.invoke() })
        val activationRow = permissionRow(root, "Ativação", "Autorizar acesso ao Shizuku", "Ativar", null,
            { dialog.dismiss(); showTab(1) }, null)
        val note = txt("", 12f, Palette.muted)
        root.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(11); bottomMargin = dp(11) })
        val controls = row()
        controls.addView(action("Cancelar", false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(44), 1f))
        controls.addView(action("Continuar", true) {
            if (activation.state() != ShizukuActivation.State.AUTHORIZED) {
                dialog.dismiss()
                toast("Precisamos de ativação")
                showTab(1)
            } else {
                dialog.dismiss()
                launchSelectedGame(app)
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(9) })
        root.addView(controls)

        permissionsRefresh = {
            val overlay = Settings.canDrawOverlays(this)
            val battery = isBatteryUnrestricted()
            val active = activation.state() == ShizukuActivation.State.AUTHORIZED
            updatePermissionRow(overlayRow, overlay, skippedOverlay)
            updatePermissionRow(batteryRow, battery, skippedBattery)
            updatePermissionRow(activationRow, active, false)
            note.text = when {
                !active -> "A ativação é obrigatória para abrir jogos através do painel."
                !overlay -> "Sem widgets flutuantes, o editor sobre o jogo não estará disponível."
                !battery -> "A otimização da bateria é opcional e pode afetar processos em segundo plano."
                else -> "Permissões verificadas."
            }
        }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            if (permissionsDialog === dialog) {
                permissionsDialog = null
                permissionsRefresh = null
            }
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .94f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        permissionsRefresh?.invoke()
    }

    private data class PermissionViews(val check: TextView, val action: TextView, val skip: TextView?)

    private fun permissionRow(
        container: LinearLayout, title: String, subtitle: String,
        button: String, skipText: String?, allow: () -> Unit, skip: (() -> Unit)?
    ): PermissionViews {
        val row = row().apply { setPadding(0, dp(11), 0, dp(11)) }
        val info = column()
        info.addView(txt(title, 14f, Palette.white, true))
        info.addView(txt(subtitle, 12f, Palette.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        val buttons = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val permissionButton = action(button, false, allow)
        buttons.addView(permissionButton, LinearLayout.LayoutParams(dp(91), dp(40)))
        val skipButton = skipText?.let {
            txt(it, 12f, Palette.muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(7), 0, dp(4))
                isClickable = true
                setOnClickListener { skip?.invoke() }
            }.also { buttons.addView(it) }
        }
        val check = txt("✓", 24f, Palette.success, true).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        buttons.addView(check, LinearLayout.LayoutParams(dp(91), dp(40)))
        row.addView(buttons)
        container.addView(row)
        container.addView(View(this).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(-1, dp(1)))
        return PermissionViews(check, permissionButton, skipButton)
    }

    private fun updatePermissionRow(row: PermissionViews, granted: Boolean, skipped: Boolean) {
        row.check.visibility = if (granted) View.VISIBLE else View.GONE
        row.action.visibility = if (granted) View.GONE else View.VISIBLE
        row.skip?.let {
            it.visibility = if (granted) View.GONE else View.VISIBLE
            it.text = if (skipped) "Ignorado" else "Pular"
        }
    }

    private fun isBatteryUnrestricted(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        // Android 11+ ignores package: URI; open the list, Android 10 can jump to this app.
        if (Build.VERSION.SDK_INT < 30) intent.data = Uri.parse("package:$packageName")
        openSystemScreen(intent)
    }

    private fun openBatterySettings() = openSystemScreen(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    private fun openSystemScreen(intent: Intent) {
        try { startActivity(intent) }
        catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"))) }
            catch (_: Exception) { toast("Não foi possível abrir as configurações") }
        }
    }

    /** A tela de permissões é única; não exibir segunda confirmação ao abrir o jogo. */
    private fun launchSelectedGame(app: InstalledApp) {
        if (activation.state() != ShizukuActivation.State.AUTHORIZED) {
            toast("Precisamos de ativação")
            showTab(1)
            return
        }
        val launch = games.launchIntent(app.packageName)
        if (launch == null) {
            toast("Não foi possível localizar o jogo")
            return
        }
        val mapping = HudAccessibilityService.current
        if (mapping == null) {
            AlertDialog.Builder(this)
                .setTitle("Ativar editor e mapeamento")
                .setMessage("Para mostrar o botão flutuante e executar os toques configurados, ative o serviço de acessibilidade Referência. Isso não modifica nem clona o jogo.")
                .setNegativeButton("Abrir sem HUD") { _, _ ->
                    try {
                        launchingGame=true
                        startActivity(launch)
                    } catch (_: Exception) {
                        launchingGame=false
                        toast("Falha ao abrir o jogo")
                    }
                }
                .setPositiveButton("Configurar acessibilidade") { _, _ ->
                    openSystemScreen(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }.show()
            return
        }
        mapping.prepareGame(app.packageName)
        try {
            launchingGame=true
            startActivity(launch)
        } catch (_: Exception) {
            launchingGame=false
            toast("Falha ao abrir o jogo")
        }
    }

    private fun updateActivation() {
        if (!::activationInfo.isInitialized || disposed) return
        val state = activation.state()
        val authorized = state == ShizukuActivation.State.AUTHORIZED
        activationChip.text = if (authorized) "● Ativado" else "● Não ativado"
        activationChip.setTextColor(if (authorized) Palette.success else Palette.warning)
        playGate.visibility = if (authorized) View.GONE else View.VISIBLE
        activationInfo.text = when (state) {
            ShizukuActivation.State.AUTHORIZED -> "Shizuku autorizado. Para usar o botão flutuante, ative também Referência em Acessibilidade."
            ShizukuActivation.State.NOT_RUNNING -> "Shizuku não está em execução. Inicie-o por USB/ADB ou, no Android 11+, pela depuração Wi-Fi."
            ShizukuActivation.State.PERMISSION_REQUIRED -> "Shizuku está ativo. Toque em Autorizar para conceder acesso ao Referência."
            ShizukuActivation.State.DENIED -> "Permissão negada. Revise a autorização do aplicativo no Shizuku."
            ShizukuActivation.State.ERROR -> "Falha na comunicação com Shizuku. Confira a instalação e a versão."
        }
        permissionsRefresh?.invoke()
    }

    private fun requestShizukuPermission() {
        when (activation.state()) {
            ShizukuActivation.State.PERMISSION_REQUIRED -> try { activation.requestPermission(SHIZUKU_PERMISSION_CODE) }
                catch (_: Exception) { toast("Falha ao solicitar autorização") }
            ShizukuActivation.State.AUTHORIZED -> toast("Shizuku já está autorizado")
            else -> openShizuku()
        }
    }

    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launch != null) startActivity(launch)
        else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
    }

    private fun startPairingScan() {
        if (Build.VERSION.SDK_INT < 30) {
            toast("Pareamento Wi-Fi disponível no Android 11 ou superior")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2202)
            return
        }
        val intent = Intent(this, PairingService::class.java).setAction(PairingService.ACTION_START)
        try {
            startForegroundService(intent)
            openDeveloperOptions()
        } catch (_: Exception) {
            toast("Não foi possível iniciar a descoberta Wi-Fi")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2202) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startPairingScan()
            else info("Notificações necessárias",
                "Permita notificações para digitar o código de pareamento sem fechar as configurações.")
        }
    }

    private fun openDeveloperOptions() = openSystemScreen(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

    private fun copyAdbCommand() {
        val command = "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Shizuku ADB", command))
        toast("Comando ADB copiado")
    }

    /** Open Android's SAF document browser. Do not request broad filesystem privileges. */
    private fun openCursorPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivityForResult(intent, PICK_CURSOR_FILE) }
        catch (_: Exception) { toast("Não foi possível abrir os arquivos do Android") }
    }

    @Deprecated("Using Activity result for API 29 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode != PICK_CURSOR_FILE || resultCode != Activity.RESULT_OK) return
        val uri=data?.data ?: return
        val display=try {
            contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                if(it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null } ?: "cursor.cur"
        if(!display.lowercase().endsWith(".cur")){
            toast("Escolha um arquivo .cur do Windows")
            return
        }
        val available=if(allApps.isEmpty())games.allLaunchable()else allApps
        val targets=available.filter { it.isGame }.ifEmpty { available }
        if(targets.isEmpty()){
            toast("Adicione um jogo na aba Jogar antes de importar a seta")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Aplicar cursor a qual jogo?")
            .setItems(targets.map { it.label }.toTypedArray()) { _,index ->
                val selected=targets[index]
                if(cursorStore.import(selected.packageName,uri,display))
                    info("Cursor importado",display+" salvo para "+selected.label+
                        ". Em Configurações durante o jogo, ajuste o tamanho da seta.")
                else
                    info("Formato não suportado",
                        "O arquivo precisa ser um .cur válido, com imagem PNG ou BMP 32-bit.")
            }
            .setNegativeButton("Cancelar",null).show()
    }

    private fun showAppMenu() {
        AlertDialog.Builder(this).setTitle("Referência")
.setItems(arrayOf("Jogar", "Ativação", "Otimizar", "Atualizar jogos", "Sobre")) { _, option ->
                when (option) {
                    0 -> showTab(0)
                    1 -> showTab(1)
                    2 -> showTab(2)
                    3 -> refreshGames()
                    else -> info("Referência V12", "Correção de sessão do botão flutuante, diagnóstico do HUD e edição por jogo. Analógico contínuo e mouse-look ainda não estão implementados.")
                }
            }.show()
    }

    private fun showHudDiagnostics() {
        val preferences=getSharedPreferences("referencia_overlay_diagnostics",Context.MODE_PRIVATE)
        val events=preferences.getString("events","").orEmpty()
        val serviceActive=HudAccessibilityService.current!=null
        val message="Serviço de acessibilidade: "+(if(serviceActive)"Conectado" else "Desconectado")+
            "\n\nÚltimos eventos:\n"+events.ifBlank { "Ainda não há eventos gravados." }
        AlertDialog.Builder(this)
            .setTitle("Diagnóstico HUD")
            .setMessage(message)
            .setPositiveButton("Copiar diagnóstico") { _,_ ->
                val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico Referência",message))
                toast("Diagnóstico copiado")
            }
            .setNeutralButton("Limpar registro") { _,_ ->
                preferences.edit().remove("events").apply()
                toast("Registro apagado")
            }
            .setNegativeButton("Fechar",null)
            .show()
    }
    private fun info(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object { private const val SHIZUKU_PERMISSION_CODE = 1001
        private const val PICK_CURSOR_FILE = 7042 }
}
