package org.paw.app.model

// A track's input is a device plus a channel mode on that device. The tagged
// strings below are only the on-disk encoding of [InputDevice].

/** Serialized forms, frozen: every manifest on disk holds one of these, so
 *  parse/serialize must stay byte-compatible. */
const val DEV_NONE = ""
const val DEV_MIC = "mic"

/** The "whichever interface is connected" key, and also the [InputDevice.Usb]
 *  prefix, which is why [InputDevice.parse] tests it before the prefix. */
const val DEV_ANY_USB = "usb:"

/**
 * Where a track takes its input from. Tracks keep their device when it is
 * unplugged (it simply stops matching until the device comes back), so this
 * is a saved intent, not a live handle.
 */
sealed interface InputDevice {
    /** No input. */
    data object None : InputDevice

    /** The phone's own mic (Camcorder preset plus the software boost). */
    data object Mic : InputDevice

    /** Whichever interface happens to be connected. */
    data object AnyUsb : InputDevice

    /** One named interface, matched on its USB product name. */
    data class Usb(val name: String) : InputDevice

    /** True for anything that resolves to an audio interface. */
    val isUsb: Boolean get() = this is Usb || this === AnyUsb

    fun serialize(): String = when (this) {
        None -> DEV_NONE
        Mic -> DEV_MIC
        AnyUsb -> DEV_ANY_USB
        is Usb -> DEV_ANY_USB + name
    }

    companion object {
        fun parse(key: String): InputDevice = when {
            key == DEV_MIC -> Mic
            key == DEV_ANY_USB -> AnyUsb
            key.startsWith(DEV_ANY_USB) -> Usb(key.removePrefix(DEV_ANY_USB))
            else -> None
        }
    }
}

/** One selectable input endpoint: a connected interface, or the phone mic. */
data class InputOption(val device: InputDevice, val label: String)
