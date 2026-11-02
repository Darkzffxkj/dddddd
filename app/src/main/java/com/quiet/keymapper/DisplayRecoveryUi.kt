package com.quiet.keymapper

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/** Rescue screen available even when the game HUD is inaccessible. */
object DisplayRecoveryUi {
    fun show(activity:Activity) {
        val client=DisplayTuningClient(activity)
        val dialog=AlertDialog.Builder(activity)
            .setTitle("Restaurar resolução e DPI")
            .setMessage("Consultando o Shizuku...\n\nRestaurar remove os ajustes do comando wm para a tela inteira do Android.")
            .setPositiveButton("Restaurar padrão",null)
            .setNeutralButton("Copiar comandos",null)
            .setNegativeButton("Cancelar",null)
            .create()
        dialog.setOnDismissListener { client.close() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dialog.setMessage("Restaurando resolução e densidade padrão...")
            client.reset { code ->
                if(code==0) {
                    DisplaySessionReset.clear(activity.applicationContext)
                    Toast.makeText(activity,"Tela restaurada",Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                } else {
                    dialog.setMessage("Falha ao restaurar (código "+code+"). Verifique o Shizuku.")
                }
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val c=activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            c.setPrimaryClip(ClipData.newPlainText(
                "Restaurar tela Android","wm size reset\nwm density reset"
            ))
            Toast.makeText(activity,"Comandos copiados",Toast.LENGTH_SHORT).show()
        }
        client.connect { connected ->
            if(!dialog.isShowing) return@connect
            if(!connected) {
                dialog.setMessage("Para restaurar a tela, inicie o Shizuku e autorize Referência.\n\n"+
                    "Alternativa via ADB:\nwm size reset\nwm density reset")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            } else client.query { d,error ->
                if(!dialog.isShowing) return@query
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
                dialog.setMessage(if(d==null) {
                    "Shizuku conectado, mas a leitura falhou: "+error
                } else {
                    "Resolução atual: "+d.activeW+" × "+d.activeH+" px"+
                    "\nDensidade atual: "+d.activeDpi+" dpi"+
                    "\nPadrão físico: "+d.physicalW+" × "+d.physicalH+
                    " px / "+d.physicalDpi+" dpi\n\n"+
                    "Restaurar padrão altera a tela inteira do Android."
                })
            }
        }
    }
}
