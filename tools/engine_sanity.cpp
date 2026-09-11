// Host-side sanity checks for the engine layers that don't need Android:
// WavReader, TimelineRenderer, peaks, the LADSPA host with the built-in
// effects, and the offline mixdown. Build and run:
//
//   g++ -O2 -std=c++17 -Wall -Wextra -Iapp/src/main/cpp tools/engine_sanity.cpp
//       app/src/main/cpp/{WavFile,Timeline,Peaks,EffectHost,Mixdown}.cpp
//       app/src/main/cpp/effects/BuiltinEffects.cpp -ldl
//       -o /tmp/engine_sanity && /tmp/engine_sanity   (one line)
//
// Exits nonzero on any failure. Temp files land in /tmp.

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "EffectHost.h"
#include "Mix.h"
#include "Mixdown.h"
#include "Peaks.h"
#include "Timeline.h"
#include "WavFile.h"

static int gFailures = 0;

static void check(bool ok, const std::string& name) {
    printf("%s  %s\n", ok ? "PASS" : "FAIL", name.c_str());
    if (!ok) ++gFailures;
}

// Writes a mono 16-bit WAV whose sample at frame i is (i % 1000) / 1000.0
// scaled to int16, so the content is position-identifiable for placement tests.
static std::string writeRampTake(const std::string& path, int frames) {
    WavWriter w;
    w.open(path, 48000, WavWriter::Format::I16, 1);
    std::vector<int16_t> data(frames);
    for (int i = 0; i < frames; ++i)
        data[i] = static_cast<int16_t>(std::lround((i % 1000) / 1000.0 * 32767.0));
    w.writeSamples(reinterpret_cast<const uint8_t*>(data.data()), data.size() * 2);
    w.finalize();
    return path;
}

static float rampValue(int frame) {
    return static_cast<float>(std::lround((frame % 1000) / 1000.0 * 32767.0)) / 32768.0f;
}

static void testWavRoundTrip() {
    // Stereo 16-bit round trip, sample-exact.
    const std::string path = "/tmp/ov_rt.wav";
    WavWriter w;
    w.open(path, 48000, WavWriter::Format::I16, 2);
    std::vector<int16_t> data;
    for (int i = 0; i < 300; ++i) {
        data.push_back(static_cast<int16_t>(i * 100 - 15000));
        data.push_back(static_cast<int16_t>(-i * 100 + 15000));
    }
    w.writeSamples(reinterpret_cast<const uint8_t*>(data.data()), data.size() * 2);
    check(w.finalize(), "WavWriter finalize (stereo)");

    WavReader r;
    std::string err;
    check(r.open(path, err), "WavReader open: " + err);
    check(r.channels() == 2 && r.frames() == 300 && r.sampleRate() == 48000,
          "WavReader header fields");

    std::vector<float> buf(300 * 2, -9.0f);
    const int64_t got = r.readFrames(0, 300, buf.data(), 2);
    bool exact = (got == 300);
    for (int i = 0; i < 300 && exact; ++i) {
        exact = buf[2 * i] == data[2 * i] / 32768.0f && buf[2 * i + 1] == data[2 * i + 1] / 32768.0f;
    }
    check(exact, "WavReader 16-bit stereo bit-exact");

    // Offset read + EOF clamp.
    const int64_t got2 = r.readFrames(290, 100, buf.data(), 2);
    check(got2 == 10 && buf[0] == data[580] / 32768.0f, "WavReader offset read + EOF clamp");

    // Mono source duplicated to stereo.
    writeRampTake("/tmp/ov_mono.wav", 100);
    WavReader rm;
    check(rm.open("/tmp/ov_mono.wav", err), "WavReader mono open");
    std::vector<float> sbuf(100 * 2, -9.0f);
    rm.readFrames(0, 100, sbuf.data(), 2);
    check(sbuf[0] == sbuf[1] && sbuf[42 * 2] == sbuf[42 * 2 + 1] && sbuf[42 * 2] == rampValue(42),
          "mono take duplicated to both channels");
}

