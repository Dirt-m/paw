#pragma once

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

struct WavData {
    int sampleRate = 0;
    int channels = 0;
    std::vector<float> interleaved;  // [-1, 1]
};

// Reads a 16-bit PCM WAV (the bundled test signal). Returns false on any
// parse problem; err gets a short reason.
bool loadWav16(const std::string& path, WavData& out, std::string& err);

// Converts one sample from any format AAudio delivers to float in [-1, 1).
inline float pcmSampleToFloat(const uint8_t* p, int bytesPerSample, bool isFloat) {
    if (isFloat) {
        float v;
        __builtin_memcpy(&v, p, 4);
        return v;
    }
    switch (bytesPerSample) {
        case 2: {
            int16_t v;
            __builtin_memcpy(&v, p, 2);
            return v / 32768.0f;
        }
        case 3: {  // packed little-endian, sign in the top byte
            const uint32_t u = static_cast<uint32_t>(p[0]) << 8 |
                               static_cast<uint32_t>(p[1]) << 16 |
                               static_cast<uint32_t>(p[2]) << 24;
            return static_cast<float>(static_cast<int32_t>(u) >> 8) / 8388608.0f;
        }
        default: {
            int32_t v;
            __builtin_memcpy(&v, p, 4);
            return v / 2147483648.0f;
        }
    }
}

// Streams sample data to disk in one of the formats AAudio can deliver,
// so takes land bit-identical to the hardware stream.
class WavWriter {
public:
    enum class Format { I16, I24Packed, I32, Float32 };

    bool open(const std::string& path, int sampleRate, Format fmt, int channels = 1);
    void writeSamples(const uint8_t* data, size_t bytes);  // raw interleaved sample bytes
    // Patches the RIFF sizes in place and flushes, leaving the file valid as
    // written so far, then returns the write position to the end. Called
    // periodically during capture so a crashed process leaves a readable WAV
    // instead of one whose header claims zero frames.
    bool flushHeader();
    bool finalize();  // patches RIFF sizes; returns false on any I/O error so far
    ~WavWriter();

private:
    bool patchSizes(long end);

    FILE* mFile = nullptr;
    long mDataSizePos = 0;
    long mRiffSizePos = 0;
    size_t mDataBytes = 0;
    int mBytesPerFrame = 1;
    bool mIoError = false;
};

// Seekable streaming reader for the take files the engine plays back:
// PCM 16/24-packed/32-bit int and 32-bit float, any channel count.
// Immutable takes mean one reader per consumer needs no locking.
class WavReader {
public:
    bool open(const std::string& path, std::string& err);
    void close();
    ~WavReader() { close(); }

    int sampleRate() const { return mSampleRate; }
    int channels() const { return mChannels; }
    int64_t frames() const { return mFrames; }

    // Reads up to numFrames starting at frameOffset, converted to float and
    // mapped to dstChannels (mono sources duplicate; averaging when folding
    // down to mono). Returns frames actually read; dst gets frames*dstChannels
    // floats. Frames past EOF are not written; the caller zero-fills.
    int64_t readFrames(int64_t frameOffset, int64_t numFrames, float* dst, int dstChannels);

private:
    FILE* mFile = nullptr;
    int mSampleRate = 0;
    int mChannels = 0;
    int mBytesPerSample = 0;
    bool mFloat = false;
    int64_t mFrames = 0;
    long mDataOffset = 0;
    std::vector<uint8_t> mBuf;
};
