package org.paw.app.model

import org.json.JSONArray
import org.json.JSONObject

// The song model is immutable data; every edit produces a new Song via copy()
// and is persisted immediately. Takes on disk are never modified. A Clip is
// a window into a take, so trim/split/duplicate/drag are pure arithmetic here.

const val INPUT_NONE = 0
const val INPUT_CH1 = 1
const val INPUT_CH2 = 2
const val INPUT_STEREO = 3

const val SAMPLE_RATE = 48000
const val MASTER_TRACK_ID = -1

/** Minimum clip length after a trim or a split: 10 ms. */
const val MIN_CLIP_FRAMES = 480L

data class Clip(
    val id: Int,
    val take: String,      // path relative to the project dir, e.g. "audio/x.wav"
    val srcStart: Long,    // first take frame that plays
    val length: Long,      // frames
    val start: Long,       // timeline frame
    val fadeIn: Long = 0,  // linear edge fades, frames; 0 = none
    val fadeOut: Long = 0,
) {
    val end: Long get() = start + length
}

// The clip edit clamps live on the data because the lane's drag preview and
// the commit in SongController have to apply exactly the same arithmetic.

/** Fades can never outlast the clip that carries them. */
fun Clip.clampFades(): Clip =
    if (fadeIn <= length && fadeOut <= length) this
    else copy(fadeIn = fadeIn.coerceAtMost(length), fadeOut = fadeOut.coerceAtMost(length))

/** Slide the whole clip; the timeline has no negative side. */
fun Clip.movedTo(newStart: Long): Clip = copy(start = newStart.coerceAtLeast(0))

/** Drag the left edge: reveals or hides take audio, never past take frame 0. */
fun Clip.trimmedLeft(newStart: Long): Clip {
    val minStart = start - srcStart          // can't reveal before take frame 0
    val maxStart = end - MIN_CLIP_FRAMES
    val s = newStart.coerceIn(minStart, maxStart)
    val delta = s - start
    return copy(start = s, srcStart = srcStart + delta, length = length - delta).clampFades()
}

/** Drag the right edge; [takeFrames] is the length of the take on disk, which
 *  is the hard cap on how far the edge can go. */
fun Clip.trimmedRight(newEnd: Long, takeFrames: Long): Clip {
    val maxLen = (takeFrames - srcStart).coerceAtLeast(MIN_CLIP_FRAMES)
    return copy(length = (newEnd - start).coerceIn(MIN_CLIP_FRAMES, maxLen)).clampFades()
}

/** Fade handles; values in frames from the respective clip edge. */
fun Clip.withFades(fadeIn: Long, fadeOut: Long): Clip = copy(
    fadeIn = fadeIn.coerceIn(0, length),
    fadeOut = fadeOut.coerceIn(0, length),
)

data class Effect(
    val pluginId: String,
    val values: List<Float>,
    val bypass: Boolean = false,
    val mix: Float = 1f,       // wet/dry: 1 = fully wet
)

data class Track(
    val id: Int,
    val name: String,
    val gain: Float = 1f,      // linear
    val pan: Float = 0f,       // -1..1
    val mute: Boolean = false,
    val solo: Boolean = false,
    val armed: Boolean = false,
    val inputDevice: InputDevice = InputDevice.AnyUsb,
    val inputMode: Int = INPUT_CH1,
    val monitor: Boolean = false,  // software pass-through while armed
    val clips: List<Clip> = emptyList(),
    val effects: List<Effect> = emptyList(),
)

data class Song(
    val name: String,
    val sampleRate: Int = SAMPLE_RATE,
    val tracks: List<Track> = emptyList(),
    val master: Track = Track(id = MASTER_TRACK_ID, name = "Master", inputMode = INPUT_NONE),
    val nextTrackId: Int = 1,
    val nextClipId: Int = 1,
    val tempoBpm: Float = 120f,     // metronome grid; frame 0 = beat 1
    val beatsPerBar: Int = 4,
    val loopStart: Long = 0,        // frames; loop plays [loopStart, loopEnd)
    val loopEnd: Long = 0,
    val loopEnabled: Boolean = false,
) {
    val endFrame: Long
        get() = tracks.maxOfOrNull { t -> t.clips.maxOfOrNull { it.end } ?: 0L } ?: 0L

    fun track(id: Int): Track? = if (id == MASTER_TRACK_ID) master else tracks.find { it.id == id }

    fun withTrack(updated: Track): Song =
        if (updated.id == MASTER_TRACK_ID) copy(master = updated)
        else copy(tracks = tracks.map { if (it.id == updated.id) updated else it })
}

