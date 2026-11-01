#pragma once

#include <cstdint>
#include <string>
#include <vector>

// The sampler's sounds and the voices that play them.
//
// A Sample is a slice of a take read into memory once, so a touch can start it
// inside the next callback burst instead of waiting on the disk thread, whose
// rings run up to ~0.7 s ahead. A SampleBank is every sample the open song
// uses; the engine publishes it through an atomic pointer and retires the old
// one like any other graph object.
//
// The voice pool is callback-owned and never allocates. A voice holds a
// pointer into the bank it was started from, so the pool forgets every voice
// the moment it sees a different bank.

struct Sample {
    int trackId = 0;            // the sample track whose chain it plays through
    std::vector<float> stereo;  // interleaved stereo float, the whole slice
    int64_t frames = 0;
};

struct SampleBank {
    std::vector<Sample> samples;
};

// One programmed hit: a sample (index into the bank) starting at a frame.
struct SampleHit {
    int64_t frame;
    int32_t sample;
    float velocity;
};

// Hits sorted by frame. For the timeline the frames are timeline frames; for
// a pattern preview they are offsets into a loop of loopFrames.
struct HitList {
    std::vector<SampleHit> hits;
    int64_t loopFrames = 0;
};

// Reads [srcStart, srcStart + length) of a take into out.stereo. Control
// threads only: it allocates and does file I/O. False with err set when the
// take cannot be opened or holds nothing in that range.
bool loadSampleSlice(const std::string& path, int64_t srcStart, int64_t length, Sample& out,
                     std::string& err);

class VoicePool {
public:
    // Voices that may sound at once. Past this the oldest fades out over
    // kStealFrames; the pool keeps spare slots so the fading voice and its
    // replacement can overlap.
    static constexpr int kMaxSounding = 32;
    static constexpr int kSlots = 48;
    static constexpr int kStealFrames = 96;  // 2 ms at 48 kHz

    // Forgets every voice if bank is not the one they were started from.
    void adopt(const SampleBank* bank);

    // Starts sample index at gain velocity (0..1), delay frames into the next
    // render. Ignored when the index is out of range for the adopted bank.
    void start(int index, float velocity, int delay = 0);

    // Starts every hit in [from, from + frames) of list, each at its own
    // offset plus burstOffset into the next render.
    void startHits(const HitList& list, int64_t from, int frames, int burstOffset);

    // Adds every voice into the stereo buffer of its sample's track and
    // advances it by frames. bufferFor(trackId) returns that buffer (frames
    // stereo floats) or nullptr, in which case the voice advances silently.
    template <typename BufferFor>
    void render(int frames, BufferFor&& bufferFor) {
        for (Voice& v : mVoices) {
            if (v.sample < 0) continue;
            renderVoice(v, frames, bufferFor(mBank->samples[v.sample].trackId));
        }
    }

    int sounding() const;

private:
    struct Voice {
        int sample = -1;  // index into the bank; -1 = free
        int64_t pos = 0;
        int delay = 0;  // frames of the next render before the sample starts
        float gain = 1.0f;
        int fadeLeft = -1;  // frames left of a steal fade; -1 = not fading
        uint64_t order = 0;
    };

    void renderVoice(Voice& v, int frames, float* dst);

    const SampleBank* mBank = nullptr;
    Voice mVoices[kSlots];
    uint64_t mNextOrder = 1;
};
