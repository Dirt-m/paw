// Host-side sanity check for the built-in LADSPA effects. No Android, no Oboe.
//
//   g++ -O2 -std=c++17 -Wall -Wextra -Iapp/src/main/cpp tools/effects_sanity.cpp
//       app/src/main/cpp/effects/BuiltinEffects.cpp -o /tmp/fx_sanity && /tmp/fx_sanity
//
// (one line; wrapped here for readability)
//
// Exits nonzero if any check fails.

#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <random>
#include <vector>

#include "effects/BuiltinEffects.h"

namespace {

int gFailures = 0;

__attribute__((format(printf, 2, 3)))
void check(bool ok, const char* fmt, ...) {
    char msg[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    printf("%s  %s\n", ok ? "PASS" : "FAIL", msg);
    if (!ok) ++gFailures;
}

// The LADSPA v1.1 default rules, exactly as the header specifies them. The
// app's parameter UI has to do the same thing, so exercising it here keeps the
// descriptors honest.
float defaultOf(const LADSPA_PortRangeHint& h, unsigned long rate) {
    const LADSPA_PortRangeHintDescriptor hd = h.HintDescriptor;
    float lo = h.LowerBound;
    float hi = h.UpperBound;
    if (LADSPA_IS_HINT_SAMPLE_RATE(hd)) {
        lo *= static_cast<float>(rate);
        hi *= static_cast<float>(rate);
    }
    const bool log = LADSPA_IS_HINT_LOGARITHMIC(hd) && lo > 0.0f && hi > 0.0f;
    auto blend = [&](float a, float b) {
        return log ? std::exp(std::log(lo) * a + std::log(hi) * b) : lo * a + hi * b;
    };

    float v = 0.0f;
    if (LADSPA_IS_HINT_DEFAULT_MINIMUM(hd)) v = lo;
    else if (LADSPA_IS_HINT_DEFAULT_LOW(hd)) v = blend(0.75f, 0.25f);
    else if (LADSPA_IS_HINT_DEFAULT_MIDDLE(hd)) v = blend(0.5f, 0.5f);
    else if (LADSPA_IS_HINT_DEFAULT_HIGH(hd)) v = blend(0.25f, 0.75f);
    else if (LADSPA_IS_HINT_DEFAULT_MAXIMUM(hd)) v = hi;
    else if (LADSPA_IS_HINT_DEFAULT_0(hd)) v = 0.0f;
    else if (LADSPA_IS_HINT_DEFAULT_1(hd)) v = 1.0f;
    else if (LADSPA_IS_HINT_DEFAULT_100(hd)) v = 100.0f;
    else if (LADSPA_IS_HINT_DEFAULT_440(hd)) v = 440.0f;
    if (LADSPA_IS_HINT_INTEGER(hd)) v = std::round(v);
    return v;
}

bool isControlIn(const LADSPA_Descriptor* d, unsigned long p) {
    return LADSPA_IS_PORT_CONTROL(d->PortDescriptors[p]) &&
           LADSPA_IS_PORT_INPUT(d->PortDescriptors[p]);
}

// Instantiates one plugin, holds its control values, and drives audio through
// it in host-sized blocks (re-connecting the audio ports per block, which the
// LADSPA contract allows).
class Fx {
public:
    Fx(const LADSPA_Descriptor* d, unsigned long rate) : mDesc(d), mCtl(d->PortCount, 0.0f) {
        mHandle = d->instantiate(d, rate);
        if (!mHandle) return;
        for (unsigned long p = 0; p < d->PortCount; ++p) {
            if (isControlIn(d, p)) {
                mCtl[p] = defaultOf(d->PortRangeHints[p], rate);
                d->connect_port(mHandle, p, &mCtl[p]);
            } else if (LADSPA_IS_PORT_AUDIO(d->PortDescriptors[p])) {
                (LADSPA_IS_PORT_INPUT(d->PortDescriptors[p]) ? mAudioIn : mAudioOut)
                        .push_back(p);
            }
        }
    }

    ~Fx() {
        if (!mHandle) return;
        if (mDesc->deactivate) mDesc->deactivate(mHandle);
        mDesc->cleanup(mHandle);
    }

    Fx(const Fx&) = delete;
    Fx& operator=(const Fx&) = delete;

    bool valid() const { return mHandle != nullptr && mAudioIn.size() == 2 && mAudioOut.size() == 2; }
    void set(unsigned long port, float v) { mCtl[port] = v; }

    void activate() {
        if (mDesc->activate) mDesc->activate(mHandle);
    }

    void process(const float* inL, const float* inR, float* outL, float* outR,
                 size_t frames, size_t block) {
        for (size_t off = 0; off < frames; off += block) {
            const size_t n = std::min(block, frames - off);
            mDesc->connect_port(mHandle, mAudioIn[0], const_cast<float*>(inL) + off);
            mDesc->connect_port(mHandle, mAudioIn[1], const_cast<float*>(inR) + off);
            mDesc->connect_port(mHandle, mAudioOut[0], outL + off);
            mDesc->connect_port(mHandle, mAudioOut[1], outR + off);
            mDesc->run(mHandle, n);
        }
    }

private:
    const LADSPA_Descriptor* mDesc;
    LADSPA_Handle mHandle = nullptr;
    std::vector<float> mCtl;
    std::vector<unsigned long> mAudioIn, mAudioOut;
};

std::vector<float> sine(size_t frames, float hz, float rate, float amp) {
    std::vector<float> v(frames);
    for (size_t i = 0; i < frames; ++i)
        v[i] = amp * std::sin(2.0f * 3.14159265f * hz * static_cast<float>(i) / rate);
    return v;
}

float rms(const std::vector<float>& v, size_t from, size_t to) {
    double acc = 0.0;
    for (size_t i = from; i < to; ++i) acc += static_cast<double>(v[i]) * v[i];
    return static_cast<float>(std::sqrt(acc / static_cast<double>(to - from)));
}

float peak(const std::vector<float>& v, size_t from, size_t to) {
    float p = 0.0f;
    for (size_t i = from; i < to; ++i) p = std::fmax(p, std::fabs(v[i]));
    return p;
}

bool allFinite(const std::vector<float>& v) {
    for (float x : v)
        if (!std::isfinite(x)) return false;
    return true;
}

float maxDiff(const std::vector<float>& a, const std::vector<float>& b) {
    float m = 0.0f;
    for (size_t i = 0; i < a.size(); ++i) m = std::fmax(m, std::fabs(a[i] - b[i]));
    return m;
}

// Largest sample-to-sample jump. A gain that steps instead of ramping shows up
// here as a discontinuity the input never had.
float maxStep(const std::vector<float>& v, size_t from, size_t to) {
    float m = 0.0f;
    for (size_t i = from + 1; i < to; ++i) m = std::fmax(m, std::fabs(v[i] - v[i - 1]));
    return m;
}

// White noise normalised to an exact peak amplitude, so tests that care about a
// signal sitting just under a threshold can rely on it.
std::vector<float> noiseBuf(size_t frames, float amp, unsigned seed) {
    std::vector<float> v(frames);
    std::mt19937 rng(seed);
    std::normal_distribution<float> d(0.0f, 1.0f);
    for (auto& x : v) x = d(rng);
    const float p = peak(v, 0, frames);
    for (auto& x : v) x *= amp / p;
    return v;
}

// dB gain of a steady tone measured over the tail, after the filter settles.
float toneGainDb(const LADSPA_Descriptor* d, unsigned long rate,
                 const std::vector<std::pair<unsigned long, float>>& params, float hz) {
    const size_t frames = rate / 2;
    const std::vector<float> in = sine(frames, hz, static_cast<float>(rate), 0.25f);
    std::vector<float> outL(frames, 0.0f), outR(frames, 0.0f);
    Fx fx(d, rate);
    for (const auto& p : params) fx.set(p.first, p.second);
    fx.activate();
    fx.process(in.data(), in.data(), outL.data(), outR.data(), frames, 128);
    const size_t from = frames / 2;
    const float ref = rms(in, from, frames);
    const float got = rms(outL, from, frames);
    return 20.0f * std::log10(std::fmax(got, 1e-12f) / ref);
}

void printPortTable() {
    for (unsigned long i = 0; i < kPawBuiltinCount; ++i) {
        const LADSPA_Descriptor* d = pawBuiltinDescriptor(i);
        printf("\n%s  [%s]  id=0x%lX  ports=%lu\n", d->Name, d->Label, d->UniqueID,
               d->PortCount);
        for (unsigned long p = 0; p < d->PortCount; ++p) {
            const LADSPA_PortDescriptor pd = d->PortDescriptors[p];
            const LADSPA_PortRangeHint& h = d->PortRangeHints[p];
            const char* kind = LADSPA_IS_PORT_CONTROL(pd)
                                       ? "control in"
                                       : (LADSPA_IS_PORT_INPUT(pd) ? "audio in   " : "audio out  ");
            if (isControlIn(d, p)) {
                printf("  [%lu] %-15s %s  %9.2f .. %9.2f  default %8.2f%s\n", p,
                       d->PortNames[p], kind, h.LowerBound, h.UpperBound,
                       defaultOf(h, 48000), LADSPA_IS_HINT_LOGARITHMIC(h.HintDescriptor)
                                                    ? "  (log)" : "");
            } else {
                printf("  [%lu] %-15s %s\n", p, d->PortNames[p], kind);
            }
        }
    }
    printf("\n");
}

void testDescriptors() {
    check(pawBuiltinDescriptor(kPawBuiltinCount) == nullptr,
          "descriptor index %lu returns nullptr", kPawBuiltinCount);
    check(pawBuiltinDescriptor(99) == nullptr, "descriptor index 99 returns nullptr");

    for (unsigned long i = 0; i < kPawBuiltinCount; ++i) {
        const LADSPA_Descriptor* d = pawBuiltinDescriptor(i);
        if (!d) {
            check(false, "descriptor %lu is non-null", i);
            continue;
        }
        unsigned ain = 0, aout = 0, cin = 0;
        bool hintsOk = true;
        for (unsigned long p = 0; p < d->PortCount; ++p) {
            const LADSPA_PortDescriptor pd = d->PortDescriptors[p];
            if (LADSPA_IS_PORT_AUDIO(pd)) {
                LADSPA_IS_PORT_INPUT(pd) ? ++ain : ++aout;
            } else if (isControlIn(d, p)) {
                ++cin;
                const LADSPA_PortRangeHint& h = d->PortRangeHints[p];
                const float def = defaultOf(h, 48000);
                if (!LADSPA_IS_HINT_BOUNDED_BELOW(h.HintDescriptor) ||
                    !LADSPA_IS_HINT_BOUNDED_ABOVE(h.HintDescriptor) ||
                    !LADSPA_IS_HINT_HAS_DEFAULT(h.HintDescriptor) ||
                    def < h.LowerBound || def > h.UpperBound || !d->PortNames[p][0]) {
                    hintsOk = false;
                }
            }
        }
        check(ain == 2 && aout == 2, "%s: 2 audio in / 2 audio out (got %u/%u)", d->Label,
              ain, aout);
        check(hintsOk, "%s: all %u control ports bounded, named and defaulted", d->Label,
              cin);
        check(LADSPA_IS_HARD_RT_CAPABLE(d->Properties) &&
                      !LADSPA_IS_INPLACE_BROKEN(d->Properties),
              "%s: hard-RT capable, in-place supported", d->Label);
        check(d->instantiate && d->connect_port && d->run && d->cleanup,
              "%s: mandatory entry points present", d->Label);
    }
}

void testRobustness() {
    std::mt19937 rng(7);
    std::normal_distribution<float> noise(0.0f, 0.3f);

    for (unsigned long rate : {32000u, 44100u, 48000u, 96000u}) {
        const size_t frames = rate / 4;
        std::vector<float> nL(frames), nR(frames), silence(frames, 0.0f);
        for (size_t i = 0; i < frames; ++i) {
            nL[i] = noise(rng);
            nR[i] = noise(rng);
        }

        for (unsigned long i = 0; i < kPawBuiltinCount; ++i) {
            const LADSPA_Descriptor* d = pawBuiltinDescriptor(i);
            bool ok = true;
            for (size_t block : {1u, 37u, 64u, 1024u}) {
                std::vector<float> outL(frames, 0.0f), outR(frames, 0.0f);
                Fx fx(d, rate);
                if (!fx.valid()) {
                    ok = false;
                    break;
                }
                fx.activate();
                fx.process(nL.data(), nR.data(), outL.data(), outR.data(), frames, block);
                ok = ok && allFinite(outL) && allFinite(outR);
                // Silence straight after loud noise: must decay clean, no NaN
                // and no denormal-fed garbage.
                fx.process(silence.data(), silence.data(), outL.data(), outR.data(), frames,
                           block);
                ok = ok && allFinite(outL) && allFinite(outR);
            }
            check(ok, "%s @ %lu Hz: noise + silence finite at blocks 1/37/64/1024", d->Label,
                  rate);
        }
    }
}

void testEq() {
    const LADSPA_Descriptor* d = pawBuiltinDescriptor(0);
    enum { kLowDb, kMidDb, kMidHz, kHighDb };

    const float lowBoost100 = toneGainDb(d, 48000, {{kLowDb, 12.0f}}, 100.0f);
    const float lowBoost8k = toneGainDb(d, 48000, {{kLowDb, 12.0f}}, 8000.0f);
    check(lowBoost100 > 9.0f, "EQ +12 dB low shelf: 100 Hz gain %+.2f dB (> +9)", lowBoost100);
    check(std::fabs(lowBoost8k) < 0.5f, "EQ +12 dB low shelf: 8 kHz gain %+.2f dB (|.| < 0.5)",
          lowBoost8k);

    const float highBoost8k = toneGainDb(d, 48000, {{kHighDb, 12.0f}}, 8000.0f);
    const float highBoost100 = toneGainDb(d, 48000, {{kHighDb, 12.0f}}, 100.0f);
    check(highBoost8k > 9.0f, "EQ +12 dB high shelf: 8 kHz gain %+.2f dB (> +9)", highBoost8k);
    check(std::fabs(highBoost100) < 0.5f,
          "EQ +12 dB high shelf: 100 Hz gain %+.2f dB (|.| < 0.5)", highBoost100);

    const float cut100 = toneGainDb(d, 48000, {{kLowDb, -12.0f}}, 100.0f);
    check(cut100 < -9.0f, "EQ -12 dB low shelf: 100 Hz gain %+.2f dB (< -9)", cut100);

    const float midOn = toneGainDb(d, 48000, {{kMidDb, 12.0f}, {kMidHz, 1000.0f}}, 1000.0f);
    const float midOff = toneGainDb(d, 48000, {{kMidDb, 12.0f}, {kMidHz, 1000.0f}}, 100.0f);
    check(std::fabs(midOn - 12.0f) < 0.5f, "EQ +12 dB mid @1 kHz: 1 kHz gain %+.2f dB", midOn);
    check(std::fabs(midOff) < 1.0f, "EQ +12 dB mid @1 kHz: 100 Hz gain %+.2f dB", midOff);

    // Flat EQ must be transparent: identical coefficients top and bottom.
    const size_t frames = 8192;
    std::mt19937 rng(11);
    std::normal_distribution<float> noise(0.0f, 0.3f);
    std::vector<float> in(frames), outL(frames, 0.0f), outR(frames, 0.0f);
    for (auto& v : in) v = noise(rng);
    Fx fx(d, 48000);
    fx.set(kLowDb, 0.0f);
    fx.set(kMidDb, 0.0f);
    fx.set(kHighDb, 0.0f);
    fx.activate();
    fx.process(in.data(), in.data(), outL.data(), outR.data(), frames, 128);
    const float diff = maxDiff(in, outL);
    check(diff <= 1e-7f, "EQ flat at 0 dB is unity (max diff %.3g)", static_cast<double>(diff));
}

void testCompressor() {
    const LADSPA_Descriptor* d = pawBuiltinDescriptor(1);
    enum { kThreshDb, kRatio, kAttackMs, kReleaseMs, kMakeupDb };
    const unsigned long rate = 48000;

    // 300 ms loud, then 800 ms quiet well under the threshold, long enough for
    // a 200 ms release to run all the way back to unity.
    const size_t loud = rate * 300 / 1000;
    const size_t quiet = rate * 800 / 1000;
    std::vector<float> in = sine(loud, 1000.0f, rate, 0.9f);
    const std::vector<float> tail = sine(quiet, 1000.0f, rate, 0.05f);
    in.insert(in.end(), tail.begin(), tail.end());
    std::vector<float> outL(in.size(), 0.0f), outR(in.size(), 0.0f);

    Fx fx(d, rate);
    fx.set(kThreshDb, -20.0f);
    fx.set(kRatio, 8.0f);
    fx.set(kAttackMs, 5.0f);
    fx.set(kReleaseMs, 200.0f);
    fx.set(kMakeupDb, 0.0f);
    fx.activate();
    fx.process(in.data(), in.data(), outL.data(), outR.data(), in.size(), 64);

    const float burstPeak = peak(outL, loud - rate / 10, loud);
    check(burstPeak < 0.45f && burstPeak > 0.02f,
          "comp: 0.9 burst squashed to %.3f peak (thr -20 dB, 8:1)",
          static_cast<double>(burstPeak));

    const float justAfter = peak(outL, loud, loud + rate / 100);          // first 10 ms
    const float recovered = peak(outL, in.size() - rate / 10, in.size()); // last 100 ms
    check(recovered > 2.0f * justAfter,
          "comp: releases after burst (%.4f -> %.4f over 800 ms)",
          static_cast<double>(justAfter), static_cast<double>(recovered));
    check(std::fabs(recovered - 0.05f) < 0.005f,
          "comp: recovers to unity below threshold (%.4f vs 0.05)",
          static_cast<double>(recovered));
    check(allFinite(outL) && allFinite(outR), "comp: output finite");

    // Ratio 1:1 at 0 dB threshold with no makeup is a straight wire.
    std::mt19937 rng(13);
    std::normal_distribution<float> noise(0.0f, 0.2f);
    const size_t frames = 8192;
    std::vector<float> dry(frames), dL(frames, 0.0f), dR(frames, 0.0f);
    for (auto& v : dry) v = noise(rng);
    Fx unity(d, rate);
    unity.set(kThreshDb, 0.0f);
    unity.set(kRatio, 1.0f);
    unity.set(kMakeupDb, 0.0f);
    unity.activate();
    unity.process(dry.data(), dry.data(), dL.data(), dR.data(), frames, 128);
    const float diff = maxDiff(dry, dL);
    check(diff <= 1e-7f, "comp: 1:1 ratio, 0 dB makeup is unity (max diff %.3g)",
          static_cast<double>(diff));
}

void testDelay() {
    const LADSPA_Descriptor* d = pawBuiltinDescriptor(2);
    enum { kTimeMs, kFeedbackPct, kMixPct };
    const unsigned long rate = 48000;
    const float timeMs = 100.0f;
    const float feedback = 0.5f;

    const size_t frames = rate * 350 / 1000;
    std::vector<float> in(frames, 0.0f), outL(frames, 0.0f), outR(frames, 0.0f);
    in[0] = 1.0f;

    Fx fx(d, rate);
    fx.set(kTimeMs, timeMs);
    fx.set(kFeedbackPct, feedback * 100.0f);
    fx.set(kMixPct, 100.0f);
    fx.activate();
    fx.process(in.data(), in.data(), outL.data(), outR.data(), frames, 64);

    const size_t expect = static_cast<size_t>(timeMs * 0.001f * rate);
    const size_t tolerance = rate / 1000;  // 1 ms
    bool ok = true;
    for (int echo = 1; echo <= 3; ++echo) {
        const size_t centre = expect * static_cast<size_t>(echo);
        const size_t from = centre - 2 * tolerance;
        const size_t to = std::min(frames, centre + 2 * tolerance);
        size_t at = from;
        float best = 0.0f;
        for (size_t i = from; i < to; ++i) {
            if (std::fabs(outL[i]) > best) {
                best = std::fabs(outL[i]);
                at = i;
            }
        }
        const float want = std::pow(feedback, static_cast<float>(echo - 1));
        const bool hit = at >= centre - tolerance && at <= centre + tolerance &&
                         std::fabs(best - want) < 0.02f * want + 1e-4f;
        check(hit, "delay: echo %d at %.2f ms amp %.4f (want %.2f ms, %.4f)", echo,
              1000.0 * static_cast<double>(at) / rate, static_cast<double>(best),
              static_cast<double>(timeMs * echo), static_cast<double>(want));
        ok = ok && hit;
    }
    check(peak(outL, 0, tolerance) < 1e-6f, "delay: 100%% wet passes no dry signal");

    // Mix at 0 % is a straight wire.
    std::mt19937 rng(17);
    std::normal_distribution<float> noise(0.0f, 0.25f);
    const size_t n = 8192;
    std::vector<float> dry(n), dL(n, 0.0f), dR(n, 0.0f);
    for (auto& v : dry) v = noise(rng);
    Fx unity(d, rate);
    unity.set(kTimeMs, 250.0f);
    unity.set(kFeedbackPct, 60.0f);
    unity.set(kMixPct, 0.0f);
    unity.activate();
    unity.process(dry.data(), dry.data(), dL.data(), dR.data(), n, 128);
    const float diff = maxDiff(dry, dL);
    check(diff <= 1e-7f, "delay: 0 %% mix is unity (max diff %.3g)", static_cast<double>(diff));

    // A long tail at high feedback must stay bounded and decay away.
    std::vector<float> impulse(rate * 3, 0.0f), tL(rate * 3, 0.0f), tR(rate * 3, 0.0f);
    impulse[0] = 1.0f;
    Fx tail(d, rate);
    tail.set(kTimeMs, 2000.0f);
    tail.set(kFeedbackPct, 95.0f);
    tail.set(kMixPct, 100.0f);
    tail.activate();
    tail.process(impulse.data(), impulse.data(), tL.data(), tR.data(), impulse.size(), 256);
    check(allFinite(tL) && peak(tL, 0, tL.size()) <= 1.0f,
          "delay: 2000 ms / 95 %% feedback tail bounded (peak %.3f)",
          static_cast<double>(peak(tL, 0, tL.size())));
}

void testReverb() {
    const LADSPA_Descriptor* d = pawBuiltinDescriptor(3);
    enum { kSizePct, kDampPct, kPredelayMs, kMixPct };
    const unsigned long rate = 48000;

    // An impulse into a 100 % wet tank: energy has to build, then decay away.
    const size_t frames = rate * 3;
    std::vector<float> impulse(frames, 0.0f), outL(frames, 0.0f), outR(frames, 0.0f);
    impulse[0] = 1.0f;
    Fx fx(d, rate);
    fx.set(kSizePct, 70.0f);
    fx.set(kDampPct, 30.0f);
    fx.set(kPredelayMs, 0.0f);
    fx.set(kMixPct, 100.0f);
    fx.activate();
    fx.process(impulse.data(), impulse.data(), outL.data(), outR.data(), frames, 128);

    const float early = rms(outL, rate / 20, rate * 3 / 20);         // 50..150 ms
    const float mid = rms(outL, rate / 2, rate * 6 / 10);            // 500..600 ms
    const float late = rms(outL, rate * 24 / 10, rate * 25 / 10);    // 2.4..2.5 s
    check(early > 1e-4f, "reverb: impulse builds a tail (early rms %.5f)",
          static_cast<double>(early));
    check(mid < early && late < mid && late < 0.2f * early,
          "reverb: tail decays (%.5f -> %.5f -> %.5f over 50 ms / 550 ms / 2.45 s)",
          static_cast<double>(early), static_cast<double>(mid),
          static_cast<double>(late));
    check(allFinite(outL) && allFinite(outR), "reverb: tail finite");

    // Left and right run different line lengths, so the tank decorrelates.
    check(maxDiff(outL, outR) > 1e-3f, "reverb: left and right tails differ (%.4f)",
          static_cast<double>(maxDiff(outL, outR)));

    // Pre-delay holds the whole wet signal off for its duration.
    const size_t preFrames = rate / 2;
    std::vector<float> pIn(preFrames, 0.0f), pL(preFrames, 0.0f), pR(preFrames, 0.0f);
    pIn[0] = 1.0f;
    Fx pre(d, rate);
    pre.set(kSizePct, 70.0f);
    pre.set(kDampPct, 30.0f);
    pre.set(kPredelayMs, 50.0f);
    pre.set(kMixPct, 100.0f);
    pre.activate();
    pre.process(pIn.data(), pIn.data(), pL.data(), pR.data(), preFrames, 128);
    const size_t preAt = rate * 50 / 1000;
    check(peak(pL, 0, preAt - rate / 500) < 1e-6f &&
                  peak(pL, preAt, preFrames) > 1e-4f,
          "reverb: 50 ms pre-delay silent before, wet after (%.3g / %.3g)",
          static_cast<double>(peak(pL, 0, preAt - rate / 500)),
          static_cast<double>(peak(pL, preAt, preFrames)));

    // Mix at 0 % is a straight wire.
    const std::vector<float> dry = noiseBuf(8192, 0.5f, 29);
    std::vector<float> dL(dry.size(), 0.0f), dR(dry.size(), 0.0f);
    Fx unity(d, rate);
    unity.set(kSizePct, 80.0f);
    unity.set(kDampPct, 20.0f);
    unity.set(kPredelayMs, 30.0f);
    unity.set(kMixPct, 0.0f);
    unity.activate();
    unity.process(dry.data(), dry.data(), dL.data(), dR.data(), dry.size(), 128);
    check(maxDiff(dry, dL) <= 1e-7f, "reverb: 0 %% mix is unity (max diff %.3g)",
          static_cast<double>(maxDiff(dry, dL)));

    // Longest tail, no damping, fully wet: 1 s of loud noise then silence.
    // Must stay bounded, and 95 % feedback still has to run down to nothing
    // (RT60 is about 5 s at this setting, so 10 s of silence is the honest
    // window to ask that in).
    const size_t loud = rate, quiet = rate * 10;
    std::vector<float> hot = noiseBuf(loud, 0.9f, 31);
    hot.resize(loud + quiet, 0.0f);
    std::vector<float> xL(hot.size(), 0.0f), xR(hot.size(), 0.0f);
    Fx big(d, rate);
    big.set(kSizePct, 100.0f);
    big.set(kDampPct, 0.0f);
    big.set(kPredelayMs, 200.0f);
    big.set(kMixPct, 100.0f);
    big.activate();
    big.process(hot.data(), hot.data(), xL.data(), xR.data(), hot.size(), 256);
    const float hotPeak = peak(xL, 0, xL.size());
    const float hotTail = rms(xL, hot.size() - rate / 2, hot.size());
    check(allFinite(xL) && allFinite(xR) && hotPeak < 4.0f,
          "reverb: size 100 %%, no damping stays bounded (peak %.3f)",
          static_cast<double>(hotPeak));
    const float hotMid = rms(xL, loud + rate, loud + rate + rate / 2);
    check(hotTail < 1e-4f && hotTail < 0.01f * hotMid,
          "reverb: extreme tail decays into silence (%.3g -> %.3g)",
          static_cast<double>(hotMid), static_cast<double>(hotTail));
}

void testChorus() {
    const LADSPA_Descriptor* d = pawBuiltinDescriptor(4);
    enum { kRateHz, kDepthPct, kMixPct };
    const unsigned long rate = 48000;

    // Half wet, sweeping: the comb notch walks across the tone, so the level
    // has to move. A chorus that isn't modulating gives a flat envelope.
    const size_t frames = rate * 2;
    const std::vector<float> tone = sine(frames, 1000.0f, rate, 0.5f);
    std::vector<float> outL(frames, 0.0f), outR(frames, 0.0f);
    Fx fx(d, rate);
    fx.set(kRateHz, 2.0f);
    fx.set(kDepthPct, 100.0f);
    fx.set(kMixPct, 50.0f);
    fx.activate();
    fx.process(tone.data(), tone.data(), outL.data(), outR.data(), frames, 128);

    const size_t win = rate / 50;  // 20 ms
    float lo = 1e9f, hi = 0.0f;
    for (size_t off = frames / 2; off + win <= frames; off += win) {
        const float w = rms(outL, off, off + win);
        lo = std::fmin(lo, w);
        hi = std::fmax(hi, w);
    }
    check(hi > 1.5f * lo, "chorus: 2 Hz sweep modulates level (%.4f .. %.4f rms)",
          static_cast<double>(lo), static_cast<double>(hi));
    check(allFinite(outL) && allFinite(outR), "chorus: output finite");

    // Depth 0 is a plain 12 ms delay: fully wet, the impulse lands there.
    const size_t n = rate / 10;
    std::vector<float> impulse(n, 0.0f), iL(n, 0.0f), iR(n, 0.0f);
    impulse[0] = 1.0f;
    Fx flat(d, rate);
    flat.set(kRateHz, 1.0f);
    flat.set(kDepthPct, 0.0f);
    flat.set(kMixPct, 100.0f);
    flat.activate();
    flat.process(impulse.data(), impulse.data(), iL.data(), iR.data(), n, 128);
    size_t at = 0;
    float best = 0.0f;
    for (size_t i = 0; i < n; ++i) {
        if (std::fabs(iL[i]) > best) {
            best = std::fabs(iL[i]);
            at = i;
        }
    }
    const size_t want = rate * 12 / 1000;
    check(at + 2 >= want && at <= want + 2 && best > 0.99f,
          "chorus: depth 0 is a flat 12 ms tap (%.2f ms, amp %.4f)",
          1000.0 * static_cast<double>(at) / rate, static_cast<double>(best));

    // The LFO runs a quarter cycle apart per channel, so identical inputs come
    // out decorrelated, which is the whole point of the stereo spread.
    std::vector<float> sL(frames, 0.0f), sR(frames, 0.0f);
    Fx wide(d, rate);
    wide.set(kRateHz, 2.0f);
    wide.set(kDepthPct, 100.0f);
    wide.set(kMixPct, 100.0f);
    wide.activate();
    wide.process(tone.data(), tone.data(), sL.data(), sR.data(), frames, 128);
    check(maxDiff(sL, sR) > 0.05f, "chorus: stereo spread decorrelates L/R (%.3f)",
          static_cast<double>(maxDiff(sL, sR)));

    // Mix at 0 % is a straight wire.
    const std::vector<float> dry = noiseBuf(8192, 0.5f, 37);
    std::vector<float> dL(dry.size(), 0.0f), dR(dry.size(), 0.0f);
    Fx unity(d, rate);
    unity.set(kRateHz, 5.0f);
    unity.set(kDepthPct, 100.0f);
    unity.set(kMixPct, 0.0f);
    unity.activate();
    unity.process(dry.data(), dry.data(), dL.data(), dR.data(), dry.size(), 128);
    check(maxDiff(dry, dL) <= 1e-7f, "chorus: 0 %% mix is unity (max diff %.3g)",
          static_cast<double>(maxDiff(dry, dL)));
}

void testGate() {
    const LADSPA_Descriptor* d = pawBuiltinDescriptor(5);
    enum { kThreshDb, kAttackMs, kHoldMs, kReleaseMs };
    const unsigned long rate = 48000;

    // 300 ms well over the threshold, then 900 ms well under it.
    const size_t loud = rate * 300 / 1000;
    const size_t quiet = rate * 900 / 1000;
    std::vector<float> in = sine(loud, 1000.0f, rate, 0.5f);
    const std::vector<float> tail = sine(quiet, 1000.0f, rate, 0.002f);
    in.insert(in.end(), tail.begin(), tail.end());
    std::vector<float> outL(in.size(), 0.0f), outR(in.size(), 0.0f);

    Fx fx(d, rate);
    fx.set(kThreshDb, -40.0f);
    fx.set(kAttackMs, 1.0f);
    fx.set(kHoldMs, 10.0f);
    fx.set(kReleaseMs, 50.0f);
    fx.activate();
    fx.process(in.data(), in.data(), outL.data(), outR.data(), in.size(), 64);

    const float openRatio = rms(outL, loud - rate / 10, loud) / rms(in, loud - rate / 10, loud);
    check(openRatio > 0.995f && openRatio < 1.001f,
          "gate: -6 dBFS tone passes at unity (ratio %.4f)",
          static_cast<double>(openRatio));

    const size_t from = in.size() - rate * 3 / 10;
    const float closedDb = 20.0f * std::log10(std::fmax(rms(outL, from, in.size()), 1e-12f) /
                                              rms(in, from, in.size()));
    check(closedDb < -40.0f, "gate: -54 dBFS tone gated %+.1f dB (< -40)",
          static_cast<double>(closedDb));

    // The gain envelope has to glide. If it stepped, the close would put a
    // discontinuity in the output that the input never had.
    check(maxStep(outL, 0, outL.size()) <= 1.2f * maxStep(in, 0, in.size()),
          "gate: no click at open/close (max step %.5f vs input %.5f)",
          static_cast<double>(maxStep(outL, 0, outL.size())),
          static_cast<double>(maxStep(in, 0, in.size())));

    // Fastest ballistics, threshold hard against the top, noise that just
    // reaches it: the gate chatters open and shut and must stay finite, and it
    // must never add gain.
    const std::vector<float> noise = noiseBuf(rate, 1.0f, 41);
    std::vector<float> nL(noise.size(), 0.0f), nR(noise.size(), 0.0f);
    Fx hot(d, rate);
    hot.set(kThreshDb, 0.0f);
    hot.set(kAttackMs, 0.1f);
    hot.set(kHoldMs, 0.0f);
    hot.set(kReleaseMs, 10.0f);
    hot.activate();
    hot.process(noise.data(), noise.data(), nL.data(), nR.data(), noise.size(), 64);
    check(allFinite(nL) && allFinite(nR) &&
                  peak(nL, 0, nL.size()) <= peak(noise, 0, noise.size()),
          "gate: extreme settings finite and never boost (peak %.4f <= %.4f)",
          static_cast<double>(peak(nL, 0, nL.size())),
          static_cast<double>(peak(noise, 0, noise.size())));
}

void testLimiter() {
    const LADSPA_Descriptor* d = pawBuiltinDescriptor(6);
    enum { kThreshDb, kCeilingDb, kReleaseMs };
    const unsigned long rate = 48000;

    // Threshold and ceiling together: no makeup, just a wall at -6 dBFS.
    const size_t frames = rate / 2;
    const std::vector<float> hotTone = sine(frames, 1000.0f, rate, 0.9f);
    std::vector<float> outL(frames, 0.0f), outR(frames, 0.0f);
    Fx fx(d, rate);
    fx.set(kThreshDb, -6.0f);
    fx.set(kCeilingDb, -6.0f);
    fx.set(kReleaseMs, 50.0f);
    fx.activate();
    fx.process(hotTone.data(), hotTone.data(), outL.data(), outR.data(), frames, 64);
    const float ceiling = std::pow(10.0f, -6.0f / 20.0f);
    const float capped = peak(outL, frames / 2, frames);
    check(capped <= ceiling * 1.0001f && capped > ceiling * 0.99f,
          "limiter: 0.9 tone capped at %.4f (ceiling %.4f)", static_cast<double>(capped),
          static_cast<double>(ceiling));

    // Threshold below the ceiling is makeup: -12 dB threshold into a 0 dB
    // ceiling lifts anything under the threshold by exactly 12 dB.
    const std::vector<float> soft = sine(frames, 1000.0f, rate, 0.05f);
    std::vector<float> mL(frames, 0.0f), mR(frames, 0.0f);
    Fx makeup(d, rate);
    makeup.set(kThreshDb, -12.0f);
    makeup.set(kCeilingDb, 0.0f);
    makeup.set(kReleaseMs, 50.0f);
    makeup.activate();
    makeup.process(soft.data(), soft.data(), mL.data(), mR.data(), frames, 64);
    const float lifted = peak(mL, frames / 2, frames);
    const float wantLift = 0.05f * std::pow(10.0f, 12.0f / 20.0f);
    check(std::fabs(lifted - wantLift) < 0.01f * wantLift,
          "limiter: below threshold is +12 dB makeup (%.4f vs %.4f)",
          static_cast<double>(lifted), static_cast<double>(wantLift));

    // 0 dB threshold, 0 dB ceiling, nothing over full scale: straight wire.
    const std::vector<float> dry = noiseBuf(8192, 0.5f, 43);
    std::vector<float> dL(dry.size(), 0.0f), dR(dry.size(), 0.0f);
    Fx unity(d, rate);
    unity.set(kThreshDb, 0.0f);
    unity.set(kCeilingDb, 0.0f);
    unity.set(kReleaseMs, 50.0f);
    unity.activate();
    unity.process(dry.data(), dry.data(), dL.data(), dR.data(), dry.size(), 128);
    check(maxDiff(dry, dL) <= 1e-7f, "limiter: 0 dB threshold/ceiling is unity (max diff %.3g)",
          static_cast<double>(maxDiff(dry, dL)));

    // Deepest threshold, fastest release, full-scale noise: +24 dB of drive
    // must still come out under 0 dBFS, on every sample.
    const std::vector<float> hot = noiseBuf(rate, 1.0f, 47);
    std::vector<float> hL(hot.size(), 0.0f), hR(hot.size(), 0.0f);
    Fx brick(d, rate);
    brick.set(kThreshDb, -24.0f);
    brick.set(kCeilingDb, 0.0f);
    brick.set(kReleaseMs, 1.0f);
    brick.activate();
    brick.process(hot.data(), hot.data(), hL.data(), hR.data(), hot.size(), 64);
    const float brickPeak = std::fmax(peak(hL, 0, hL.size()), peak(hR, 0, hR.size()));
    check(allFinite(hL) && allFinite(hR) && brickPeak <= 1.0f && brickPeak > 0.9f,
          "limiter: +24 dB of drive still peaks at %.6f (<= 1.0)",
          static_cast<double>(brickPeak));
}

void testFilter() {
    const LADSPA_Descriptor* d = pawBuiltinDescriptor(7);
    enum { kMode, kCutoffHz, kResonance };
    const unsigned long rate = 48000;
    const float lp = 0.0f, hp = 1.0f;

    const float lpPass = toneGainDb(d, rate, {{kMode, lp}, {kCutoffHz, 1000.0f}}, 200.0f);
    const float lpStop = toneGainDb(d, rate, {{kMode, lp}, {kCutoffHz, 1000.0f}}, 8000.0f);
    check(std::fabs(lpPass) < 1.0f, "filter: LP 1 kHz passes 200 Hz at %+.2f dB", lpPass);
    check(lpStop < -30.0f, "filter: LP 1 kHz stops 8 kHz at %+.2f dB (< -30)", lpStop);

    const float hpPass = toneGainDb(d, rate, {{kMode, hp}, {kCutoffHz, 1000.0f}}, 8000.0f);
    const float hpStop = toneGainDb(d, rate, {{kMode, hp}, {kCutoffHz, 1000.0f}}, 100.0f);
    check(std::fabs(hpPass) < 1.0f, "filter: HP 1 kHz passes 8 kHz at %+.2f dB", hpPass);
    check(hpStop < -30.0f, "filter: HP 1 kHz stops 100 Hz at %+.2f dB (< -30)", hpStop);

    const float flat = toneGainDb(
            d, rate, {{kMode, lp}, {kCutoffHz, 1000.0f}, {kResonance, 0.707f}}, 1000.0f);
    const float peaky = toneGainDb(
            d, rate, {{kMode, lp}, {kCutoffHz, 1000.0f}, {kResonance, 8.0f}}, 1000.0f);
    check(std::fabs(flat + 3.0f) < 1.0f, "filter: Q 0.707 is -3 dB at cutoff (%+.2f dB)",
          flat);
    check(peaky > 12.0f, "filter: Q 8 resonates %+.2f dB at cutoff (> +12)", peaky);

    // Sweep the cutoff across the whole range, at maximum resonance, while
    // full-scale noise runs through. A filter that recomputes coefficients
    // badly blows up here.
    const size_t frames = rate;
    const std::vector<float> noise = noiseBuf(frames, 1.0f, 53);
    std::vector<float> outL(frames, 0.0f), outR(frames, 0.0f);
    Fx fx(d, rate);
    fx.set(kResonance, 16.0f);
    fx.activate();
    const size_t block = 256;
    for (size_t off = 0; off < frames; off += block) {
        const float t = static_cast<float>(off) / static_cast<float>(frames);
        fx.set(kMode, (off / block) % 8 < 4 ? lp : hp);
        fx.set(kCutoffHz, 20.0f * std::pow(1000.0f, t));  // 20 Hz .. 20 kHz
        const size_t n = std::min(block, frames - off);
        fx.process(noise.data() + off, noise.data() + off, outL.data() + off,
                   outR.data() + off, n, n);
    }
    const float sweptPeak = peak(outL, 0, frames);
    check(allFinite(outL) && allFinite(outR) && sweptPeak < 32.0f,
          "filter: 20 Hz..20 kHz sweep at Q 16 stays bounded (peak %.3f)",
          static_cast<double>(sweptPeak));
}

// Every effect snaps its filter and delay state to zero once it stops
// mattering (flush()). testRobustness only proves the output stays finite;
// this proves the state reaches zero rather than idling on denormals, which
// costs hundreds of cycles a sample on some ARM cores.
void testDenormalFlush() {
    const unsigned long rate = 48000;
    const size_t burst = rate / 10;
    const size_t silence = rate * 12;  // the reverb's tail is the long pole
    std::vector<float> in = noiseBuf(burst, 0.5f, 59);
    in.resize(burst + silence, 0.0f);

    for (unsigned long i = 0; i < kPawBuiltinCount; ++i) {
        const LADSPA_Descriptor* d = pawBuiltinDescriptor(i);
        std::vector<float> outL(in.size(), 0.0f), outR(in.size(), 0.0f);
        Fx fx(d, rate);  // default control values
        fx.activate();
        fx.process(in.data(), in.data(), outL.data(), outR.data(), in.size(), 128);
        const size_t from = in.size() - rate / 100;  // last 10 ms
        check(peak(outL, from, in.size()) == 0.0f && peak(outR, from, in.size()) == 0.0f,
              "%s: state flushes to exact zero after silence", d->Label);
    }
}

// Hosts hand us the same buffer for input and output; the result must not
// change.
void testInPlace() {
    const size_t frames = 12000;
    std::mt19937 rng(23);
    std::normal_distribution<float> noise(0.0f, 0.4f);
    std::vector<float> inL(frames), inR(frames);
    for (size_t i = 0; i < frames; ++i) {
        inL[i] = noise(rng);
        inR[i] = noise(rng);
    }

    // Non-default settings so every code path is doing something.
    const std::vector<std::vector<std::pair<unsigned long, float>>> params = {
            {{0, 9.0f}, {1, -6.0f}, {2, 2500.0f}, {3, 6.0f}},
            {{0, -24.0f}, {1, 6.0f}, {2, 3.0f}, {3, 150.0f}, {4, 6.0f}},
            {{0, 123.0f}, {1, 55.0f}, {2, 40.0f}},
            {{0, 70.0f}, {1, 35.0f}, {2, 27.0f}, {3, 65.0f}},
            {{0, 1.3f}, {1, 80.0f}, {2, 60.0f}},
            {{0, -30.0f}, {1, 2.0f}, {2, 50.0f}, {3, 120.0f}},
            {{0, -12.0f}, {1, -3.0f}, {2, 60.0f}},
            {{0, 1.0f}, {1, 900.0f}, {2, 4.0f}}};

    for (unsigned long i = 0; i < kPawBuiltinCount; ++i) {
        const LADSPA_Descriptor* d = pawBuiltinDescriptor(i);
        std::vector<float> refL(frames, 0.0f), refR(frames, 0.0f);
        Fx a(d, 48000);
        for (const auto& p : params[i]) a.set(p.first, p.second);
        a.activate();
        a.process(inL.data(), inR.data(), refL.data(), refR.data(), frames, 96);

        std::vector<float> ipL = inL, ipR = inR;
        Fx b(d, 48000);
        for (const auto& p : params[i]) b.set(p.first, p.second);
        b.activate();
        b.process(ipL.data(), ipR.data(), ipL.data(), ipR.data(), frames, 96);

        const float diff = std::fmax(maxDiff(refL, ipL), maxDiff(refR, ipR));
        check(diff == 0.0f, "%s: in-place matches out-of-place (max diff %.3g)", d->Label,
              static_cast<double>(diff));
    }
}

}  // namespace

int main() {
    printPortTable();
    testDescriptors();
    testRobustness();
    testEq();
    testCompressor();
    testDelay();
    testReverb();
    testChorus();
    testGate();
    testLimiter();
    testFilter();
    testDenormalFlush();
    testInPlace();

    if (gFailures) {
        printf("\n== %d FAILURE(S) ==\n", gFailures);
        return 1;
    }
    printf("\n== ALL PASS ==\n");
    return 0;
}
