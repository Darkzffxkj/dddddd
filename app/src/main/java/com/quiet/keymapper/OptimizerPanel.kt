package com.quiet.keymapper

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.app.role.RoleManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.util.concurrent.Executors

/**
 * Otimizador conservador: apenas aplicativos instalados pelo usuário e visíveis ao launcher.
 * Não afirma detectar todos os processos em execução.
 * Shizuku é checado na hora da execução e não há parada automática de apps de sistema.
 */
class OptimizerPanel(
    private val activity: Activity,
    private val openActivation: () -> Unit
) {
    val view = ScrollView(activity).apply { clipToPadding = false }
    private val executor = Executors.newSingleThreadExecutor()
    private val selections = mutableListOf<Pair<InstalledApp, CheckBox>>()
    private val caption = activity.txt("Carregando aplicativos...", 12f, Palette.muted)
    private val appArea = activity.column()
    private val stopButton = activity.action("Forçar parada dos selecionados", true) { confirmStop() }
    private var destroyed = false
    private var busy = false

    init {
        val content = activity.column().apply { setPadding(activity.dp(16), activity.dp(4), activity.dp(16), activity.dp(32)) }
        view.addView(content)
        content.addView(activity.txt("Otimizar", 21f, Palette.white, true))
        content.addView(activity.txt(
            "Hibernação manual de aplicativos. Não alteramos processos essenciais, serviços do sistema ou apps protegidos.",
            12f, Palette.muted
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8); bottomMargin = activity.dp(14) })

        val card = activity.column().apply {
            background = rounded(Palette.panel, activity.dp(13).toFloat())
            setPadding(activity.dp(13), activity.dp(13), activity.dp(13), activity.dp(13))
        }
        card.addView(activity.txt("Aplicativos instalados pelo usuário", 16f, Palette.white, true))
        card.addView(activity.txt(
            "Selecione os aplicativos que não estiver usando. A parada pode interromper downloads, mensagens e notificações.",
            12f, Palette.muted
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(7); bottomMargin = activity.dp(10) })
        card.addView(caption)
        val tools = activity.row()
        tools.addView(activity.action("Selecionar seguros", false) { selectSafe() },
            LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(activity.action("Limpar seleção", false) { selections.forEach { it.second.isChecked = false } },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = activity.dp(7) })
        card.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(10) })
        card.addView(appArea, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(10) })
        card.addView(stopButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(10) })
        content.addView(card)
        content.addView(activity.txt("Execulte no Brevent", 17f, Palette.white, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(21); bottomMargin = activity.dp(7) })
        content.addView(activity.txt(
            "Os comandos abaixo são fornecidos apenas para cópia. Não são executados pelo KeyMapper. " +
            "O bloco 1 desativa apps e pode interromper serviços que você usa. " +
            "O bloco 2 contém ajustes experimentais, não verificados para seu aparelho, e pode causar instabilidade ou ser ignorado pelo Android.",
            12f, Palette.warning
        ), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(10) })
        content.addView(activity.action("Copiar 1 — Desativar aplicativos", false) {
            showCopyWarning("brevent_1.txt", "O comando 1 desativa Gmail, Chrome, YouTube e outros aplicativos. Quer apenas copiar?", "Copiar 1")
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(7) })
        content.addView(activity.action("Copiar 2 — Ajustes experimentais", false) {
            showCopyWarning("brevent_2.txt", "O bloco 2 não é uma otimização comprovada. Pode prejudicar energia, memória e estabilidade. Quer apenas copiar?", "Copiar 2")
        })
        content.addView(activity.txt(
            "Reversão do bloco 1: use pm enable --user 0 seguido do pacote desativado. " +
            "Não execute os dois blocos sem conhecer os efeitos.",
            12f, Palette.muted
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(11) })
        loadApps()
    }

    private fun loadApps() {
        executor.execute {
            val candidates = try {
                val installed = GameRepository(activity).allLaunchable()
                val pm = activity.packageManager
                val protected = protectedPackages()
                installed.filter { app ->
                    app.packageName !in protected && !isSystemPackage(pm, app.packageName)
                }
            } catch (_: Exception) { emptyList() }
            activity.runOnUiThread {
                if (destroyed) return@runOnUiThread
                appArea.removeAllViews()
                selections.clear()
                caption.text = if (candidates.isEmpty())
                    "Nenhum aplicativo elegível visível. O Android pode limitar a descoberta."
                else "Encontrados " + candidates.size + " aplicativos. Selecione os que deseja parar."
                candidates.forEach { app -> addAppRow(app) }
            }
        }
    }

    private fun protectedPackages(): Set<String> {
        val result = mutableSetOf(
            activity.packageName, "android", "com.android.systemui",
            "com.android.settings", "com.android.phone", "com.android.dialer",
            "com.android.providers.settings", "com.google.android.gms",
            "com.google.android.gsf", "moe.shizuku.privileged.api"
        )
        val pm = activity.packageManager
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.let { result.add(it) }
        } catch (_: Exception) { }
        try {
            Settings.Secure.getString(activity.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.let { result.add(it) }
        } catch (_: Exception) { }
        try {
            Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.forEach { entry -> entry.substringBefore('/').takeIf { it.isNotBlank() }?.let { result.add(it) } }
        } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = activity.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            listOf(RoleManager.ROLE_DIALER, RoleManager.ROLE_SMS, RoleManager.ROLE_HOME).forEach { role ->
                try { if (roles?.isRoleAvailable(role) == true) result.addAll(roles.getRoleHolders(role)) }
                catch (_: Exception) { }
            }
        }
        return result
    }

    private fun isSystemPackage(pm: PackageManager, name: String): Boolean = try {
        @Suppress("DEPRECATION")
        val flags = pm.getApplicationInfo(name, 0).flags
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (_: Exception) { true }

    private fun addAppRow(app: InstalledApp) {
        val line = activity.row().apply { setPadding(0, activity.dp(8), 0, activity.dp(8)) }
        val icon = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(try { activity.packageManager.getApplicationIcon(app.packageName) }
            catch (_: Exception) { activity.packageManager.defaultActivityIcon })
        }
        line.addView(icon, LinearLayout.LayoutParams(activity.dp(35), activity.dp(35)).apply { rightMargin = activity.dp(8) })
        val labels = activity.column()
        labels.addView(activity.txt(app.label, 13f, Palette.white, true))
        labels.addView(activity.txt(app.packageName, 10f, Palette.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(2) })
        line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val cb = CheckBox(activity)
        cb.buttonTintList = android.content.res.ColorStateList.valueOf(Palette.violetSoft)
        line.addView(cb)
        line.setOnClickListener { cb.isChecked = !cb.isChecked }
        selections.add(app to cb)
        appArea.addView(line)
        appArea.addView(View(activity).apply { setBackgroundColor(Palette.line) },
            LinearLayout.LayoutParams(-1, activity.dp(1)))
    }

    private fun selectSafe() {
        // Jogos não são pré-selecionados para evitar fechar o jogo do usuário.
        selections.forEach { (app, cb) -> cb.isChecked = !app.isGame }
    }

    private fun confirmStop() {
        if (busy) return
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(activity).setTitle("Precisamos de ativação")
                .setMessage("Autorize o KeyMapper no Shizuku para solicitar parada de apps.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Ativar") { _, _ -> openActivation() }.show()
            return
        }
        val chosen = selections.filter { it.second.isChecked }.map { it.first }
        if (chosen.isEmpty()) {
            Toast.makeText(activity, "Selecione um aplicativo", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity).setTitle("Confirmar parada")
            .setMessage("Solicitar parada de " + chosen.size +
                " aplicativos selecionados? Isso pode interromper mensagens, uploads e tarefas não salvas.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Forçar parada") { _, _ -> stopApps(chosen) }.show()
    }

    private fun stopApps(apps: List<InstalledApp>) {
        if (busy) return
        busy = true
        stopButton.isEnabled = false
        caption.text = "Solicitando paradas..."
        executor.execute {
            val protected = protectedPackages()
            val results = apps.take(60).map { app ->
                val eligible = app.packageName !in protected &&
                    Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+").matches(app.packageName) &&
                    !isSystemPackage(activity.packageManager, app.packageName)
                app to if (eligible) stopOne(app.packageName) else false
            }
            val success = results.count { it.second }
            activity.runOnUiThread {
                if (destroyed) return@runOnUiThread
                busy = false
                stopButton.isEnabled = true
                caption.text = "Solicitações concluídas: " + success + "/" + results.size +
                    ". Não é garantido que o Android mantenha os processos encerrados."
                results.filter { it.second }.forEach { (_, _) -> }
                Toast.makeText(activity, caption.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopOne(pkg: String): Boolean = try {
        // Shizuku 13 expõe newProcess internamente. Implementação provisória,
        // encapsulada e com falha segura; migrar para UserService na versão final.
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
        val command = arrayOf("am", "force-stop", pkg)
        val p = method.invoke(null, command, null, null) as ShizukuRemoteProcess
        try { p.waitFor() == 0 } finally { p.destroy() }
    } catch (_: Exception) { false }

    private fun showCopyWarning(asset: String, warning: String, button: String) {
        AlertDialog.Builder(activity).setTitle("Atenção").setMessage(warning)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(button) { _, _ -> copyFromAssets(asset) }.show()
    }

    private fun copyFromAssets(asset: String) {
        try {
            val text = activity.assets.open(asset).bufferedReader().use { it.readText() }
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Comandos Brevent", text))
            Toast.makeText(activity, "Comandos copiados. Nenhuma alteração foi aplicada.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(activity, "Falha ao copiar", Toast.LENGTH_SHORT).show()
        }
    }

    fun close() { destroyed = true; executor.shutdownNow() }
}
