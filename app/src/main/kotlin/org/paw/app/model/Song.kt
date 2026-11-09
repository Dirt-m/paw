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
    // A pattern clip plays Song.patterns[pattern] on a beat track instead of a
    // take, and take is "". srcStart is then the offset into the pattern, which
    // repeats for as long as the clip runs, so trim, split and duplicate keep
    // working on the same arithmetic as audio clips.
    val pattern: Int? = null,
) {
    val end: Long get() = start + length
    val isPattern: Boolean get() = pattern != null
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

const val TRACK_AUDIO = 0
const val TRACK_BEAT = 1

/** A slice of a take that a beat track plays from memory. */
data class Sample(
    val id: Int,
    val name: String,
    val take: String,
    val srcStart: Long,
    val length: Long,
    val gain: Float = 1f,  // linear
)

/** Quarter-note resolution of pattern positions. */
const val TICKS_PER_QUARTER = 960

data class Hit(
    val sample: Int,  // Sample.id on the track the pattern plays on
    val tick: Int,    // from the pattern start
    val velocity: Float = 1f,
)

/**
 * A pattern has its own time signature: [beats] of [unit] (4 = quarter,
 * 8 = eighth, 16 = sixteenth) per bar, felt in [grouping] (2+2+3 for 7/8), with
 * [stepsPerBeat] grid steps per beat. Positions are ticks, so a pattern keeps
 * its shape when the tempo changes. [track] is the beat track that owns it:
 * its hits name samples in that track's kit.
 */
data class Pattern(
    val id: Int,
    val name: String,
    val track: Int = 0,
    val beats: Int = 4,
    val unit: Int = 4,
    val grouping: List<Int> = listOf(4),
    val bars: Int = 1,
    val stepsPerBeat: Int = 4,
    val hits: List<Hit> = emptyList(),
) {
    val ticksPerBeat: Int get() = TICKS_PER_QUARTER * 4 / unit
    val ticksPerBar: Int get() = ticksPerBeat * beats
    val lengthTicks: Int get() = ticksPerBar * bars
    val ticksPerStep: Int get() = ticksPerBeat / stepsPerBeat
    val steps: Int get() = lengthTicks / ticksPerStep
}

/** The default grouping for a beat count: twos and threes, threes last. */
fun defaultGrouping(beats: Int): List<Int> = when {
    beats <= 4 -> listOf(beats)
    beats % 2 == 0 && beats % 3 != 0 -> List(beats / 2) { 2 }
    beats % 3 == 0 -> List(beats / 3) { 3 }
    else -> List((beats - 3) / 2) { 2 } + 3
}

/** Every way to split [beats] into twos and threes, the default first. */
fun groupingsFor(beats: Int): List<List<Int>> {
    if (beats < 5) return listOf(listOf(beats))
    val out = mutableListOf<List<Int>>()
    fun walk(left: Int, acc: List<Int>) {
        if (left == 0) { out += acc; return }
        if (left >= 2) walk(left - 2, acc + 2)
        if (left >= 3) walk(left - 3, acc + 3)
    }
    walk(beats, emptyList())
    val d = defaultGrouping(beats)
    return listOf(d) + out.filter { it != d }.take(5)
}

data class Track(
    val id: Int,
    val name: String,
    val kind: Int = TRACK_AUDIO,
    val samples: List<Sample> = emptyList(),  // beat tracks only
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
    val patterns: List<Pattern> = emptyList(),
    val nextPatternId: Int = 1,
    val nextSampleId: Int = 1,
    val tempoBpm: Float = 120f,     // quarter notes per minute; frame 0 = beat 1
    val beatsPerBar: Int = 4,
    val beatUnit: Int = 4,          // 4 or 8: what one counted beat is
    val loopStart: Long = 0,        // frames; loop plays [loopStart, loopEnd)
    val loopEnd: Long = 0,
    val loopEnabled: Boolean = false,
    val clickEnabled: Boolean = false,
    val countInBars: Int = 0,       // bars of click before a take from stop
    val micBoostDb: Float = 16f,    // phone mic only; interfaces stay at unity
    // Where each take's first clip was placed (take path to timeline frame),
    // so a take can go back where it was recorded after its clips are gone.
    val takeOrigins: Map<String, Long> = emptyMap(),
) {
    val endFrame: Long
        get() = tracks.maxOfOrNull { t -> t.clips.maxOfOrNull { it.end } ?: 0L } ?: 0L

    fun pattern(id: Int?): Pattern? = patterns.find { it.id == id }

    val framesPerTick: Double get() = sampleRate * 60.0 / tempoBpm / TICKS_PER_QUARTER

    fun framesOf(ticks: Int): Long = Math.round(ticks * framesPerTick)

    fun track(id: Int): Track? = if (id == MASTER_TRACK_ID) master else tracks.find { it.id == id }

    fun withTrack(updated: Track): Song =
        if (updated.id == MASTER_TRACK_ID) copy(master = updated)
        else copy(tracks = tracks.map { if (it.id == updated.id) updated else it })

    /**
     * This song with pattern clips that lie exactly on top of an identical
     * one removed, keeping the first. Such a stack plays every hit once per
     * copy and nothing on screen shows it; the lane's place button could
     * build one by being tapped where it sat over the clip it had just made.
     */
    fun withoutStackedPatterns(): Song {
        fun Track.cleaned(): Track {
            val seen = HashSet<List<Long?>>()
            val kept = clips.filter {
                !it.isPattern || seen.add(listOf(it.pattern?.toLong(), it.start, it.length, it.srcStart))
            }
            return if (kept.size == clips.size) this else copy(clips = kept)
        }
        val cleaned = tracks.map { it.cleaned() }
        return if (cleaned == tracks) this else copy(tracks = cleaned)
    }

    /**
     * This snapshot with everything undo and redo must leave alone taken from
     * [current]. These are settings and live switches, not edits: the click,
     * the count-in, the mic boost, each track's arm and monitor switches,
     * whether the loop plays, and the take origins (a record of history that
     * stays true whatever the timeline looks like). A loop left on over a
     * region too short to play is switched off.
     */
    fun keepingSettingsOf(current: Song): Song {
        val live = (current.tracks + current.master).associateBy { it.id }
        fun Track.keep() = live[id]?.let { copy(armed = it.armed, monitor = it.monitor) } ?: this
        return copy(
            clickEnabled = current.clickEnabled,
            countInBars = current.countInBars,
            micBoostDb = current.micBoostDb,
            tracks = tracks.map { it.keep() },
            master = master.keep(),
            loopEnabled = current.loopEnabled && loopEnd - loopStart >= sampleRate,
            takeOrigins = current.takeOrigins,
        )
    }
}

// ---- musical time ----
// The tempo counts quarter notes, so an eighth lasts the same in 4/4 and in
// 7/8. A song beat is one beatUnit note and a bar is beatsPerBar of them;
// timeline frame 0 is the downbeat of bar 1.

/** Bar, beat (both 1-based) and hundredths of a beat. */
data class BarBeat(val bar: Int, val beat: Int, val sub: Int)

/** What a drag or a placement snaps to. Off leaves frames alone. */
enum class SnapGrid { Bar, Beat, Off }

val Song.framesPerSongBeat: Double
    get() = sampleRate * 60.0 / tempoBpm * 4.0 / beatUnit

val Song.framesPerBar: Double
    get() = framesPerSongBeat * beatsPerBar

/** First frame of the 0-based bar [barIndex]. */
fun Song.barStartFrame(barIndex: Int): Long = Math.round(barIndex * framesPerBar)

fun Song.barBeatAt(frame: Long): BarBeat {
    val beats = frame.coerceAtLeast(0) / framesPerSongBeat
    // Guards against a frame a hair under a beat line reading as sub 100.
    val whole = Math.floor(beats + 1e-9).toLong()
    val sub = ((beats - whole) * 100).toInt().coerceIn(0, 99)
    val bpb = beatsPerBar.coerceAtLeast(1)
    return BarBeat(bar = (whole / bpb).toInt() + 1, beat = (whole % bpb).toInt() + 1, sub = sub)
}

private fun Song.gridFrames(grid: SnapGrid): Double? = when (grid) {
    SnapGrid.Bar -> framesPerBar
    SnapGrid.Beat -> framesPerSongBeat
    SnapGrid.Off -> null
}

/** [frame] moved to the nearest line of [grid]. */
fun Song.snapFrame(frame: Long, grid: SnapGrid): Long {
    val unit = gridFrames(grid) ?: return frame
    return Math.round(Math.round(frame / unit) * unit)
}

/** A distance rounded to whole units of [grid], for drags that keep a
 *  clip's offset from the grid. */
fun Song.snapDelta(delta: Long, grid: SnapGrid): Long {
    val unit = gridFrames(grid) ?: return delta
    return Math.round(Math.round(delta / unit) * unit)
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
    pattern?.let { put("pattern", it) }
}

private fun Sample.toJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("take", take)
    put("srcStart", srcStart)
    put("length", length)
    put("gain", gain.toDouble())
}

