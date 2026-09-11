package org.paw.app.snap

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.paw.app.ENGINE_STATE_SLOTS
import org.paw.app.Engine
import org.paw.app.ST_IN_CHANNELS
import org.paw.app.ST_IN_DEVICE_ID
import org.paw.app.ST_INPUT_OPEN
import org.paw.app.ST_OUTPUT_OPEN
import org.paw.app.ST_PLAYHEAD
import org.paw.app.ST_REC_FRAMES
import org.paw.app.ST_REC_START_FRAME
import org.paw.app.ST_RECORDING
import org.paw.app.ST_SAMPLE_RATE
import org.paw.app.ST_TRANSPORT
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

// The one seam between the UI and the C++ engine. Everything the controller
// or a screen can call is stubbed here with deterministic fakes, so the whole
// real UI (screens, SongController, model) composes on the JVM with no
// native library. Configure the static state below before building a
// controller; reset() between tests.
@Implements(Engine::class)
class ShadowEngine {

    companion object {
        var transport: Int = 0
        var playheadFrame: Long = 0L
        var recording: Boolean = false
        var recFrames: Long = 0L
        var recStartFrame: Long = -1L
        var inputOpen: Boolean = true
        var outputOpen: Boolean = true
        var inChannels: Int = 2

        /** trackId → meter peak (0..1); MASTER_TRACK_ID for the master bus. */
        var meters: Map<Int, Float> = emptyMap()

        /** take file name (not path) → frames; what wavInfo/computePeaks serve. */
        var takeFrames: MutableMap<String, Long> = mutableMapOf()

        fun reset() {
            transport = 0
            playheadFrame = 0L
            recording = false
            recFrames = 0L
            recStartFrame = -1L
            inputOpen = true
            outputOpen = true
            inChannels = 2
            meters = emptyMap()
            takeFrames = mutableMapOf()
        }
    }

    /** Swallows the real init block's System.loadLibrary. */
    @Implementation
    fun __constructor__() {
    }

    // ---- streams ----

    @Implementation
    fun openStreams(inputDeviceId: Int, outputDeviceId: Int, sampleRate: Int, builtinMic: Boolean): String = ""

    @Implementation
    fun closeStreams() {
    }

    @Implementation
    fun setInputGain(gain: Float) {
    }

    // ---- song scope ----

    @Implementation
    fun openSong(gen: Long) {
    }

    @Implementation
    fun syncClips(
        gen: Long,
        trackId: Int,
        paths: Array<String>,
        srcStarts: LongArray,
        lengths: LongArray,
        timelineStarts: LongArray,
        fadeIns: LongArray,
        fadeOuts: LongArray,
    ) {
    }

    @Implementation
    fun removeTrack(gen: Long, trackId: Int) {
    }

    @Implementation
    fun setTrackParams(
        gen: Long,
        trackId: Int,
        gain: Float,
        pan: Float,
        mute: Boolean,
        solo: Boolean,
        armed: Boolean,
        inputMode: Int,
        monitor: Boolean,
    ) {
    }

    // ---- transport ----

    @Implementation
    fun transportPlayPause(): Int {
        transport = if (transport == 0) 1 else 0
        return transport
    }

    @Implementation
    fun transportStopAll() {
    }

    @Implementation
    fun transportSeek(frame: Long) {
        playheadFrame = frame
    }

    @Implementation
    fun setMetronome(gen: Long, enabled: Boolean, bpm: Float, beatsPerBar: Int) {
    }

    @Implementation
    fun setLoop(gen: Long, enabled: Boolean, startFrame: Long, endFrame: Long) {
    }

    // ---- record ----

    @Implementation
    fun toggleRecordSession(gen: Long, dir: String, baseName: String, countInFramesIfStopped: Long): Int = 2

    @Implementation
    fun lastRecordError(): String = ""

    @Implementation
    fun finishRecordAsync(keepRolling: Boolean): Boolean = false

    @Implementation
    fun finishRecordSync(keepRolling: Boolean): Boolean = false

