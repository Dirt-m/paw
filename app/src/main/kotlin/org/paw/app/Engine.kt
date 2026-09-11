package org.paw.app

import androidx.compose.runtime.Immutable

object Engine {
    init {
        System.loadLibrary("paw-engine")
    }

    // ---- Persistent recorder engine ----

    /**
     * outputDeviceId 0 = system default; inputDeviceId 0 = no input.
     * builtinMic picks the Camcorder preset (platform mic gain) instead of
     * Unprocessed. "" on success.
     */
    external fun openStreams(
        inputDeviceId: Int,
        outputDeviceId: Int,
        sampleRate: Int,
        builtinMic: Boolean,
    ): String

    external fun closeStreams()

    /**
     * Round-trip latency the open route reports, in frames, or -1 when the
     * streams have no timestamp yet. Used to compensate routes the loopback
     * rig never measured, Bluetooth above all.
     */
    external fun routeLatencyFrames(): Int

    /** Linear input gain (monitoring, meters, and recorded samples; 1.0 = bit-perfect). */
    external fun setInputGain(gain: Float)

    // ---- song scope ----
    //
    // The engine is a process singleton that outlives song switches (rotation
    // depends on it), so every song-scoped call carries the generation of the
    // song it belongs to. openSong adopts a generation and constructs a fresh
    // song scope; calls tagged with any other generation are dropped and
    // counted in ST_DROPPED_CALLS. SongController holds the generation and is
    // the only caller.

    /** Wipes everything song-scoped (tracks, master, loop, metronome,
     *  transport, playhead) and adopts [gen]. Leaves the streams open. */
    external fun openSong(gen: Long)

    external fun syncClips(
        gen: Long,
        trackId: Int,
        paths: Array<String>,
        srcStarts: LongArray,
        lengths: LongArray,
        timelineStarts: LongArray,
        fadeIns: LongArray,
        fadeOuts: LongArray,
    )

    external fun removeTrack(gen: Long, trackId: Int)

    /** trackId -1 = master. */
    external fun setTrackParams(
        gen: Long,
        trackId: Int,
        gain: Float,
        pan: Float,
        mute: Boolean,
        solo: Boolean,
        armed: Boolean,
        inputMode: Int,
        monitor: Boolean,
    )

    // ---- transport ----
    //
    // Intent in, outcome out: the engine decides under its own lock, so no
    // caller has to branch on a snapshot that may be a poll interval old.

    /** Plays from stopped, else stops (punching out a live take on the way).
     *  Returns the resulting TRANSPORT_* state. */
    external fun transportPlayPause(): Int

    /** Stops the transport and ends a live take through the normal finalize
     *  path; the poll places it. Total and idempotent. */
    external fun transportStopAll()

    /** Ignored by the engine while a take is being captured or finalized. */
    external fun transportSeek(frame: Long)

    /** Click on the beat grid; timeline frame 0 is beat 1. */
    external fun setMetronome(gen: Long, enabled: Boolean, bpm: Float, beatsPerBar: Int)

    /** Gapless playback loop; recording always runs linear. */
    external fun setLoop(gen: Long, enabled: Boolean, startFrame: Long, endFrame: Long)

    /** Record button as one atomic engine-side decision: punches out a live
     *  take, or opens one take per armed track under dir/<baseName>_t<id>.wav
     *  and starts capture. No file is created on the punch-out branch.
     *  countInFramesIfStopped applies only if the transport was stopped.
     *  Returns one of the REC_TOGGLE_* codes; REC_TOGGLE_ERROR leaves the
     *  reason in lastRecordError(). */
    external fun toggleRecordSession(
        gen: Long,
        dir: String,
        baseName: String,
        countInFramesIfStopped: Long,
    ): Int

    /** Why the last toggleRecordSession returned REC_TOGGLE_ERROR. */
    external fun lastRecordError(): String

    /** Punch-out: stops capture and returns at once. The take lands a few
     *  polls later: the engine raises ST_REC_RESULT_READY when the record
     *  thread has finalized it. True if a session was live. */
    external fun finishRecordAsync(keepRolling: Boolean): Boolean

    /** Teardown only (SongController.stop()): as finishRecordAsync, then waits
     *  up to ~2 s for the result. True if a result is ready to collect. */
    external fun finishRecordSync(keepRolling: Boolean): Boolean

    /** [startFrame, dropped, ioOk, takeCount, then trackId/frames per take].
     *  Consumes ST_REC_RESULT_READY: a call with no fresh result parked comes
     *  back with takeCount 0, so takes can't be placed twice. */
    external fun recordResultLongs(): LongArray

    /** Take paths in the same order as recordResultLongs()'s pairs. */
    external fun recordResultPaths(): Array<String>

