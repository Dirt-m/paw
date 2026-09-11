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
    }

    private val gen: Long = songGenerations.incrementAndGet()
    private var scope: CoroutineScope? = null
    private val prefs = AppPrefs.of(context)

    var song: Song by mutableStateOf(initial)
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

    private fun applySnapshot(s: Song) {
        val prev = song
        // Tracks present in the engine but absent in the snapshot must go.
        for (t in prev.tracks) if (s.track(t.id) == null) Engine.removeTrack(gen, t.id)
        song = s
        saveFlow.value = s
        for (t in s.tracks) syncDiff(prev.track(t.id), t)
        syncDiff(prev.master, s.master)
        syncMetronome()
        syncLoop()
        selectedClip = selectedClip?.takeIf { (tid, cid) ->
            s.track(tid)?.clips?.any { it.id == cid } == true
        }
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

    /** Mic boost in dB; the phone mic is far too quiet without it. */
    var micBoostDb: Float by PrefState(prefs.micBoostDb) { prefs.micBoostDb = it }
        private set

    // ---- metronome (click on/off and count-in are app prefs; tempo is song data) ----

    var clickEnabled: Boolean by PrefState(prefs.clickEnabled) { prefs.clickEnabled = it }
        private set

    var countInBars: Int by PrefState(prefs.countInBars) { prefs.countInBars = it }
        private set

    fun setClick(enabled: Boolean) {
        clickEnabled = enabled
        syncMetronome()
    }

    fun setCountIn(bars: Int) {
        countInBars = bars.coerceIn(0, 4)
    }

    /** Meter peak-hold: null = off, Duration.INFINITE = until the transport
     *  starts. */
    var meterHold: Duration? by PrefState(prefs.meterHold) { prefs.meterHold = it }

    /** Playhead follow style: false = jump at the view edge, true = scroll
     *  continuously under a mid-view playhead. */
    var followSmooth: Boolean by PrefState(prefs.followSmooth) { prefs.followSmooth = it }

    fun setTempo(bpm: Float, beatsPerBar: Int = song.beatsPerBar) {
        val s = song.copy(
            tempoBpm = bpm.coerceIn(30f, 300f),
            beatsPerBar = beatsPerBar.coerceIn(1, 12),
        )
        song = s
        saveFlow.value = s
        syncMetronome()
    }

    private fun syncMetronome() {
        Engine.setMetronome(gen, clickEnabled, song.tempoBpm, song.beatsPerBar)
    }

    /** The beat grid the click, the count-in and the transport clock run on. */
    val framesPerBeat: Long
        get() = (60.0 * song.sampleRate / song.tempoBpm).toLong().coerceAtLeast(1)

    // ---- loop region ----

    /** Minimum loop length: 1 s, comfortably larger than any callback burst. */
    fun setLoop(start: Long, end: Long, enabled: Boolean) {
        val s0 = start.coerceAtLeast(0)
        val e0 = end.coerceAtLeast(0)
        val valid = e0 - s0 >= song.sampleRate
        val s = song.copy(
            loopStart = s0,
            loopEnd = e0,
            loopEnabled = enabled && valid,
        )
        song = s
        saveFlow.value = s
        syncLoop()
    }

    private fun syncLoop() {
        Engine.setLoop(gen, song.loopEnabled, song.loopStart, song.loopEnd)
    }

    fun setMicBoost(db: Float) {
        micBoostDb = db
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
                for (t in song.tracks) for (c in t.clips) add(c.take)
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
        Engine.syncClips(
            gen,
            track.id,
            track.clips.map { absPath(it.take) }.toTypedArray(),
            track.clips.map { it.srcStart }.toLongArray(),
            track.clips.map { it.length }.toLongArray(),
            track.clips.map { it.start }.toLongArray(),
            track.clips.map { it.fadeIn }.toLongArray(),
            track.clips.map { it.fadeOut }.toLongArray(),
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
        Engine.setMetronome(gen, clickEnabled, bpm.coerceIn(30f, 300f), song.beatsPerBar)
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
            s = s.withTrack(updated).copy(nextClipId = s.nextClipId + 1)
            touched += updated
        }
        commit(s, *touched.toTypedArray())
        // Starting the take turned the engine loop off (capture is linear).
        syncLoop()
    }

    // ---- track ops ----

    fun addTrack() {
        pushUndo()
        val id = song.nextTrackId
        val track = Track(id = id, name = "Track $id", inputMode = INPUT_CH1)
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

    // ---- clip ops (all reference arithmetic; takes are immutable) ----

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
        editClip(trackId, clipId) { it.trimmedRight(newEnd, takeFrames(it.take)) }
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
        selectedClip = trackId to copy.id
    }

    /** Moves a clip to the neighboring track (dir -1 = up, +1 = down),
     *  keeping its timeline position. */
    fun moveClipToTrack(trackId: Int, clipId: Int, dir: Int) {
        val idx = song.tracks.indexOfFirst { it.id == trackId }
        val dest = song.tracks.getOrNull(idx + dir) ?: return
        val src = song.tracks.getOrNull(idx) ?: return
        val clip = src.clips.find { it.id == clipId } ?: return
        pushUndo()
        val newSrc = src.copy(clips = src.clips.filterNot { it.id == clipId })
        val newDest = dest.copy(clips = dest.clips + clip)
        commit(song.withTrack(newSrc).withTrack(newDest), newSrc, newDest)
        selectedClip = dest.id to clip.id
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
        for (t in song.tracks) for (c in t.clips) used.merge(c.take, 1, Int::plus)
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
        val used = song.tracks.any { t -> t.clips.any { it.take == entry.rel } }
        if (used) {
            message = "Recording is on the timeline. Remove its clips first"
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
        commit(song.withTrack(updated).copy(nextClipId = song.nextClipId + 1), updated)
        selectedClip = trackId to clip.id
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

    // ---- mixdown ----

    suspend fun exportMixdown(): File? {
        if (exporting) return null
        exporting = true
        try {
            return withContext(Dispatchers.IO) {
                Engine.mixdownBegin()
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
        Engine.mixdownAddTrack(
            isMaster,
            track.gain, track.pan, track.mute, track.solo,
            track.clips.map { absPath(it.take) }.toTypedArray(),
            track.clips.map { it.srcStart }.toLongArray(),
            track.clips.map { it.length }.toLongArray(),
            track.clips.map { it.start }.toLongArray(),
            track.clips.map { it.fadeIn }.toLongArray(),
            track.clips.map { it.fadeOut }.toLongArray(),
            track.effects.map { it.pluginId }.toTypedArray(),
            track.effects.map { it.values.toFloatArray() }.toTypedArray(),
            track.effects.map { it.bypass }.toBooleanArray(),
            track.effects.map { it.mix }.toFloatArray(),
        )
    }
}