// ---- JSON (project.json) ----

private fun Effect.toJson() = JSONObject().apply {
    put("plugin", pluginId)
    put("values", JSONArray(values.map { it.toDouble() }))
    put("bypass", bypass)
    put("mix", mix.toDouble())
}

private fun Clip.toJson() = JSONObject().apply {
    put("id", id)
    put("take", take)
    put("srcStart", srcStart)
    put("length", length)
    put("start", start)
    if (fadeIn > 0) put("fadeIn", fadeIn)
    if (fadeOut > 0) put("fadeOut", fadeOut)
}

private fun Track.toJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("gain", gain.toDouble())
    put("pan", pan.toDouble())
    put("mute", mute)
    put("solo", solo)
    put("armed", armed)
    put("inputDevice", inputDevice.serialize())
    put("inputMode", inputMode)
    put("monitor", monitor)
    put("clips", JSONArray(clips.map { it.toJson() }))
    put("effects", JSONArray(effects.map { it.toJson() }))
}

fun Song.toJson(): JSONObject = JSONObject().apply {
    put("version", 1)
    put("name", name)
    put("sampleRate", sampleRate)
    put("tracks", JSONArray(tracks.map { it.toJson() }))
    put("master", master.toJson())
    put("nextTrackId", nextTrackId)
    put("nextClipId", nextClipId)
    put("tempoBpm", tempoBpm.toDouble())
    put("beatsPerBar", beatsPerBar)
    put("loopStart", loopStart)
    put("loopEnd", loopEnd)
    put("loopEnabled", loopEnabled)
    // Older manifests may carry a "trash" array. It is ignored on load, which
    // orphans those clip references but no audio.
}

private fun effectFromJson(o: JSONObject): Effect {
    val values = o.getJSONArray("values")
    return Effect(
        pluginId = o.getString("plugin"),
        values = (0 until values.length()).map { values.getDouble(it).toFloat() },
        bypass = o.optBoolean("bypass", false),      // may be absent
        mix = o.optDouble("mix", 1.0).toFloat(),
    )
}

private fun clipFromJson(o: JSONObject) = Clip(
    id = o.getInt("id"),
    take = o.getString("take"),
    srcStart = o.getLong("srcStart"),
    length = o.getLong("length"),
    start = o.getLong("start"),
    fadeIn = o.optLong("fadeIn", 0),
    fadeOut = o.optLong("fadeOut", 0),
)

private fun trackFromJson(o: JSONObject): Track {
    val clips = o.getJSONArray("clips")
    val effects = o.getJSONArray("effects")
    return Track(
        id = o.getInt("id"),
        name = o.getString("name"),
        gain = o.getDouble("gain").toFloat(),
        pan = o.getDouble("pan").toFloat(),
        mute = o.getBoolean("mute"),
        solo = o.getBoolean("solo"),
        armed = o.getBoolean("armed"),
        // Manifests without an inputDevice stored only a mode; map it onto
        // "any interface".
        inputDevice = InputDevice.parse(
            o.optString(
                "inputDevice",
                if (o.getInt("inputMode") == INPUT_NONE) DEV_NONE else DEV_ANY_USB,
            ),
        ),
        inputMode = o.getInt("inputMode"),
        monitor = o.optBoolean("monitor", false),  // may be absent
        clips = (0 until clips.length()).map { clipFromJson(clips.getJSONObject(it)) },
        effects = (0 until effects.length()).map { effectFromJson(effects.getJSONObject(it)) },
    )
}

fun songFromJson(o: JSONObject): Song {
    val tracks = o.getJSONArray("tracks")
    return Song(
        name = o.getString("name"),
        sampleRate = o.getInt("sampleRate"),
        tracks = (0 until tracks.length()).map { trackFromJson(tracks.getJSONObject(it)) },
        master = trackFromJson(o.getJSONObject("master")),
        nextTrackId = o.getInt("nextTrackId"),
        nextClipId = o.getInt("nextClipId"),
        tempoBpm = o.optDouble("tempoBpm", 120.0).toFloat(),
        beatsPerBar = o.optInt("beatsPerBar", 4),
        loopStart = o.optLong("loopStart", 0),
        loopEnd = o.optLong("loopEnd", 0),
        loopEnabled = o.optBoolean("loopEnabled", false),
    )
}
