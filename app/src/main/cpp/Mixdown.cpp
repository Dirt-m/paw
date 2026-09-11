#include "Mixdown.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>

#include "EffectHost.h"
#include "Mix.h"
#include "WavFile.h"

namespace {

constexpr int kChunk = 4096;

// Same law the callback's fader stage uses (Mix.h), minus the smoothing: an
// offline render has no zipper noise to avoid, and static faders are what the
// smoother converges to.
void applyGainPan(float* stereo, int frames, float gain, float pan) {
    const PanGains g = panGains(gain, pan);
    for (int i = 0; i < frames; ++i) {
        stereo[2 * i] *= g.l;
        stereo[2 * i + 1] *= g.r;
    }
}

}  // namespace

std::string renderMixdown(const std::vector<MixdownTrack>& tracks, const MixdownTrack& master,
                          int sampleRate, const std::string& outPath) {
    int64_t endFrame = 0;
    for (const MixdownTrack& t : tracks)
        for (const ClipRef& c : t.clips)
            endFrame = std::max(endFrame, c.timelineStart + c.length);
    if (endFrame == 0) return "nothing to render";
    endFrame += sampleRate;  // effect tail

    WavWriter writer;
    if (!writer.open(outPath, sampleRate, WavWriter::Format::I16, 2))
        return "cannot create " + outPath;

    const bool anySolo =
            std::any_of(tracks.begin(), tracks.end(), [](const MixdownTrack& t) { return t.solo; });

    // Fresh effect instances per render; live chains keep their state.
    struct TrackState {
        const MixdownTrack* model;
        TimelineRenderer renderer;
        std::unique_ptr<EffectChain> chain;
    };
    std::vector<TrackState> states;
    states.reserve(tracks.size());
    for (const MixdownTrack& t : tracks) {
        TrackState st;
        st.model = &t;
        if (!t.effectIds.empty())
            st.chain.reset(EffectHost::instance().buildChain(t.effectIds, t.effectValues,
                                                             t.effectBypasses, t.effectMixes,
                                                             sampleRate, kChunk));
        states.push_back(std::move(st));
    }
    std::unique_ptr<EffectChain> masterChain;
    if (!master.effectIds.empty())
        masterChain.reset(EffectHost::instance().buildChain(master.effectIds, master.effectValues,
                                                            master.effectBypasses,
                                                            master.effectMixes,
                                                            sampleRate, kChunk));

    std::vector<float> trackBuf(kChunk * 2), mixBuf(kChunk * 2);
    std::vector<int16_t> pcm(kChunk * 2);

    for (int64_t pos = 0; pos < endFrame; pos += kChunk) {
        const int frames = static_cast<int>(std::min<int64_t>(kChunk, endFrame - pos));
        std::memset(mixBuf.data(), 0, sizeof(float) * 2 * frames);

        for (TrackState& st : states) {
            const MixdownTrack& t = *st.model;
            if (t.mute || (anySolo && !t.solo)) continue;
            st.renderer.render(t.clips, pos, frames, trackBuf.data());
            if (st.chain) st.chain->process(trackBuf.data(), frames);
            applyGainPan(trackBuf.data(), frames, t.gain, t.pan);
            for (int i = 0; i < frames * 2; ++i) mixBuf[i] += trackBuf[i];
        }

        if (masterChain) masterChain->process(mixBuf.data(), frames);
        applyGainPan(mixBuf.data(), frames, master.gain, master.pan);

        // Master mute is applied here, on the samples, and nowhere else, so
        // there is only ever one mute rule.
        for (int i = 0; i < frames * 2; ++i) {
            const float v = std::clamp(master.mute ? 0.0f : mixBuf[i], -1.0f, 1.0f);
            pcm[i] = static_cast<int16_t>(std::lround(v * 32767.0f));
        }
        writer.writeSamples(reinterpret_cast<const uint8_t*>(pcm.data()),
                            static_cast<size_t>(frames) * 4);
    }

    if (!writer.finalize()) return "failed writing " + outPath;
    return "";
}