static void testTimelineRender() {
    writeRampTake("/tmp/ov_take.wav", 48000);
    TimelineRenderer renderer;

    // One clip: take frames [1000, 3000) placed at timeline 5000.
    std::vector<ClipRef> clips = {{"/tmp/ov_take.wav", 1000, 2000, 5000}};
    std::vector<float> buf(512 * 2);

    // Window fully inside the clip: timeline 6000 -> take frame 2000.
    renderer.render(clips, 6000, 512, buf.data());
    bool ok = true;
    for (int i = 0; i < 512 && ok; ++i) ok = buf[2 * i] == rampValue(2000 + i);
    check(ok, "clip window maps timeline to take frames (srcStart + trim)");

    // Window straddling the clip start: silence before, content from exactly
    // frame 5000 (timeline) == take frame 1000.
    renderer.render(clips, 4800, 512, buf.data());
    ok = true;
    for (int i = 0; i < 200 && ok; ++i) ok = buf[2 * i] == 0.0f;
    check(ok, "silence before clip start");
    ok = true;
    for (int i = 200; i < 512 && ok; ++i) ok = buf[2 * i] == rampValue(1000 + i - 200);
    check(ok, "content begins exactly at clip start");

    // Window straddling the clip end (timeline 7000): content then silence.
    renderer.render(clips, 6900, 512, buf.data());
    ok = true;
    for (int i = 0; i < 100 && ok; ++i) ok = buf[2 * i] == rampValue(2900 + i);
    for (int i = 100; i < 512 && ok; ++i) ok = buf[2 * i] == 0.0f;
    check(ok, "content ends exactly at clip end");

    // Split invariant: [1000,3000)@5000 == [1000,1500)@5000 + [1500,3000)@5500.
    std::vector<ClipRef> split = {{"/tmp/ov_take.wav", 1000, 500, 5000},
                                  {"/tmp/ov_take.wav", 1500, 1500, 5500}};
    std::vector<float> whole(1024 * 2), parts(1024 * 2);
    renderer.render(clips, 5200, 1024, whole.data());
    renderer.render(split, 5200, 1024, parts.data());
    check(std::memcmp(whole.data(), parts.data(), sizeof(float) * 1024 * 2) == 0,
          "split clips render identically to the original");

    // Overlapping duplicates sum.
    std::vector<ClipRef> dup = {{"/tmp/ov_take.wav", 1000, 500, 5000},
                                {"/tmp/ov_take.wav", 1000, 500, 5000}};
    renderer.render(dup, 5000, 256, buf.data());
    ok = true;
    for (int i = 0; i < 256 && ok; ++i) ok = std::fabs(buf[2 * i] - 2 * rampValue(1000 + i)) < 1e-6f;
    check(ok, "overlapping clips sum");

    // Missing take renders silence, engine keeps going.
    std::vector<ClipRef> missing = {{"/tmp/ov_nope.wav", 0, 500, 0}};
    renderer.render(missing, 0, 256, buf.data());
    ok = true;
    for (int i = 0; i < 512 && ok; ++i) ok = buf[i] == 0.0f;
    check(ok && renderer.missingTakes() == 1, "missing take renders silence and is counted");

    // Fades: linear ramps over clip-relative frames; first fade-in sample and
    // last fade-out sample are exactly zero, the middle is untouched.
    std::vector<ClipRef> faded = {{"/tmp/ov_take.wav", 1000, 2000, 5000, 100, 200}};
    std::vector<float> fbuf(2048 * 2);
    renderer.render(faded, 5000, 2000, fbuf.data());
    ok = fbuf[0] == 0.0f && fbuf[2 * 1999] == 0.0f;
    check(ok, "fade edges are exactly silent");
    ok = true;
    for (int i = 0; i < 100 && ok; ++i) {
        const float expect = rampValue(1000 + i) * (static_cast<float>(i) / 100.0f);
        ok = std::fabs(fbuf[2 * i] - expect) < 1e-6f;
    }
    check(ok, "fade-in is a linear ramp");
    ok = true;
    for (int i = 1800; i < 2000 && ok; ++i) {
        const float expect = rampValue(1000 + i) * (static_cast<float>(1999 - i) / 200.0f);
        ok = std::fabs(fbuf[2 * i] - expect) < 1e-6f;
    }
    check(ok, "fade-out is a linear ramp");
    ok = true;
    for (int i = 100; i < 1799 && ok; ++i) ok = fbuf[2 * i] == rampValue(1000 + i);
    check(ok, "between fades the clip is bit-exact");

    // A fade applies identically when rendered in windows (the ramp is a
    // function of clip-relative position, not of the render window).
    std::vector<float> w1(1000 * 2), w2(1000 * 2);
    renderer.render(faded, 5000, 1000, w1.data());
    renderer.render(faded, 6000, 1000, w2.data());
    ok = std::memcmp(w1.data(), fbuf.data(), sizeof(float) * 1000 * 2) == 0 &&
         std::memcmp(w2.data(), fbuf.data() + 1000 * 2, sizeof(float) * 1000 * 2) == 0;
    check(ok, "fades are window-invariant");
}

