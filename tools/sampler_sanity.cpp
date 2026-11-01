// Host-side checks for the sampler: slice loading and the voice pool. Build
// and run:
//
//   g++ -O2 -std=c++17 -Wall -Wextra -Iapp/src/main/cpp tools/sampler_sanity.cpp
//       app/src/main/cpp/{WavFile,Sampler}.cpp -o /tmp/sampler_sanity
//       && /tmp/sampler_sanity   (one line)
//
// Exits nonzero on any failure. Temp files land in /tmp.

#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

#include "Sampler.h"
#include "WavFile.h"

static int gFailures = 0;

#define CHECK(cond)                                                    \
    do {                                                               \
        if (!(cond)) {                                                 \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++gFailures;                                               \
        }                                                              \
    } while (0)

static bool near(float a, float b) { return std::fabs(a - b) < 1e-4f; }

// A mono 16-bit take whose frame i holds the value i / 32768.
static std::string writeRampTake(int frames) {
    const std::string path = "/tmp/paw_sampler_ramp.wav";
    WavWriter w;
    w.open(path, 48000, WavWriter::Format::I16, 1);
    std::vector<int16_t> pcm(frames);
    for (int i = 0; i < frames; ++i) pcm[i] = static_cast<int16_t>(i);
    w.writeSamples(reinterpret_cast<const uint8_t*>(pcm.data()), pcm.size() * 2);
    w.finalize();
    return path;
}

// One sample of constant value v, frames long, on trackId.
static Sample constant(int trackId, float v, int frames) {
    Sample s;
    s.trackId = trackId;
    s.frames = frames;
    s.stereo.assign(static_cast<size_t>(frames) * 2, v);
    return s;
}

static void testSliceLoad() {
    const std::string path = writeRampTake(1000);
    Sample s;
    std::string err;
    CHECK(loadSampleSlice(path, 100, 50, s, err));
    CHECK(s.frames == 50);
    CHECK(near(s.stereo[0], 100 / 32768.0f));  // mono lands on both sides
    CHECK(near(s.stereo[1], 100 / 32768.0f));
    CHECK(near(s.stereo[98], 149 / 32768.0f));

    // A slice running past the end is cut at the end of the take.
    Sample tail;
    CHECK(loadSampleSlice(path, 990, 50, tail, err));
    CHECK(tail.frames == 10);

    Sample none;
    CHECK(!loadSampleSlice(path, 2000, 50, none, err));
    CHECK(!loadSampleSlice("/tmp/paw_no_such_take.wav", 0, 50, none, err));
}

static void testPlayAndRoute() {
    SampleBank bank;
    bank.samples.push_back(constant(7, 0.5f, 100));
    bank.samples.push_back(constant(9, 0.25f, 100));
    VoicePool pool;
    pool.adopt(&bank);
    pool.start(0, 1.0f);
    pool.start(1, 0.5f);
    pool.start(5, 1.0f);  // out of range: ignored
    CHECK(pool.sounding() == 2);

    std::vector<float> t7(128, 0.0f), t9(128, 0.0f);
    auto bufferFor = [&](int id) -> float* {
        return id == 7 ? t7.data() : id == 9 ? t9.data() : nullptr;
    };
    pool.render(64, bufferFor);
    CHECK(near(t7[0], 0.5f) && near(t7[127], 0.5f));
    CHECK(near(t9[0], 0.125f));  // velocity scales

    // 36 frames left of 100; the rest of the buffer stays untouched.
    std::fill(t7.begin(), t7.end(), 0.0f);
    std::fill(t9.begin(), t9.end(), 0.0f);
    pool.render(64, bufferFor);
    CHECK(near(t7[2 * 35], 0.5f));
    CHECK(near(t7[2 * 36], 0.0f));
    CHECK(pool.sounding() == 0);
}

