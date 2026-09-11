#pragma once

#include <cstdint>
#include <string>
#include <vector>

// Waveform peak data for UI rendering: per bucket of frames, the min and max
// sample across all channels, companded to int8 (sign(v) * sqrt(|v|) * 127,
// see Peaks.cpp). The UI aggregates buckets further when zoomed out. Returns
// an empty vector if the file can't be read.
std::vector<int8_t> computePeaks(const std::string& wavPath, int bucketFrames);

// {frames, channels, sampleRate}, all zero on failure.
struct WavInfo {
    int64_t frames = 0;
    int channels = 0;
    int sampleRate = 0;
};
WavInfo wavInfo(const std::string& wavPath);