private fun Pattern.toJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("track", track)
    put("beats", beats)
    put("unit", unit)
    put("grouping", JSONArray(grouping))
    put("bars", bars)
    put("stepsPerBeat", stepsPerBeat)
    put("hits", JSONArray(hits.map {
        JSONObject().apply {
            put("sample", it.sample)
            put("tick", it.tick)
            put("velocity", it.velocity.toDouble())
        }
    }))
}

private fun Track.toJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    if (kind != TRACK_AUDIO) put("kind", kind)
    if (samples.isNotEmpty()) put("samples", JSONArray(samples.map { it.toJson() }))
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
    put("patterns", JSONArray(patterns.map { it.toJson() }))
    put("nextPatternId", nextPatternId)
    put("nextSampleId", nextSampleId)
    put("tempoBpm", tempoBpm.toDouble())
    put("beatsPerBar", beatsPerBar)
    put("beatUnit", beatUnit)
    put("loopStart", loopStart)
    put("loopEnd", loopEnd)
    put("loopEnabled", loopEnabled)
    put("clickEnabled", clickEnabled)
    put("countInBars", countInBars)
    put("micBoostDb", micBoostDb.toDouble())
    put("takeOrigins", JSONObject().apply { for ((take, frame) in takeOrigins) put(take, frame) })
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
    pattern = if (o.has("pattern")) o.getInt("pattern") else null,
)