static void testPeaks() {
    // 1000 frames: first 500 at +0.5, rest at -0.25.
    const std::string path = "/tmp/ov_peaks.wav";
    WavWriter w;
    w.open(path, 48000, WavWriter::Format::I16, 1);
    std::vector<int16_t> data(1000);
    for (int i = 0; i < 1000; ++i) data[i] = (i < 500) ? 16384 : -8192;
    w.writeSamples(reinterpret_cast<const uint8_t*>(data.data()), data.size() * 2);
    w.finalize();

    const std::vector<int8_t> peaks = computePeaks(path, 250);
    check(peaks.size() == 8, "peaks: bucket count");
    if (peaks.size() == 8) {
        // Companded values: lround(sqrt(|v|) * 127) with v's sign.
        check(peaks[0] == 0 && peaks[1] == 90 && peaks[4] == -64 && peaks[5] == 0,
              "peaks: min/max per bucket");
    }
    const WavInfo info = wavInfo(path);
    check(info.frames == 1000 && info.channels == 1 && info.sampleRate == 48000, "wavInfo fields");
}

static void testEffectHost() {
    EffectHost& host = EffectHost::instance();
    host.scan("");
    check(host.find("builtin:paw_eq3") != nullptr &&
                  host.find("builtin:paw_comp") != nullptr &&
                  host.find("builtin:paw_delay") != nullptr,
          "built-in effects registered");
    check(host.catalogJson().find("\"ports\"") != std::string::npos, "catalog JSON has ports");

    // Flat EQ chain is a bit-exact wire; unknown ids are skipped.
    EffectChain* chain =
            host.buildChain({"builtin:paw_eq3", "builtin:gone"}, {}, {}, {}, 48000, 512);
    check(chain != nullptr && chain->unitCount() == 1, "chain built, unknown id skipped");
    std::vector<float> buf(512 * 2), ref(512 * 2);
    for (int i = 0; i < 512; ++i) {
        buf[2 * i] = std::sin(i * 0.01f) * 0.5f;
        buf[2 * i + 1] = std::cos(i * 0.02f) * 0.25f;
    }
    ref = buf;
    chain->process(buf.data(), 512);
    check(std::memcmp(buf.data(), ref.data(), sizeof(float) * 512 * 2) == 0,
          "flat EQ chain is bit-exact unity");
    delete chain;

    // EQ3 controls: low dB, mid dB, mid Hz, high dB. A hot low shelf makes
    // the unit audibly non-unity for the blend/bypass checks below.
    const std::vector<std::vector<float>> hotEq = {{12.0f, 0.0f, 1000.0f, 0.0f}};

    // A bypassed unit is a bit-exact wire no matter its settings.
    chain = host.buildChain({"builtin:paw_eq3"}, hotEq, {1}, {1.0f}, 48000, 512);
    buf = ref;
    chain->process(buf.data(), 512);
    check(std::memcmp(buf.data(), ref.data(), sizeof(float) * 512 * 2) == 0,
          "bypassed unit is bit-exact passthrough");
    delete chain;

    // mix = 0 (fully dry) likewise.
    chain = host.buildChain({"builtin:paw_eq3"}, hotEq, {0}, {0.0f}, 48000, 512);
    buf = ref;
    chain->process(buf.data(), 512);
    check(std::memcmp(buf.data(), ref.data(), sizeof(float) * 512 * 2) == 0,
          "mix=0 unit is bit-exact passthrough");
    delete chain;

    // The hot EQ changes the signal when active, which guards the checks
    // above against a unit that was never doing anything.
    chain = host.buildChain({"builtin:paw_eq3"}, hotEq, {}, {}, 48000, 512);
    buf = ref;
    chain->process(buf.data(), 512);
    check(std::memcmp(buf.data(), ref.data(), sizeof(float) * 512 * 2) != 0,
          "hot EQ is not unity when active");
    std::vector<float> wet1 = buf;
    delete chain;

    // Wet/dry blend is linear: mix=0.5 == 0.5*dry + 0.5*wet (biquads are LTI
    // and both chains start from identical fresh state).
    chain = host.buildChain({"builtin:paw_eq3"}, hotEq, {0}, {0.5f}, 48000, 512);
    buf = ref;
    chain->process(buf.data(), 512);
    bool blendOk = true;
    for (int i = 0; i < 512 * 2 && blendOk; ++i)
        blendOk = std::fabs(buf[i] - 0.5f * (ref[i] + wet1[i])) < 1e-5f;
    check(blendOk, "mix=0.5 blends half dry, half wet");
    delete chain;

    // Live bypass ramps down and settles to a bit-exact wire.
    chain = host.buildChain({"builtin:paw_eq3"}, hotEq, {}, {}, 48000, 512);
    chain->setUnitState(0, true, 1.0f);
    for (int pass = 0; pass < 200; ++pass) {  // ~2 s of audio: far past settling
        buf = ref;
        chain->process(buf.data(), 512);
    }
    buf = ref;
    chain->process(buf.data(), 512);
    check(std::memcmp(buf.data(), ref.data(), sizeof(float) * 512 * 2) == 0,
          "live bypass settles to bit-exact passthrough");
    // And ramps back in without blowing up.
    chain->setUnitState(0, false, 1.0f);
    bool finiteOk = true;
    for (int pass = 0; pass < 200 && finiteOk; ++pass) {
        buf = ref;
        chain->process(buf.data(), 512);
        for (int i = 0; i < 512 * 2 && finiteOk; ++i) finiteOk = std::isfinite(buf[i]);
    }
    check(finiteOk, "un-bypass ramps back in, output stays finite");
    check(std::memcmp(buf.data(), ref.data(), sizeof(float) * 512 * 2) != 0,
          "un-bypassed unit is processing again");
    delete chain;
}

