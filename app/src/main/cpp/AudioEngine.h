#pragma once

#include <oboe/Oboe.h>

#include <array>
#include <atomic>
#include <condition_variable>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "RingBuffer.h"
#include "RtCheck.h"
#include "Timeline.h"
#include "WavFile.h"

class EffectChain;

// The persistent recorder engine.
//
// Threads and their contracts:
//  - Audio callback (real-time): mixes per-track rings to the output, captures
//    input to the record ring. Never allocates, locks, frees, or does I/O; it
//    reads params from atomics and never frees graph memory it stops using. Its
//    one piece of setup is the FPU flush-to-zero mode, set on its first burst
//    because that mode is per-thread. onAudioReady is the whole thread:
//    captureInput, adoptSeek, mixTracks, applyFaderPanMeter and advancePlayhead
//    are its private steps, called from nowhere else, and the RT scope claimed
//    at the top of the burst covers all of them.
//  - Disk thread: renders clips from take files into per-track SPSC rings,
//    ahead of the playhead. Owns all playback file reads, and frees the
//    retired graph memory the control thread hands it.
//  - Record thread: drains the record ring to per-take WAV writers, then
//    finalizes them and publishes the result the UI collects.
//  - Control thread(s) (JNI calls): edit the track registry and clip lists
//    under mControlMutex; structural swaps use an atomic pointer plus
//    deferred reclamation (see below). No control path ever sleeps while
//    holding mControlMutex; the 30 Hz UI poll and the disk thread take it too.
//
// Debug builds check the contract instead of trusting it (RtCheck.h, compiled
// out under NDEBUG): PAW_ASSERT_RT() claims the callback thread on its first
// burst, PAW_ASSERT_CONTROL() asserts that every control entry point and both
// polls are *not* on it, and PAW_RT_SCOPE() makes any allocation or free
// reached from the callback abort. The buffers the callback reads are
// fixed-capacity std::arrays rather than vectors, so rewriting one (rebuilding
// the click on a tempo change, say) cannot reallocate storage under the
// callback: it can only overwrite in place, which is why openStreams does that
// with the streams stopped.
//
// Song scope: the engine is a process singleton that outlives song switches
// (rotation depends on it), so everything song-scoped hangs off a generation.
// openSong(gen) wipes the registry, the master, the loop, the metronome and
// the transport and adopts gen; every song-scoped mutating entry point carries
// the caller's generation and is dropped, counted (kStDroppedCalls) and logged
// once per generation when it does not match, so one song's stragglers (an
// effects scan finishing after the user backed out, say) cannot land in the
// next song's engine.
//
// Deferred reclamation: a TrackArray, EffectChain or TrackRT the callback may
// still be reading is published-away first, then parked on mRetired (under
// mControlMutex) together with the tick it was retired at. The disk thread
// frees an entry once mTicks >= tick + 2 (two callback bursts have *started*
// since the swap, so the burst that could still hold the old pointer has
// finished), or immediately once mStreamsOpen is false, which is only set
// after the output stream has been stopped (or closed under us on device
// loss), i.e. with no callback in flight. closeStreams and shutdown drain the
// list once the callback is provably gone.
//
// Idle: both service threads park on a condition variable (mDiskCv/mRecCv,
// mControlMutex, 500 ms fallback) when there is nothing to do, and every
// control path that creates work sets mDiskWake/mRecWake under mControlMutex
// and notifies, so no wakeup can be lost. The audio callback never signals
// them: that would mean a mutex and a syscall on the real-time thread. While
// the transport rolls or a take drains, the loops keep their 2 ms cadence.
//
// Seek/flush protocol: control bumps mSeekGen with mPendingSeek set. The
// callback, seeing a new generation, adopts the pending position and stops
// popping rings until mPrimedGen catches up. With the callback out of the way,
// the disk thread flushes the rings (consumer handoff), re-renders from the new
// position, then publishes mPrimedGen = generation.
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    static AudioEngine& instance();

    static constexpr int kTransportStopped = 0;
    static constexpr int kTransportPlaying = 1;
    static constexpr int kTransportRecording = 2;

    // Scalar state snapshot layout. Kotlin mirrors these indices (Engine.kt);
    // the poll fills a caller-allocated long[] with no lock and no allocation.
    static constexpr int kStTransport = 0;
    static constexpr int kStPlayhead = 1;
    static constexpr int kStCountdown = 2;
    static constexpr int kStRecStartFrame = 3;
    static constexpr int kStRecFrames = 4;
    static constexpr int kStRecording = 5;
    static constexpr int kStRecPending = 6;
    static constexpr int kStOutputOpen = 7;
    static constexpr int kStInputOpen = 8;
    static constexpr int kStDeviceLost = 9;
    static constexpr int kStInChannels = 10;
    static constexpr int kStInDeviceId = 11;
    static constexpr int kStSampleRate = 12;
    static constexpr int kStRecResultReady = 13;
    // Cumulative count of song-scoped calls dropped on a generation mismatch.
    // Zero in a healthy run; a rising number means a controller has outlived
    // its song.
    static constexpr int kStDroppedCalls = 14;
    // Distinct take paths the disk thread currently cannot open, counted over
    // the clip lists the open song references; a missing take plays as silence.
    // Drops back to zero on its own if the file reappears (the renderer's
    // failure cache is dropped on every generation the disk thread adopts).
    static constexpr int kStMissingTakes = 15;
    static constexpr int kStateSlots = 16;

    // Meter slots the callback publishes each burst, plus one for the master.
    static constexpr int kMaxMeters = 64;

    // Input modes (which interface channels feed an armed track).
    static constexpr int kInputNone = 0;
    static constexpr int kInputCh1 = 1;
    static constexpr int kInputCh2 = 2;
    static constexpr int kInputStereo = 3;

    // What toggleRecordSession did. Kotlin mirrors these (Engine.kt).
    static constexpr int kRecToggleError = 0;    // nothing happened; see lastRecordError()
    static constexpr int kRecToggleStarted = 1;  // capture is running
    static constexpr int kRecToggleStopped = 2;  // punched out; the take lands via the poll
    static constexpr int kRecToggleDropped = 3;  // stale generation, ignored

    // Streams. outputDeviceId 0 = system default; inputDeviceId 0 = no input.
    // builtinMic selects the Camcorder input preset (platform gain for the
    // phone's own mic) instead of Unprocessed (bit-perfect, interfaces).
    // Stops the transport. Returns "" on success.
    std::string openStreams(int inputDeviceId, int outputDeviceId, int sampleRate,
                            bool builtinMic);
    void closeStreams();

    // Round-trip latency of the currently open route, in frames, or -1 while
    // the streams cannot report one. Bluetooth output runs 15-30x the USB
    // figure, so the loopback-measured constant cannot cover every route.
    int routeLatencyFrames();

    // Linear gain applied to the input everywhere it goes: monitoring,
    // meters, and the samples written into takes. Exactly 1.0 bypasses the
    // sample rewrite, keeping interface takes bit-perfect; the phone mic
    // needs boost (its Camcorder-preset level still runs low).
    void setInputGain(float gain) {
        PAW_ASSERT_CONTROL();
        mInputGain.store(gain, std::memory_order_relaxed);
    }

    // Opens a song generation, resetting everything song-scoped in one place:
    // the registry (clips, rings, open take readers), the master's params and
    // chain, the loop, the metronome, the transport and the playhead. Streams
    // stay open: the engine and its streams outlive song switches, which is
    // what rotation rests on. A recording still running is punched out first
    // (its take finalizes normally and stays on disk).
    void openSong(int64_t gen);

    // Song-scoped mutations. Each carries the caller's generation and is
    // dropped if the engine has since opened another song. trackId -1 = master
    // (params and chain only).
    void syncTrackClips(int64_t gen, int trackId, std::vector<ClipRef> clips);
    void removeTrack(int64_t gen, int trackId);
    void setTrackParams(int64_t gen, int trackId, float gain, float pan, bool mute, bool solo,
                        bool armed, int inputMode, bool monitor);
    // Takes ownership either way: a chain arriving for a stale generation is
    // deleted rather than installed.
    void setTrackChain(int64_t gen, int trackId, EffectChain* chain);
    void setChainControl(int64_t gen, int trackId, int unitIdx, int controlIdx, float value);
    void setChainUnitState(int64_t gen, int trackId, int unitIdx, bool bypass, float mix);

    // Transport. Total and idempotent commands that decide under the engine's
    // own lock and report what happened, so no caller has to branch on a copy
    // of the state that is up to a poll interval old.
    // playPause: plays from stopped, else stops (punching out a live take on
    // the way, exactly as stopAll does). Returns the resulting transport state.
    int transportPlayPause();
    // Stops the transport and, if a record session is live, flips capture off
    // through the finishRecordAsync path.
    void transportStopAll();
    // Refused while a take is being captured or finalized.
    void seek(int64_t frame);
    int64_t playhead() const { return mPlayhead.load(std::memory_order_relaxed); }

    // Metronome: a click on the beat grid (timeline frame 0 = beat 1),
    // rendered post-master so the mix faders never swallow it.
    void setMetronome(int64_t gen, bool enabled, float bpm, int beatsPerBar);

    // Loop region. The disk thread renders across the wrap so playback loops
    // with no re-prime stall at the boundary; changing the region mid-play
    // re-anchors (one normal seek stall). Recording always runs linear:
    // starting a take disables the engine loop, and the controller re-syncs it
    // when the take is collected.
    void setLoop(int64_t gen, bool enabled, int64_t startFrame, int64_t endFrame);

    // Record button, punch-in and punch-out in one atomic decision: under
    // mControlMutex, either a live session is flipped off (kRecToggleStopped)
    // or capture starts with one take per armed track under
    // dir/<baseName>_t<trackId>.wav (kRecToggleStarted). Take files are created
    // only on the starting branch, under the same lock that decided to start.
    // countInFramesIfStopped applies only when the transport was stopped: a
    // punch-in mid-play must not freeze the transport under the musician.
    // kRecToggleError leaves a message in lastRecordError().
    int toggleRecordSession(int64_t gen, const std::string& dir, const std::string& baseName,
                            int64_t countInFramesIfStopped);
    std::string lastRecordError();

    // Result contract. finishRecordAsync flips capture off, sets the transport
    // per keepRolling and returns at once, never waiting on the record thread,
    // so an interactive punch-out costs one JNI call. The record thread drains
    // the tail, finalizes the writers, parks the result and raises
    // kStRecResultReady in the state snapshot; the caller's poll sees the flag
    // and collects. Nothing else has to be called for the result to appear: a
    // device loss mid-record takes the same path, because the error callback
    // stops capture and the record thread finishes on its own. Returns false
    // when no session is pending.
    bool finishRecordAsync(bool keepRolling);
    // Teardown only (the app is closing the song and must place the take
    // before its last save): as finishRecordAsync, then waits up to ~2 s for
    // the result. Never call it from an interactive path. Returns true if a
    // result is ready to collect.
    bool finishRecordSync(bool keepRolling);

    // [startFrame, dropped, ioOk, takeCount, then trackId/frames per take].
    // Timeline starts are uncompensated: the caller applies the measured
    // latency constant. Paths come back in take order.
    // Reading the longs consumes kStRecResultReady: an empty header comes back
    // if no fresh result is parked, so the same takes can never be collected
    // twice. The paths stay readable until the next take starts, so fetch them
    // in either order.
    std::vector<int64_t> recordResultLongs();
    std::vector<std::string> recordResultPaths();

    // Lock-free polls. stateSnapshot fills kStateSlots entries; meterSnapshot
    // fills up to cap (id, peak) pairs from the array the callback publishes
    // and appends the master as id -1. Neither takes mControlMutex.
    void stateSnapshot(int64_t* out);
    int meterSnapshot(int* ids, float* peaks, int cap);
    void shutdown();

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    static constexpr int kMaxBurst = 8192;          // frames the callback can process
    static constexpr int kChunkFrames = 4096;       // disk render chunk
    static constexpr size_t kRingBytes = 1u << 18;  // 32768 stereo float frames
    // Playback starts once every track holds this much: two chunks, ~170 ms.
    // Filling the whole 682 ms ring first would make every seek and clip edit
    // an audible gap; the disk thread tops the rings up afterwards.
    static constexpr size_t kMinPrimeBytes = sizeof(float) * 2 * kChunkFrames * 2;
    static constexpr float kSmooth = 0.00416f;  // ~5 ms one-pole at 48 kHz
    static constexpr int kIdleWaitMs = 500;      // service-thread CV fallback
    static constexpr int kFinishDrainMs = 2000;  // teardown-only record drain
    // Compile-time maxima for the callback-visible buffers. The engine is
    // opened at 48 kHz with this hardware (see docs/engineering.md); a higher
    // rate would only shorten the click, never overrun it.
    static constexpr int kEngineSampleRate = 48000;
    static constexpr int kMaxClickFrames = kEngineSampleRate * 3 / 100;  // 30 ms
    static constexpr int kInBufFrames = 4096;    // frames read from the input per burst
    static constexpr int kMaxInFrameBytes = 8;   // 2 channels x 4 bytes, the widest input frame
    // Draining the input backlog after open: at most this many reads in total,
    // and at most this many inside any one callback. All 64 in the first burst
    // is up to a quarter-second of memcpy inside one deadline.
    static constexpr int kMaxDrainReads = 64;
    static constexpr int kDrainReadsPerBurst = 4;

    struct TrackRT {
        explicit TrackRT(int trackId, bool withRing);
        ~TrackRT();

        const int id;
        // Control-side, guarded by mControlMutex.
        std::vector<ClipRef> clips;
        // Params, written by control, read by callback.
        std::atomic<float> gain{1.0f};
        std::atomic<float> pan{0.0f};  // -1..1 stereo balance
        std::atomic<bool> mute{false};
        std::atomic<bool> solo{false};
        std::atomic<bool> armed{false};
        std::atomic<bool> monitor{false};  // software pass-through when armed
        std::atomic<int> inputMode{kInputCh1};
        std::atomic<EffectChain*> chain{nullptr};
        // Playback ring: dry interleaved stereo float, disk thread -> callback.
        std::unique_ptr<RingBuffer> ring;
        // Callback-owned.
        std::vector<float> scratch;
        float smoothL = 0.0f, smoothR = 0.0f;
        // Disk-thread-owned.
        int64_t renderPos = 0;
    };

    struct TrackArray {
        std::vector<TrackRT*> tracks;
    };

    struct RecordTarget {
        int trackId = 0;
        int mode = kInputCh1;
        std::string path;
        WavWriter wav;
        std::vector<uint8_t> staging;
        int64_t frames = 0;
    };

    struct RecordedTakeInfo {
        int trackId = 0;
        int64_t frames = 0;
        std::string path;
    };

    struct RecordResultData {
        int64_t startFrame = -1;
        int64_t dropped = 0;
        bool ioOk = true;
        std::vector<RecordedTakeInfo> takes;
    };

    struct RecordSession {
        std::vector<std::unique_ptr<RecordTarget>> targets;
        std::atomic<bool> finalizing{false};  // claims the finalize, once
        std::atomic<bool> done{false};
        RecordResultData result;
    };

    // One retired graph object, waiting out the callback. Exactly one of the
    // three is set; the shared_ptr form keeps a removed TrackRT (and the chain
    // its destructor deletes) alive past the last disk-thread reference.
    struct Retired {
        TrackArray* array = nullptr;
        EffectChain* chain = nullptr;
        std::shared_ptr<TrackRT> track;
        uint64_t tick = 0;
    };

    // Per-track burst peak, published by the callback with relaxed stores.
    struct MeterSlot {
        std::atomic<int> id{0};
        std::atomic<float> peak{0.0f};
    };

    AudioEngine();
    ~AudioEngine();

    // Callback thread only, all five, and only from onAudioReady.
    // applyFaderPanMeter is the fader/pan/meter tail both the tracks and the
    // master run: with accumulate it sums into the mix bus (tracks), otherwise
    // it overwrites (master). It returns the burst peak of what it wrote.
    void captureInput(oboe::AudioStream* in, bool countInDone);
    uint64_t adoptSeek();
    void mixTracks(int frames, bool rolling, bool inputLive);
    float applyFaderPanMeter(TrackRT& tr, const float* in, float* out, int frames, float gain,
                             float pan, bool accumulate);
    void advancePlayhead(int frames);

    void diskLoop();
    void recordLoop();
    // False (and counted, and logged once per generation) when the caller's
    // song is no longer the open one. Call with mControlMutex held.
    bool acceptGenLocked(int64_t gen, const char* what);
    void resetSongScopeLocked();
    void playLocked();
    void stopAllLocked();
    std::string startRecordLocked(const std::string& dir, const std::string& baseName,
                                  int64_t countInFrames);
    bool finishRecordAsyncLocked(bool keepRolling);
    void buildClickBuffersLocked();
    void renderClickInto(float* stereoOut, int frames, int64_t gridStart);
    // Timeline position for a virtual (monotonic) render position, given the
    // generation's start position. Identity unless the loop wraps.
    int64_t loopTimelineOf(int64_t virtualPos, int64_t genOrigin) const;
    void demuxAppend(RecordSession& s, const uint8_t* data, size_t bytes);
    void finalizeRecordSession(RecordSession& s);
    void publishRecordResultLocked(RecordSession& s);
    void rebuildTrackArrayLocked();
    // Park graph memory the callback may still be reading. Call it *after* the
    // replacement is published: the tick is sampled here, and sampling it
    // before the swap would let a burst that already read the old pointer run
    // past the two-tick window.
    void retireLocked(TrackArray* array, EffectChain* chain, std::shared_ptr<TrackRT> track);
    void drainRetiredLocked(bool force);
    void wakeDiskLocked();
    void wakeRecordLocked();
    TrackRT* trackForParamsLocked(int trackId);
    void bumpGenLocked(int64_t position);
    void closeStreamsLocked();

    // Streams.
    std::shared_ptr<oboe::AudioStream> mInStream;
    std::shared_ptr<oboe::AudioStream> mOutStream;
    std::atomic<oboe::AudioStream*> mInRaw{nullptr};  // callback's view of the input
    // Written on the control thread at open/close. Atomic so the 30 Hz state
    // poll can read them without taking mControlMutex.
    std::atomic<bool> mStreamsOpen{false};
    std::atomic<bool> mInputOpen{false};
    std::atomic<int> mSampleRate{48000};
    std::atomic<int> mInChannels{0};  // 1 (phone mic) or 2 (interface)
    std::atomic<int> mInDeviceId{0};
    int mInBytesPerFrame = 0;
    int mInBytesPerSample = 0;
    oboe::AudioFormat mInFormat = oboe::AudioFormat::Invalid;
    // Fixed capacity, written in place by openStreams with the streams down:
    // the callback reads both every burst and nothing may ever move them.
    std::array<uint8_t, static_cast<size_t>(kInBufFrames) * kMaxInFrameBytes> mInBuf{};
    std::array<float, static_cast<size_t>(kInBufFrames) * 2> mInFloat{};  // stereo float, callback
    int32_t mInFramesThisBurst = 0;               // callback thread only
    int mInDrainReadsLeft = kMaxDrainReads;       // callback thread only
    std::atomic<float> mInPeak[2] = {{0.0f}, {0.0f}};
    std::atomic<float> mInputGain{1.0f};

    // Song scope. mSongGen is the only generation whose mutations are
    // accepted; 0 means no song is open (nothing song-scoped is accepted).
    std::atomic<int64_t> mSongGen{0};
    std::atomic<int64_t> mDroppedCalls{0};
    bool mDropLogged = false;  // one log per generation; under mControlMutex

    // Track graph.
    std::mutex mControlMutex;
    std::map<int, std::shared_ptr<TrackRT>> mRegistry;
    // Bumped under mControlMutex on every registry change, so the disk thread
    // can hold its snapshot across wakes.
    std::atomic<uint64_t> mRegistryVersion{1};
    std::vector<Retired> mRetired;  // under mControlMutex
    std::atomic<TrackArray*> mTracksRT{nullptr};
    TrackRT mMaster{-1, false};
    std::atomic<int> mSoloCount{0};
    MeterSlot mMeterSlots[kMaxMeters];
    std::atomic<int> mMeterCount{0};
    std::atomic<float> mMasterPeak{0.0f};

    // Transport / seek protocol.
    std::atomic<int> mTransport{kTransportStopped};
    std::atomic<int64_t> mPlayhead{0};
    std::atomic<int64_t> mPendingSeek{0};
    std::atomic<uint64_t> mSeekGen{1};
    std::atomic<uint64_t> mPrimedGen{0};
    uint64_t mCbGen = 0;                   // callback thread only
    bool mFpuConfigured = false;           // callback thread only (FTZ setup)
    std::atomic<uint64_t> mCbGenSeen{0};   // callback publishes its adopted gen
    std::atomic<uint64_t> mTicks{0};

    // Mix scratch (callback only).
    std::vector<float> mMixBuf;

    // Metronome / count-in / loop.
    std::atomic<bool> mClickEnabled{false};
    std::atomic<int64_t> mFramesPerBeat{24000};  // 120 bpm at 48 kHz
    std::atomic<int> mBeatsPerBar{4};
    std::atomic<int64_t> mCountdown{0};  // count-in frames left; 0 = none
    // Fixed capacity for the same reason as the input buffers. mClickFrames is
    // how much of them is live.
    std::array<float, kMaxClickFrames> mClickAccent{}, mClickNormal{};
    std::atomic<int> mClickFrames{0};
    std::atomic<bool> mLoopEnabled{false};
    std::atomic<int64_t> mLoopStart{0};
    std::atomic<int64_t> mLoopEnd{0};
    int64_t mDiskGenOrigin = 0;  // disk-thread-owned
    // Published by the disk thread each pass from its renderer; read by the
    // state poll. See kStMissingTakes.
    std::atomic<int> mMissingTakes{0};

    // Recording.
    std::unique_ptr<RingBuffer> mRecRing;
    std::atomic<bool> mRecCapture{false};
    std::atomic<uint64_t> mRecStopTick{0};
    std::atomic<int64_t> mRecStartFrame{-1};
    std::atomic<int64_t> mRecFramesCaptured{0};
    std::atomic<int64_t> mRecDropped{0};
    std::atomic<RecordSession*> mRecSession{nullptr};
    std::unique_ptr<RecordSession> mRecSessionOwned;  // under mControlMutex
    std::atomic<bool> mRecPending{false};             // a session is live or finalizing
    int64_t mRecSessionGen = 0;  // song generation that started it; under mControlMutex
    std::atomic<bool> mRecResultReady{false};         // a result awaits collection
    RecordResultData mRecResult;                      // under mControlMutex
    std::string mRecError;                            // under mControlMutex

    std::atomic<bool> mDeviceLost{false};

    // Threads. mDiskWake/mRecWake are the "look again" flags the CV
    // predicates read; both live under mControlMutex.
    std::thread mDiskThread;
    std::thread mRecordThread;
    std::condition_variable mDiskCv;
    std::condition_variable mRecCv;
    bool mDiskWake = false;
    bool mRecWake = false;
    std::atomic<bool> mQuit{false};
};