    @Implementation
    fun recordResultLongs(): LongArray = longArrayOf(0, 0, 1, 0)

    @Implementation
    fun recordResultPaths(): Array<String> = emptyArray()

    // ---- state / meters ----

    @Implementation
    fun engineState(dst: LongArray) {
        java.util.Arrays.fill(dst, 0, ENGINE_STATE_SLOTS, 0L)
        dst[ST_TRANSPORT] = transport.toLong()
        dst[ST_PLAYHEAD] = playheadFrame
        dst[ST_REC_START_FRAME] = recStartFrame
        dst[ST_REC_FRAMES] = recFrames
        dst[ST_RECORDING] = if (recording) 1L else 0L
        dst[ST_OUTPUT_OPEN] = if (outputOpen) 1L else 0L
        dst[ST_INPUT_OPEN] = if (inputOpen) 1L else 0L
        dst[ST_IN_CHANNELS] = inChannels.toLong()
        dst[ST_IN_DEVICE_ID] = 42L
        dst[ST_SAMPLE_RATE] = 48000L
    }

    @Implementation
    fun engineMeters(ids: IntArray, peaks: FloatArray): Int {
        var n = 0
        for ((id, peak) in meters) {
            if (n >= ids.size) break
            ids[n] = id
            peaks[n] = peak
            n++
        }
        return n
    }

    @Implementation
    fun engineShutdown() {
    }

    // ---- peaks / file info ----

    @Implementation
    fun wavInfo(path: String): LongArray {
        val frames = takeFrames[java.io.File(path).name] ?: return longArrayOf(0, 0, 0)
        return longArrayOf(frames, 2, 48000)
    }

    /** A plausible musical waveform: phrase-shaped envelope with transients,
     *  companded to the sqrt domain like Peaks.cpp. Deterministic per bucket
     *  and per file name, so snapshots are stable. */
    @Implementation
    fun computePeaks(path: String, bucketFrames: Int): ByteArray {
        val frames = takeFrames[java.io.File(path).name] ?: return ByteArray(0)
        val buckets = ((frames + bucketFrames - 1) / bucketFrames).toInt()
        val seed = java.io.File(path).name.hashCode().toFloat()
        val out = ByteArray(buckets * 2)
        for (i in 0 until buckets) {
            val t = i.toFloat()
            val phrase = 0.55f + 0.45f * sin(t * 0.011f + seed)
            val beat = if (sin(t * 0.35f + seed * 2f) > 0.75f) 1.0f else 0.55f
            val wobble = 0.15f * sin(t * 0.13f + seed * 3f)
            val amp = (abs(phrase) * beat + wobble).coerceIn(0.04f, 1.0f)
            val v = (sqrt(amp) * 127f).toInt().coerceIn(1, 127)
            out[i * 2] = (-v).toByte()
            out[i * 2 + 1] = v.toByte()
        }
        return out
    }

    // ---- effects ----

    @Implementation
    fun scanEffects(dir: String): String = builtinCatalogJson()

    @Implementation
    fun setChain(
        gen: Long,
        trackId: Int,
        sampleRate: Int,
        effectIds: Array<String>,
        controlValues: Array<FloatArray>,
        bypasses: BooleanArray,
        mixes: FloatArray,
    ) {
    }

    @Implementation
    fun setEffectParam(gen: Long, trackId: Int, unitIdx: Int, controlIdx: Int, value: Float) {
    }

    @Implementation
    fun setEffectUnitState(gen: Long, trackId: Int, unitIdx: Int, bypass: Boolean, mix: Float) {
    }

    // ---- mixdown ----

    @Implementation
    fun mixdownBegin() {
    }

    @Implementation
    fun mixdownAddTrack(
        isMaster: Boolean,
        gain: Float,
        pan: Float,
        mute: Boolean,
        solo: Boolean,
        paths: Array<String>,
        srcStarts: LongArray,
        lengths: LongArray,
        timelineStarts: LongArray,
        fadeIns: LongArray,
        fadeOuts: LongArray,
        effectIds: Array<String>,
        controlValues: Array<FloatArray>,
        effectBypasses: BooleanArray,
        effectMixes: FloatArray,
    ) {
    }

