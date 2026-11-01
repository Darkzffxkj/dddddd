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
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
    private lateinit var audioManager: AudioManager
    private lateinit var deviceStrip: LinearLayout
    private lateinit var devicesSummary: TextView
    private lateinit var activationChip: TextView
    private lateinit var activationInfo: TextView
    private lateinit var playGate: LinearLayout
    private lateinit var gameCount: TextView
    private lateinit var playTab: TextView
    private lateinit var activationTab: TextView
    private lateinit var optimizeTab: TextView
    private lateinit var optimizerPanel: OptimizerPanel
    private lateinit var body: FrameLayout
    private lateinit var playPage: ScrollView
    private lateinit var activationPage: ScrollView
    private lateinit var gameAdapter: GameAdapter
    private var allApps = emptyList<InstalledApp>()
    private var disposed = false
    private var permissionsDialog: Dialog? = null
    private var permissionsRefresh: (() -> Unit)? = null

    private val onShizukuArrived = Shizuku.OnBinderReceivedListener { runOnUiThread { updateActivation() } }
    private val onShizukuDead = Shizuku.OnBinderDeadListener { runOnUiThread { updateActivation() } }
    private val onShizukuPermission = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == SHIZUKU_PERMISSION_CODE) runOnUiThread { updateActivation() }
    }
    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = scheduleDeviceRefresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = scheduleDeviceRefresh()
    }
    private val deviceRefresh = Runnable { if (!disposed) renderDevices() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        devices = DeviceRepository(this)
        games = GameRepository(this)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
        audioManager.registerAudioDeviceCallback(audioCallback, handler)
        scheduleDeviceRefresh()
    }

    override fun onResume() {
        super.onResume()
        if (::activationInfo.isInitialized) updateActivation()
        permissionsRefresh?.invoke()
    }

    override fun onStop() {
        inputManager.unregisterInputDeviceListener(this)
        audioManager.unregisterAudioDeviceCallback(audioCallback)
        handler.removeCallbacks(deviceRefresh)
        super.onStop()
    }

    override fun onDestroy() {
        disposed = true
        executor.shutdownNow()
        handler.removeCallbacks(deviceRefresh)
        permissionsDialog?.dismiss()
        if (::optimizerPanel.isInitialized) optimizerPanel.dispose()
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

    private fun buildUi() {
        window.statusBarColor = Palette.primaryDark
        window.navigationBarColor = Palette.bg
        val root = column().apply { setBackgroundColor(Palette.bg) }
        setContentView(root)

        val header = column().apply {
            background = gradientHeader(this@MainActivity)
            setPadding(dp(17), dp(16), dp(17), dp(14))
        }
        val top = row()
        val title = column().apply {
            addView(txt("✦  KeyMapper Pro", 20f, Palette.white, true))
            addView(txt("MAPEADOR ANDROID 10+", 10f, 0xFFE3C9FF.toInt(), true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(action("☰", false) { showAppMenu() },
            LinearLayout.LayoutParams(dp(45), dp(43)))
        header.addView(top, top.withMargin(bottom = 14))

        val statusCard = column().apply {
            background = rounded(0xFF17121F.toInt(), dp(13).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(11))
        }
        val deviceRow = row()
        deviceRow.addView(txt("◈  Periféricos conectados", 13f, Palette.white, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        devicesSummary = txt("Procurando...", 11f, Palette.violetSoft, true)
        deviceRow.addView(devicesSummary)
        statusCard.addView(deviceRow)
        val deviceScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(8), 0, 0)
        }
        deviceStrip = row()
        deviceScroll.addView(deviceStrip)
        statusCard.addView(deviceScroll)
        statusCard.addView(View(this).apply { setBackgroundColor(Palette.line) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(9) })
        val statusRow = row()
        statusRow.addView(txt("Ativação do aplicativo", 13f, Palette.white),
            LinearLayout.LayoutParams(0, -2, 1f))
        activationChip = txt("VERIFICANDO", 11f, Palette.warning, true)
        statusRow.addView(activationChip)
        statusRow.setOnClickListener { showTab(1) }
        statusCard.addView(statusRow)
        header.addView(statusCard)
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })

        val tabs = row().apply {
            background = rounded(Palette.panel, dp(13).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        playTab = tabButton("Jogar") { showTab(0) }
        activationTab = tabButton("Ativação") { showTab(1) }
        optimizeTab = tabButton("Otimizar") { showTab(2) }
        tabs.addView(playTab, LinearLayout.LayoutParams(0, dp(43), 1f))
        tabs.addView(activationTab, LinearLayout.LayoutParams(0, dp(43), 1f))
        tabs.addView(optimizeTab, LinearLayout.LayoutParams(0, dp(43), 1f))
        root.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(16), 0, dp(16), dp(10))
        })
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        createPlayPage()
        createActivationPage()
        optimizerPanel = OptimizerPanel(this, activation) { showTab(1) }
        showTab(0)
    }

    private fun tabButton(label: String, tap: () -> Unit): TextView = txt(label, 14f, Palette.white, true).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { tap() }
    }

    private fun showTab(index: Int) {
        playTab.background = rounded(if (index == 0) Palette.primary else Palette.panel, dp(10).toFloat())
        activationTab.background = rounded(if (index == 1) Palette.primary else Palette.panel, dp(10).toFloat())
        optimizeTab.background = rounded(if (index == 2) Palette.primary else Palette.panel, dp(10).toFloat())
        body.removeAllViews()
        val page = when (index) {
            0 -> playPage
            1 -> activationPage
            else -> optimizerPanel.view
        }
        (page.parent as? ViewGroup)?.removeView(page)
        body.addView(page, FrameLayout.LayoutParams(-1, -1))
        if (index == 1) updateActivation()
    }

    private fun createPlayPage() {
        playPage = ScrollView(this).apply { isFillViewport = false; clipToPadding = false }
        val content = column().apply { setPadding(dp(16), dp(4), dp(16), dp(28)) }
        playPage.addView(content)

        playGate = column().apply {
            background = rounded(0xFF2C1C42.toInt(), dp(14).toFloat(), Palette.primaryDark)
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        playGate.addView(txt("⚠  Precisamos de ativação", 15f, Palette.white, true))
        playGate.addView(txt("Ative o mapeador antes de abrir um jogo por aqui.", 12f, Palette.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(10) })
        playGate.addView(action("Ativar", true) { showTab(1) })
        content.addView(playGate, playGate.withMargin(bottom = 19))

        content.addView(txt("Meus Jogos", 19f, Palette.white, true),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        gameCount = txt("Procurando jogos instalados...", 12f, Palette.muted)
        content.addView(gameCount)
        gameAdapter = GameAdapter(this, { onGameSelected(it) }, { openManualPicker() })
        val recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            adapter = gameAdapter
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
        }
        content.addView(recycler, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12); bottomMargin = dp(25)
        })

        content.addView(txt("Ferramentas e configurações", 18f, Palette.white, true),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        settingsRow(content, "Configurações do app", "Aparência, entrada e compatibilidade") {
            AlertDialog.Builder(this).setTitle("Configurações do app")
                .setItems(arrayOf("Atualizar lista de jogos", "Ver dispositivos conectados", "Sobre o KeyMapper")) { _, i ->
                    when (i) {
                        0 -> refreshGames()
                        1 -> renderDevices()
                        else -> info("KeyMapper Pro V2", "Android 10+. Interface e ativação, sem motor de mapeamento nesta etapa.")
                    }
                }.show()
        }
        settingsRow(content, "Mapeamentos", "Perfis e configurações de teclas") {
            info("Mapeamentos", "O editor de teclas e o motor de toques serão implementados em uma próxima etapa.")
        }
        settingsRow(content, "Cursor do mouse", "Personalização do cursor") {
            info("Cursor do mouse", "O cursor personalizado requer a futura camada flutuante. A permissão pode ser concedida na tela de jogo.")
        }
        settingsRow(content, "Terminal ADB", "Informações de ativação") { showTab(1) }
        settingsRow(content, "Apps em execução", "Gerenciamento de aplicativos") {
            info("Apps em execução", "Esta versão não encerra outros aplicativos. A função depende de permissões e integração adicionais.")
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
                "Android 11+. Inicie o Shizuku após parear pela depuração sem fio e autorize o KeyMapper.",
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
        if (found.isEmpty()) {
            deviceStrip.addView(txt("Nenhum periférico externo conectado", 12f, Palette.muted))
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
                gameCount.text = when (list.size) {
                    0 -> "Nenhum jogo detectado. Use Adicionar."
                    1 -> "1 jogo encontrado"
                    else -> "${list.size} jogos encontrados"
                }
            }
        }
    }

    /** Mostra o ícone original do APK ao adicionar jogos (sem clones). */
    private fun openManualPicker() {
        val candidates = allApps.filterNot { it.isGame }
        if (candidates.isEmpty()) { toast("Nenhum outro aplicativo disponível"); return }
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
        val overlayRow = permissionRow(root, "Widgets flutuantes", "Exibir KeyMapper sobre o jogo", "Permitir", "Pular",
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
        // A interface abre o jogo original; o motor de mapeamento ainda não está integrado.
        try { startActivity(launch) }
        catch (_: Exception) { toast("Falha ao abrir o jogo") }
    }

    private fun updateActivation() {
        if (!::activationInfo.isInitialized || disposed) return
        val state = activation.state()
        val authorized = state == ShizukuActivation.State.AUTHORIZED
        activationChip.text = if (authorized) "● Ativado" else "● Não ativado"
        activationChip.setTextColor(if (authorized) Palette.success else Palette.warning)
        playGate.visibility = if (authorized) View.GONE else View.VISIBLE
        activationInfo.text = when (state) {
            ShizukuActivation.State.AUTHORIZED -> "Shizuku autorizado. O mapeamento de entradas será integrado em uma próxima versão."
            ShizukuActivation.State.NOT_RUNNING -> "Shizuku não está em execução. Inicie-o por USB/ADB ou, no Android 11+, pela depuração Wi-Fi."
            ShizukuActivation.State.PERMISSION_REQUIRED -> "Shizuku está ativo. Toque em Autorizar para conceder acesso ao KeyMapper."
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

    private fun showAppMenu() {
        AlertDialog.Builder(this).setTitle("KeyMapper Pro")
             .setItems(arrayOf("Jogar", "Ativação", "Otimizar", "Atualizar jogos", "Sobre")) { _, option ->
                when (option) {
                    0 -> showTab(0)
                    1 -> showTab(1)
                    2 -> showTab(2)
                    3 -> refreshGames()
                    else -> info("KeyMapper Pro 0.2", "Interface roxa, permissões reais, jogos e periféricos Android 10+.")
                }
            }.show()
    }

    private fun info(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object { private const val SHIZUKU_PERMISSION_CODE = 1001 }
}