static void testRetriggerOverlaps() {
    SampleBank bank;
    bank.samples.push_back(constant(1, 0.1f, 1000));
    VoicePool pool;
    pool.adopt(&bank);
    pool.start(0, 1.0f);
    pool.start(0, 1.0f);  // a second hit rings on top of the first
    std::vector<float> buf(64, 0.0f);
    pool.render(32, [&](int) { return buf.data(); });
    CHECK(near(buf[0], 0.2f));
}

static void testStealFades() {
    SampleBank bank;
    bank.samples.push_back(constant(1, 1.0f, 10000));
    VoicePool pool;
    pool.adopt(&bank);
    for (int i = 0; i < VoicePool::kMaxSounding; ++i) pool.start(0, 1.0f);
    CHECK(pool.sounding() == VoicePool::kMaxSounding);
    pool.start(0, 1.0f);  // the oldest starts fading; the new one sounds
    CHECK(pool.sounding() == VoicePool::kMaxSounding);

    // First frame: 32 full voices plus the fading one at gain 1. Last frame of
    // the fade: 32 plus 1/96. After it, exactly 32.
    std::vector<float> buf(2 * 200, 0.0f);
    pool.render(200, [&](int) { return buf.data(); });
    CHECK(near(buf[0], 33.0f));
    CHECK(near(buf[2 * 95], 32.0f + 1.0f / VoicePool::kStealFrames));
    CHECK(near(buf[2 * 96], 32.0f));

    // Hammering past every spare slot never crashes and never exceeds the cap.
    for (int i = 0; i < 500; ++i) pool.start(0, 1.0f);
    CHECK(pool.sounding() <= VoicePool::kMaxSounding);
}

static void testNewBankSilencesOld() {
    SampleBank a, b;
    a.samples.push_back(constant(1, 1.0f, 1000));
    b.samples.push_back(constant(1, 1.0f, 1000));
    VoicePool pool;
    pool.adopt(&a);
    pool.start(0, 1.0f);
    pool.adopt(&a);  // same bank: voices keep going
    CHECK(pool.sounding() == 1);
    pool.adopt(&b);
    CHECK(pool.sounding() == 0);
    pool.adopt(nullptr);
    pool.start(0, 1.0f);  // no bank: ignored
    CHECK(pool.sounding() == 0);
}

static void testDelayedStartsAndHitLists() {
    SampleBank bank;
    bank.samples.push_back(constant(1, 1.0f, 10));
    VoicePool pool;
    pool.adopt(&bank);

    // A hit 70 frames into a 64 frame render sounds 6 frames into the next.
    HitList list;
    list.hits = {{70, 0, 1.0f}, {200, 0, 1.0f}};
    pool.startHits(list, 0, 128, 0);  // only the first is in range
    CHECK(pool.sounding() == 1);
    std::vector<float> buf(2 * 64, 0.0f);
    pool.render(64, [&](int) { return buf.data(); });
    CHECK(near(buf[2 * 63], 0.0f));
    std::fill(buf.begin(), buf.end(), 0.0f);
    pool.render(64, [&](int) { return buf.data(); });
    CHECK(near(buf[2 * 5], 0.0f));
    CHECK(near(buf[2 * 6], 1.0f) && near(buf[2 * 15], 1.0f));
    CHECK(near(buf[2 * 16], 0.0f));

    // burstOffset shifts every start: a window starting at frame 190 placed
    // 20 frames into the burst puts the hit at 200 on burst frame 30.
    std::fill(buf.begin(), buf.end(), 0.0f);
    pool.startHits(list, 190, 20, 20);
    pool.render(64, [&](int) { return buf.data(); });
    CHECK(near(buf[2 * 29], 0.0f) && near(buf[2 * 30], 1.0f));
}

int main() {
    testDelayedStartsAndHitLists();
    testSliceLoad();
    testPlayAndRoute();
    testRetriggerOverlaps();
    testStealFades();
    testNewBankSilencesOld();
    if (gFailures) {
        std::fprintf(stderr, "%d failure(s)\n", gFailures);
        return 1;
    }
    std::printf("sampler_sanity: all checks passed\n");
    return 0;
}
