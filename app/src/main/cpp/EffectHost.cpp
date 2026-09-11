#include "EffectHost.h"

#include <dirent.h>
#include <dlfcn.h>

#include <algorithm>
#include <cmath>
#include <cstring>

#include "effects/BuiltinEffects.h"

namespace {

std::string jsonEscape(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (char c : s) {
        if (c == '"' || c == '\\') out += '\\';
        if (static_cast<unsigned char>(c) >= 0x20) out += c;
    }
    return out;
}

float hintDefault(const LADSPA_PortRangeHint& hint, int sampleRate) {
    const LADSPA_PortRangeHintDescriptor d = hint.HintDescriptor;
    const float rate = LADSPA_IS_HINT_SAMPLE_RATE(d) ? static_cast<float>(sampleRate) : 1.0f;
    float lo = LADSPA_IS_HINT_BOUNDED_BELOW(d) ? hint.LowerBound * rate : 0.0f;
    float hi = LADSPA_IS_HINT_BOUNDED_ABOVE(d) ? hint.UpperBound * rate : 1.0f;
    switch (d & LADSPA_HINT_DEFAULT_MASK) {
        case LADSPA_HINT_DEFAULT_MINIMUM: return lo;
        case LADSPA_HINT_DEFAULT_MAXIMUM: return hi;
        case LADSPA_HINT_DEFAULT_0: return 0.0f;
        case LADSPA_HINT_DEFAULT_1: return 1.0f;
        case LADSPA_HINT_DEFAULT_100: return 100.0f;
        case LADSPA_HINT_DEFAULT_440: return 440.0f;
        case LADSPA_HINT_DEFAULT_LOW:
            return LADSPA_IS_HINT_LOGARITHMIC(d) ? std::exp(0.75f * std::log(std::max(lo, 1e-6f)) +
                                                            0.25f * std::log(std::max(hi, 1e-6f)))
                                                 : lo * 0.75f + hi * 0.25f;
        case LADSPA_HINT_DEFAULT_HIGH:
            return LADSPA_IS_HINT_LOGARITHMIC(d) ? std::exp(0.25f * std::log(std::max(lo, 1e-6f)) +
                                                            0.75f * std::log(std::max(hi, 1e-6f)))
                                                 : lo * 0.25f + hi * 0.75f;
        case LADSPA_HINT_DEFAULT_MIDDLE:
            return LADSPA_IS_HINT_LOGARITHMIC(d) ? std::exp(0.5f * (std::log(std::max(lo, 1e-6f)) +
                                                                    std::log(std::max(hi, 1e-6f))))
                                                 : 0.5f * (lo + hi);
        default: return lo;
    }
}

}  // namespace

EffectUnit::~EffectUnit() {
    if (!info || !info->descriptor) return;
    const LADSPA_Descriptor* d = info->descriptor;
    for (LADSPA_Handle h : {handleL, handleR}) {
        if (!h) continue;
        if (d->deactivate) d->deactivate(h);
        if (d->cleanup) d->cleanup(h);
    }
}

EffectHost& EffectHost::instance() {
    static EffectHost host;
    return host;
}

void EffectHost::registerDescriptor(const LADSPA_Descriptor* d, const std::string& source) {
    if (!d || !d->run || !d->instantiate) return;
    int audioIn = 0, audioOut = 0;
    for (unsigned long p = 0; p < d->PortCount; ++p) {
        const LADSPA_PortDescriptor pd = d->PortDescriptors[p];
        if (LADSPA_IS_PORT_AUDIO(pd)) {
            if (LADSPA_IS_PORT_INPUT(pd)) ++audioIn;
            else ++audioOut;
        }
    }
    const bool stereo = (audioIn == 2 && audioOut == 2);
    const bool mono = (audioIn == 1 && audioOut == 1);
    if (!stereo && !mono) return;  // unsupported topology

    EffectInfo info;
    info.id = source + ":" + (d->Label ? d->Label : "?");
    info.name = d->Name ? d->Name : info.id;
    info.stereo = stereo;
    info.descriptor = d;
    for (unsigned long p = 0; p < d->PortCount; ++p) {
        const LADSPA_PortDescriptor pd = d->PortDescriptors[p];
        if (!LADSPA_IS_PORT_CONTROL(pd) || !LADSPA_IS_PORT_INPUT(pd)) continue;
        EffectControlPort port;
        port.name = d->PortNames[p] ? d->PortNames[p] : "?";
        port.portIndex = static_cast<int>(p);
        const LADSPA_PortRangeHint& hint = d->PortRangeHints[p];
        const LADSPA_PortRangeHintDescriptor hd = hint.HintDescriptor;
        port.min = LADSPA_IS_HINT_BOUNDED_BELOW(hd) ? hint.LowerBound : 0.0f;
        port.max = LADSPA_IS_HINT_BOUNDED_ABOVE(hd) ? hint.UpperBound : 1.0f;
        if (LADSPA_IS_HINT_SAMPLE_RATE(hd)) {
            port.min *= 48000.0f;
            port.max *= 48000.0f;
        }
        port.def = hintDefault(hint, 48000);
        port.logarithmic = LADSPA_IS_HINT_LOGARITHMIC(hd);
        port.integer = LADSPA_IS_HINT_INTEGER(hd);
        port.toggled = LADSPA_IS_HINT_TOGGLED(hd);
        info.controls.push_back(std::move(port));
    }
    mCatalog[info.id] = std::move(info);
}

