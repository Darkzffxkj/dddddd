package com.quiet.keymapper

import android.content.Context
import android.hardware.input.InputManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.view.InputDevice

/** Only external mouse/keyboard devices; never advertise internal audio/touch/keypad. */
data class ConnectedDevice(val name: String, val kind: String, val identifier: String)

class DeviceRepository(private val context: Context) {
    fun snapshot(): List<ConnectedDevice> {
        val found = mutableListOf<ConnectedDevice>()
        val seenVidPid = mutableSetOf<Pair<Int, Int>>()
        val input = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val internalNames = listOf(
            "gpio_keys", "sec_touchpad", "touchscreen", "touch screen", "virtual",
            "uinput", "power button", "volume key", "fingerprint",
            "sec_key", "hall sensor", "goodix", "fts_ts",
            "touch_key", "handwriting", "stylus"
        )
        for (id in input.inputDeviceIds) {
            val device = input.getInputDevice(id) ?: continue
            if (device.isVirtual || !device.isExternal) continue
            val label = device.name.ifBlank { "Periférico externo" }
            if (internalNames.any { label.lowercase().contains(it) }) continue
            val mouse = device.supportsSource(InputDevice.SOURCE_MOUSE)
            val keyboard = device.supportsSource(InputDevice.SOURCE_KEYBOARD) &&
                device.keyboardType != InputDevice.KEYBOARD_TYPE_NONE
            if (!mouse && !keyboard) continue
            val kind = when {
                mouse && keyboard -> "Mouse / Teclado"
                mouse -> "Mouse"
                else -> "Teclado"
            }
            if (device.vendorId != 0 || device.productId != 0)
                seenVidPid.add(device.vendorId to device.productId)
            found.add(ConnectedDevice(label, kind, "input:$id"))
        }

        // USB host may expose a HID receiver before an InputDevice exists.
        // Accept only boot HID protocols 1 (keyboard) and 2 (mouse).
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        for (device in usb.deviceList.values) {
            if ((device.vendorId to device.productId) in seenVidPid) continue
            var mouse = false
            var keyboard = false
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass != UsbConstants.USB_CLASS_HID) continue
                when (iface.interfaceProtocol) {
                    1 -> keyboard = true
                    2 -> mouse = true
                }
            }
            if (!mouse && !keyboard) continue
            val kind = when {
                mouse && keyboard -> "Mouse / Teclado"
                mouse -> "Mouse"
                else -> "Teclado"
            }
            val name = try {
                listOfNotNull(device.manufacturerName, device.productName)
                    .filter { it.isNotBlank() }.distinct().joinToString(" ")
            } catch (_: SecurityException) { "" }
            found.add(ConnectedDevice(name.ifBlank { "$kind USB" }, kind, "usb:${device.deviceId}"))
        }
        return found.distinctBy { it.identifier }.sortedWith(compareBy({ it.kind }, { it.name.lowercase() }))
    }
}
