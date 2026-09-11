#include "Peaks.h"

#include <algorithm>
#include <cmath>

#include "WavFile.h"

WavInfo wavInfo(const std::string& wavPath) {
    WavReader reader;
    std::string err;
    WavInfo info;
    if (!reader.open(wavPath, err)) return info;
    info.frames = reader.frames();
    info.channels = reader.channels();
    info.sampleRate = reader.sampleRate();
    return info;
}

// Buckets are stored companded (sqrt of the amplitude): linear int8 zeroes out
// everything under ~-48 dBFS, which draws a quiet take as nothing at all. In
// the sqrt domain the smallest nonzero step is ~-84 dBFS, and the UI draws the
// values linearly to get the perceptual curve for free.
static int8_t compand(float v) {
    const float c = std::clamp(v, -1.0f, 1.0f);
    const float mag = std::sqrt(std::fabs(c));
    return static_cast<int8_t>(std::lround((c < 0.0f ? -mag : mag) * 127));
}

std::vector<int8_t> computePeaks(const std::string& wavPath, int bucketFrames) {
    std::vector<int8_t> out;
    WavReader reader;
    std::string err;
    if (!reader.open(wavPath, err) || bucketFrames <= 0) return out;

    const int64_t frames = reader.frames();
    const int channels = reader.channels();
    const int64_t buckets = (frames + bucketFrames - 1) / bucketFrames;
    out.reserve(static_cast<size_t>(buckets) * 2);

    const int64_t chunkFrames = 1 << 16;
    std::vector<float> buf(static_cast<size_t>(chunkFrames) * channels);

    int64_t pos = 0;
    float lo = 0.0f, hi = 0.0f;
    int64_t inBucket = 0;
    while (pos < frames) {
        const int64_t got = reader.readFrames(pos, chunkFrames, buf.data(), channels);
        if (got <= 0) break;
        for (int64_t f = 0; f < got; ++f) {
            for (int c = 0; c < channels; ++c) {
                const float v = buf[f * channels + c];
                lo = std::min(lo, v);
                hi = std::max(hi, v);
            }
            if (++inBucket == bucketFrames) {
                out.push_back(compand(lo));
                out.push_back(compand(hi));
                lo = hi = 0.0f;
                inBucket = 0;
            }
        }
        pos += got;
    }
    if (inBucket > 0) {
        out.push_back(compand(lo));
        out.push_back(compand(hi));
    }
    return out;
}