    @Implementation
    fun mixdownRun(sampleRate: Int, outPath: String): String = "snapshot harness: no mixdown"
}

/** Mirrors EffectHost::catalogJson() for the eight built-ins, ids and names
 *  matching BuiltinEffects.cpp so saved chains resolve. */
private fun builtinCatalogJson(): String {
    fun port(name: String, def: Float, min: Float, max: Float, log: Boolean = false, int: Boolean = false) =
        JSONObject()
            .put("name", name)
            .put("def", def.toDouble())
            .put("min", min.toDouble())
            .put("max", max.toDouble())
            .put("log", log)
            .put("int", int)
            .put("toggle", false)

    fun plugin(id: String, name: String, ports: List<JSONObject>) =
        JSONObject()
            .put("id", "builtin:$id")
            .put("name", name)
            .put("stereo", true)
            .put("ports", JSONArray(ports))

    return JSONArray(
        listOf(
            plugin(
                "paw_eq3", "PAW! 3-Band EQ",
                listOf(
                    port("Low (dB)", 0f, -24f, 24f),
                    port("Mid (dB)", 0f, -24f, 24f),
                    port("Mid Freq (Hz)", 1000f, 125f, 8000f, log = true),
                    port("High (dB)", 0f, -24f, 24f),
                ),
            ),
            plugin(
                "paw_comp", "PAW! Compressor",
                listOf(
                    port("Threshold (dB)", -15f, -60f, 0f),
                    port("Ratio (:1)", 4f, 1f, 16f, log = true),
                    port("Attack (ms)", 10f, 1f, 100f, log = true),
                    port("Release (ms)", 100f, 10f, 1000f, log = true),
                    port("Makeup (dB)", 0f, 0f, 24f),
                ),
            ),
            plugin(
                "paw_delay", "PAW! Delay",
                listOf(
                    port("Time (ms)", 200f, 20f, 2000f, log = true),
                    port("Feedback (%)", 23.75f, 0f, 95f),
                    port("Mix (%)", 25f, 0f, 100f),
                ),
            ),
            plugin(
                "paw_reverb", "PAW! Reverb",
                listOf(
                    port("Size (%)", 50f, 0f, 100f),
                    port("Damping (%)", 50f, 0f, 100f),
                    port("Pre-delay (ms)", 0f, 0f, 200f),
                    port("Mix (%)", 25f, 0f, 100f),
                ),
            ),
            plugin(
                "paw_chorus", "PAW! Chorus",
                listOf(
                    port("Rate (Hz)", 0.7f, 0.05f, 10f, log = true),
                    port("Depth (%)", 50f, 0f, 100f),
                    port("Mix (%)", 25f, 0f, 100f),
                ),
            ),
            plugin(
                "paw_gate", "PAW! Noise Gate",
                listOf(
                    port("Threshold (dB)", -60f, -80f, 0f),
                    port("Attack (ms)", 0.6f, 0.1f, 100f, log = true),
                    port("Hold (ms)", 125f, 0f, 500f),
                    port("Release (ms)", 100f, 10f, 1000f, log = true),
                ),
            ),
            plugin(
                "paw_limiter", "PAW! Limiter",
                listOf(
                    port("Threshold (dB)", 0f, -24f, 0f),
                    port("Ceiling (dB)", 0f, -24f, 0f),
                    port("Release (ms)", 31.6f, 1f, 1000f, log = true),
                ),
            ),
            plugin(
                "paw_filter", "PAW! Filter",
                listOf(
                    port("Mode (0=LP 1=HP)", 0f, 0f, 1f, int = true),
                    port("Cutoff (Hz)", 20000f, 20f, 20000f, log = true),
                    port("Resonance (Q)", 0.707f, 0.707f, 16f, log = true),
                ),
            ),
        ),
    ).toString()
}