// The pan law lives in one header (Mix.h), called by both the audio callback's
// fader stage and the offline mixdown. Pin it against the formula so the shared
// version can't quietly drift.
static void testPanLaw() {
    bool ok = true;
    for (float pan : {-1.0f, -0.6f, -0.001f, 0.0f, 0.001f, 0.25f, 1.0f}) {
        for (float gain : {0.0f, 0.5f, 1.0f, 2.0f}) {
            const PanGains g = panGains(gain, pan);
            const float wantL = gain * (pan >= 0.0f ? 1.0f - pan : 1.0f);
            const float wantR = gain * (pan <= 0.0f ? 1.0f + pan : 1.0f);
            ok = ok && g.l == wantL && g.r == wantR;
        }
    }
    check(ok, "panGains matches the fader/mixdown law exactly");
    const PanGains centre = panGains(0.8f, 0.0f);
    check(centre.l == 0.8f && centre.r == 0.8f, "centre pan is unity on both channels");
}

// A settled chain hands the plugin the whole burst in one run() instead of a
// string of 64-frame sub-blocks. That shortcut is only sound if the settled
// path is block-size invariant, so pin it: one 512-frame call must equal eight
// 64-frame calls, bit for bit, fully wet and half wet alike.
static void testChainBlockInvariance() {
    EffectHost& host = EffectHost::instance();
    std::vector<float> src(512 * 2);
    for (int i = 0; i < 512; ++i) {
        src[2 * i] = std::sin(i * 0.013f) * 0.4f;
        src[2 * i + 1] = std::cos(i * 0.021f) * 0.3f;
    }
    const std::vector<std::vector<float>> hotEq = {{12.0f, -6.0f, 900.0f, 6.0f}};
    for (float mix : {1.0f, 0.5f}) {
        EffectChain* whole =
                host.buildChain({"builtin:paw_eq3"}, hotEq, {0}, {mix}, 48000, 512);
        EffectChain* split =
                host.buildChain({"builtin:paw_eq3"}, hotEq, {0}, {mix}, 48000, 512);
        std::vector<float> a = src, b = src;
        whole->process(a.data(), 512);
        for (int off = 0; off < 512; off += 64) split->process(b.data() + off * 2, 64);
        check(std::memcmp(a.data(), b.data(), sizeof(float) * 512 * 2) == 0,
              mix == 1.0f ? "settled chain: one 512 burst == eight 64 bursts (wet)"
                          : "settled chain: one 512 burst == eight 64 bursts (mix 0.5)");
        delete whole;
        delete split;
    }
}

// Readers and remembered open failures are scoped to what the clips still
// reference, so a long session cannot run out of file handles and a take that
// failed once is not silent forever.
static void testRendererLifecycle() {
    writeRampTake("/tmp/ov_life.wav", 4800);
    const std::string missing = "/tmp/ov_life_missing.wav";
    std::remove(missing.c_str());
    TimelineRenderer r;
    const std::vector<ClipRef> gone = {{missing, 0, 4800, 0}};
    std::vector<float> buf(256 * 2);

    r.render(gone, 0, 256, buf.data());
    check(r.missingTakes() == 1, "renderer counts a referenced take it cannot open");
    r.retain({"/tmp/ov_life.wav"});
    check(r.missingTakes() == 0, "retain forgets failures no clip references");
    r.render(gone, 0, 256, buf.data());
    check(r.missingTakes() == 1, "a forgotten failure is retried, not remembered");
    r.retain({missing});
    check(r.missingTakes() == 1, "retain keeps failures the clips still reference");
    r.clear();
    check(r.missingTakes() == 0, "clear forgets every reader and failure");
}

