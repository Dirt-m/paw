package org.paw.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import java.io.File
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import kotlin.reflect.KProperty
import kotlin.time.Duration
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.paw.app.model.Clip
import org.paw.app.model.Hit
import org.paw.app.model.Pattern
import org.paw.app.model.Sample
import org.paw.app.model.TRACK_AUDIO
import org.paw.app.model.TRACK_BEAT
import org.paw.app.model.barStartFrame
import org.paw.app.model.framesPerBar
import org.paw.app.model.framesPerSongBeat
import org.paw.app.model.defaultGrouping
import org.paw.app.model.Effect
import org.paw.app.model.INPUT_CH1
import org.paw.app.model.INPUT_NONE
import org.paw.app.model.InputDevice
import org.paw.app.model.InputOption
import org.paw.app.model.MASTER_TRACK_ID
import org.paw.app.model.MIN_CLIP_FRAMES
import org.paw.app.model.PluginInfo
import org.paw.app.model.ProjectStore
import org.paw.app.model.SAMPLE_RATE
import org.paw.app.model.Song
import org.paw.app.model.Track
import org.paw.app.model.clampFades
import org.paw.app.model.movedTo
import org.paw.app.model.parseEffectCatalog
import org.paw.app.model.trimmedLeft
import org.paw.app.model.trimmedRight
import org.paw.app.model.importTake
import org.paw.app.model.withFades

/** Transport poll interval; also the reconciliation loop's cadence. */
private const val POLL_MS = 33L

/**
 * A snapshot state that writes through to [AppPrefs] on every set: the UI can
 * read it in composition, and the setting is on disk before the frame is.
 */
private class PrefState<T>(initial: T, private val persist: (T) -> Unit) {
    private val state = mutableStateOf(initial)

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        state.value = value
        persist(value)
    }
}

data class PeakData(val data: ByteArray, val bucketFrames: Int, val frames: Long)

/** One take WAV in the project's audio dir (take pool browser). */
data class TakeEntry(
    val rel: String,      // "audio/<name>", as clips reference it
    val name: String,
    val frames: Long,
    val usedBy: Int,      // live clip references (0 = not on the timeline)
    val file: File,
)

