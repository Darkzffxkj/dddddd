package com.quiet.keymapper

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Authorization is checked live. No preference, fake state or permanent bypass. */
class ShizukuActivation {
    enum class State { NOT_RUNNING, PERMISSION_REQUIRED, AUTHORIZED, DENIED, ERROR }

    fun state(): State = try {
        if (!Shizuku.pingBinder()) State.NOT_RUNNING
        else when {
            Shizuku.isPreV11() -> State.ERROR
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.AUTHORIZED
            Shizuku.shouldShowRequestPermissionRationale() -> State.DENIED
            else -> State.PERMISSION_REQUIRED
        }
    } catch (_: RuntimeException) {
        State.ERROR
    }

    fun requestPermission(requestCode: Int) {
        if (state() == State.PERMISSION_REQUIRED) Shizuku.requestPermission(requestCode)
    }
}