static void testMixdown() {
    writeRampTake("/tmp/ov_mix_take.wav", 4800);

    MixdownTrack a;
    a.clips = {{"/tmp/ov_mix_take.wav", 0, 4800, 0}};
    a.gain = 0.5f;
    MixdownTrack b;
    b.clips = {{"/tmp/ov_mix_take.wav", 0, 4800, 2400}};
    b.mute = true;
    MixdownTrack master;

    const std::string out = "/tmp/ov_mixdown.wav";
    std::string err = renderMixdown({a, b}, master, 48000, out);
    check(err.empty(), "mixdown renders: " + err);

    WavReader r;
    check(r.open(out, err) && r.channels() == 2 && r.sampleRate() == 48000, "mixdown WAV opens");
    // Track b is muted: at frame 3000 only track a at gain 0.5 sounds.
    std::vector<float> buf(2);
    r.readFrames(3000, 1, buf.data(), 2);
    const float expect = 0.5f * rampValue(3000);
    check(std::fabs(buf[0] - expect) < 2.0f / 32768.0f, "mixdown applies gain and mute");
    // Ends one second (48000 frames) after the last clip end (7200).
    check(r.frames() == 7200 + 48000, "mixdown length = last clip end + 1 s tail");

    err = renderMixdown({}, master, 48000, "/tmp/ov_empty.wav");
    check(!err.empty(), "empty mixdown reports an error");
}

// A recorder crash never reaches finalize(). The reader must recover the
// audio anyway: from a periodically-patched header, or, when even that never
// ran, from the file length itself.
static void testCrashRecovery() {
    std::vector<int16_t> data(4800);
    for (size_t i = 0; i < data.size(); ++i) data[i] = static_cast<int16_t>(i);

    // Header patched by flushHeader, then the process "dies" (writer leaks
    // its FILE via destructor; sizes were already on disk).
    const std::string patched = "/tmp/ov_crash_patched.wav";
    {
        WavWriter w;
        w.open(patched, 48000, WavWriter::Format::I16, 1);
        w.writeSamples(reinterpret_cast<const uint8_t*>(data.data()), data.size() * 2);
        check(w.flushHeader(), "flushHeader reports clean I/O");
    }
    WavReader r;
    std::string err;
    check(r.open(patched, err) && r.frames() == 4800,
          "crashed take with flushed header reads full length");

    // No flushHeader at all: the header still says zero frames. The reader
    // derives the count from the bytes actually on disk.
    const std::string bare = "/tmp/ov_crash_bare.wav";
    {
        WavWriter w;
        w.open(bare, 48000, WavWriter::Format::I16, 1);
        w.writeSamples(reinterpret_cast<const uint8_t*>(data.data()), data.size() * 2);
    }
    check(r.open(bare, err) && r.frames() == 4800,
          "crashed take with zero-size header recovers via file length");
    std::vector<float> f(4800);
    check(r.readFrames(0, 4800, f.data(), 1) == 4800 &&
              f[100] == 100 / 32768.0f && f[4799] == 4799 / 32768.0f,
          "recovered take reads sample-exact");

    // A well-formed header with trailing garbage after the data chunk must
    // NOT be second-guessed by the length fallback.
    const std::string trailing = "/tmp/ov_trailing.wav";
    {
        WavWriter w;
        w.open(trailing, 48000, WavWriter::Format::I16, 1);
        w.writeSamples(reinterpret_cast<const uint8_t*>(data.data()), 100 * 2);
        w.finalize();
        FILE* fp = fopen(trailing.c_str(), "ab");
        const char junk[64] = {0};
        fwrite(junk, 1, sizeof junk, fp);
        fclose(fp);
    }
    check(r.open(trailing, err) && r.frames() == 100,
          "valid header with trailing bytes keeps its declared length");
}

int main() {
    testWavRoundTrip();
    testCrashRecovery();
    testTimelineRender();
    testRendererLifecycle();
    testPeaks();
    testEffectHost();
    testChainBlockInvariance();
    testPanLaw();
    testMixdown();
    printf(gFailures ? "\n== %d FAILURE(S) ==\n" : "\n== ALL PASS ==\n", gFailures);
    return gFailures ? 1 : 0;
}
