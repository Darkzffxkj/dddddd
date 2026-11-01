package com.quiet.keymapper

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Conservative app hibernator. System essentials are never candidates. */
class OptimizerPanel(
    private val host: Activity,
    private val activation: ShizukuActivation,
    private val onActivationNeeded: () -> Unit
) {
    val view = ScrollView(host)
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var remote: IOptimizerService? = null
    private var binding = false
    private var disposed = false
    private val list = host.column()
    private val summary = host.txt("Localize os processos dos aplicativos.", 12f, Palette.muted)
    private val state = host.txt("Shizuku necessário para encerrar aplicativos.", 12f, Palette.muted)
    private val items = mutableListOf<Item>()
    private data class Item(val app: InstalledApp, val isSystem: Boolean, var selected: Boolean)
    private val allowedSystem = setOf(
        "com.google.android.youtube", "com.android.chrome", "com.google.android.googlequicksearchbox",
        "com.google.android.apps.docs", "com.google.android.gm", "com.google.android.apps.photos",
        "com.google.android.apps.maps", "com.google.android.apps.tachyon", "com.google.android.videos"
    )
    private val protectedPrefixes = listOf(
        "com.android.systemui", "com.google.android.gms", "com.google.android.gsf",
        "com.android.providers.", "com.android.permissioncontroller", "com.android.phone",
        "com.android.bluetooth", "com.android.nfc", "com.android.settings",
        "com.android.launcher", "com.google.android.inputmethod",
        "com.samsung.android.honeyboard", "com.android.vending",
        "com.google.android.networkstack", "com.android.networkstack"
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = IOptimizerService.Stub.asInterface(binder)
            binding = false
            state.text = "Serviço Shizuku conectado."
            scan()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            binding = false
            state.text = "Serviço Shizuku desconectado."
        }
    }

    init {
        val root = host.column().apply { setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(30)) }
        view.addView(root)
        root.addView(host.txt("Otimizar", 21f, Palette.white, true))
        root.addView(host.txt("Hibernador de aplicativos", 17f, Palette.violetSoft, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(16) })
        root.addView(host.txt(
            "Detecta processos principais de apps comuns e permite forçar sua parada. " +
            "Aplicativos críticos do Android, serviços do sistema, teclado e tela inicial ficam protegidos. " +
            "A detecção pode não incluir processos secundários.",
            12f, Palette.muted
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(7) })
        root.addView(state, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = host.dp(12); bottomMargin = host.dp(12)
        })
        root.addView(host.action("Localizar apps em execução", true) { scan() },
            LinearLayout.LayoutParams(-1, host.dp(46)))
        root.addView(summary, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = host.dp(12); bottomMargin = host.dp(9)
        })
        root.addView(list)
        root.addView(host.action("Hibernar selecionados", true) { confirmStop() },
            LinearLayout.LayoutParams(-1, host.dp(46)).apply { topMargin = host.dp(12) })
        root.addView(host.txt(
            "Forçar parada pode interromper notificações, sincronização e alarmes. " +
            "Isso não garante FPS maior. Apps pré-instalados opcionais não são selecionados automaticamente.",
            12f, Palette.warning
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(10) })
        root.addView(host.txt("Executar no Brevent", 18f, Palette.white, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(27); bottomMargin = host.dp(10) })
        scriptCard(root, "1. Desativar apps opcionais",
            "Copia os comandos enviados. Desativar navegador, Gmail ou Mapas pode afetar serviços e persistir após reiniciar.",
            "brevent_1.txt")
        scriptCard(root, "2. Ajustes avançados",
            "Experimental: muitos parâmetros podem ser ignorados ou piorar a bateria, a RAM e a estabilidade. Não é executado automaticamente.",
            "brevent_2.txt")
    }

    private fun scriptCard(parent: LinearLayout, title: String, description: String, file: String) {
        val card = host.column().apply {
            background = rounded(Palette.panel, host.dp(12).toFloat())
            setPadding(host.dp(12), host.dp(13), host.dp(12), host.dp(13))
        }
        card.addView(host.txt(title, 14f, Palette.white, true))
        card.addView(host.txt(description, 12f, Palette.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(6); bottomMargin = host.dp(10) })
        card.addView(host.action("Copiar", false) {
            try {
                val script = host.assets.open(file).bufferedReader().use { it.readText() }
                val clipboard = host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Comandos Brevent", script))
                state.text = "Comandos copiados para a área de transferência."
            } catch (_: Exception) { state.text = "Falha ao copiar o arquivo de comandos." }
        }, LinearLayout.LayoutParams(-1, host.dp(43)))
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = host.dp(9) })
    }

    private fun isProtected(pkg: String): Boolean {
        if (pkg == "android" || pkg == host.packageName) return true
        if (protectedPrefixes.any { pkg == it || pkg.startsWith(if (it.endsWith(".")) it else "$it.") }) return true
        val ime = Settings.Secure.getString(host.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        if (ime?.substringBefore('/') == pkg) return true
        val home = host.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )
        return home?.activityInfo?.packageName == pkg
    }

    private fun candidates(): List<Item> {
        val pm = host.packageManager
        return GameRepository(host).allLaunchable().mapNotNull { app ->
            if (isProtected(app.packageName)) return@mapNotNull null
            val flags = try { pm.getApplicationInfo(app.packageName, 0).flags }
                catch (_: Exception) { return@mapNotNull null }
            val system = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (system && app.packageName !in allowedSystem) return@mapNotNull null
            Item(app, system, !system && !app.isGame)
        }
    }

    fun scan() {
        if (disposed || !busy.compareAndSet(false, true)) return
        if (activation.state() != ShizukuActivation.State.AUTHORIZED) {
            busy.set(false)
            state.text = "Precisamos de ativação Shizuku."
            onActivationNeeded()
            return
        }
        val svc = remote
        if (svc == null) {
            busy.set(false)
            if (!binding) {
                binding = true
                state.text = "Conectando ao Shizuku..."
                try {
                    Shizuku.bindUserService(args(), connection)
                } catch (e: Exception) {
                    binding = false
                    state.text = "Falha no serviço: " + (e.message ?: "desconhecida")
                }
            }
            return
        }
        summary.text = "Procurando processos..."
        executor.execute {
            val found = mutableListOf<Item>()
            for (item in candidates()) {
                if (disposed) break
                try { if (svc.isRunning(item.app.packageName)) found.add(item) }
                catch (_: Exception) { }
            }
            host.runOnUiThread {
                busy.set(false)
                if (!disposed) {
                    items.clear()
                    items.addAll(found)
                    render()
                    state.text = "Verificação concluída via Shizuku."
                }
            }
        }
    }

    private fun render() {
        list.removeAllViews()
        summary.text = if (items.isEmpty()) "Nenhum processo elegível foi encontrado."
            else items.size.toString() + " apps detectados. Revise sua seleção."
        for (item in items) {
            val row = host.row().apply { setPadding(0, host.dp(7), 0, host.dp(7)) }
            val icon = ImageView(host).apply {
                setImageDrawable(try { host.packageManager.getApplicationIcon(item.app.packageName) }
                    catch (_: Exception) { host.packageManager.defaultActivityIcon })
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(icon, LinearLayout.LayoutParams(host.dp(39), host.dp(39)).apply { rightMargin = host.dp(8) })
            val labels = host.column()
            labels.addView(host.txt(item.app.label, 13f, Palette.white, true))
            val detail = if (item.isSystem) "Pré-instalado • seleção manual"
                else if (item.app.isGame) "Jogo • não selecionado" else "App instalado"
            labels.addView(host.txt(detail, 11f, Palette.muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(4) })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            val check = CheckBox(host).apply {
                buttonTintList = android.content.res.ColorStateList.valueOf(Palette.primary)
                isChecked = item.selected
                setOnCheckedChangeListener { _, checked -> item.selected = checked }
            }
            row.addView(check)
            row.setOnClickListener { check.isChecked = !check.isChecked }
            list.addView(row)
            list.addView(View(host).apply { setBackgroundColor(Palette.line) },
                LinearLayout.LayoutParams(-1, host.dp(1)))
        }
    }

    private fun confirmStop() {
        val chosen = items.filter { it.selected && !isProtected(it.app.packageName) }
        if (chosen.isEmpty()) { state.text = "Selecione pelo menos um app."; return }
        AlertDialog.Builder(host)
            .setTitle("Forçar parada de " + chosen.size + " aplicativos?")
            .setMessage("Notificações e tarefas desses apps podem ser interrompidas até que sejam abertos novamente. Dados e instalações permanecem intactos.")
            .setPositiveButton("Forçar parada") { _, _ -> stop(chosen) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun stop(chosen: List<Item>) {
        val svc = remote
        if (svc == null || activation.state() != ShizukuActivation.State.AUTHORIZED) {
            state.text = "Shizuku indisponível."; return
        }
        if (!busy.compareAndSet(false, true)) return
        state.text = "Forçando parada dos apps selecionados..."
        executor.execute {
            var ok = 0
            var failed = 0
            for (item in chosen) {
                if (disposed) break
                if (isProtected(item.app.packageName)) { failed++; continue }
                try { if (svc.forceStop(item.app.packageName) == 0) ok++ else failed++ }
                catch (_: Exception) { failed++ }
            }
            host.runOnUiThread {
                busy.set(false)
                if (!disposed) {
                    state.text = "Enviada parada de " + ok + " apps; erros: " + failed
                    scan()
                }
            }
        }
    }

    private fun args() = Shizuku.UserServiceArgs(
        ComponentName(host.packageName, OptimizerUserService::class.java.name)
    ).processNameSuffix("optimizer").daemon(false).debuggable(false).version(1)

    fun dispose() {
        disposed = true
        executor.shutdownNow()
        if (binding || remote != null) try {
            Shizuku.unbindUserService(args(), connection, true)
        } catch (_: Exception) { }
    }
}