    /** Fills dst (ENGINE_STATE_SLOTS longs) from a lock-free engine snapshot. */
    external fun engineState(dst: LongArray)

    /** Fills the arrays with (trackId, peak) pairs and returns the count; the
     *  master bus comes back as id MASTER_TRACK_ID. */
    external fun engineMeters(ids: IntArray, peaks: FloatArray): Int

    external fun engineShutdown()

    // ---- Peaks / file info ----

    /** Pairs of (min, max) int8 per bucket across all channels, companded
     *  to the sqrt domain (draw linearly; see Peaks.cpp). */
    external fun computePeaks(path: String, bucketFrames: Int): ByteArray

    /** [frames, channels, sampleRate], zeros on failure. */
    external fun wavInfo(path: String): LongArray

    // ---- Effects ----

    /** Scans built-ins plus a folder of LADSPA .so files; returns the catalog JSON. */
    external fun scanEffects(dir: String): String

    external fun setChain(
        gen: Long,
        trackId: Int,
        sampleRate: Int,
        effectIds: Array<String>,
        controlValues: Array<FloatArray>,
        bypasses: BooleanArray,
        mixes: FloatArray,
    )

    external fun setEffectParam(
        gen: Long,
        trackId: Int,
        unitIdx: Int,
        controlIdx: Int,
        value: Float,
    )

    /** Live bypass / wet-dry for one chain slot, no rebuild. */
    external fun setEffectUnitState(
        gen: Long,
        trackId: Int,
        unitIdx: Int,
        bypass: Boolean,
        mix: Float,
    )

    // ---- Mixdown ----

    external fun mixdownBegin()

    external fun mixdownAddTrack(
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
    )

    external fun mixdownRun(sampleRate: Int, outPath: String): String
}

// ---- Recorder engine state ----

const val TRANSPORT_STOPPED = 0
const val TRANSPORT_PLAYING = 1
const val TRANSPORT_RECORDING = 2

// Layout of the engineState() snapshot; mirrors AudioEngine.h's kSt* indices.
const val ST_TRANSPORT = 0
const val ST_PLAYHEAD = 1
const val ST_COUNTDOWN = 2
const val ST_REC_START_FRAME = 3
const val ST_REC_FRAMES = 4
const val ST_RECORDING = 5
const val ST_REC_PENDING = 6
const val ST_OUTPUT_OPEN = 7
const val ST_INPUT_OPEN = 8
const val ST_DEVICE_LOST = 9
const val ST_IN_CHANNELS = 10
const val ST_IN_DEVICE_ID = 11
const val ST_SAMPLE_RATE = 12
const val ST_REC_RESULT_READY = 13

/** Song-scoped calls the engine dropped on a generation mismatch, cumulative.
 *  Zero in a healthy run; anything else names a controller that outlived its
 *  song and is still talking to the engine. */
const val ST_DROPPED_CALLS = 14

/** Clips whose take file the renderer could not open, cumulative for the song.
 *  They play as silence, so this is the only signal they exist. */
const val ST_MISSING_TAKES = 15
const val ENGINE_STATE_SLOTS = 16

// What toggleRecordSession() did; mirrors AudioEngine.h's kRecToggle* codes.
const val REC_TOGGLE_ERROR = 0
const val REC_TOGGLE_STARTED = 1
const val REC_TOGGLE_STOPPED = 2
const val REC_TOGGLE_DROPPED = 3

/** Meter slots the engine can return: its 64 tracks plus the master. */
const val METER_SLOTS = 65

/** Layout of recordResultLongs(): header, then a (trackId, frames) pair each. */
const val RR_START_FRAME = 0
const val RR_DROPPED = 1
const val RR_IO_OK = 2
const val RR_COUNT = 3
const val RR_TAKES = 4

/**
 * Everything in the engine snapshot that changes rarely: published as one
 * immutable value so a poll tick with nothing new costs no recomposition.
 * The fast-moving pieces (playhead, capture length, meters) live beside it in
 * SongController as their own states.
 */
@Immutable
data class EngineStatus(
    val transport: Int = TRANSPORT_STOPPED,
    val countdown: Long = 0,  // count-in frames remaining
    val outputOpen: Boolean = false,
    val inputOpen: Boolean = false,
    val inChannels: Int = 0,
    val inDeviceId: Int = 0,
    val deviceLost: Boolean = false,
    val recording: Boolean = false,
    val recPending: Boolean = false,
    val recStartFrame: Long = -1,
    /** Diagnostics: engine-side drops from a stale song generation. */
    val droppedCalls: Long = 0,
    /** Take files the renderer couldn't open; they play as silence. */
    val missingTakes: Long = 0,
)

