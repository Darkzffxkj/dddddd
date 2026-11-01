package com.quiet.keymapper

import android.content.Context
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.InputDevice

/** Event-driven discovery is handled by MainActivity; this is a snapshot, never a poll loop. */
data class ConnectedDevice(val name: String, val kind: String, val identifier: String)

class DeviceRepository(private val context: Context) {
    fun snapshot(): List<ConnectedDevice> {
        val result = mutableListOf<ConnectedDevice>()
        val knownUsbPairs = mutableSetOf<Pair<Int, Int>>()
        val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        for (id in manager.inputDeviceIds) {
            val device = manager.getInputDevice(id) ?: continue
            if (device.isVirtual) continue
            val types = mutableListOf<String>()
            if (device.supportsSource(InputDevice.SOURCE_MOUSE)) types.add("Mouse")
            if (device.supportsSource(InputDevice.SOURCE_KEYBOARD) && device.keyboardType != InputDevice.KEYBOARD_TYPE_NONE) types.add("Teclado")
            if (device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.supportsSource(InputDevice.SOURCE_JOYSTICK)) types.add("Controle")
            if (types.isEmpty()) continue
            knownUsbPairs.add(device.vendorId to device.productId)
            result.add(ConnectedDevice(
                device.name.ifBlank { "Dispositivo de entrada" },
                types.distinct().joinToString(" / "),
                "input:${device.id}"
            ))
        }
        // InputDevice does not enumerate microphones or headphones; use AudioManager.
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (device in audio.getDevices(AudioManager.GET_DEVICES_ALL)) {
            // Integrated speaker/microphone are not externally connected peripherals.
            if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
                device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) continue
            val kind = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microfone integrado"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset / microfone"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Áudio Bluetooth (chamadas)"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Fone / áudio Bluetooth"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "Áudio USB"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Alto-falante integrado"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Fone de ouvido"
                else -> if (device.isSource) "Entrada de áudio" else "Saída de áudio"
            }
            result.add(ConnectedDevice(
                device.productName?.toString()?.takeIf { it.isNotBlank() } ?: kind,
                kind,
                "audio:${device.id}"
            ))
        }
        // Devices exposed only through USB host may not be InputDevice objects.
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        for (device in usb.deviceList.values) {
            if ((device.vendorId to device.productId) in knownUsbPairs) continue
            val product = try { device.productName } catch (_: SecurityException) { null }
            val manufacturer = try { device.manufacturerName } catch (_: SecurityException) { null }
            result.add(ConnectedDevice(
                listOfNotNull(manufacturer, product).joinToString(" ").ifBlank { "USB ${device.vendorId}:${device.productId}" },
                "Dispositivo USB",
                "usb:${device.deviceId}"
            ))
        }
        return result.distinctBy { it.identifier }.sortedWith(compareBy({ it.kind }, { it.name.lowercase() }))
    }
}
