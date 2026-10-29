package org.paw.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The app's one preferences file, and the only place that knows its keys.
 * Everything here applies to every song; anything that belongs to one song
 * lives in its manifest. The settings screen edits the observable values
 * below, and each one is on disk the moment it changes.
 */
enum class KeepAwakeMode { Off, FiveMinutes, On }

class AppPrefs private constructor(private val prefs: SharedPreferences) {

    /** Output route: "" = auto (interface, else phone), "phone", "usb:<name>". */
    var outputDevice: String
        get() = prefs.getString(KEY_OUTPUT_DEVICE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OUTPUT_DEVICE, value).apply()

    // Songs saved before click, count-in and mic boost moved into the song
    // carry none of the three; they open with what these keys last held.
    val legacyMicBoostDb: Float get() = prefs.getFloat(KEY_MIC_BOOST_DB, 16f)
    val legacyClickEnabled: Boolean get() = prefs.getBoolean(KEY_CLICK_ENABLED, false)
    val legacyCountInBars: Int get() = prefs.getInt(KEY_COUNT_IN_BARS, 0)

    /**
     * Keep-awake: [KeepAwakeMode.On] holds the screen for good,
     * [KeepAwakeMode.FiveMinutes] holds it until five minutes past the last
     * touch (a busy transport always holds it, so a take can't die to the
     * lock screen), [KeepAwakeMode.Off] leaves the system timeout alone.
     */
    var keepAwakeMode: KeepAwakeMode by mutableStateOf(readKeepAwake())
        private set

    fun updateKeepAwakeMode(mode: KeepAwakeMode) {
        keepAwakeMode = mode
        prefs.edit().putString(
            KEY_KEEP_AWAKE_MODE,
            when (mode) {
                KeepAwakeMode.On -> "on"
                KeepAwakeMode.FiveMinutes -> "5min"
                KeepAwakeMode.Off -> "off"
            },
        ).apply()
    }

    private fun readKeepAwake(): KeepAwakeMode = when (prefs.getString(KEY_KEEP_AWAKE_MODE, null)) {
        "on" -> KeepAwakeMode.On
        "5min" -> KeepAwakeMode.FiveMinutes
        "off" -> KeepAwakeMode.Off
        // Installs that stored the older boolean: only an explicit false
        // survives as off, everything else gets the five-minute default.
        else -> if (prefs.getBoolean(KEY_KEEP_AWAKE, true)) {
            KeepAwakeMode.FiveMinutes
        } else {
            KeepAwakeMode.Off
        }
    }

    /** Playhead follow style: false = jump when the playhead nears the edge,
     *  true = scroll continuously under a mid-view playhead. */
    var followSmooth: Boolean by mutableStateOf(prefs.getBoolean(KEY_FOLLOW_SMOOTH, false))
        private set

    fun updateFollowSmooth(smooth: Boolean) {
        followSmooth = smooth
        prefs.edit().putBoolean(KEY_FOLLOW_SMOOTH, smooth).apply()
    }

    /** Timeline zoom (frames per pixel), remembered per song. */
    fun zoomFor(songKey: String): Float? =
        "$KEY_ZOOM:$songKey".let { k ->
            if (prefs.contains(k)) prefs.getFloat(k, 0f) else null
        }

    fun setZoomFor(songKey: String, framesPerPx: Float) {
        prefs.edit().putFloat("$KEY_ZOOM:$songKey", framesPerPx).apply()
    }

    /**
     * Round-trip latency compensation for the interface path, in frames at the
     * engine's rate. This is what the loopback rig measures, and the fallback
     * for any route with nothing better.
     */
    var latencyFrames: Int
        get() = prefs.getInt(KEY_LATENCY_FRAMES, DEFAULT_LATENCY_FRAMES)
        set(value) = prefs.edit().putInt(KEY_LATENCY_FRAMES, value).apply()

    /**
     * Per-route latency, learned from what the open streams report. Bluetooth
     * runs 150-300 ms against the interface's 10, so one constant cannot place
     * takes correctly on every route.
     */
    fun routeLatencyFrames(routeKey: String): Int? =
        routeLatencyKey(routeKey).let { k ->
            if (prefs.contains(k)) prefs.getInt(k, DEFAULT_LATENCY_FRAMES) else null
        }

    fun setRouteLatencyFrames(routeKey: String, frames: Int) {
        prefs.edit().putInt(routeLatencyKey(routeKey), frames).apply()
    }

    private fun routeLatencyKey(routeKey: String) = "$KEY_LATENCY_FRAMES:$routeKey"

    /**
     * Meter peak-hold time: null = off, [Duration.INFINITE] = hold until the
     * transport starts. On disk it stays the seconds-with-negative-one
     * encoding existing installs hold, and nothing above this line sees that.
     */
    var meterHold: Duration? by mutableStateOf(
        when (val secs = prefs.getInt(KEY_METER_HOLD_SECS, 2)) {
            0 -> null
            -1 -> Duration.INFINITE
            else -> secs.seconds
        },
    )
        private set

    fun updateMeterHold(hold: Duration?) {
        meterHold = hold
        prefs.edit().putInt(
            KEY_METER_HOLD_SECS,
            when {
                hold == null -> 0
                hold == Duration.INFINITE -> -1
                else -> hold.inWholeSeconds.toInt()
            },
        ).apply()
    }

    companion object {
        private const val FILE = "paw"

        // Frozen: existing installs keep their settings only if these match.
        private const val KEY_OUTPUT_DEVICE = "outputDevice"
        private const val KEY_MIC_BOOST_DB = "micBoostDb"
        private const val KEY_CLICK_ENABLED = "clickEnabled"
        private const val KEY_COUNT_IN_BARS = "countInBars"
        private const val KEY_METER_HOLD_SECS = "meterHoldSecs"
        private const val KEY_KEEP_AWAKE = "keepAwake"  // older boolean, read-only
        private const val KEY_KEEP_AWAKE_MODE = "keepAwakeMode"
        private const val KEY_FOLLOW_SMOOTH = "followSmooth"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_LATENCY_FRAMES = "latencyFrames"

        /** Measured on the reference rig (Pixel 9a + M-Track Duo, ~10 ms @48k). */
        private const val DEFAULT_LATENCY_FRAMES = 480

        @Volatile
        private var instance: AppPrefs? = null

        fun of(context: Context): AppPrefs = instance ?: synchronized(this) {
            instance ?: AppPrefs(
                context.applicationContext
                    .getSharedPreferences(FILE, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
