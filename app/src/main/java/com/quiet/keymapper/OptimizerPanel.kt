package com.quiet.keymapper

import android.app.Activity
import android.app.AlertDialog
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.provider.Settings
import android.telecom.TelecomManager
import android.provider.Telephony
import android.view.View
import android.widget.Switch
import java.util.Locale
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** User-initiated hibernation via a Shizuku UserService: actual PS results, never inferred from Recent tasks. */
class OptimizerPanel(private val activity: Activity, private val openActivation: () -> Unit) {
    val view = ScrollView(activity).apply { clipToPadding = false }
    private val executor = Executors.newSingleThreadExecutor()
    private val caption = activity.txt("Verificando Shizuku...", 12f, Palette.muted)
    private val appArea = activity.column()
    private val stopButton = activity.action("Hibernar selecionados", true) { confirmStop() }
    private val rows = mutableListOf<Triple<InstalledApp, CheckBox, Boolean>>()
    private var closed = false
    private var busy = false
    private var bound = false
    private val prefs = activity.getSharedPreferences("referencia_optimizer", Context.MODE_PRIVATE)
    private val exceptions = prefs.getStringSet("exceptions", emptySet())?.toMutableSet() ?: mutableSetOf()
    private val customPackages = prefs.getStringSet("custom", emptySet())?.toMutableSet() ?: mutableSetOf()
    private var showUser = true
    private var showSystem = false
    private var customOnly = false
    private var processesKnown = false
    private var closedSession = 0
    private data class RunState(val app: InstalledApp, val active: Boolean, val system: Boolean)
    private var cached = emptyList<RunState>()
    private val ramGauge = RamGauge(activity)
    private val ramText = activity.txt("Verificando memória...", 12f, Palette.muted)
    private val launchedCount = activity.txt("—", 20f, Palette.violetSoft, true)
    private val closedCount = activity.txt("0", 20f, Palette.violetSoft, true)
    private val freeRam = activity.txt("—", 20f, Palette.violetSoft, true)
    private lateinit var exceptionsHolder: LinearLayout
    private lateinit var allSegment: TextView
    private lateinit var customSegment: TextView
    @Volatile private var remote: IOptimizerService? = null
    private val args = Shizuku.UserServiceArgs(
        ComponentName(activity.packageName, OptimizerUserService::class.java.name)
    ).daemon(false).processNameSuffix("optimizer").version(5)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            remote = IOptimizerService.Stub.asInterface(binder)
            activity.runOnUiThread { if (!closed) refresh() }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            bound = false
            activity.runOnUiThread { if (!closed) caption.text = "Shizuku desconectado. Toque em Atualizar." }
        }
    }

    init {
        val content = activity.column().apply {
            setPadding(activity.dp(16), activity.dp(4), activity.dp(16), activity.dp(30))
        }
        view.addView(content)
        content.addView(activity.txt("Otimizar", 21f, Palette.white, true))
        content.addView(activity.txt(
            "RAM, processos ativos e hibernação de aplicativos — status consultado pelo Shizuku.",
            12f, Palette.muted
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(7); bottomMargin = activity.dp(12) })

        // Header metrics patterned after the reference, in the established purple theme.
        val memoryCard = activity.row().apply {
            background = rounded(Palette.panel, activity.dp(14).toFloat())
            setPadding(activity.dp(8), activity.dp(12), activity.dp(12), activity.dp(12))
        }
        memoryCard.addView(ramGauge, LinearLayout.LayoutParams(activity.dp(154), activity.dp(154)))
        val stats = activity.column().apply { setPadding(activity.dp(9), 0, 0, 0) }
        stats.addView(launchedCount)
        stats.addView(activity.txt("Apps lançados", 11f, Palette.muted))
        stats.addView(closedCount, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(12) })
        stats.addView(activity.txt("Fechados nesta sessão", 11f, Palette.muted))
        stats.addView(freeRam, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(12) })
        stats.addView(activity.txt("Memória disponível", 11f, Palette.muted))
        memoryCard.addView(stats, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(memoryCard)
        content.addView(ramText, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = activity.dp(5); bottomMargin = activity.dp(12)
        })

        val toggles = activity.column().apply {
            background = rounded(Palette.panel, activity.dp(12).toFloat())
            setPadding(activity.dp(13), activity.dp(10), activity.dp(13), activity.dp(10))
        }
        fun addToggle(label: String, on: Boolean, changed: (Boolean) -> Unit) {
            val line = activity.row()
            line.addView(activity.txt(label, 14f, Palette.white, true), LinearLayout.LayoutParams(0, -2, 1f))
            val toggle = Switch(activity).apply {
                isChecked = on
                thumbTintList = android.content.res.ColorStateList.valueOf(Palette.primary)
                setOnCheckedChangeListener { _, checked -> changed(checked) }
            }
            line.addView(toggle)
            toggles.addView(line)
        }
        addToggle("Apps do usuário", true) { showUser = it; redrawApps() }
        addToggle("Apps do sistema (somente leitura)", false) { showSystem = it; redrawApps() }
        content.addView(toggles, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(11) })

        val segments = activity.row().apply {
            background = rounded(Palette.panel, activity.dp(12).toFloat())
            setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
        }
        allSegment = activity.action("Todos os aplicativos", true) {
            customOnly = false; refreshSegment(); redrawApps()
        }
        customSegment = activity.action("Lista personalizada", false) {
            customOnly = true; refreshSegment(); redrawApps()
        }
        segments.addView(allSegment, LinearLayout.LayoutParams(0, -2, 1f))
        segments.addView(customSegment, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = activity.dp(5) })
        content.addView(segments, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(11) })
        content.addView(activity.action("Editar lista personalizada", false) { pickCustomApps() },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(11) })

        val exceptionsCard = activity.column().apply {
            background = rounded(Palette.panel, activity.dp(13).toFloat())
            setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(13))
        }
        val exceptionsTop = activity.row()
        exceptionsTop.addView(activity.txt("LISTA DE EXCEÇÕES", 16f, Palette.violetSoft, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        exceptionsTop.addView(activity.action("+", false) { addException() })
        exceptionsCard.addView(exceptionsTop)
        exceptionsHolder = activity.column()
        exceptionsCard.addView(exceptionsHolder, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = activity.dp(10)
        })
        content.addView(exceptionsCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(12) })
        drawExceptions()
        updateMemory()

        val card = activity.column().apply {
            background = rounded(Palette.panel, activity.dp(14).toFloat())
            setPadding(activity.dp(13), activity.dp(13), activity.dp(13), activity.dp(14))
        }
        card.addView(activity.txt("APPS LANÇADOS", 16f, Palette.violetSoft, true))
        card.addView(caption, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = activity.dp(7); bottomMargin = activity.dp(11)
        })
        val buttons = activity.row()
        buttons.addView(activity.action("Atualizar", false) { refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(activity.action("Selecionar ativos", false) { selectActive() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = activity.dp(7) })
        card.addView(buttons)
        card.addView(activity.action("Limpar seleção", false) { rows.forEach { it.second.isChecked = false } },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(7) })
        card.addView(appArea, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(10) })
        card.addView(stopButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(10) })
        content.addView(card)

        content.addView(activity.txt("Reativar apps opcionais", 18f, Palette.white, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(22) })
        content.addView(activity.txt(
            "Selecione quais aplicativos do comando de desativação deseja reativar. A operação não é automática.",
            12f, Palette.muted
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(5); bottomMargin = activity.dp(9) })
        content.addView(activity.action("Selecionar e reativar apps", false) { showRestorePicker() })
        content.addView(activity.action("Copiar — comandos para reativar", false) {
            copyAsset("brevent_restore.txt")
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(6) })

        content.addView(activity.txt("Execute no Brevent", 18f, Palette.white, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(20) })
        content.addView(activity.txt(
            "Os comandos são copiados, nunca executados automaticamente. O segundo bloco contém ajustes " +
            "experimentais sem garantia de aumento de FPS e pode prejudicar bateria e estabilidade.",
            12f, Palette.warning
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(6); bottomMargin = activity.dp(10) })
        content.addView(activity.action("Copiar 1 — Desativar aplicativos", false) {
            warnCopy("brevent_1.txt", "O bloco 1 desativa Gmail, Chrome, YouTube e outros apps. Copiar?")
        })
        content.addView(activity.action("Performance Max 🧊", false) {
            warnCopy("brevent_2.txt", "Performance Max contém parâmetros não validados que podem ser ignorados ou causar instabilidade. Copiar?")
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(7) })
        content.addView(activity.txt(
            "Apps essenciais, serviços Android, teclado, launcher e o próprio Shizuku são protegidos. " +
            "O Android pode reiniciar processos que forem encerrados.",
            12f, Palette.muted
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(12) })
    }


    private fun refreshSegment() {
        allSegment.background = rounded(if (customOnly) Palette.panelAlt else Palette.primary, activity.dp(10).toFloat())
        customSegment.background = rounded(if (customOnly) Palette.primary else Palette.panelAlt, activity.dp(10).toFloat())
        allSegment.setTextColor(if (customOnly) Palette.violetSoft else Palette.white)
        customSegment.setTextColor(if (customOnly) Palette.white else Palette.violetSoft)
    }

    private fun updateMemory() {
        try {
            val mem = ActivityManager.MemoryInfo()
            (activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
            if (mem.totalMem <= 0) return
            val used = (100.0 * (mem.totalMem - mem.availMem) / mem.totalMem).toInt().coerceIn(0, 100)
            ramGauge.usedPercent = used
            val free = mem.availMem.toDouble() / 1_073_741_824.0
            val total = mem.totalMem.toDouble() / 1_073_741_824.0
            val usedGb = total - free
            ramText.text = String.format(Locale.getDefault(),
                "%.2f GB / %.2f GB em uso", usedGb, total)
            freeRam.text = String.format(Locale.getDefault(), "%.2f GB", free)
            closedCount.text = closedSession.toString()
        } catch (_: Exception) { ramText.text = "RAM indisponível" }
    }

    private fun redrawApps() {
        if (!::exceptionsHolder.isInitialized) return
        rows.clear()
        appArea.removeAllViews()
        val visible = cached.filter { state ->
            state.active &&
                (if (state.system) showSystem else showUser) &&
                state.app.packageName !in exceptions &&
                (!customOnly || state.app.packageName in customPackages)
        }
        launchedCount.text = if (processesKnown) cached.count { it.active }.toString() else "—"
        if (!processesKnown || visible.isEmpty()) {
            appArea.addView(activity.txt(
                if (!processesKnown) "Sem dados. Verifique o Shizuku."
                else "Nenhum aplicativo ativo corresponde aos filtros.",
                12f, Palette.muted
            ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(10) })
        }
        visible.sortedWith(compareBy<RunState> { it.system }.thenBy { it.app.label.lowercase() })
            .forEach { entry -> addAppRow(entry.app, entry.active, processesKnown, entry.system) }
        caption.text = if (!processesKnown) "Consulta indisponível. Verifique a ativação Shizuku."
            else "${visible.size} exibidos • ${cached.count { it.active }} processos de apps detectados"
    }

    private fun drawExceptions() {
        if (!::exceptionsHolder.isInitialized) return
        exceptionsHolder.removeAllViews()
        if (exceptions.isEmpty()) {
            exceptionsHolder.addView(activity.txt("Nenhuma exceção adicionada.", 12f, Palette.muted))
            return
        }
        val pm = activity.packageManager
        exceptions.sorted().forEach { pkg ->
            val label = try {
                @Suppress("DEPRECATION")
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { pkg }
            val line = activity.row().apply { setPadding(0, activity.dp(5), 0, activity.dp(5)) }
            line.addView(activity.txt(label, 12f, Palette.white), LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(activity.action("Remover", false) {
                exceptions.remove(pkg); saveLists(); drawExceptions(); redrawApps()
            })
            exceptionsHolder.addView(line)
        }
    }

    private fun addException() {
        val available = cached.map { it.app }.distinctBy { it.packageName }
            .filter { it.packageName !in exceptions }.sortedBy { it.label.lowercase() }
        if (available.isEmpty()) {
            toast("Atualize a lista para escolher exceções")
            return
        }
        AlertDialog.Builder(activity).setTitle("Adicionar à lista de exceções")
            .setItems(available.map { it.label }.toTypedArray()) { _, index ->
                exceptions.add(available[index].packageName)
                saveLists(); drawExceptions(); redrawApps()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun pickCustomApps() {
        val available = cached.map { it.app }.distinctBy { it.packageName }
            .filter { it.packageName !in protectedPackages() }.sortedBy { it.label.lowercase() }
        if (available.isEmpty()) { toast("Atualize os aplicativos"); return }
        val choices = BooleanArray(available.size) { i ->
            available[i].packageName in customPackages
        }
        AlertDialog.Builder(activity).setTitle("Lista personalizada")
            .setMultiChoiceItems(available.map { it.label }.toTypedArray(), choices) { _, i, checked ->
                choices[i] = checked
            }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                customPackages.clear()
                available.forEachIndexed { i, app -> if (choices[i]) customPackages.add(app.packageName) }
                saveLists(); customOnly = true; refreshSegment(); redrawApps()
            }.show()
    }

    private fun saveLists() {
        prefs.edit().putStringSet("exceptions", exceptions.toSet())
            .putStringSet("custom", customPackages.toSet()).apply()
    }

    fun onTabShown() { bindIfNeeded(); refresh() }

    private fun bindIfNeeded() {
        if (closed || bound) return
        try {
            if (!Shizuku.pingBinder() ||
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                caption.text = "Precisamos de ativação Shizuku para consultar processos."
                return
            }
            Shizuku.bindUserService(args, connection)
            bound = true
        } catch (_: Exception) { caption.text = "Erro ao conectar ao serviço Shizuku." }
    }

    fun refresh() {
        if (closed || busy) return
        bindIfNeeded()
        updateMemory()
        val service = remote
        caption.text = if (service == null) "Conectando ao Shizuku..." else "Lendo processos em execução..."
        executor.execute {
            val candidates = try { loadUserApps() } catch (_: Exception) { emptyList() }
            val processes: Set<String>? = try { service?.listRunningProcesses()?.toSet() }
                catch (_: Exception) { null }
            val good = processes != null && "!ERROR_PS" !in processes
            val pm = activity.packageManager
            val list = candidates.map { app ->
                val active = good && processes!!.any { it == app.packageName || it.startsWith(app.packageName + ":") }
                val isSystem = try {
                    @Suppress("DEPRECATION")
                    val flags = pm.getApplicationInfo(app.packageName, 0).flags
                    flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                } catch (_: Exception) { true }
                RunState(app, active, isSystem)
            }
            activity.runOnUiThread {
                if (closed) return@runOnUiThread
                processesKnown = good
                cached = list
                updateMemory()
                redrawApps()
            }
        }
    }

    private fun loadUserApps(): List<InstalledApp> {
        val pm = activity.packageManager
        val ignored = protectedPackages()
        @Suppress("DEPRECATION")
        val installed = pm.getInstalledApplications(0)
        return installed.asSequence().filter { a ->
            a.enabled && a.packageName !in ignored
        }.map { a ->
            val label = try { pm.getApplicationLabel(a).toString() } catch (_: Exception) { a.packageName }
            @Suppress("DEPRECATION")
            val game = a.category == ApplicationInfo.CATEGORY_GAME ||
                (a.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            InstalledApp(a.packageName, label, game)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }.toList()
    }

    private fun protectedPackages(): Set<String> {
        val set = mutableSetOf(activity.packageName, "android", "com.android.systemui",
            "com.android.settings", "com.android.phone", "com.android.dialer",
            "com.android.permissioncontroller", "com.google.android.gms",
            "com.google.android.gsf", "moe.shizuku.privileged.api")
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            activity.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { set.add(it) }
        } catch (_: Exception) { }
        try { Settings.Secure.getString(activity.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.let { set.add(it) } } catch (_: Exception) { }
        try { Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.forEach { name -> name.substringBefore('/').takeIf { it.isNotBlank() }?.let { set.add(it) } }
        } catch (_: Exception) { }
        try {
            (activity.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
                ?.defaultDialerPackage?.let { set.add(it) }
            Telephony.Sms.getDefaultSmsPackage(activity)?.let { set.add(it) }
        } catch (_: Exception) { }
        return set
    }

    private fun addAppRow(app: InstalledApp, active: Boolean, good: Boolean, system: Boolean) {
        val row = activity.row().apply { setPadding(0, activity.dp(8), 0, activity.dp(8)) }
        val icon = ImageView(activity).apply {
            setImageDrawable(try { activity.packageManager.getApplicationIcon(app.packageName) }
                catch (_: Exception) { activity.packageManager.defaultActivityIcon })
        }
        row.addView(icon, LinearLayout.LayoutParams(activity.dp(35), activity.dp(35)).apply {
            rightMargin = activity.dp(9)
        })
        val labels = activity.column()
        labels.addView(activity.txt(app.label, 13f, Palette.white, true))
        labels.addView(activity.txt(when {
            !good -> "Estado desconhecido"
            system -> "Sistema • protegido"
            active -> "● Em execução"
            else -> "Parado"
        }, 11f, if (active && good) Palette.success else Palette.muted))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val box = CheckBox(activity).apply {
            buttonTintList = android.content.res.ColorStateList.valueOf(Palette.violetSoft)
            isEnabled = active && good && !system
        }
        row.addView(box)
        row.setOnClickListener { if (box.isEnabled) box.isChecked = !box.isChecked }
        rows.add(Triple(app, box, active))
        appArea.addView(row)
        appArea.addView(View(activity).apply { setBackgroundColor(Palette.line) },
            LinearLayout.LayoutParams(-1, activity.dp(1)))
    }

    private fun selectActive() {
        rows.forEach { (app, box, active) -> box.isChecked = active && box.isEnabled && !app.isGame }
    }

    private fun confirmStop() {
        if (remote == null) { bindIfNeeded(); toast("Shizuku não está conectado"); return }
        val chosen = rows.filter { it.second.isChecked && it.third }.map { it.first.packageName }
        if (chosen.isEmpty()) { toast("Selecione aplicativos em execução"); return }
        AlertDialog.Builder(activity).setTitle("Hibernar ${chosen.size} aplicativos?")
            .setMessage("Aplicativos selecionados poderão perder tarefas não salvas e parar de enviar notificações. Continuar?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Hibernar") { _, _ -> applyActions(chosen, false) }.show()
    }

    private val restoreApps = listOf(
        "com.google.android.youtube", "com.android.chrome", "com.google.android.googlequicksearchbox",
        "com.google.android.apps.docs", "com.google.android.gm", "com.instagram.android",
        "com.google.android.apps.photos", "com.google.android.apps.maps",
        "com.google.android.apps.tachyon", "com.google.android.videos"
    )

    private fun showRestorePicker() {
        if (remote == null) {
            AlertDialog.Builder(activity).setTitle("Precisamos de ativação")
                .setMessage("Ative o Shizuku para reativar apps. Os comandos podem ser copiados sem ativação.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Ativar") { _, _ -> openActivation() }.show()
            return
        }
        val checked = BooleanArray(restoreApps.size)
        AlertDialog.Builder(activity).setTitle("Reativar apps opcionais")
            .setMultiChoiceItems(restoreApps.toTypedArray(), checked) { _, index, value -> checked[index] = value }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ ->
                val chosen = restoreApps.filterIndexed { i, _ -> checked[i] }
                if (chosen.isEmpty()) { toast("Selecione pelo menos um app"); return@setPositiveButton }
                AlertDialog.Builder(activity).setTitle("Confirmar reativação")
                    .setMessage("Reativar ${chosen.size} apps para o usuário 0?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Reativar") { _, _ -> applyActions(chosen, true) }.show()
            }.show()
    }

    private fun applyActions(packages: List<String>, enable: Boolean) {
        if (busy) return
        val svc = remote ?: return
        busy = true
        stopButton.isEnabled = false
        executor.execute {
            val protected = protectedPackages()
            val result = packages.distinct().take(60).map { pkg ->
                val code = try {
                    if (!enable && pkg in protected) -2
                    else if (enable) svc.enableOptionalApp(pkg)
                    else svc.forceStopUserApp(pkg)
                } catch (_: Exception) { -6 }
                pkg to code
            }
            activity.runOnUiThread {
                if (closed) return@runOnUiThread
                busy = false
                stopButton.isEnabled = true
                val success = result.count { it.second == 0 }
                if (!enable) closedSession += success
                updateMemory()
                if (success < result.size)
                    AlertDialog.Builder(activity).setTitle("Algumas operações falharam")
                        .setMessage(result.filter { it.second != 0 }.joinToString("\n") {
                            "${it.first}: código ${it.second}"
                        }).setPositiveButton("OK", null).show()
                else toast("${success} operações concluídas")
                refresh()
            }
        }
    }

    private fun warnCopy(file: String, warning: String) {
        AlertDialog.Builder(activity).setTitle("Atenção")
            .setMessage(warning).setNegativeButton("Cancelar", null)
            .setPositiveButton("Copiar") { _, _ -> copyAsset(file) }.show()
    }

    private fun copyAsset(name: String) {
        try {
            val content = activity.assets.open(name).bufferedReader().use { it.readText() }
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Comandos Brevent", content))
            toast("Comandos copiados. Nenhuma alteração executada.")
        } catch (_: Exception) { toast("Falha ao copiar") }
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    fun close() {
        closed = true
        try { if (bound) Shizuku.unbindUserService(args, connection, true) } catch (_: Exception) { }
        remote = null
        executor.shutdownNow()
    }
}