void EffectHost::scan(const std::string& dir) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mBuiltinsDone) {
        for (unsigned long i = 0;; ++i) {
            const LADSPA_Descriptor* d = pawBuiltinDescriptor(i);
            if (!d) break;
            registerDescriptor(d, "builtin");
        }
        mBuiltinsDone = true;
    }
    if (dir.empty()) return;

    DIR* dp = opendir(dir.c_str());
    if (!dp) return;
    while (dirent* entry = readdir(dp)) {
        const std::string name = entry->d_name;
        if (name.size() < 3 || name.substr(name.size() - 3) != ".so") continue;
        const std::string path = dir + "/" + name;
        if (std::find(mScannedFiles.begin(), mScannedFiles.end(), path) != mScannedFiles.end())
            continue;
        mScannedFiles.push_back(path);
        void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!handle) continue;
        auto entryFn = reinterpret_cast<LADSPA_Descriptor_Function>(
                dlsym(handle, "ladspa_descriptor"));
        if (!entryFn) {
            dlclose(handle);
            continue;
        }
        mDlHandles.push_back(handle);  // held for the process lifetime
        for (unsigned long i = 0;; ++i) {
            const LADSPA_Descriptor* d = entryFn(i);
            if (!d) break;
            registerDescriptor(d, path);
        }
    }
    closedir(dp);
}

const EffectInfo* EffectHost::findLocked(const std::string& id) {
    auto it = mCatalog.find(id);
    return it == mCatalog.end() ? nullptr : &it->second;
}

const EffectInfo* EffectHost::find(const std::string& id) {
    std::lock_guard<std::mutex> lock(mMutex);
    return findLocked(id);
}

std::string EffectHost::catalogJson() {
    std::lock_guard<std::mutex> lock(mMutex);
    std::string s = "[";
    bool first = true;
    for (auto& [id, info] : mCatalog) {
        if (!first) s += ",";
        first = false;
        s += "{\"id\":\"" + jsonEscape(id) + "\",\"name\":\"" + jsonEscape(info.name) +
             "\",\"stereo\":" + (info.stereo ? "true" : "false") + ",\"ports\":[";
        for (size_t p = 0; p < info.controls.size(); ++p) {
            const EffectControlPort& port = info.controls[p];
            if (p) s += ",";
            char buf[256];
            snprintf(buf, sizeof(buf),
                     "{\"name\":\"%s\",\"def\":%g,\"min\":%g,\"max\":%g,"
                     "\"log\":%s,\"int\":%s,\"toggle\":%s}",
                     jsonEscape(port.name).c_str(), port.def, port.min, port.max,
                     port.logarithmic ? "true" : "false", port.integer ? "true" : "false",
                     port.toggled ? "true" : "false");
            s += buf;
        }
        s += "]}";
    }
    s += "]";
    return s;
}

