package com.quiet.keymapper

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reset only display modifications initiated by Referência.
 * The flag is persisted before the Activity can leave the foreground.
 * No auto-reset is attempted when the app never applied an override.
 */
object DisplaySessionReset {
    private const val FILE="referencia_display_session"
    private const val MODIFIED="modified"
    private val resetting=AtomicBoolean(false)

    fun markApplied(context:Context) {
        context.applicationContext.getSharedPreferences(FILE,Context.MODE_PRIVATE)
            .edit().putBoolean(MODIFIED,true).commit()
    }

    fun clear(context:Context) {
        context.applicationContext.getSharedPreferences(FILE,Context.MODE_PRIVATE)
            .edit().putBoolean(MODIFIED,false).apply()
    }

    fun restoreIfModified(context:Context) {
        val app=context.applicationContext
        if(!app.getSharedPreferences(FILE,Context.MODE_PRIVATE)
                .getBoolean(MODIFIED,false))return
        if(!resetting.compareAndSet(false,true))return
        val client=DisplayTuningClient(app)
        client.connect { connected ->
            if(!connected) {
                resetting.set(false)
                client.close()
                return@connect
            }
            client.reset { code ->
                if(code==0) clear(app)
                resetting.set(false)
                client.close()
            }
        }
    }
}
