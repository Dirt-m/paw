package org.paw.app

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

// AAudio does no device enumeration: this Kotlin layer finds the USB interface
// and hands its device ids to the engine (see docs/engineering.md).

data class UsbAudioDevice(
    val id: Int,
    val name: String,
    val isInput: Boolean,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val encodings: List<String>,
) {
    val specLine: String
        get() {
            val ch = if (channelCounts.isEmpty()) "? ch"
            else channelCounts.joinToString("/") { "$it" } + " ch"
            val rates = if (sampleRates.isEmpty()) "any rate"
            else sampleRates.joinToString(" / ") {
                if (it % 1000 == 0) "${it / 1000}k" else "%.1fk".format(it / 1000.0)
            }
            val enc = if (encodings.isEmpty()) "" else " · " + encodings.joinToString(" / ")
            return "$ch · $rates$enc"
        }
}

private fun encodingName(encoding: Int): String = when (encoding) {
    AudioFormat.ENCODING_PCM_16BIT -> "16-bit"
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit"
    AudioFormat.ENCODING_PCM_32BIT -> "32-bit"
    AudioFormat.ENCODING_PCM_FLOAT -> "float"
    else -> "enc$encoding"
}

private fun AudioDeviceInfo.isUsb(): Boolean =
    type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET

private fun AudioDeviceInfo.toModel(isInput: Boolean) = UsbAudioDevice(
    id = id,
    name = productName.toString().ifBlank { "USB audio device" },
    isInput = isInput,
    sampleRates = sampleRates.toList().sorted(),
    channelCounts = channelCounts.toList().sorted(),
    encodings = encodings.map(::encodingName),
)

/** One selectable output endpoint: "" = auto, else an [OutputDevice.key].
 *  Inputs are typed instead; see model.InputOption. */
data class DeviceOption(val key: String, val label: String)

/**
 * What an output endpoint is. This decides AUTO's preference order and how far
 * off the loopback-measured latency constant is likely to be. Ordinal order is
 * the AUTO order: an attached interface wins, the speaker loses.
 */
enum class OutputKind { Usb, Wired, Bluetooth, Other, Speaker }

/**
 * A selectable playback endpoint. [key] is what gets persisted, so it must
 * survive a disconnect/reconnect cycle. Device ids do not: they are reassigned
 * per connection.
 */
data class OutputDevice(
    val id: Int,
    val name: String,
    val key: String,
    val kind: OutputKind,
)

private fun outputKind(type: Int): OutputKind? = when (type) {
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    -> OutputKind.Usb

    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    -> OutputKind.Wired

    // SCO is deliberately absent: it is the 8/16 kHz call path, and opening it
    // is what drags the whole phone into communication routing.
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    -> OutputKind.Bluetooth

    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC,
    AudioDeviceInfo.TYPE_DOCK,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL,
    AudioDeviceInfo.TYPE_AUX_LINE,
    -> OutputKind.Other

    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputKind.Speaker

    // Earpiece, telephony, remote submix, bus: never a monitoring path.
    else -> null
}

private fun AudioDeviceInfo.toOutput(kind: OutputKind): OutputDevice {
    val name = productName.toString().ifBlank {
        when (kind) {
            OutputKind.Usb -> "USB audio device"
            OutputKind.Wired -> "Headphones"
            OutputKind.Bluetooth -> "Bluetooth"
            OutputKind.Other -> "Line out"
            OutputKind.Speaker -> "Phone speaker"
        }
    }
    // "phone" and "usb:<name>" are the keys existing installs already store.
    val key = when (kind) {
        OutputKind.Speaker -> "phone"
        OutputKind.Usb -> "usb:$name"
        OutputKind.Wired -> "wired"
        OutputKind.Bluetooth -> "bt:$name"
        OutputKind.Other -> "out:$name"
    }
    return OutputDevice(id = id, name = name, key = key, kind = kind)
}

/** Every connected playback endpoint, in AUTO-preference order. */
fun outputDevices(am: AudioManager): List<OutputDevice> =
    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .mapNotNull { info -> outputKind(info.type)?.let { info.toOutput(it) } }
        .distinctBy { it.key }
        .sortedBy { it.kind.ordinal }

// Explicit built-in device ids: with a USB interface attached Android routes
// "default" (id 0) to it, so choosing the phone's speaker or mic needs the
// real device id.
fun builtinSpeakerId(am: AudioManager): Int =
    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.id ?: 0

fun builtinMicId(am: AudioManager): Int =
    am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.id ?: 0

fun usbAudioDevices(am: AudioManager): List<UsbAudioDevice> =
    am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isUsb() }.map { it.toModel(true) } +
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isUsb() }.map { it.toModel(false) }

/** Inputs stay USB-or-mic; outputs are anything you can listen on. */
data class AudioEndpoints(
    val usb: List<UsbAudioDevice> = emptyList(),
    val outputs: List<OutputDevice> = emptyList(),
)

@Composable
fun rememberAudioEndpoints(): AudioEndpoints {
    val context = LocalContext.current
    var endpoints by remember { mutableStateOf(AudioEndpoints()) }
    DisposableEffect(Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun read() = AudioEndpoints(usbAudioDevices(am), outputDevices(am))
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                endpoints = read()
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                endpoints = read()
            }
        }
        am.registerAudioDeviceCallback(callback, null)
        endpoints = read()
        onDispose { am.unregisterAudioDeviceCallback(callback) }
    }
    return endpoints
}