// Owns the open song: every edit rewrites the immutable model, saves the
// manifest, and pushes the affected slice to the native engine. Recorded takes
// on disk are never modified or deleted by any path in this class.
//
// The engine is a process singleton, so this instance's generation id tags
// every song-scoped call it makes; once another song opens, the engine drops
// anything still arriving from here. Its coroutines live on a scope created in
// start() and cancelled in stop(), so nothing can outlive the controller.
class SongController(
    private val context: Context,
    private val store: ProjectStore,
    val projectDir: File,
    initial: Song,
) {
    private companion object {
        /** Process-wide monotonic song generation; 0 is "no song open". */
        val songGenerations = AtomicLong(0)

        /** Longest slice a sample holds in memory: 10 s, about 3.8 MB. */
        const val MAX_SAMPLE_FRAMES = 10L * SAMPLE_RATE

        /** Bank and hit pushes run one at a time, so a slow bank load can
         *  never land after the hits that were computed for the next one. */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val samplerIo = Dispatchers.IO.limitedParallelism(1)
    }

    private val gen: Long = songGenerations.incrementAndGet()
    private var scope: CoroutineScope? = null
    private val prefs = AppPrefs.of(context)

    var song: Song by mutableStateOf(initial.withoutStackedPatterns())
        private set

    // Engine state is split by how fast it moves, so a meter twitch can't
    // recompose the timeline: playhead and capture length are scalars, meters a
    // map keyed by track (one entry, one meter bar), the rest one value on
    // change.
    var playheadFrame: Long by mutableLongStateOf(0L)
        private set
    var recFrames: Long by mutableLongStateOf(0L)
        private set
    var masterPeak: Float by mutableFloatStateOf(0f)
        private set
    val meters: SnapshotStateMap<Int, Float> = mutableStateMapOf()
    var status: EngineStatus by mutableStateOf(EngineStatus())
        private set
    var message: String? by mutableStateOf(null)
    var selectedClip: Pair<Int, Int>? by mutableStateOf(null)  // trackId to clipId

    /** The track the panels act on: a track id or MASTER_TRACK_ID. Selecting
     *  a clip selects its track too. */
    var selectedTrack: Int? by mutableStateOf(null)
    var exporting: Boolean by mutableStateOf(false)
    var lastExport: File? by mutableStateOf(null)

    // ---- undo / redo ----
    // Snapshots of the immutable Song; cheap because every edit is a copy()
    // anyway. Continuous gestures push once at gesture start (beginGesture),
    // discrete edits push themselves.

    private val undoStack = ArrayDeque<Song>()
    private val redoStack = ArrayDeque<Song>()
    var canUndo: Boolean by mutableStateOf(false)
        private set
    var canRedo: Boolean by mutableStateOf(false)
        private set

    private fun pushUndo(prev: Song = song) {
        undoStack.addLast(prev)
        while (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        canUndo = true
        canRedo = false
    }

    /** Call once when a slider/drag gesture starts; the gesture then edits freely. */
    fun beginGesture() = pushUndo()

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(song)
        applySnapshot(prev)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(song)
        applySnapshot(next)
    }

    private fun applySnapshot(snapshot: Song) {
        val prev = song
        val s = snapshot.keepingSettingsOf(prev)
        // Tracks present in the engine but absent in the snapshot must go.
        for (t in prev.tracks) if (s.track(t.id) == null) Engine.removeTrack(gen, t.id)
        song = s
        saveFlow.value = s
        for (t in s.tracks) syncDiff(prev.track(t.id), t)
        syncDiff(prev.master, s.master)
        syncMetronome()
        syncLoop()
        syncSampler()
        selectedClip = selectedClip?.takeIf { (tid, cid) ->
            s.track(tid)?.clips?.any { it.id == cid } == true
        }
        selectedTrack = selectedTrack?.takeIf { s.track(it) != null }
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    // ---- device routing ----
    // The engine opens whichever device the armed tracks reference. A track on
    // any other device reads as "no input" until that device is the open one.

    /** Connected input/output endpoints, refreshed by the activity. */
    var inputOptions: List<InputOption> by mutableStateOf(emptyList())
    var outputOptions: List<DeviceOption> by mutableStateOf(emptyList())

    /** The input stream that is actually open. */
    var currentInput: InputDevice by mutableStateOf(InputDevice.None)
        private set
    val inputIsUsb: Boolean get() = currentInput.isUsb

    /** Output route preference: "" = auto, else an OutputDevice key. */
    var outputDevice: String by PrefState(prefs.outputDevice) { prefs.outputDevice = it }
        private set

    /**
     * The route the open output stream actually landed on. AUTO resolves to a
     * concrete one, and latency compensation is per route.
     */
    var activeOutputRoute: String by mutableStateOf("")
        private set

    fun setOutputRoute(key: String) {
        outputDevice = key
    }

    // ---- per-song settings ----
    // Click, count-in and the phone mic boost live in the song, so every song
    // keeps its own.

    /** Mic boost in dB; the phone mic is far too quiet without it. */
    val micBoostDb: Float get() = song.micBoostDb

    val clickEnabled: Boolean get() = song.clickEnabled

    val countInBars: Int get() = song.countInBars

    private fun saveSettings(s: Song) {
        song = s
        saveFlow.value = s
    }

    fun setClick(enabled: Boolean) {
        saveSettings(song.copy(clickEnabled = enabled))
        syncMetronome()
    }

    /** 0 (off), 1 or 2 bars of click before a take from stop. */
    fun setCountIn(bars: Int) {
        saveSettings(song.copy(countInBars = bars.coerceIn(0, 2)))
    }

    // ---- app-wide display settings ----
    // Read through here so the views have one source.

    /** Meter peak-hold: null = off, Duration.INFINITE = until the transport
     *  starts. */
    val meterHold: Duration? get() = prefs.meterHold

    /** Playhead follow style: false = jump at the view edge, true = scroll
     *  continuously under a mid-view playhead. */
    val followSmooth: Boolean get() = prefs.followSmooth

    /** Tempo in quarter notes per minute. Undoable, one step per call. */
    fun setTempo(bpm: Float, beatsPerBar: Int = song.beatsPerBar) {
        val tempo = bpm.coerceIn(30f, 300f)
        val bpb = beatsPerBar.coerceIn(1, 12)
        if (tempo == song.tempoBpm && bpb == song.beatsPerBar) return
        pushUndo()
        // Pattern clips live in musical time: their frames scale with the
        // tempo so they keep their bars. Audio clips stay where they are.
        val ratio = song.tempoBpm / tempo
        fun Long.scaled() = Math.round(this * ratio.toDouble())
        val tracks = if (ratio == 1f) song.tracks else song.tracks.map { t ->
            if (t.clips.none { it.isPattern }) t
            else t.copy(clips = t.clips.map {
                if (!it.isPattern) it
                else it.copy(start = it.start.scaled(), srcStart = it.srcStart.scaled(), length = it.length.scaled())
            })
        }
        saveSettings(song.copy(tempoBpm = tempo, beatsPerBar = bpb, tracks = tracks))
        syncMetronome()
        syncSampler()
    }

    /** The song's time signature: [beatsPerBar] (1 to 12) beats of [beatUnit]
     *  (4 or 8). The tempo still counts quarters, so nothing on the timeline
     *  moves. Undoable. */
    fun setMeter(beatsPerBar: Int, beatUnit: Int) {
        val bpb = beatsPerBar.coerceIn(1, 12)
        val unit = if (beatUnit == 8) 8 else 4
        if (bpb == song.beatsPerBar && unit == song.beatUnit) return
        pushUndo()
        saveSettings(song.copy(beatsPerBar = bpb, beatUnit = unit))
        syncMetronome()
    }

    /** The engine's click counts plain beats per minute, so it gets the song
     *  beat's rate: in /8 that is twice the quarter-note tempo. */
    private fun clickBpm(tempo: Float): Float = tempo * song.beatUnit / 4f

    private fun syncMetronome() {
        Engine.setMetronome(gen, clickEnabled, clickBpm(song.tempoBpm), song.beatsPerBar)
    }

    /** One song beat in frames, rounded the way the engine's click rounds
     *  it: the grid the click, the count-in and the transport clock run on. */
    val framesPerBeat: Long
        get() = Math.round(song.framesPerSongBeat).coerceAtLeast(1)

    // ---- loop region ----

    // The region is an edit and undoable; whether the loop plays is a switch
    // and is not. Minimum loop length: 1 s, comfortably larger than any
    // callback burst.

    private fun loopValid(start: Long, end: Long) = end - start >= song.sampleRate

    /** Turns looping on or off over the current region. Not an undo step. */
    fun setLoopEnabled(on: Boolean) {
        if (on && !loopValid(song.loopStart, song.loopEnd)) {
            message = "Loop needs at least one second"
            return
        }
        if (on == song.loopEnabled) return
        saveSettings(song.copy(loopEnabled = on))
        syncLoop()
    }

    /** Sets the loop region and turns the loop on. A region shorter than a
     *  second is refused. Undoable. */
    fun setLoopRegion(start: Long, end: Long) {
        val s0 = start.coerceAtLeast(0)
        val e0 = end.coerceAtLeast(0)
        if (!loopValid(s0, e0)) {
            message = "Loop needs at least one second"
            return
        }
        if (s0 == song.loopStart && e0 == song.loopEnd && song.loopEnabled) return
        pushUndo()
        saveSettings(song.copy(loopStart = s0, loopEnd = e0, loopEnabled = true))
        syncLoop()
    }

    /** Clears the region and turns the loop off. Undoable. */
    fun clearLoop() {
        if (song.loopStart == 0L && song.loopEnd == 0L && !song.loopEnabled) return
        pushUndo()
        saveSettings(song.copy(loopStart = 0, loopEnd = 0, loopEnabled = false))
        syncLoop()
    }

    /** The older all-in-one call: a new region goes through [setLoopRegion]
     *  (or [clearLoop] when it is too short), then [enabled] through
     *  [setLoopEnabled]. */
    fun setLoop(start: Long, end: Long, enabled: Boolean) {
        val s0 = start.coerceAtLeast(0)
        val e0 = end.coerceAtLeast(0)
        if (s0 != song.loopStart || e0 != song.loopEnd) {
            if (!loopValid(s0, e0)) {
                clearLoop()
                return
            }
            setLoopRegion(s0, e0)
        }
        setLoopEnabled(enabled)
    }

    private fun syncLoop() {
        Engine.setLoop(gen, song.loopEnabled, song.loopStart, song.loopEnd)
    }

    fun setMicBoost(db: Float) {
        saveSettings(song.copy(micBoostDb = db))
        if (currentInput == InputDevice.Mic) Engine.setInputGain(10f.pow(db / 20f))
    }

    fun deviceMatches(track: Track): Boolean = track.inputMode != INPUT_NONE &&
        when (val device = track.inputDevice) {
            InputDevice.None -> false
            InputDevice.Mic -> currentInput == InputDevice.Mic
            InputDevice.AnyUsb -> currentInput is InputDevice.Usb
            is InputDevice.Usb -> currentInput == device
        }

    /** The channel mode the engine should use for this track right now. */
    fun effectiveMode(track: Track): Int = when {
        !deviceMatches(track) -> INPUT_NONE
        track.inputDevice == InputDevice.Mic -> INPUT_CH1  // mic is one mono stream
        else -> track.inputMode
    }

    private val audioDir = store.audioDir(projectDir)
    private val peakCache = HashMap<String, PeakData>()
    private val peakMutex = Mutex()
    // Reused across polls: the engine fills these in place, so a tick
    // allocates nothing on either side of the JNI boundary.
    private val stateBuf = LongArray(ENGINE_STATE_SLOTS)
    private val meterIds = IntArray(METER_SLOTS)
    private val meterPeaks = FloatArray(METER_SLOTS)
    // Conflated: rapid edits (fader drags) collapse to the latest snapshot,
    // written off the UI thread.
    private val saveFlow = MutableStateFlow<Song?>(null)

    var catalog: List<PluginInfo> by mutableStateOf(emptyList())
        private set

    // ---- lifecycle ----

    fun start() {
        // The engine outlives song switches (rotation depends on that), so
        // openSong wipes everything song-scoped and adopts this generation;
        // after that the engine ignores the previous song's controller. Without
        // the wipe its tracks keep playing through still-open readers, even
        // from deleted takes.
        Engine.openSong(gen)
        syncAllToEngine()
        syncMetronome()
        syncLoop()
        // The controller's own scope, so everything launched here dies with
        // stop(). Supervisor job, so one failed child (a save that throws)
        // can't take the poll down with it.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        this.scope = scope
        // openSong emptied the engine's sample bank, so whatever was pushed
        // before (by an earlier start of this same song) is gone. The sampler
        // loads on this scope, so it has to exist first.
        syncedBank = null
        syncedHitsKey = null
        syncSampler()
        scope.launch {
            while (isActive) {
                pollEngine()
                delay(POLL_MS)
            }
        }
        scope.launch(Dispatchers.IO) {
            saveFlow.filterNotNull().collectLatest {
                try {
                    store.save(projectDir, it)
                } catch (e: Exception) {
                    message = "Save failed: ${e.message ?: "I/O error"}"
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            // An aborted count-in can leave a header-only take file; the
            // engine deletes those at finalize, this sweeps survivors. Guarded
            // three ways (unreferenced, zero frames, header-sized) so a corrupt
            // take that merely fails to parse is never touched.
            val used = buildSet {
                for (t in song.tracks) {
                    for (c in t.clips) add(c.take)
                    for (s in t.samples) add(s.take)
                }
            }
            audioDir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.forEach { f ->
                if ("audio/" + f.name !in used && f.length() <= 128 &&
                    Engine.wavInfo(f.absolutePath)[0] == 0L
                ) f.delete()
            }
        }
        scope.launch(Dispatchers.IO) {
            val dir = File(context.getExternalFilesDir(null), "effects").apply { mkdirs() }
            val json = Engine.scanEffects(dir.absolutePath)
            withContext(Dispatchers.Main) {
                // The scan must not outlive its song, or its chains land in
                // the next song's engine: this runs only while the scope is
                // alive (stop() cancels it), and every setChain carries the
                // generation.
                catalog = parseEffectCatalog(json)
                // Chains synced before the scan landed resolved against an
                // empty catalog; rebuild them now that plugins exist.
                for (t in song.tracks) syncChain(t)
                syncChain(song.master)
            }
        }
    }

    /** One tick of the transport poll: a binary snapshot, then the narrowest
     *  state writes that describe it. Anything unchanged is left alone.
     *
     *  Also the only path that collects finished recordings, however a take
     *  ended (punch-out, stop, or the interface unplugged mid-song): the record
     *  thread finalizes it and parks the result, and this tick places it on the
     *  timeline. Teardown drains the same result through stop(). */
    private fun pollEngine() {
        Engine.engineState(stateBuf)
        if (stateBuf[ST_REC_RESULT_READY] != 0L) placeRecordedTakes()
        playheadFrame = stateBuf[ST_PLAYHEAD]
        recFrames = stateBuf[ST_REC_FRAMES]
        previewFrame = stateBuf[ST_PREVIEW_POS]
        val next = EngineStatus(
            transport = stateBuf[ST_TRANSPORT].toInt(),
            countdown = stateBuf[ST_COUNTDOWN],
            outputOpen = stateBuf[ST_OUTPUT_OPEN] != 0L,
            inputOpen = stateBuf[ST_INPUT_OPEN] != 0L,
            inChannels = stateBuf[ST_IN_CHANNELS].toInt(),
            inDeviceId = stateBuf[ST_IN_DEVICE_ID].toInt(),
            deviceLost = stateBuf[ST_DEVICE_LOST] != 0L,
            recording = stateBuf[ST_RECORDING] != 0L,
            recPending = stateBuf[ST_REC_PENDING] != 0L,
            recStartFrame = stateBuf[ST_REC_START_FRAME],
            droppedCalls = stateBuf[ST_DROPPED_CALLS],
            missingTakes = stateBuf[ST_MISSING_TAKES],
        )
        // A take missing off disk plays as silence; announce it once, when
        // the engine first fails to open one.
        if (status.missingTakes == 0L && next.missingTakes > 0L) {
            message = "${next.missingTakes} recording(s) missing on disk"
        }
        if (next != status) status = next

        val n = Engine.engineMeters(meterIds, meterPeaks)
        for (i in 0 until n) {
            val id = meterIds[i]
            val peak = meterPeaks[i]
            if (id == MASTER_TRACK_ID) {
                if (masterPeak != peak) masterPeak = peak
            } else if (meters[id] != peak) {
                meters[id] = peak
            }
        }
    }

    fun stop() {
        // Kills every job this controller started (poll, saves, take sweep,
        // effects scan) so none of them can touch the engine or this instance's
        // state after the next song opens.
        scope?.cancel()
        scope = null
        // The one place that waits: the poll is gone, so the take has to be on
        // the timeline before the final save below. Idempotent, and the engine
        // bounds the wait so a lost device can't hang teardown.
        if (Engine.finishRecordSync(false)) placeRecordedTakes()
        Engine.transportStopAll()
        try {
            store.save(projectDir, song)  // flush any pending edit synchronously
        } catch (e: Exception) {
            // Last-chance flush; the conflated saves already ran, and
            // throwing here would take the activity down with it.
        }
    }

    fun openStreams(
        inputDeviceId: Int,
        outputDeviceId: Int,
        input: InputDevice,
        outputRoute: String,
    ) {
        val isMic = input == InputDevice.Mic
        val err = Engine.openStreams(inputDeviceId, outputDeviceId, SAMPLE_RATE, isMic)
        if (err.isNotEmpty()) {
            message = err
            return
        }
        activeOutputRoute = outputRoute
        Engine.setInputGain(if (isMic) 10f.pow(micBoostDb / 20f) else 1f)
        currentInput = input
        // Effective input modes depend on the open device; resync them all.
        for (t in song.tracks) syncParams(t)
    }

    // ---- engine sync ----

    private fun absPath(rel: String) = File(projectDir, rel).absolutePath

    private fun syncClips(track: Track) {
        // Pattern clips play through the sampler, not the disk thread.
        val clips = track.clips.filterNot { it.isPattern }
        Engine.syncClips(
            gen,
            track.id,
            clips.map { absPath(it.take) }.toTypedArray(),
            clips.map { it.srcStart }.toLongArray(),
            clips.map { it.length }.toLongArray(),
            clips.map { it.start }.toLongArray(),
            clips.map { it.fadeIn }.toLongArray(),
            clips.map { it.fadeOut }.toLongArray(),
        )
    }

    private fun syncParams(track: Track) {
        Engine.setTrackParams(
            gen, track.id, track.gain, track.pan, track.mute, track.solo, track.armed,
            effectiveMode(track), track.monitor,
        )
    }

    private fun syncChain(track: Track) {
        Engine.setChain(
            gen,
            track.id,
            song.sampleRate,
            track.effects.map { it.pluginId }.toTypedArray(),
            track.effects.map { it.values.toFloatArray() }.toTypedArray(),
            track.effects.map { it.bypass }.toBooleanArray(),
            track.effects.map { it.mix }.toFloatArray(),
        )
    }

    private fun syncAllToEngine() {
        for (t in song.tracks) {
            syncParams(t)
            syncClips(t)
            syncChain(t)
        }
        syncParams(song.master)
        syncChain(song.master)
    }

    /** Pushes only the slices that differ. A clip sync re-primes the ring
     *  (playback gaps) and a chain rebuild resets reverb tails, so a blanket
     *  re-push is audible. */
    private fun syncDiff(prev: Track?, next: Track) {
        if (prev == null || !paramsMatch(prev, next)) syncParams(next)
        if (next.id != MASTER_TRACK_ID && prev?.clips != next.clips) syncClips(next)
        if (prev?.effects != next.effects) syncChain(next)
    }

    /** Exactly the fields setTrackParams pushes, including the routing that
     *  effectiveMode() derives, not the saved device it derives it from. */
    private fun paramsMatch(a: Track, b: Track): Boolean =
        a.gain == b.gain && a.pan == b.pan && a.mute == b.mute && a.solo == b.solo &&
            a.armed == b.armed && a.monitor == b.monitor &&
            effectiveMode(a) == effectiveMode(b)

    private fun commit(updated: Song, vararg resync: Track) {
        val prev = song
        song = updated
        saveFlow.value = updated
        syncSampler()
        for (t in resync) {
            // Every clip sync while playing is an audible ring re-prime, so
            // push clips only when they changed.
            if (t.id != MASTER_TRACK_ID && prev.track(t.id)?.clips != t.clips) syncClips(t)
            syncParams(t)
        }
    }

    // ---- live gesture previews ----
    // A slider drag pushes only the engine parameter; the model, the save and
    // the undo entry land once, on release. None of these touch song, saveFlow
    // or the undo stacks.

    fun previewTrackGain(trackId: Int, gain: Float) = previewParams(trackId, gain = gain)

    fun previewTrackPan(trackId: Int, pan: Float) = previewParams(trackId, pan = pan)

    private fun previewParams(trackId: Int, gain: Float? = null, pan: Float? = null) {
        val t = song.track(trackId) ?: return
        Engine.setTrackParams(
            gen, t.id, gain ?: t.gain, pan ?: t.pan, t.mute, t.solo, t.armed,
            effectiveMode(t), t.monitor,
        )
    }

    fun previewEffectValue(trackId: Int, effectIndex: Int, portIndex: Int, value: Float) {
        Engine.setEffectParam(gen, trackId, effectIndex, portIndex, value)
    }

    fun previewEffectMix(trackId: Int, index: Int, bypass: Boolean, mix: Float) {
        Engine.setEffectUnitState(gen, trackId, index, bypass, mix)
    }

    fun previewTempo(bpm: Float) {
        Engine.setMetronome(gen, clickEnabled, clickBpm(bpm.coerceIn(30f, 300f)), song.beatsPerBar)
    }

    fun previewMicBoost(db: Float) {
        if (currentInput == InputDevice.Mic) Engine.setInputGain(10f.pow(db / 20f))
    }

    // ---- transport ----
    //
    // These issue intent and let the engine decide under its own lock. None of
    // them branch on `status`: it is a copy of the engine's state up to a poll
    // interval old, and read-modify-writing it across that window races.

    fun playPause() {
        // The song and a pattern loop never play over each other: the engine
        // only pauses a preview under a running transport, and would bring it
        // back by itself when the song stops.
        stopPreview()
        // The answer is the state the engine settled on; adopting it saves
        // the button a poll interval of looking wrong.
        val transport = Engine.transportPlayPause()
        if (transport != status.transport) status = status.copy(transport = transport)
    }

    fun stopAll() {
        // Non-blocking: the engine punches out a live take and the poll places
        // it a few tens of ms later, once the record thread has finalized it.
        Engine.transportStopAll()
    }

    fun seek(frame: Long) {
        // The engine refuses this while a take is being captured.
        Engine.transportSeek(frame.coerceAtLeast(0))
    }

    /** Record button: starts capture (from stop or as punch-in), or punches
     *  out. Which one it is, is the engine's call. */
    fun toggleRecord() {
        stopPreview()
        // Model-side preconditions, and only when this can't be a punch-out: a
        // track disarmed mid-take must not block stopping it. The engine
        // enforces the real rule either way; these just word it better.
        if (!status.recording && !status.recPending) {
            val armed = song.tracks.filter { it.armed }
            if (armed.isEmpty()) {
                message = "Arm a track to record"
                return
            }
            if (armed.none { effectiveMode(it) != INPUT_NONE }) {
                message = "Armed track's input device isn't connected"
                return
            }
        }
        // Prepared speculatively: the punch-out branch opens no file, since
        // takes are created inside the locked decision that starts capture.
        val base = "take_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val countIn = if (countInBars > 0) countInBars * song.beatsPerBar * framesPerBeat else 0L
        // Count-in applies only if the engine finds the transport stopped; a
        // punch-in mid-play must not freeze the transport under the musician.
        val result = Engine.toggleRecordSession(gen, audioDir.absolutePath, base, countIn)
        if (result == REC_TOGGLE_ERROR) message = Engine.lastRecordError()
    }

    /** Places a finished recording's takes on the timeline. The engine hands
     *  each result out once (the fetch consumes its ready flag), so a call
     *  with nothing fresh parked is a no-op and takes can't double up. */
    private fun placeRecordedTakes() {
        val result = Engine.recordResultLongs()
        if (result.size < RR_TAKES) return
        val paths = Engine.recordResultPaths()
        val startFrame = result[RR_START_FRAME]
        val dropped = result[RR_DROPPED]
        val count = result[RR_COUNT].toInt()
        if (result[RR_IO_OK] == 0L) message = "Recording I/O error, check the recordings"
        if (dropped > 0) message = "Recording dropped $dropped frames"

        // The route's own report, not one global constant: a take monitored
        // over Bluetooth arrives hundreds of ms late, not the interface's ten.
        val reported = Engine.routeLatencyFrames()
        Latency.learn(context, activeOutputRoute, reported)
        val latency = Latency.frames(context, activeOutputRoute, reported)
        val before = song
        var s = song
        val touched = mutableListOf<Track>()
        for (i in 0 until minOf(count, paths.size)) {
            val trackId = result[RR_TAKES + 2 * i].toInt()
            val frames = result[RR_TAKES + 2 * i + 1]
            if (frames <= 0 || startFrame < 0) continue
            val track = s.track(trackId) ?: continue
            // Latency compensation: audio heard at playhead P arrives in the
            // recording ~latency frames late; shift the clip left to match.
            var start = startFrame - latency
            var srcStart = 0L
            if (start < 0) {
                srcStart = -start
                start = 0
            }
            val length = frames - srcStart
            if (length < MIN_CLIP_FRAMES) continue
            val clip = Clip(
                id = s.nextClipId,
                take = "audio/" + File(paths[i]).name,
                srcStart = srcStart,
                length = length,
                start = start,
            )
            val updated = track.copy(clips = track.clips + clip)
            s = s.withTrack(updated).copy(
                nextClipId = s.nextClipId + 1,
                takeOrigins = s.takeOrigins + (clip.take to clip.start),
            )
            touched += updated
        }
        // A take is an undo step of its own, so the next undo takes its clips
        // off the timeline (the files stay on the recordings screen) instead
        // of reverting an earlier edit and dropping the take with it.
        if (touched.isNotEmpty()) pushUndo(before)
        commit(s, *touched.toTypedArray())
        // Starting the take turned the engine loop off (capture is linear).
        syncLoop()
    }

    // ---- track ops ----

    fun addTrack() {
        pushUndo()
        val id = song.nextTrackId
        // On the input that is there right now, like a new song's tracks.
        val input = inputOptions.firstOrNull()?.device ?: InputDevice.Mic
        val track = Track(id = id, name = "Track $id", inputDevice = input, inputMode = INPUT_CH1)
        commit(song.copy(tracks = song.tracks + track, nextTrackId = id + 1), track)
    }

    fun renameTrack(trackId: Int, name: String) {
        val track = song.track(trackId) ?: return
        val trimmed = name.trim().take(24)
        if (trimmed.isEmpty() || trimmed == track.name) return
        pushUndo()
        commit(song.withTrack(track.copy(name = trimmed)))
    }

    fun removeTrack(trackId: Int) {
        if (trackId == MASTER_TRACK_ID) return
        pushUndo()
        // Only the clip references die with the track; the recordings on disk
        // are untouched, and undo brings the track back whole.
        commit(song.copy(tracks = song.tracks.filterNot { it.id == trackId }))
        Engine.removeTrack(gen, trackId)
        if (selectedClip?.first == trackId) selectedClip = null
        if (selectedTrack == trackId) selectedTrack = null
    }

    /**
     * undoable=false for continuous gestures; the UI calls beginGesture()
     * once at the gesture start instead.
     */
    fun updateTrack(trackId: Int, undoable: Boolean = true, transform: (Track) -> Track) {
        val track = song.track(trackId) ?: return
        val updated = transform(track)
        if (undoable) pushUndo()
        commit(song.withTrack(updated), updated)
    }

    // ---- arm and monitor ----
    // Live switches, not edits: neither is an undo step, and undo leaves them
    // as they are.

    /**
     * Arms or disarms a track. The engine opens one input device at a time,
     * so arming a track on another device than the tracks already armed
     * disarms those and says so. Channels of the same device can be armed
     * together.
     */
    /**
     * Points an audio track at an input. An armed track keeps the one device
     * rule: other armed tracks on a different device are disarmed, as when
     * arming.
     */
    fun setInput(trackId: Int, device: InputDevice, mode: Int) {
        val track = song.track(trackId) ?: return
        if (track.inputDevice == device && track.inputMode == mode) return
        updateTrack(trackId) { it.copy(inputDevice = device, inputMode = mode) }
        if (track.armed) {
            setArmed(trackId, false)
            setArmed(trackId, true)
        }
    }

    fun setArmed(trackId: Int, on: Boolean) {
        val track = song.track(trackId) ?: return
        if (track.kind != TRACK_AUDIO || track.armed == on) return
        val disarmed = if (!on || !recordsFrom(track)) emptyList() else song.tracks.filter {
            it.id != trackId && it.armed && recordsFrom(it) && !sameInput(it.inputDevice, track.inputDevice)
        }
        val updated = listOf(track.copy(armed = on)) + disarmed.map { it.copy(armed = false) }
        commit(updated.fold(song) { s, t -> s.withTrack(t) }, *updated.toTypedArray())
        if (disarmed.isNotEmpty()) {
            message = "${disarmed.joinToString(", ") { it.name }} disarmed: " +
                "${track.name} records from ${inputLabel(track.inputDevice)}"
        }
    }

    /** Software monitoring for an armed track. Not an undo step. */
    fun setMonitor(trackId: Int, on: Boolean) {
        val track = song.track(trackId) ?: return
        if (track.kind != TRACK_AUDIO || track.monitor == on) return
        val updated = track.copy(monitor = on)
        commit(song.withTrack(updated), updated)
    }

    private fun recordsFrom(t: Track) = t.inputMode != INPUT_NONE && t.inputDevice != InputDevice.None

    /** "Any interface" is whichever interface is connected first, the same
     *  rule MainActivity uses to choose the device it opens. */
    private fun resolveInput(d: InputDevice): InputDevice =
        if (d == InputDevice.AnyUsb) inputOptions.firstOrNull { it.device.isUsb }?.device ?: d else d

    private fun sameInput(a: InputDevice, b: InputDevice): Boolean {
        val ra = resolveInput(a)
        val rb = resolveInput(b)
        // With no interface connected an unresolved "any interface" may still
        // turn out to be the named one; keep both armed.
        if (ra.isUsb && rb.isUsb && (ra == InputDevice.AnyUsb || rb == InputDevice.AnyUsb)) return true
        return ra == rb
    }

    private fun inputLabel(d: InputDevice): String = when (val r = resolveInput(d)) {
        InputDevice.Mic -> "the phone mic"
        is InputDevice.Usb -> r.name
        else -> "the interface"
    }

    // ---- clip ops (all reference arithmetic; takes are immutable) ----

    private fun selectClip(trackId: Int, clipId: Int) {
        selectedClip = trackId to clipId
        selectedTrack = trackId
    }

    private fun takeFrames(rel: String): Long = peakCache[rel]?.frames
        ?: Engine.wavInfo(absPath(rel))[0]

    /** Cache-only take length for drag previews: never touches disk, so it is
     *  safe in the draw path. Unknown (cache still filling) means no cap; the
     *  commit re-clamps against the real length anyway. */
    fun takeFramesCached(rel: String): Long = peakCache[rel]?.frames ?: Long.MAX_VALUE

    fun moveClip(trackId: Int, clipId: Int, newStart: Long) {
        editClip(trackId, clipId) { it.movedTo(newStart) }
    }

    fun trimClipLeft(trackId: Int, clipId: Int, newStart: Long) {
        editClip(trackId, clipId) { it.trimmedLeft(newStart) }
    }

    fun trimClipRight(trackId: Int, clipId: Int, newEnd: Long) {
        editClip(trackId, clipId) {
            it.trimmedRight(newEnd, if (it.isPattern) Long.MAX_VALUE else takeFrames(it.take))
        }
    }

    fun setClipFades(trackId: Int, clipId: Int, fadeIn: Long, fadeOut: Long) {
        editClip(trackId, clipId) { it.withFades(fadeIn, fadeOut) }
    }

    fun splitClip(trackId: Int, clipId: Int, atFrame: Long) {
        val track = song.track(trackId) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val offset = atFrame - clip.start
        if (offset < MIN_CLIP_FRAMES || clip.length - offset < MIN_CLIP_FRAMES) return
        pushUndo()
        // The cut edges get no fade, so a split renders identically to the
        // original.
        val left = clip.copy(length = offset, fadeOut = 0).clampFades()
        val right = Clip(
            id = song.nextClipId,
            take = clip.take,
            srcStart = clip.srcStart + offset,
            length = clip.length - offset,
            start = clip.start + offset,
            fadeIn = 0,
            fadeOut = clip.fadeOut.coerceAtMost(clip.length - offset),
            pattern = clip.pattern,
        )
        val updated = track.copy(
            clips = track.clips.flatMap { if (it.id == clipId) listOf(left, right) else listOf(it) },
        )
        commit(song.withTrack(updated).copy(nextClipId = song.nextClipId + 1), updated)
    }

    fun duplicateClip(trackId: Int, clipId: Int) {
        val track = song.track(trackId) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        pushUndo()
        val copy = clip.copy(id = song.nextClipId, start = clip.end)
        val updated = track.copy(clips = track.clips + copy)
        commit(song.withTrack(updated).copy(nextClipId = song.nextClipId + 1), updated)
        selectClip(trackId, copy.id)
    }

    /** Moves a clip to the neighboring track (dir -1 = up, +1 = down),
     *  keeping its timeline position. */
    fun moveClipToTrack(trackId: Int, clipId: Int, dir: Int) {
        val idx = song.tracks.indexOfFirst { it.id == trackId }
        val dest = song.tracks.getOrNull(idx + dir) ?: return
        val src = song.tracks.getOrNull(idx) ?: return
        val clip = src.clips.find { it.id == clipId } ?: return
        if (src.kind != dest.kind) {
            message = if (clip.isPattern) "Patterns go on beat tracks" else "Beat tracks hold patterns"
            return
        }
        pushUndo()
        val newSrc = src.copy(clips = src.clips.filterNot { it.id == clipId })
        val newDest = dest.copy(clips = dest.clips + clip)
        commit(song.withTrack(newSrc).withTrack(newDest), newSrc, newDest)
        selectClip(dest.id, clip.id)
    }

    fun deleteClip(trackId: Int, clipId: Int) {
        val track = song.track(trackId) ?: return
        pushUndo()
        // Only the clip reference goes (undo brings it back); the recording
        // on disk is untouched and stays on the recordings screen.
        val updated = track.copy(clips = track.clips.filterNot { it.id == clipId })
        commit(song.withTrack(updated), updated)
        if (selectedClip == trackId to clipId) selectedClip = null
    }

    // ---- recordings ----

    /** Every recording WAV in the project's audio dir, with usage counts. */
    fun allTakes(): List<TakeEntry> {
        val used = HashMap<String, Int>()
        for (t in song.tracks) {
            for (c in t.clips) if (!c.isPattern) used.merge(c.take, 1, Int::plus)
            for (s in t.samples) used.merge(s.take, 1, Int::plus)
        }
        return (audioDir.listFiles { f -> f.isFile && f.name.endsWith(".wav") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { f ->
                val rel = "audio/" + f.name
                TakeEntry(
                    rel = rel,
                    name = f.name,
                    frames = Engine.wavInfo(f.absolutePath)[0],
                    usedBy = used[rel] ?: 0,
                    file = f,
                )
            }
    }

    /** Deletes a recording from disk, the one user-initiated exception to
     *  "recordings are immutable". Refused while any clip references it, so a
     *  delete can never silence the timeline; the screen confirms first. */
    fun deleteRecording(entry: TakeEntry): Boolean {
        // A take being written has no clip yet and would read as unused.
        if (status.recording || status.recPending) {
            message = "Recordings can be deleted once recording stops"
            return false
        }
        val used = song.tracks.any { t -> t.clips.any { it.take == entry.rel } }
        if (used) {
            message = "Recording is on the timeline. Remove its clips first"
            return false
        }
        if (song.tracks.any { t -> t.samples.any { it.take == entry.rel } }) {
            message = "Recording is a sample. Remove the sample first"
            return false
        }
        if (!entry.file.delete()) {
            message = "Couldn't delete ${entry.name}"
            return false
        }
        return true
    }

    fun addTakeToTrack(trackId: Int, takeRel: String, atFrame: Long) {
        val track = song.track(trackId) ?: return
        if (track.kind == TRACK_BEAT) {
            message = "Beat tracks hold patterns"
            return
        }
        val frames = takeFrames(takeRel)
        if (frames <= 0) {
            message = "Can't read recording"
            return
        }
        pushUndo()
        val clip = Clip(
            id = song.nextClipId,
            take = takeRel,
            srcStart = 0,
            length = frames,
            start = atFrame.coerceAtLeast(0),
        )
        val updated = track.copy(clips = track.clips + clip)
        // An imported take's first placement is its origin.
        val origins = if (takeRel in song.takeOrigins) song.takeOrigins else song.takeOrigins + (takeRel to clip.start)
        commit(song.withTrack(updated).copy(nextClipId = song.nextClipId + 1, takeOrigins = origins), updated)
        selectClip(trackId, clip.id)
    }

    /** Where [takeRel] was first placed on the timeline, or null when that
     *  is not known (takes from before origins were kept). */
    fun originalStartOf(takeRel: String): Long? = song.takeOrigins[takeRel]

    // ---- takes stacked on one track ----
    // Later in a track's clip list plays on top where clips overlap.

    /** The older clips on [trackId] that [clipId] overlaps, the one right
     *  under it first. */
    fun clipsUnder(trackId: Int, clipId: Int): List<Clip> {
        val track = song.track(trackId) ?: return emptyList()
        if (track.kind != TRACK_AUDIO) return emptyList()
        val idx = track.clips.indexOfFirst { it.id == clipId }
        if (idx < 0) return emptyList()
        val clip = track.clips[idx]
        return track.clips.subList(0, idx).filter { it.start < clip.end && clip.start < it.end }.reversed()
    }

    /** Puts [clipId] on top of whatever it overlaps. Undoable. */
    fun bringClipToTop(trackId: Int, clipId: Int) {
        val track = song.track(trackId) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        if (track.clips.last() == clip) return
        pushUndo()
        val updated = track.copy(clips = track.clips.filterNot { it.id == clipId } + clip)
        commit(song.withTrack(updated), updated)
    }

    private fun editClip(trackId: Int, clipId: Int, transform: (Clip) -> Clip) {
        val track = song.track(trackId) ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val transformed = transform(clip)
        if (transformed == clip) return
        pushUndo()  // clip gestures commit once, on release
        val updated = track.copy(
            clips = track.clips.map { if (it.id == clipId) transformed else it },
        )
        commit(song.withTrack(updated), updated)
    }

    // ---- effects ----

    fun addEffect(trackId: Int, plugin: PluginInfo) {
        val track = song.track(trackId) ?: return
        pushUndo()
        val updated = track.copy(
            effects = track.effects + Effect(plugin.id, plugin.ports.map { it.def }),
        )
        commit(song.withTrack(updated))
        syncChain(updated)
    }

    fun removeEffect(trackId: Int, index: Int) {
        val track = song.track(trackId) ?: return
        pushUndo()
        val updated = track.copy(effects = track.effects.filterIndexed { i, _ -> i != index })
        commit(song.withTrack(updated))
        syncChain(updated)
    }

    /** Move a chain slot one step; rebuilds the chain (brief state reset). */
    fun moveEffect(trackId: Int, index: Int, delta: Int) {
        val track = song.track(trackId) ?: return
        val to = index + delta
        if (index !in track.effects.indices || to !in track.effects.indices) return
        pushUndo()
        val effects = track.effects.toMutableList()
        effects[index] = effects[to].also { effects[to] = effects[index] }
        val updated = track.copy(effects = effects)
        commit(song.withTrack(updated))
        syncChain(updated)
    }

    /** Live bypass / wet-dry: writes the running chain slot, no rebuild.
     *  undoable=false for the mix slider (beginGesture covers it). */
    fun setEffectState(trackId: Int, index: Int, bypass: Boolean, mix: Float,
                       undoable: Boolean = true) {
        val track = song.track(trackId) ?: return
        if (index !in track.effects.indices) return
        if (undoable) pushUndo()
        val updated = track.copy(
            effects = track.effects.mapIndexed { i, e ->
                if (i == index) e.copy(bypass = bypass, mix = mix) else e
            },
        )
        song = song.withTrack(updated)
        saveFlow.value = song
        Engine.setEffectUnitState(gen, trackId, index, bypass, mix)
    }

    /** Live tweak: writes the running chain's control directly, no rebuild. */
    fun setEffectValue(trackId: Int, effectIndex: Int, portIndex: Int, value: Float) {
        val track = song.track(trackId) ?: return
        val effect = track.effects.getOrNull(effectIndex) ?: return
        val values = effect.values.toMutableList()
        if (portIndex !in values.indices) return
        values[portIndex] = value
        val updated = track.copy(
            effects = track.effects.mapIndexed { i, e ->
                if (i == effectIndex) e.copy(values = values) else e
            },
        )
        song = song.withTrack(updated)
        saveFlow.value = song
        Engine.setEffectParam(gen, trackId, effectIndex, portIndex, value)
    }

    fun plugin(id: String): PluginInfo? = catalog.find { it.id == id }

    // ---- waveform peaks ----

    suspend fun peaksFor(takeRel: String): PeakData? {
        peakMutex.withLock { peakCache[takeRel] }?.let { return it }
        return withContext(Dispatchers.IO) {
            val abs = absPath(takeRel)
            val info = Engine.wavInfo(abs)
            if (info[0] <= 0L) return@withContext null
            val data = Engine.computePeaks(abs, 256)
            val peaks = PeakData(data, 256, info[0])
            peakMutex.withLock { peakCache[takeRel] = peaks }
            peaks
        }
    }

    // ---- sampler ----
    //
    // Beat tracks hold a kit of samples; patterns are step grids over a kit,
    // and pattern clips place them on a beat track. The engine sees one bank
    // of every beat track's samples and one sorted list of every hit the
    // pattern clips expand to, both rebuilt here whenever the song changes.

    /** Frames into the pattern preview, or -1 when none plays. */
    var previewFrame: Long by mutableLongStateOf(-1L)
        private set

    /** The pattern looping in the preview, or null. */
    var previewing: Int? by mutableStateOf(null)
        private set
    private var previewTrack: Int = 0

    private class HitArrays(val frames: LongArray, val samples: IntArray, val velocities: FloatArray)

    /** Every beat track's samples in bank order, as (trackId, sample). */
    private fun bankOf(s: Song): List<Pair<Int, Sample>> =
        s.tracks.filter { it.kind == TRACK_BEAT }.flatMap { t -> t.samples.map { t.id to it } }

    private fun bankIndex(bank: List<Pair<Int, Sample>>, trackId: Int, sampleId: Int): Int =
        bank.indexOfFirst { it.first == trackId && it.second.id == sampleId }

    /** The pattern's hits at frame offsets from its start, inside its length. */
    private fun patternHits(
        s: Song,
        p: Pattern,
        trackId: Int,
        bank: List<Pair<Int, Sample>>,
        out: (offset: Long, index: Int, velocity: Float) -> Unit,
    ) {
        for (h in p.hits) {
            if (h.tick >= p.lengthTicks) continue
            val idx = bankIndex(bank, trackId, h.sample)
            if (idx < 0) continue
            out(s.framesOf(h.tick), idx, h.velocity * bank[idx].second.gain)
        }
    }

    /** Every pattern clip, repeated over its length, as timeline hits. */
    private fun hitsOf(s: Song, bank: List<Pair<Int, Sample>>): HitArrays {
        val frames = ArrayList<Long>()
        val samples = ArrayList<Int>()
        val vels = ArrayList<Float>()
        for (t in s.tracks) {
            if (t.kind != TRACK_BEAT) continue
            for (c in t.clips) {
                val p = s.pattern(c.pattern) ?: continue
                val len = s.framesOf(p.lengthTicks)
                if (len <= 0) continue
                patternHits(s, p, t.id, bank) { off, idx, vel ->
                    var pos = Math.floorMod(off - c.srcStart, len)
                    while (pos < c.length) {
                        frames += c.start + pos
                        samples += idx
                        vels += vel
                        pos += len
                    }
                }
            }
        }
        return HitArrays(frames.toLongArray(), samples.toIntArray(), vels.toFloatArray())
    }

    private var syncedBank: List<Triple<Int, String, Pair<Long, Long>>>? = null
    private var syncedHitsKey: Any? = null

    /** Pushes the bank when the set of slices changed, then the hits and the
     *  preview when anything they depend on changed. */
    private fun syncSampler() {
        // Nothing is pushed without a scope, so nothing may be marked pushed:
        // start() syncs again once it has one.
        val scope = scope ?: return
        val s = song
        // An undo can take away the pattern that is looping.
        previewing?.let { if (s.pattern(it) == null) stopPreview() }
        val bank = bankOf(s)
        val bankKey = bank.map { (tid, smp) -> Triple(tid, smp.take, smp.srcStart to smp.length) }
        val hitsKey = listOf(s.tempoBpm, s.patterns, s.tracks.filter { it.kind == TRACK_BEAT }.map { it.clips to it.samples })
        val loadBank = bankKey != syncedBank
        if (!loadBank && hitsKey == syncedHitsKey) return
        syncedBank = bankKey
        syncedHitsKey = hitsKey
        val hits = hitsOf(s, bank)
        val preview = previewing?.let { previewArrays(s, it, previewTrack, bank) }
        val previewLen = previewing?.let { id -> s.pattern(id)?.let { s.framesOf(it.lengthTicks) } } ?: 0L
        scope.launch(samplerIo) {
            if (loadBank) {
                val loaded = Engine.setSamples(
                    gen,
                    IntArray(bank.size) { bank[it].first },
                    Array(bank.size) { absPath(bank[it].second.take) },
                    LongArray(bank.size) { bank[it].second.srcStart },
                    LongArray(bank.size) { bank[it].second.length },
                )
                if (loaded < bank.size) withContext(Dispatchers.Main) {
                    message = "${bank.size - loaded} sample(s) could not be read"
                }
            }
            Engine.setHits(gen, hits.frames, hits.samples, hits.velocities)
            if (preview != null) {
                Engine.setPreview(gen, preview.frames, preview.samples, preview.velocities, previewLen)
            }
        }
    }

    private fun previewArrays(s: Song, patternId: Int, trackId: Int, bank: List<Pair<Int, Sample>>): HitArrays? {
        val p = s.pattern(patternId) ?: return null
        val frames = ArrayList<Long>()
        val samples = ArrayList<Int>()
        val vels = ArrayList<Float>()
        patternHits(s, p, trackId, bank) { off, idx, vel ->
            frames += off
            samples += idx
            vels += vel
        }
        return HitArrays(frames.toLongArray(), samples.toIntArray(), vels.toFloatArray())
    }

    /** Loops [patternId] through [trackId]'s kit with the transport stopped. */
    fun startPreview(trackId: Int, patternId: Int) {
        // Stopping the transport for a preview would end a take mid-performance.
        if (status.recording || status.recPending) {
            message = "Stop recording to play the pattern on its own"
            return
        }
        if (status.transport != TRANSPORT_STOPPED) stopAll()
        previewing = patternId
        previewTrack = trackId
        val s = song
        val p = s.pattern(patternId) ?: return
        val a = previewArrays(s, patternId, trackId, bankOf(s)) ?: return
        val len = s.framesOf(p.lengthTicks)
        val scope = scope ?: return
        scope.launch(samplerIo) { Engine.setPreview(gen, a.frames, a.samples, a.velocities, len) }
    }

    fun stopPreview() {
        if (previewing == null) return
        previewing = null
        val scope = scope ?: return
        scope.launch(samplerIo) { Engine.setPreview(gen, LongArray(0), IntArray(0), FloatArray(0), 0) }
    }

    /** Plays one of [trackId]'s samples now; the finger-drumming path. */
    fun triggerSample(trackId: Int, sampleId: Int, velocity: Float = 1f) {
        val bank = bankOf(song)
        val idx = bankIndex(bank, trackId, sampleId)
        if (idx >= 0) Engine.triggerSample(idx, velocity * bank[idx].second.gain)
    }

    fun addBeatTrack(): Int {
        pushUndo()
        val id = song.nextTrackId
        val names = song.tracks.mapTo(HashSet()) { it.name }
        val n = generateSequence(song.tracks.count { it.kind == TRACK_BEAT } + 1) { it + 1 }
            .first { "Beats $it" !in names }
        val track = Track(
            id = id, name = "Beats $n", kind = TRACK_BEAT,
            inputDevice = InputDevice.None, inputMode = INPUT_NONE,
        )
        commit(song.copy(tracks = song.tracks + track, nextTrackId = id + 1), track)
        return id
    }

    /** The first beat track, or null when the song has none. */
    fun firstBeatTrack(): Int? = song.tracks.firstOrNull { it.kind == TRACK_BEAT }?.id

    /**
     * Turns an audio clip's slice into a sample named [name] on the beat
     * track [targetTrackId], or on a new beat track when that is null. Trim
     * and split on the timeline are how a sample gets cut. One undo step,
     * the new track included. Returns the new sample's id.
     */
    fun sampleFromClip(trackId: Int, clipId: Int, targetTrackId: Int?, name: String): Int? {
        val src = song.track(trackId) ?: return null
        val clip = src.clips.find { it.id == clipId } ?: return null
        if (clip.isPattern) return null
        if (targetTrackId != null && song.track(targetTrackId)?.kind != TRACK_BEAT) {
            message = "Samples go on beat tracks"
            return null
        }
        val beatId = targetTrackId ?: addBeatTrack()
        // addBeatTrack already pushed this step's undo entry.
        if (targetTrackId != null) pushUndo()
        val beat = song.track(beatId) ?: return null
        val sample = Sample(
            id = song.nextSampleId,
            name = name.trim().take(24).ifEmpty { "${src.name} ${src.clips.sortedBy { it.start }.indexOf(clip) + 1}" },
            take = clip.take,
            srcStart = clip.srcStart,
            length = clip.length.coerceAtMost(MAX_SAMPLE_FRAMES),
        )
        val updated = beat.copy(samples = beat.samples + sample)
        commit(song.withTrack(updated).copy(nextSampleId = song.nextSampleId + 1))
        message = "Added ${sample.name} to ${beat.name}"
        return sample.id
    }

    fun renameSample(trackId: Int, sampleId: Int, name: String) {
        val t = song.track(trackId) ?: return
        val sample = t.samples.find { it.id == sampleId } ?: return
        val trimmed = name.trim().take(24)
        if (trimmed.isEmpty() || trimmed == sample.name) return
        pushUndo()
        commit(song.withTrack(t.copy(samples = t.samples.map { if (it.id == sampleId) it.copy(name = trimmed) else it })))
    }

    fun removeSample(trackId: Int, sampleId: Int) {
        val t = song.track(trackId) ?: return
        pushUndo()
        // Its hits stay in the patterns and come back with an undo; with the
        // sample gone they are silent.
        commit(song.withTrack(t.copy(samples = t.samples.filterNot { it.id == sampleId })))
    }

    /**
     * The engine has no live per-sample gain: a sample's level is folded
     * into its hits' velocities. So a level drag calls beginGesture() once,
     * then this with undoable=false for every move (each one re-pushes the
     * hit list), and the gesture is one undo step.
     */
    fun setSampleGain(trackId: Int, sampleId: Int, gain: Float, undoable: Boolean = true) {
        val t = song.track(trackId) ?: return
        if (undoable) pushUndo()
        commit(song.withTrack(t.copy(samples = t.samples.map { if (it.id == sampleId) it.copy(gain = gain) else it })))
    }

    // ---- patterns ----

    /** The patterns owned by [trackId], in creation order. */
    fun patternsFor(trackId: Int): List<Pattern> = song.patterns.filter { it.track == trackId }

    /** A new pattern owned by [trackId], empty or a copy of [from]. Nothing
     *  goes on the timeline. Returns its id. */
    fun newPattern(trackId: Int, from: Pattern? = null): Int {
        pushUndo()
        val id = song.nextPatternId
        val p = (from ?: Pattern(id = id, name = "")).copy(id = id, name = "Pattern $id", track = trackId)
        commit(song.copy(patterns = song.patterns + p, nextPatternId = id + 1))
        return id
    }

    fun renamePattern(patternId: Int, name: String) {
        val trimmed = name.trim().take(24)
        if (trimmed.isEmpty()) return
        editPattern(patternId) { it.copy(name = trimmed) }
    }

    /** Deletes a pattern no clip uses. Refused, with a message, while any
     *  clip still plays it. */
    fun deletePattern(patternId: Int): Boolean {
        if (song.pattern(patternId) == null) return false
        if (song.tracks.any { t -> t.clips.any { it.pattern == patternId } }) {
            message = "Pattern is on the timeline. Remove its clips first"
            return false
        }
        if (previewing == patternId) stopPreview()
        pushUndo()
        commit(song.copy(patterns = song.patterns.filterNot { it.id == patternId }))
        return true
    }

    /** The 0-based song bar [placePattern] uses: the bar at or after the
     *  playhead. */
    fun nextBarIndex(): Int {
        val i = Math.floor(playheadFrame / song.framesPerBar).toInt()
        return if (song.barStartFrame(i) < playheadFrame) i + 1 else i
    }

    /**
     * The 0-based bar at or after the playhead where one clip of [patternId]
     * fits on [trackId] without overlapping a clip already there. Placing
     * twice in a row then puts the second clip after the first instead of on
     * top of it, where every hit would play twice.
     */
    fun freeBarFor(trackId: Int, patternId: Int): Int {
        val s = song
        val clips = s.track(trackId)?.clips.orEmpty()
        val p = s.pattern(patternId) ?: return nextBarIndex()
        val length = s.framesOf(p.lengthTicks).coerceAtLeast(MIN_CLIP_FRAMES)
        var bar = nextBarIndex()
        while (true) {
            val start = s.barStartFrame(bar)
            val blocking = clips.filter { it.start < start + length && it.end > start }
            if (blocking.isEmpty()) return bar
            // On to the first bar that starts at or after the last overlap ends.
            val end = blocking.maxOf { it.end }
            var next = Math.floor(end / s.framesPerBar).toInt()
            if (s.barStartFrame(next) < end) next++
            bar = maxOf(next, bar + 1)
        }
    }

    /** Puts a clip of [patternId], one pattern long, on [trackId] at the
     *  0-based song bar [barIndex]. The pattern must belong to the track. */
    fun placePatternAt(trackId: Int, patternId: Int, barIndex: Int) =
        placePatternAt(trackId, patternId, barIndex, undoable = true)

    private fun placePatternAt(trackId: Int, patternId: Int, barIndex: Int, undoable: Boolean) {
        val t = song.track(trackId) ?: return
        val p = song.pattern(patternId) ?: return
        if (p.track != trackId) {
            message = "${p.name} belongs to another beat track"
            return
        }
        if (undoable) pushUndo()
        val clip = Clip(
            id = song.nextClipId, take = "", srcStart = 0,
            length = song.framesOf(p.lengthTicks).coerceAtLeast(MIN_CLIP_FRAMES),
            start = song.barStartFrame(barIndex.coerceAtLeast(0)), pattern = patternId,
        )
        val updated = t.copy(clips = t.clips + clip)
        commit(song.withTrack(updated).copy(nextClipId = song.nextClipId + 1), updated)
        selectClip(trackId, clip.id)
    }

    /** Puts a clip of [patternId] on [trackId], one pattern long, at the
     *  first free bar at or after the playhead. */
    fun placePattern(trackId: Int, patternId: Int, undoable: Boolean = true) =
        placePatternAt(trackId, patternId, freeBarFor(trackId, patternId), undoable)

    /** Makes the pattern clip [clipId] play [patternId] instead, keeping its
     *  place and length. The pattern must belong to the track. Undoable. */
    fun setClipPattern(trackId: Int, clipId: Int, patternId: Int) {
        val p = song.pattern(patternId) ?: return
        if (p.track != trackId) {
            message = "${p.name} belongs to another beat track"
            return
        }
        editClip(trackId, clipId) { if (it.isPattern) it.copy(pattern = patternId) else it }
    }

    /**
     * Copies the pattern clip [clipId] plays into a new pattern named
     * "<name> 2" (or the next free number; a variation of a variation counts
     * on from the original's name) and gives it to that clip alone,
     * so it can change without touching the other clips. One undo step.
     * Returns the new pattern's id, or -1 when [clipId] is no pattern clip.
     */
    fun makeVariation(trackId: Int, clipId: Int): Int {
        val track = song.track(trackId) ?: return -1
        val clip = track.clips.find { it.id == clipId } ?: return -1
        val src = song.pattern(clip.pattern) ?: return -1
        val names = song.patterns.mapTo(HashSet()) { it.name }
        // A variation of "Groove 2" is "Groove 3", not "Groove 2 2".
        val base = Regex("^(.+) \\d+$").find(src.name)?.groupValues?.get(1)?.takeIf { it in names } ?: src.name
        var n = 2
        while ("$base $n" in names) n++
        pushUndo()
        val id = song.nextPatternId
        val copy = src.copy(id = id, name = "$base $n".take(24), track = trackId)
        val updated = track.copy(clips = track.clips.map { if (it.id == clipId) it.copy(pattern = id) else it })
        commit(
            song.withTrack(updated).copy(patterns = song.patterns + copy, nextPatternId = id + 1),
            updated,
        )
        return id
    }

    /** How many times [clip]'s pattern plays over it, a partial pass counted
     *  as one. 1 for anything that is not a pattern clip. */
    fun patternRepeats(clip: Clip): Int {
        val p = song.pattern(clip.pattern) ?: return 1
        val len = song.framesOf(p.lengthTicks)
        if (len <= 0) return 1
        // A frame of slack for clips whose length was rounded by a tempo change.
        return ((clip.length - 1 + len - 1) / len).toInt().coerceAtLeast(1)
    }

    /** Stretches or shrinks a pattern clip to exactly [repeats] passes of its
     *  pattern. Undoable. */
    fun setPatternRepeats(trackId: Int, clipId: Int, repeats: Int) {
        editClip(trackId, clipId) { c ->
            val p = song.pattern(c.pattern) ?: return@editClip c
            val len = song.framesOf(p.lengthTicks).coerceAtLeast(MIN_CLIP_FRAMES)
            c.copy(length = len * repeats.coerceAtLeast(1)).clampFades()
        }
    }

    /** undoable=false for a gesture that already pushed (beginGesture). */
    fun editPattern(patternId: Int, undoable: Boolean = true, transform: (Pattern) -> Pattern) {
        val p = song.pattern(patternId) ?: return
        val next = transform(p)
        if (next == p) return
        if (undoable) pushUndo()
        commit(song.copy(patterns = song.patterns.map { if (it.id == patternId) next else it }))
    }

    /** Sets or clears the hit for [sampleId] on grid step [step]. */
    fun setStep(patternId: Int, sampleId: Int, step: Int, on: Boolean, undoable: Boolean = true) {
        editPattern(patternId, undoable) { p ->
            // Anything inside the step counts, so a hit left between steps by
            // a change of resolution can still be cleared.
            val rest = p.hits.filterNot { it.sample == sampleId && it.tick / p.ticksPerStep == step }
            p.copy(hits = if (on) rest + Hit(sampleId, step * p.ticksPerStep) else rest)
        }
    }

    /** A new time signature resets the grouping to that beat count's default. */
    fun setPatternMeter(patternId: Int, beats: Int, unit: Int) {
        editPattern(patternId) {
            val b = beats.coerceIn(1, 16)
            it.copy(beats = b, unit = unit, grouping = if (b == it.beats) it.grouping else defaultGrouping(b))
        }
    }

    /**
     * The whole layout of a pattern in one undo step: [beats] (1 to 16) of
     * [unit] (4, 8 or 16) per bar, [bars] (1 to 16), [stepsPerBeat] (1 to 4)
     * and the [grouping] of the beats, which falls back to the default when
     * it does not add up to [beats]. When the unit changes the step keeps its
     * length where it can, so sixteenth steps stay sixteenths: 4 steps of a
     * quarter become 2 of an eighth and 1 of a sixteenth.
     */
    fun setPatternLayout(patternId: Int, beats: Int, unit: Int, bars: Int, stepsPerBeat: Int, grouping: List<Int>) {
        editPattern(patternId) { p ->
            val b = beats.coerceIn(1, 16)
            val u = if (unit in listOf(4, 8, 16)) unit else p.unit
            var steps = stepsPerBeat.coerceIn(1, 4)
            if (u != p.unit && steps * p.unit % u == 0 && steps * p.unit / u in 1..4) steps = steps * p.unit / u
            p.copy(
                beats = b,
                unit = u,
                bars = bars.coerceIn(1, 16),
                stepsPerBeat = steps,
                grouping = if (grouping.sum() == b && grouping.all { it > 0 }) grouping else defaultGrouping(b),
            )
        }
    }

    // ---- import ----

    var importing: Boolean by mutableStateOf(false)
        private set

    /**
     * Copies audio files the user picked (a recorder's card, Downloads, a
     * cloud drive) into the song as recordings. They land on the Recordings
     * screen, ready to place; nothing goes on the timeline by itself. Runs
     * on the controller's scope, so leaving the screen doesn't stop it.
     */
    fun importRecordings(uris: List<android.net.Uri>) {
        val scope = scope ?: return
        if (importing || uris.isEmpty()) return
        importing = true
        scope.launch {
            try {
                var done = 0
                var lastError: String? = null
                withContext(Dispatchers.IO) {
                    for (uri in uris) {
                        try {
                            importTake(context, uri, audioDir)
                            done++
                        } catch (e: Exception) {
                            lastError = e.message ?: "Import failed"
                        }
                    }
                }
                message = when {
                    lastError == null -> if (done == 1) "Imported 1 recording" else "Imported $done recordings"
                    done == 0 -> lastError
                    else -> "Imported $done of ${uris.size}. $lastError"
                }
            } finally {
                importing = false
            }
        }
    }

    // ---- mixdown ----

    suspend fun exportMixdown(): File? {
        if (exporting) return null
        exporting = true
        try {
            return withContext(Dispatchers.IO) {
                Engine.mixdownBegin()
                val bank = bankOf(song)
                val hits = hitsOf(song, bank)
                Engine.mixdownSetSampler(
                    IntArray(bank.size) { bank[it].first },
                    Array(bank.size) { absPath(bank[it].second.take) },
                    LongArray(bank.size) { bank[it].second.srcStart },
                    LongArray(bank.size) { bank[it].second.length },
                    hits.frames, hits.samples, hits.velocities,
                )
                for (t in song.tracks) mixdownAdd(t, isMaster = false)
                mixdownAdd(song.master, isMaster = true)
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val out = File(store.mixdownDir(projectDir), "${song.name}-$stamp.wav")
                val err = Engine.mixdownRun(song.sampleRate, out.absolutePath)
                if (err.isNotEmpty()) {
                    withContext(Dispatchers.Main) { message = err }
                    null
                } else {
                    withContext(Dispatchers.Main) { lastExport = out }
                    out
                }
            }
        } finally {
            exporting = false
        }
    }

    private fun mixdownAdd(track: Track, isMaster: Boolean) {
        val clips = track.clips.filterNot { it.isPattern }
        Engine.mixdownAddTrack(
            track.id,
            isMaster,
            track.gain, track.pan, track.mute, track.solo,
            clips.map { absPath(it.take) }.toTypedArray(),
            clips.map { it.srcStart }.toLongArray(),
            clips.map { it.length }.toLongArray(),
            clips.map { it.start }.toLongArray(),
            clips.map { it.fadeIn }.toLongArray(),
            clips.map { it.fadeOut }.toLongArray(),
            track.effects.map { it.pluginId }.toTypedArray(),
            track.effects.map { it.values.toFloatArray() }.toTypedArray(),
            track.effects.map { it.bypass }.toBooleanArray(),
            track.effects.map { it.mix }.toFloatArray(),
        )
    }
}