private fun sampleFromJson(o: JSONObject) = Sample(
    id = o.getInt("id"),
    name = o.getString("name"),
    take = o.getString("take"),
    srcStart = o.getLong("srcStart"),
    length = o.getLong("length"),
    gain = o.optDouble("gain", 1.0).toFloat(),
)

/** [track] is -1 when the manifest predates pattern owners; songFromJson
 *  then picks one. */
private fun patternFromJson(o: JSONObject): Pattern {
    val grouping = o.getJSONArray("grouping")
    val hits = o.getJSONArray("hits")
    return Pattern(
        id = o.getInt("id"),
        name = o.getString("name"),
        track = o.optInt("track", -1),
        beats = o.getInt("beats"),
        unit = o.getInt("unit"),
        grouping = (0 until grouping.length()).map { grouping.getInt(it) },
        bars = o.getInt("bars"),
        stepsPerBeat = o.getInt("stepsPerBeat"),
        hits = (0 until hits.length()).map {
            val h = hits.getJSONObject(it)
            Hit(h.getInt("sample"), h.getInt("tick"), h.optDouble("velocity", 1.0).toFloat())
        },
    )
}

private fun trackFromJson(o: JSONObject): Track {
    val clips = o.getJSONArray("clips")
    val effects = o.getJSONArray("effects")
    val samples = o.optJSONArray("samples")
    return Track(
        id = o.getInt("id"),
        name = o.getString("name"),
        kind = o.optInt("kind", TRACK_AUDIO),
        samples = samples?.let { a -> (0 until a.length()).map { sampleFromJson(a.getJSONObject(it)) } }
            ?: emptyList(),
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

/**
 * The owner of a pattern saved before patterns had one: the beat track whose
 * kit holds the samples its hits name, else the first beat track with a clip
 * of it, else the first beat track, else 0.
 */
private fun ownerFor(p: Pattern, tracks: List<Track>): Int {
    val beat = tracks.filter { it.kind == TRACK_BEAT }
    val used = p.hits.mapTo(HashSet()) { it.sample }
    val kits = beat.filter { t -> t.samples.any { it.id in used } }
    if (kits.size == 1) return kits[0].id
    beat.firstOrNull { t -> t.clips.any { it.pattern == p.id } }?.let { return it.id }
    return beat.firstOrNull()?.id ?: 0
}

/** [legacy] supplies what a manifest from before a field existed lacks. */
fun songFromJson(o: JSONObject, legacy: Song = Song(name = "")): Song {
    val tracks = o.getJSONArray("tracks")
    val patterns = o.optJSONArray("patterns")
    val origins = o.optJSONObject("takeOrigins")
    val trackList = (0 until tracks.length()).map { trackFromJson(tracks.getJSONObject(it)) }
    return Song(
        name = o.getString("name"),
        sampleRate = o.getInt("sampleRate"),
        tracks = trackList,
        master = trackFromJson(o.getJSONObject("master")),
        nextTrackId = o.getInt("nextTrackId"),
        nextClipId = o.getInt("nextClipId"),
        patterns = patterns?.let { a ->
            (0 until a.length()).map { i ->
                val p = patternFromJson(a.getJSONObject(i))
                if (p.track >= 0) p else p.copy(track = ownerFor(p, trackList))
            }
        } ?: emptyList(),
        nextPatternId = o.optInt("nextPatternId", 1),
        nextSampleId = o.optInt("nextSampleId", 1),
        tempoBpm = o.optDouble("tempoBpm", 120.0).toFloat(),
        beatsPerBar = o.optInt("beatsPerBar", 4),
        beatUnit = o.optInt("beatUnit", 4).let { if (it == 8) 8 else 4 },
        loopStart = o.optLong("loopStart", 0),
        loopEnd = o.optLong("loopEnd", 0),
        loopEnabled = o.optBoolean("loopEnabled", false),
        clickEnabled = o.optBoolean("clickEnabled", legacy.clickEnabled),
        countInBars = o.optInt("countInBars", legacy.countInBars),
        micBoostDb = o.optDouble("micBoostDb", legacy.micBoostDb.toDouble()).toFloat(),
        takeOrigins = origins?.keys()?.asSequence()?.associateWith { origins.getLong(it) } ?: emptyMap(),
    )
}
