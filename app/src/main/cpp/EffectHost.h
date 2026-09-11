#pragma once

#include <atomic>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "effects/ladspa.h"

// LADSPA hosting. Effects come from two places: built-ins compiled into the
// app (effects/BuiltinEffects.h) and third-party .so files scanned from a
// folder on device storage. Only mono (1 audio in / 1 out) and stereo (2 in /
// 2 out) plugins are accepted; mono plugins run as a pair of instances sharing
// control values.
//
// Everything here is control-side except EffectChain::process, which runs in
// the audio callback: it deinterleaves into preallocated buffers, calls run()
// on each unit, and reinterleaves.
//
// The handoff from control side to callback is one-way and atomic. setControl
// and setUnitState write only std::atomic<float> targets (relaxed: there is no
// other data to publish alongside them, and a control that lands a burst late
// is inaudible); the callback loads them relaxed and owns every write to the
// smoothing state and to the connected port storage the plugin reads. No plain
// float is written by one thread and read by another.
//
// Live parameter changes are de-zippered: setControl writes a target, and
// process() steps the connected port value toward it once per kCtlBlock frames
// (~15 ms time constant). Integer and toggled ports snap instead, since a mode
// switch must never pass through meaningless intermediate values; the callback
// applies the snap on the first sub-block after the write, for the same
// ownership reason. Each unit also carries a bypass flag and a wet/dry mix,
// folded into one smoothed wet gain so engaging bypass or riding the mix never
// clicks.
//
// Once every control has reached its target and wet has reached wetTarget, the
// unit is settled: process() skips the smoothing pass and hands the plugin the
// whole burst in one run() instead of a chain of kCtlBlock sub-blocks. Settled
// output stays bit-identical either way because kCtlBlock is a multiple of the
// built-ins' internal chunk and a settled wet blend is a constant factor.

struct EffectControlPort {
    std::string name;
    int portIndex = 0;  // index in the descriptor's port list
    float def = 0.0f, min = 0.0f, max = 1.0f;
    bool logarithmic = false, integer = false, toggled = false;
};

struct EffectInfo {
    std::string id;    // "builtin:<Label>" or "<abs .so path>:<Label>"
    std::string name;  // descriptor's human-readable Name
    bool stereo = false;
    const LADSPA_Descriptor* descriptor = nullptr;
    std::vector<EffectControlPort> controls;
};

// One plugin slot in a chain: the instantiated handle(s) plus its control
// value storage (what the UI edits).
class EffectUnit {
public:
    ~EffectUnit();

    const EffectInfo* info = nullptr;
    // Mono plugins: two handles (L, R). Stereo: one handle, second null.
    LADSPA_Handle handleL = nullptr;
    LADSPA_Handle handleR = nullptr;
    // Callback-owned once the chain is published: the storage the plugin's
    // control ports are connected to, stepped toward controlTargets.
    std::vector<float> controlValues;
    // Written by the control thread, read by the callback.
    std::vector<std::atomic<float>> controlTargets;
    std::vector<uint8_t> controlSnap;   // 1 = integer/toggled: no smoothing
    std::vector<float> controlOutputs;  // dummy storage for output controls
    // Audio port indices resolved at build time. Stereo units use both
    // entries; mono units use [0] only and run the pair of handles through it.
    unsigned long audioIn[2] = {0, 0};
    unsigned long audioOut[2] = {0, 0};

    // Bypass and wet/dry collapse into one smoothed wet gain:
    // out = wet * processed + (1 - wet) * dry. Control-side writes wetTarget;
    // the callback owns wet and ramps it toward the target.
    float wet = 1.0f;
    std::atomic<float> wetTarget{1.0f};
    // Control-side memory of the two knobs behind wetTarget.
    std::atomic<bool> bypass{false};
    std::atomic<float> mix{1.0f};
};

class EffectChain {
public:
    ~EffectChain() = default;

    // Real-time safe. stereoInterleaved holds frames*2 floats, processed in
    // place. frames must be <= the maxFrames the chain was built with.
    void process(float* stereoInterleaved, int frames);

    void setControl(size_t unitIdx, size_t controlIdx, float value);
    // Live bypass / wet-dry, no rebuild. mix is clamped to [0, 1].
    void setUnitState(size_t unitIdx, bool bypass, float mix);
    size_t unitCount() const { return mUnits.size(); }

private:
    friend class EffectHost;

    // Controls step once per this many frames; small enough that even a full-
    // range jump moves in inaudible increments, and a multiple of the
    // built-ins' internal chunk so a settled unit's one-block run is
    // bit-identical to the sub-block chain.
    static constexpr int kCtlBlock = 64;

    // True when nothing is moving: every connected control sits on its target
    // and the wet gain sits on wetTarget. Callback thread.
    static bool settled(const EffectUnit& unit);
    // One sub-block of control smoothing: steps each connected port value
    // toward its target, snapping the integer/toggled ones. Callback thread,
    // the only writer of controlValues.
    void smoothControls(EffectUnit& unit) const;
    // Connects this unit's audio ports at `off` and runs `n` frames.
    static void runUnit(const EffectUnit& unit, float* const src[2], float* const dst[2],
                        int off, int n);

    std::vector<std::unique_ptr<EffectUnit>> mUnits;
    // Ping-pong mono buffers; each unit reads one pair and writes the other.
    std::vector<float> mBufA[2], mBufB[2];
    int mMaxFrames = 0;
    float mCtlCoef = 0.1f;  // per-kCtlBlock one-pole toward targets
};

class EffectHost {
public:
    static EffectHost& instance();

    // Registers the built-ins (idempotent) and scans dir for .so plugins.
    // Pass "" to skip the folder. Safe to call again to pick up new files.
    void scan(const std::string& dir);

    // Catalog for the UI: [{id,name,stereo,ports:[{name,def,min,max,log,int,toggle}]}]
    std::string catalogJson();

    const EffectInfo* find(const std::string& id);

    // Builds a chain ready for process(); ids not found are skipped (the
    // project may reference a plugin whose .so is gone; never fatal).
    // controlValues[i] may be empty to take defaults; bypasses/mixes[i] are
    // optional the same way (defaults: active, fully wet).
    EffectChain* buildChain(const std::vector<std::string>& ids,
                            const std::vector<std::vector<float>>& controlValues,
                            const std::vector<uint8_t>& bypasses,
                            const std::vector<float>& mixes,
                            int sampleRate, int maxFrames);

private:
    EffectHost() = default;
    void registerDescriptor(const LADSPA_Descriptor* d, const std::string& source);
    const EffectInfo* findLocked(const std::string& id);

    // Control-side calls can come from several threads (UI edits, mixdown on
    // an IO thread, the startup scan); one mutex covers catalog and handles.
    std::mutex mMutex;
    std::map<std::string, EffectInfo> mCatalog;
    std::vector<void*> mDlHandles;
    std::vector<std::string> mScannedFiles;
    bool mBuiltinsDone = false;
};