EffectChain* EffectHost::buildChain(const std::vector<std::string>& ids,
                                    const std::vector<std::vector<float>>& controlValues,
                                    const std::vector<uint8_t>& bypasses,
                                    const std::vector<float>& mixes,
                                    int sampleRate, int maxFrames) {
    std::lock_guard<std::mutex> lock(mMutex);
    auto chain = std::make_unique<EffectChain>();
    chain->mMaxFrames = maxFrames;
    // ~15 ms control smoothing regardless of sample rate.
    chain->mCtlCoef =
            1.0f - std::exp(-static_cast<float>(EffectChain::kCtlBlock) /
                            (0.015f * static_cast<float>(sampleRate > 0 ? sampleRate : 48000)));
    for (int c = 0; c < 2; ++c) {
        chain->mBufA[c].assign(static_cast<size_t>(maxFrames), 0.0f);
        chain->mBufB[c].assign(static_cast<size_t>(maxFrames), 0.0f);
    }

    for (size_t i = 0; i < ids.size(); ++i) {
        const EffectInfo* info = findLocked(ids[i]);
        if (!info) continue;  // plugin gone: skip, never fatal
        const LADSPA_Descriptor* d = info->descriptor;

        auto unit = std::make_unique<EffectUnit>();
        unit->info = info;
        unit->controlValues.resize(info->controls.size());
        unit->controlTargets = std::vector<std::atomic<float>>(info->controls.size());
        unit->controlSnap.resize(info->controls.size());
        for (size_t p = 0; p < info->controls.size(); ++p) {
            unit->controlValues[p] =
                    (i < controlValues.size() && p < controlValues[i].size())
                            ? controlValues[i][p]
                            : info->controls[p].def;
            unit->controlTargets[p].store(unit->controlValues[p], std::memory_order_relaxed);
            unit->controlSnap[p] =
                    (info->controls[p].integer || info->controls[p].toggled) ? 1 : 0;
        }
        const bool bypass = i < bypasses.size() && bypasses[i] != 0;
        const float mix = i < mixes.size() ? std::min(std::max(mixes[i], 0.0f), 1.0f) : 1.0f;
        unit->bypass.store(bypass, std::memory_order_relaxed);
        unit->mix.store(mix, std::memory_order_relaxed);
        // A rebuilt chain starts at its settled state, with no fade-in.
        unit->wet = bypass ? 0.0f : mix;
        unit->wetTarget.store(unit->wet, std::memory_order_relaxed);
        unit->controlOutputs.assign(d->PortCount, 0.0f);

        // Audio port indices, once: in descriptor order, inputs then outputs.
        // A mono unit contributes one of each and runs both handles on them.
        for (unsigned long p = 0, in = 0, out = 0; p < d->PortCount; ++p) {
            const LADSPA_PortDescriptor pd = d->PortDescriptors[p];
            if (!LADSPA_IS_PORT_AUDIO(pd)) continue;
            if (LADSPA_IS_PORT_INPUT(pd)) {
                if (in < 2) unit->audioIn[in++] = p;
            } else {
                if (out < 2) unit->audioOut[out++] = p;
            }
        }

        unit->handleL = d->instantiate(d, static_cast<unsigned long>(sampleRate));
        if (!unit->handleL) continue;
        if (!info->stereo) {
            unit->handleR = d->instantiate(d, static_cast<unsigned long>(sampleRate));
            if (!unit->handleR) continue;  // unit dtor cleans up handleL
        }

        // Audio buffers are connected per-process() call (ping-pong); control
        // ports connect once, here.
        for (LADSPA_Handle h : {unit->handleL, unit->handleR}) {
            if (!h) continue;
            for (unsigned long p = 0; p < d->PortCount; ++p) {
                const LADSPA_PortDescriptor pd = d->PortDescriptors[p];
                if (!LADSPA_IS_PORT_CONTROL(pd)) continue;
                if (LADSPA_IS_PORT_INPUT(pd)) {
                    // Find our control slot for this port index.
                    for (size_t ci = 0; ci < info->controls.size(); ++ci) {
                        if (info->controls[ci].portIndex == static_cast<int>(p)) {
                            d->connect_port(h, p, &unit->controlValues[ci]);
                            break;
                        }
                    }
                } else {
                    d->connect_port(h, p, &unit->controlOutputs[p]);
                }
            }
            if (d->activate) d->activate(h);
        }
        chain->mUnits.push_back(std::move(unit));
    }
    return chain.release();
}

// Control thread. Both setters only publish atomics: the callback owns every
// write to controlValues (the storage the plugin reads) and to wet.
void EffectChain::setControl(size_t unitIdx, size_t controlIdx, float value) {
    if (unitIdx >= mUnits.size()) return;
    EffectUnit& unit = *mUnits[unitIdx];
    if (controlIdx >= unit.controlTargets.size()) return;
    unit.controlTargets[controlIdx].store(value, std::memory_order_relaxed);
}

void EffectChain::setUnitState(size_t unitIdx, bool bypass, float mix) {
    if (unitIdx >= mUnits.size()) return;
    EffectUnit& unit = *mUnits[unitIdx];
    const float clamped = std::min(std::max(mix, 0.0f), 1.0f);
    unit.bypass.store(bypass, std::memory_order_relaxed);
    unit.mix.store(clamped, std::memory_order_relaxed);
    unit.wetTarget.store(bypass ? 0.0f : clamped, std::memory_order_relaxed);
}

