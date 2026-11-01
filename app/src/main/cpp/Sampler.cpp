#include "Sampler.h"

#include <algorithm>

#include "WavFile.h"

bool loadSampleSlice(const std::string& path, int64_t srcStart, int64_t length, Sample& out,
                     std::string& err) {
    WavReader reader;
    if (!reader.open(path, err)) return false;
    const int64_t start = std::max<int64_t>(0, srcStart);
    const int64_t frames = std::min(length, reader.frames() - start);
    if (frames <= 0) {
        err = "empty slice";
        return false;
    }
    out.stereo.assign(static_cast<size_t>(frames) * 2, 0.0f);
    out.frames = reader.readFrames(start, frames, out.stereo.data(), 2);
    if (out.frames <= 0) {
        err = "read failed";
        return false;
    }
    out.stereo.resize(static_cast<size_t>(out.frames) * 2);
    return true;
}

void VoicePool::adopt(const SampleBank* bank) {
    if (bank == mBank) return;
    mBank = bank;
    for (Voice& v : mVoices) v.sample = -1;
}

int VoicePool::sounding() const {
    int n = 0;
    for (const Voice& v : mVoices)
        if (v.sample >= 0 && v.fadeLeft < 0) ++n;
    return n;
}

void VoicePool::start(int index, float velocity) {
    if (!mBank || index < 0 || index >= static_cast<int>(mBank->samples.size())) return;

    // Too many sounding: fade the oldest one out under the new one.
    if (sounding() >= kMaxSounding) {
        Voice* oldest = nullptr;
        for (Voice& v : mVoices)
            if (v.sample >= 0 && v.fadeLeft < 0 && (!oldest || v.order < oldest->order))
                oldest = &v;
        if (oldest) oldest->fadeLeft = kStealFrames;
    }

    // A free slot, else the oldest slot of all, cut hard. That only happens
    // when every spare slot is still fading, which takes kSlots - kMaxSounding
    // triggers inside 2 ms.
    Voice* slot = nullptr;
    for (Voice& v : mVoices) {
        if (v.sample < 0) {
            slot = &v;
            break;
        }
        if (!slot || v.order < slot->order) slot = &v;
    }
    slot->sample = index;
    slot->pos = 0;
    slot->gain = std::clamp(velocity, 0.0f, 1.0f);
    slot->fadeLeft = -1;
    slot->order = mNextOrder++;
}

void VoicePool::renderVoice(Voice& v, int frames, float* dst) {
    const Sample& s = mBank->samples[v.sample];
    int n = static_cast<int>(std::min<int64_t>(frames, s.frames - v.pos));
    if (v.fadeLeft >= 0) n = std::min(n, v.fadeLeft);
    if (dst) {
        const float* src = s.stereo.data() + v.pos * 2;
        if (v.fadeLeft < 0) {
            for (int i = 0; i < 2 * n; ++i) dst[i] += src[i] * v.gain;
        } else {
            for (int i = 0; i < n; ++i) {
                const float g = v.gain * static_cast<float>(v.fadeLeft - i) / kStealFrames;
                dst[2 * i] += src[2 * i] * g;
                dst[2 * i + 1] += src[2 * i + 1] * g;
            }
        }
    }
    v.pos += n;
    if (v.fadeLeft >= 0) v.fadeLeft -= n;
    if (v.pos >= s.frames || v.fadeLeft == 0) v.sample = -1;
}
