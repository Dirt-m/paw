#include "Timeline.h"

#include <algorithm>
#include <cstring>

WavReader* TimelineRenderer::readerFor(const std::string& path) {
    auto it = mReaders.find(path);
    if (it != mReaders.end()) return it->second.get();
    if (mFailed.count(path)) return nullptr;
    auto reader = std::make_unique<WavReader>();
    std::string err;
    if (!reader->open(path, err)) {
        mFailed.insert(path);
        return nullptr;
    }
    return mReaders.emplace(path, std::move(reader)).first->second.get();
}

void TimelineRenderer::retain(const std::vector<std::string>& keep) {
    const std::set<std::string> live(keep.begin(), keep.end());
    std::vector<std::string> doomed;
    for (const auto& [path, reader] : mReaders)
        if (!live.count(path)) doomed.push_back(path);
    for (const std::string& path : mFailed)
        if (!live.count(path)) doomed.push_back(path);
    for (const std::string& path : doomed) dropReader(path);
}

namespace {

// True when a clip earlier in the list (lower in the stack) is sounding on
// both sides of frame `edge`, so a clip that starts or ends there switches
// the track from one take to another rather than from or to silence.
bool coveredBelow(const std::vector<ClipRef>& clips, size_t top, int64_t edge) {
    for (size_t j = 0; j < top; ++j) {
        const ClipRef& c = clips[j];
        if (c.length > 0 && c.timelineStart < edge && edge < c.timelineStart + c.length)
            return true;
    }
    return false;
}

}  // namespace

void TimelineRenderer::render(const std::vector<ClipRef>& clips, int64_t startFrame,
                              int numFrames, float* dstStereo) {
    std::memset(dstStereo, 0, sizeof(float) * 2 * numFrames);
    const int64_t windowEnd = startFrame + numFrames;

    // Clips are composited in list order, each one over what the clips before
    // it left in dst: out = under * (1 - g) + clip * g, where g is the clip's
    // edge gain. In the middle of a clip g is 1, so the clip replaces whatever
    // lies under it outright and the top take is the only one heard.
    for (size_t k = 0; k < clips.size(); ++k) {
        const ClipRef& clip = clips[k];
        const int64_t clipEnd = clip.timelineStart + clip.length;
        const int64_t s = std::max(startFrame, clip.timelineStart);
        const int64_t e = std::min(windowEnd, clipEnd);
        if (s >= e) continue;

        // A missing or short take leaves its frames transparent, so the take
        // under it keeps playing.
        WavReader* reader = readerFor(clip.path);
        if (!reader) continue;

        const int64_t frames = e - s;
        const int64_t srcPos = clip.srcStart + (s - clip.timelineStart);
        mScratch.resize(static_cast<size_t>(frames) * 2);
        const int64_t got = reader->readFrames(srcPos, frames, mScratch.data(), 2);
        float* out = dstStereo + (s - startFrame) * 2;

        // Edge ramps, linear over clip-relative positions. The gain is 0 at
        // the first sample of a fade-in and at the last sample of a fade-out;
        // where the two overlap they multiply. An edge that lands inside an
        // older clip gets at least kTakeCrossfadeFrames of ramp even with no
        // fade set, so the switch between takes is a short crossfade and not
        // a click. A longer fade there is itself the crossfade: the take
        // underneath is revealed by it rather than the track dipping to
        // silence. An edge with nothing under it keeps exactly its own fade.
        const int64_t minRamp = std::min<int64_t>(kTakeCrossfadeFrames, clip.length / 2);
        int64_t rampIn = clip.fadeIn;
        int64_t rampOut = clip.fadeOut;
        if (rampIn < minRamp && coveredBelow(clips, k, clip.timelineStart)) rampIn = minRamp;
        if (rampOut < minRamp && coveredBelow(clips, k, clipEnd)) rampOut = minRamp;

        if (rampIn == 0 && rampOut == 0) {
            std::memcpy(out, mScratch.data(), sizeof(float) * 2 * got);
            continue;
        }
        const int64_t rel0 = s - clip.timelineStart;
        const int64_t lastRel = clip.length - 1;
        for (int64_t i = 0; i < got; ++i) {
            const int64_t p = rel0 + i;
            float g = 1.0f;
            if (p < rampIn) g *= static_cast<float>(p) / static_cast<float>(rampIn);
            if (rampOut > 0 && p > lastRel - rampOut)
                g *= static_cast<float>(lastRel - p) / static_cast<float>(rampOut);
            if (g == 1.0f) {
                out[2 * i] = mScratch[2 * i];
                out[2 * i + 1] = mScratch[2 * i + 1];
            } else {
                const float under = 1.0f - g;
                out[2 * i] = out[2 * i] * under + mScratch[2 * i] * g;
                out[2 * i + 1] = out[2 * i + 1] * under + mScratch[2 * i + 1] * g;
            }
        }
    }
}
