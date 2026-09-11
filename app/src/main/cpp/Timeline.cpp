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

void TimelineRenderer::render(const std::vector<ClipRef>& clips, int64_t startFrame,
                              int numFrames, float* dstStereo) {
    std::memset(dstStereo, 0, sizeof(float) * 2 * numFrames);
    const int64_t windowEnd = startFrame + numFrames;

    for (const ClipRef& clip : clips) {
        const int64_t clipEnd = clip.timelineStart + clip.length;
        const int64_t s = std::max(startFrame, clip.timelineStart);
        const int64_t e = std::min(windowEnd, clipEnd);
        if (s >= e) continue;

        WavReader* reader = readerFor(clip.path);
        if (!reader) continue;

        const int64_t frames = e - s;
        const int64_t srcPos = clip.srcStart + (s - clip.timelineStart);
        mScratch.resize(static_cast<size_t>(frames) * 2);
        const int64_t got = reader->readFrames(srcPos, frames, mScratch.data(), 2);

        // Linear edge fades over clip-relative positions. Gain is 0 at the
        // first fade-in sample and 0 at the last fade-out sample; overlapping
        // fades multiply.
        if (clip.fadeIn > 0 || clip.fadeOut > 0) {
            const int64_t rel0 = s - clip.timelineStart;
            const int64_t lastRel = clip.length - 1;
            for (int64_t i = 0; i < got; ++i) {
                const int64_t p = rel0 + i;
                float g = 1.0f;
                if (p < clip.fadeIn)
                    g *= static_cast<float>(p) / static_cast<float>(clip.fadeIn);
                if (clip.fadeOut > 0 && p > lastRel - clip.fadeOut)
                    g *= static_cast<float>(lastRel - p) / static_cast<float>(clip.fadeOut);
                mScratch[2 * i] *= g;
                mScratch[2 * i + 1] *= g;
            }
        }

        float* out = dstStereo + (s - startFrame) * 2;
        for (int64_t i = 0; i < got * 2; ++i) out[i] += mScratch[i];
    }
}
