package com.quiet.keymapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.app.RemoteInput
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * Descobre o serviço oficial _adb-tls-pairing._tcp enquanto o usuário vê
 * "Parear dispositivo com código" em Configurações / Depuração sem fio.
 * O código é fornecido voluntariamente pelo usuário via RemoteInput.
 * NÃO ativa automaticamente Shizuku e NÃO altera o APK de nenhum jogo.
 */
class PairingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var manager: NotificationManager
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var endpoint: Pair<String, Int>? = null
    @Volatile private var pairing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Reuse the same pairing identity across app restarts.
        try {
            val cert = java.io.File(filesDir, "kadb-cert.pem")
            val key = java.io.File(filesDir, "kadb-key.pem")
            if (cert.exists() && key.exists()) KadbCert.set(cert.readBytes(), key.readBytes())
            else {
                val pair = KadbCert.get()
                cert.writeBytes(pair.first)
                key.writeBytes(pair.second)
            }
        } catch (_: Exception) { KadbCert.get() }
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "Pareamento ADB", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Detecta pareamento da depuração Wi-Fi e solicita os seis dígitos" })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < 30) {
            stopSelf()
            return START_NOT_STICKY
        }
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notice("Aguardando pareamento", "Abra 'Parear dispositivo com código' na depuração Wi-Fi.", false))
        if (action == ACTION_REPLY) {
            val code = intent.getStringExtra(EXTRA_CODE)?.trim().orEmpty()
            if (!Regex("\\d{6}").matches(code)) {
                publish("Código inválido", "Digite exatamente os seis dígitos exibidos pelo Android.", true)
            } else {
                val service = endpoint
                if (service == null) {
                    publish("Serviço indisponível", "Abra novamente 'Parear dispositivo com código' e aguarde a detecção.", false)
                } else {
                    pair(service.first, service.second, code)
                }
            }
        } else if (discovery == null) {
            startDiscovery()
        }
        return START_NOT_STICKY
    }

    private fun startDiscovery() {
        if (discovery != null) return
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicast = wifi.createMulticastLock("keymapper-adb-pair").apply {
                setReferenceCounted(false)
                acquire()
            }
            val localNsd = getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd = localNsd
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) { }
                override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                    handler.post { publish("Falha na descoberta", "Verifique se o celular está conectado ao Wi-Fi.", false) }
                }
                override fun onStopDiscoveryFailed(type: String, errorCode: Int) { }
                override fun onDiscoveryStopped(type: String) { }
                override fun onServiceLost(service: NsdServiceInfo) {
                    handler.post {
                        if (!pairing) {
                            endpoint = null
                            publish("Aguardando pareamento", "Abra 'Parear dispositivo com código' na depuração Wi-Fi.", false)
                        }
                    }
                }
                override fun onServiceFound(service: NsdServiceInfo) {
                    try {
                        localNsd.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { }
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val address = resolved.host?.hostAddress ?: return
                                val port = resolved.port
                                if (port !in 1..65535) return
                                handler.post {
                                    endpoint = address to port
                                    publish("Pareamento encontrado", "Toque em 'Digite aqui' e insira o código de 6 dígitos.", true)
                                }
                            }
                        })
                    } catch (_: Exception) { }
                }
            }
            discovery = listener
            localNsd.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            publish("Não foi possível procurar dispositivos", "Verifique a depuração Wi-Fi e as permissões.", false)
        }
    }

    private fun pair(host: String, port: Int, code: String) {
        if (pairing) return
        pairing = true
        publish("Pareando via ADB", "Validando o código com o Android…", false)
        worker.execute {
            val result = runCatching { runBlocking { Kadb.pair(host, port, code, "KeyMapper Pro") } }
            handler.post {
                pairing = false
                if (result.isSuccess) {
                    publish("Pareamento ADB concluído", "A autorização do Shizuku é independente; retorne ao app para verificar.", false)
                    stopDiscovery()
                } else {
                    publish("Falha no pareamento", "Confira o código e abra novamente o pareamento no Android.", true)
                }
            }
        }
    }

    private fun publish(title: String, content: String, acceptCode: Boolean) {
        manager.notify(NOTIFICATION_ID, notice(title, content, acceptCode))
    }

    private fun notice(title: String, content: String, acceptCode: Boolean): Notification {
        val home = PendingIntent.getActivity(
            this, 102, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setContentIntent(home)
            .setOnlyAlertOnce(!acceptCode)
            .setOngoing(!acceptCode)
            .setAutoCancel(false)
        if (acceptCode) {
            val replyIntent = Intent(this, PairingReplyReceiver::class.java).apply { action = ACTION_REPLY }
            val pending = PendingIntent.getBroadcast(
                this, 103, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val remoteInput = RemoteInput.Builder(KEY_REPLY)
                .setLabel("Código de 6 dígitos")
                .build()
            builder.addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_edit, "Digite aqui", pending
            ).addRemoteInput(remoteInput).build())
        }
        val stop = PendingIntent.getService(
            this, 104, Intent(this, PairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "Encerrar", stop
        ).build())
        return builder.build()
    }

    private fun stopDiscovery() {
        discovery?.let { listener -> try { nsd?.stopServiceDiscovery(listener) } catch (_: Exception) { } }
        discovery = null
        try { if (multicast?.isHeld == true) multicast?.release() } catch (_: Exception) { }
        multicast = null
    }

    override fun onDestroy() {
        stopDiscovery()
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.quiet.keymapper.PAIR_START"
        const val ACTION_STOP = "com.quiet.keymapper.PAIR_STOP"
        const val ACTION_REPLY = "com.quiet.keymapper.PAIR_REPLY"
        const val EXTRA_CODE = "pair_code"
        const val KEY_REPLY = "pairing_code_reply"
        private const val CHANNEL = "keymapper_pairing"
        private const val NOTIFICATION_ID = 2213
    }
}

/** Manifest receiver não exportado; lê somente a resposta do próprio PendingIntent. */
class PairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingService.KEY_REPLY)?.toString() ?: return
        val reply = Intent(context, PairingService::class.java)
            .setAction(PairingService.ACTION_REPLY)
            .putExtra(PairingService.EXTRA_CODE, text)
        try { context.startService(reply) } catch (_: Exception) { }
    }
}