bool EffectChain::settled(const EffectUnit& unit) {
    if (unit.wet != unit.wetTarget.load(std::memory_order_relaxed)) return false;
    for (size_t ci = 0; ci < unit.controlValues.size(); ++ci) {
        if (unit.controlValues[ci] != unit.controlTargets[ci].load(std::memory_order_relaxed))
            return false;
    }
    return true;
}

void EffectChain::smoothControls(EffectUnit& unit) const {
    for (size_t ci = 0; ci < unit.controlValues.size(); ++ci) {
        const float target = unit.controlTargets[ci].load(std::memory_order_relaxed);
        // Mode switches must not glide through in-between values.
        if (unit.controlSnap[ci]) {
            unit.controlValues[ci] = target;
            continue;
        }
        float v = unit.controlValues[ci] + mCtlCoef * (target - unit.controlValues[ci]);
        if (std::fabs(v - target) < 1e-6f * (1.0f + std::fabs(target))) v = target;
        unit.controlValues[ci] = v;
    }
}

void EffectChain::runUnit(const EffectUnit& unit, float* const src[2], float* const dst[2],
                          int off, int n) {
    const LADSPA_Descriptor* d = unit.info->descriptor;
    if (unit.info->stereo) {
        d->connect_port(unit.handleL, unit.audioIn[0], src[0] + off);
        d->connect_port(unit.handleL, unit.audioIn[1], src[1] + off);
        d->connect_port(unit.handleL, unit.audioOut[0], dst[0] + off);
        d->connect_port(unit.handleL, unit.audioOut[1], dst[1] + off);
        d->run(unit.handleL, static_cast<unsigned long>(n));
        return;
    }
    const LADSPA_Handle handles[2] = {unit.handleL, unit.handleR};
    for (int c = 0; c < 2; ++c) {
        d->connect_port(handles[c], unit.audioIn[0], src[c] + off);
        d->connect_port(handles[c], unit.audioOut[0], dst[c] + off);
        d->run(handles[c], static_cast<unsigned long>(n));
    }
}

void EffectChain::process(float* stereoInterleaved, int frames) {
    if (mUnits.empty() || frames > mMaxFrames) return;

    float* src[2] = {mBufA[0].data(), mBufA[1].data()};
    float* dst[2] = {mBufB[0].data(), mBufB[1].data()};
    for (int i = 0; i < frames; ++i) {
        src[0][i] = stereoInterleaved[2 * i];
        src[1][i] = stereoInterleaved[2 * i + 1];
    }

    for (auto& unitPtr : mUnits) {
        EffectUnit& unit = *unitPtr;

        // Settled fully dry: skip the plugin, keep src as this unit's output.
        // Its state freezes while bypassed, so tails resume stale on re-enable.
        if (unit.wetTarget.load(std::memory_order_relaxed) <= 0.0f && unit.wet < 1e-3f) {
            unit.wet = 0.0f;
            continue;
        }

        // Nothing moving means nothing to re-smooth: one full-length run
        // instead of frames/kCtlBlock of them. This is the normal case; a chain
        // only leaves it while a knob moves.
        const bool isSettled = settled(unit);
        const int block = isSettled ? frames : kCtlBlock;

        for (int off = 0; off < frames; off += block) {
            const int n = std::min(block, frames - off);

            float w0 = unit.wet;
            float w1 = w0;
            if (!isSettled) {
                smoothControls(unit);
                const float target = unit.wetTarget.load(std::memory_order_relaxed);
                w1 = w0 + mCtlCoef * (target - w0);
                if (std::fabs(w1 - target) < 1e-4f) w1 = target;
                unit.wet = w1;
            }

            runUnit(unit, src, dst, off, n);

            // Fully wet must stay bit-exact. A settled blend has w0 == w1, so
            // the ramp collapses to a constant factor.
            if (w0 < 1.0f || w1 < 1.0f) {
                const float step = n > 0 ? (w1 - w0) / static_cast<float>(n) : 0.0f;
                for (int c = 0; c < 2; ++c) {
                    float w = w0;
                    float* wetBuf = dst[c] + off;
                    const float* dryBuf = src[c] + off;
                    for (int i = 0; i < n; ++i) {
                        w += step;
                        wetBuf[i] = w * wetBuf[i] + (1.0f - w) * dryBuf[i];
                    }
                }
            }
        }
        std::swap(src[0], dst[0]);
        std::swap(src[1], dst[1]);
    }

    // After the last swap, src holds the processed audio.
    for (int i = 0; i < frames; ++i) {
        stereoInterleaved[2 * i] = src[0][i];
        stereoInterleaved[2 * i + 1] = src[1][i];
    }
}
