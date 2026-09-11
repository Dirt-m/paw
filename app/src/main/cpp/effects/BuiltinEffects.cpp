// PAW! built-in effects: 3-band EQ, compressor, stereo delay, reverb,
// chorus, noise gate, peak limiter, resonant filter.
//
// Copyright (C) 2026 PAW! contributors
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation, either version 3 of the License, or (at your option)
// any later version.
//
// This program is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
// FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
// more details. You should have received a copy of the GNU General Public
// License along with this program. If not, see <https://www.gnu.org/licenses/>.
//
// SPDX-License-Identifier: GPL-3.0-or-later

#include "BuiltinEffects.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <initializer_list>
#include <new>
#include <utility>

namespace {

constexpr float kPi = 3.14159265358979f;
constexpr float kTwoPi = 6.28318530717959f;
constexpr float kSqrt2 = 1.41421356237309f;

// run() is chopped into chunks this long; parameters are re-smoothed once per
// chunk (0.67 ms at 48 kHz) and, where a jump would be audible, ramped per
// sample across it. Keeps glide times independent of the host's block size.
constexpr unsigned long kChunk = 32;
constexpr float kSmoothSec = 0.01f;

// Denormals cost hundreds of cycles on some ARM cores; snap filter and delay
// state to zero once it stops mattering.
constexpr float kTiny = 1e-20f;

inline float flush(float v) {
    return (v > -kTiny && v < kTiny) ? 0.0f : v;
}

inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

inline float dbToGain(float db) {
    return std::pow(10.0f, db * 0.05f);
}

// Fractional read from a circular line of `len` frames whose next write goes to
// `write`. `d` is the delay in samples and must be >= 1 and <= len - 2, so the
// interpolated pair never straddles the write cursor.
inline float tapLine(const float* buf, unsigned long len, unsigned long write, float d) {
    const unsigned long di = static_cast<unsigned long>(d);
    const float frac = d - static_cast<float>(di);
    unsigned long i0 = write + len - di;
    if (i0 >= len) i0 -= len;
    const unsigned long i1 = i0 == 0 ? len - 1 : i0 - 1;
    return buf[i0] + (buf[i1] - buf[i0]) * frac;
}

inline unsigned long wrapInc(unsigned long i, unsigned long len) {
    return i + 1 == len ? 0 : i + 1;
}

// One-pole smoother stepped once per chunk. Callers that need sample-accurate
// motion take value() before step() and ramp between the two.
class Smooth {
public:
    void configure(float coeff) { mCoeff = coeff; }
    void snap(float v) { mValue = v; }
    float value() const { return mValue; }

    float step(float target) {
        mValue = target + mCoeff * (mValue - target);
        return mValue;
    }

private:
    float mValue = 0.0f;
    float mCoeff = 0.0f;
};

// RBJ cookbook biquad, transposed direct form II, state for two channels.
// Coefficients are normalised by division so a 0 dB setting yields b == a and
// therefore bit-exact unity gain.
struct Biquad {
    float mB0 = 1.0f, mB1 = 0.0f, mB2 = 0.0f, mA1 = 0.0f, mA2 = 0.0f;
    float mZ1[2] = {0.0f, 0.0f};
    float mZ2[2] = {0.0f, 0.0f};

    void reset() { mZ1[0] = mZ1[1] = mZ2[0] = mZ2[1] = 0.0f; }

    inline float process(int ch, float x) {
        const float y = mB0 * x + mZ1[ch];
        mZ1[ch] = flush(mB1 * x - mA1 * y + mZ2[ch]);
        mZ2[ch] = flush(mB2 * x - mA2 * y);
        return y;
    }

    void setLowShelf(float freq, float gainDb, float rate) {
        const float A = std::pow(10.0f, gainDb * 0.025f);
        const float w0 = 2.0f * kPi * freq / rate;
        const float cw = std::cos(w0);
        const float alpha = std::sin(w0) * 0.5f * kSqrt2;  // shelf slope S = 1
        const float beta = 2.0f * std::sqrt(A) * alpha;
        const float ap1 = A + 1.0f, am1 = A - 1.0f;
        normalise(A * (ap1 - am1 * cw + beta),
                  2.0f * A * (am1 - ap1 * cw),
                  A * (ap1 - am1 * cw - beta),
                  ap1 + am1 * cw + beta,
                  -2.0f * (am1 + ap1 * cw),
                  ap1 + am1 * cw - beta);
    }

    void setHighShelf(float freq, float gainDb, float rate) {
        const float A = std::pow(10.0f, gainDb * 0.025f);
        const float w0 = 2.0f * kPi * freq / rate;
        const float cw = std::cos(w0);
        const float alpha = std::sin(w0) * 0.5f * kSqrt2;
        const float beta = 2.0f * std::sqrt(A) * alpha;
        const float ap1 = A + 1.0f, am1 = A - 1.0f;
        normalise(A * (ap1 + am1 * cw + beta),
                  -2.0f * A * (am1 + ap1 * cw),
                  A * (ap1 + am1 * cw - beta),
                  ap1 - am1 * cw + beta,
                  2.0f * (am1 - ap1 * cw),
                  ap1 - am1 * cw - beta);
    }

    void setPeaking(float freq, float gainDb, float q, float rate) {
        const float A = std::pow(10.0f, gainDb * 0.025f);
        const float w0 = 2.0f * kPi * freq / rate;
        const float cw = std::cos(w0);
        const float alpha = std::sin(w0) / (2.0f * q);
        normalise(1.0f + alpha * A, -2.0f * cw, 1.0f - alpha * A,
                  1.0f + alpha / A, -2.0f * cw, 1.0f - alpha / A);
    }

private:
    void normalise(float b0, float b1, float b2, float a0, float a1, float a2) {
        mB0 = b0 / a0;
        mB1 = b1 / a0;
        mB2 = b2 / a0;
        mA1 = a1 / a0;
        mA2 = a2 / a0;
    }
};

// The four audio ports every built-in ends with, fetched in one go. A unit the
// host has not fully connected yields the null object, so run() bails on one
// test.
struct AudioPorts {
    const float* inL = nullptr;
    const float* inR = nullptr;
    float* outL = nullptr;
    float* outR = nullptr;

    bool connected() const { return inL && inR && outL && outR; }
};

// Shared plumbing: port pointers, sample rate, smoothing coefficient, and the
// "snap parameters on the first run after activate" flag (connect_port may
// arrive either side of activate, so targets are only readable in run()).
template <unsigned long N>
struct EffectBase {
    static constexpr unsigned long kPortCount = N;
    // Audio ports are the last four of every built-in: In L/R, Out L/R.
    static constexpr unsigned long kFirstAudioPort = N - 4;

    LADSPA_Data* mPort[N] = {};
    float mRate = 48000.0f;
    float mSmoothCoeff = 0.0f;
    bool mSnap = true;

    void initBase(float rate) {
        mRate = rate;
        mSmoothCoeff = std::exp(-static_cast<float>(kChunk) / (kSmoothSec * rate));
    }

    float ctl(unsigned long port) const { return mPort[port] ? *mPort[port] : 0.0f; }

    AudioPorts ports() const {
        AudioPorts p;
        for (unsigned long i = kFirstAudioPort; i < N; ++i)
            if (!mPort[i]) return p;
        p.inL = mPort[kFirstAudioPort];
        p.inR = mPort[kFirstAudioPort + 1];
        p.outL = mPort[kFirstAudioPort + 2];
        p.outR = mPort[kFirstAudioPort + 3];
        return p;
    }

    // One-pole coefficient for a time constant in milliseconds. Attack,
    // release and detector ballistics across three effects are all this.
    float ballistic(float ms) const { return std::exp(-1000.0f / (ms * mRate)); }

    // Parameter smoothers start settled on the first run after activate rather
    // than gliding up from zero, which would fade every effect in.
    void snapAll(std::initializer_list<std::pair<Smooth*, float>> targets) {
        if (!mSnap) return;
        for (const auto& [smooth, value] : targets) smooth->snap(value);
        mSnap = false;
    }
};

/*** 3-band EQ *************************************************************/

constexpr float kLowShelfHz = 200.0f;
constexpr float kHighShelfHz = 4000.0f;
constexpr float kMidQ = 1.0f;
constexpr float kMaxEqDb = 24.0f;

struct Eq3 : EffectBase<8> {
    enum Port { kLowDb, kMidDb, kMidHz, kHighDb, kInL, kInR, kOutL, kOutR };

    Biquad mLow, mMid, mHigh;
    Smooth mLowDb, mMidDb, mMidHz, mHighDb;
    // Sentinel forces a coefficient update on the first chunk.
    float mSetLowDb = 1e30f, mSetMidDb = 1e30f, mSetMidHz = 1e30f, mSetHighDb = 1e30f;

    bool init(float rate) {
        initBase(rate);
        mLowDb.configure(mSmoothCoeff);
        mMidDb.configure(mSmoothCoeff);
        mMidHz.configure(mSmoothCoeff);
        mHighDb.configure(mSmoothCoeff);
        return true;
    }

    void activate() {
        mLow.reset();
        mMid.reset();
        mHigh.reset();
        mSetLowDb = mSetMidDb = mSetMidHz = mSetHighDb = 1e30f;
        mSnap = true;
    }

    void run(unsigned long count) {
        const AudioPorts io = ports();
        if (!io.connected()) return;

        const float maxHz = 0.45f * mRate;
        const float lowTarget = clampf(ctl(kLowDb), -kMaxEqDb, kMaxEqDb);
        const float midTarget = clampf(ctl(kMidDb), -kMaxEqDb, kMaxEqDb);
        const float hzTarget = clampf(ctl(kMidHz), 30.0f, maxHz);
        const float highTarget = clampf(ctl(kHighDb), -kMaxEqDb, kMaxEqDb);
        snapAll({{&mLowDb, lowTarget},
                 {&mMidDb, midTarget},
                 {&mMidHz, hzTarget},
                 {&mHighDb, highTarget}});

        for (unsigned long done = 0; done < count;) {
            const unsigned long n = std::min<unsigned long>(kChunk, count - done);

            // Coefficients update at chunk rate off smoothed gains; only the
            // filters whose setting moved are recomputed.
            const float lowDb = mLowDb.step(lowTarget);
            const float midDb = mMidDb.step(midTarget);
            const float midHz = mMidHz.step(hzTarget);
            const float highDb = mHighDb.step(highTarget);
            if (lowDb != mSetLowDb) {
                mLow.setLowShelf(kLowShelfHz, lowDb, mRate);
                mSetLowDb = lowDb;
            }
            if (midDb != mSetMidDb || midHz != mSetMidHz) {
                mMid.setPeaking(midHz, midDb, kMidQ, mRate);
                mSetMidDb = midDb;
                mSetMidHz = midHz;
            }
            if (highDb != mSetHighDb) {
                mHigh.setHighShelf(kHighShelfHz, highDb, mRate);
                mSetHighDb = highDb;
            }

            for (unsigned long i = done; i < done + n; ++i) {
                const float l = io.inL[i];
                const float r = io.inR[i];
                const float yl = mHigh.process(0, mMid.process(0, mLow.process(0, l)));
                const float yr = mHigh.process(1, mMid.process(1, mLow.process(1, r)));
                io.outL[i] = yl;
                io.outR[i] = yr;
            }
            done += n;
        }
    }
};

/*** Compressor ************************************************************/

struct Comp : EffectBase<9> {
    enum Port {
        kThreshDb, kRatio, kAttackMs, kReleaseMs, kMakeupDb, kInL, kInR, kOutL, kOutR
    };

    float mEnv = 0.0f;  // linear peak envelope of the stereo max
    Smooth mThresh, mRatioS, mMakeup;
    float mSetAttack = -1.0f, mSetRelease = -1.0f;
    float mAttackCoeff = 0.0f, mReleaseCoeff = 0.0f;

    bool init(float rate) {
        initBase(rate);
        mThresh.configure(mSmoothCoeff);
        mRatioS.configure(mSmoothCoeff);
        mMakeup.configure(mSmoothCoeff);
        return true;
    }

    void activate() {
        mEnv = 0.0f;
        mSetAttack = mSetRelease = -1.0f;
        mSnap = true;
    }

    void run(unsigned long count) {
        const AudioPorts io = ports();
        if (!io.connected()) return;

        const float threshTarget = clampf(ctl(kThreshDb), -60.0f, 0.0f);
        const float ratioTarget = clampf(ctl(kRatio), 1.0f, 16.0f);
        const float attackMs = clampf(ctl(kAttackMs), 0.1f, 500.0f);
        const float releaseMs = clampf(ctl(kReleaseMs), 1.0f, 5000.0f);
        const float makeupTarget = clampf(ctl(kMakeupDb), 0.0f, 24.0f);
        snapAll({{&mThresh, threshTarget}, {&mRatioS, ratioTarget}, {&mMakeup, makeupTarget}});
        if (attackMs != mSetAttack) {
            mAttackCoeff = ballistic(attackMs);
            mSetAttack = attackMs;
        }
        if (releaseMs != mSetRelease) {
            mReleaseCoeff = ballistic(releaseMs);
            mSetRelease = releaseMs;
        }

        for (unsigned long done = 0; done < count;) {
            const unsigned long n = std::min<unsigned long>(kChunk, count - done);
            const float threshLin = dbToGain(mThresh.step(threshTarget));
            const float slope = 1.0f - 1.0f / mRatioS.step(ratioTarget);
            // Makeup is a straight gain, so ramp it per sample. The envelope
            // ballistics already keep the compression gain itself smooth.
            const float makeupFrom = dbToGain(mMakeup.value());
            const float makeupTo = dbToGain(mMakeup.step(makeupTarget));
            const float makeupInc = (makeupTo - makeupFrom) / static_cast<float>(n);
            float makeup = makeupFrom;

            for (unsigned long i = done; i < done + n; ++i) {
                const float l = io.inL[i];
                const float r = io.inR[i];
                const float det = std::fmax(std::fabs(l), std::fabs(r));
                const float coeff = det > mEnv ? mAttackCoeff : mReleaseCoeff;
                mEnv = flush(det + coeff * (mEnv - det));

                // At ratio 1:1 slope is 0 and pow(x, -0) is exactly 1, so the
                // straight-wire case stays bit-exact.
                float g = makeup;
                if (mEnv > threshLin) g *= std::pow(mEnv / threshLin, -slope);
                io.outL[i] = l * g;
                io.outR[i] = r * g;
                makeup += makeupInc;
            }
            done += n;
        }
    }
};

/*** Delay *****************************************************************/

constexpr float kMaxDelaySec = 2.0f;
constexpr float kMaxFeedback = 0.95f;  // guarantees the tail decays

struct Delay : EffectBase<7> {
    enum Port { kTimeMs, kFeedbackPct, kMixPct, kInL, kInR, kOutL, kOutR };

    float* mBuf = nullptr;  // two channels, mLen frames each, laid end to end
    unsigned long mLen = 0;
    unsigned long mWrite = 0;
    Smooth mDelay, mFb, mMix;

    ~Delay() { delete[] mBuf; }

    bool init(float rate) {
        initBase(rate);
        mDelay.configure(mSmoothCoeff);
        mFb.configure(mSmoothCoeff);
        mMix.configure(mSmoothCoeff);
        mLen = static_cast<unsigned long>(kMaxDelaySec * rate) + 8;
        mBuf = new (std::nothrow) float[mLen * 2];
        if (!mBuf) return false;
        std::memset(mBuf, 0, sizeof(float) * mLen * 2);
        return true;
    }

    void activate() {
        std::memset(mBuf, 0, sizeof(float) * mLen * 2);
        mWrite = 0;
        mSnap = true;
    }

    inline float tap(const float* buf, float d) const {
        return tapLine(buf, mLen, mWrite, d);
    }

    void run(unsigned long count) {
        const AudioPorts io = ports();
        if (!io.connected() || !mBuf) return;

        const float maxDelay = static_cast<float>(mLen - 2);
        const float delayTarget =
                clampf(ctl(kTimeMs) * 0.001f * mRate, 1.0f, maxDelay);
        const float fbTarget = clampf(ctl(kFeedbackPct) * 0.01f, 0.0f, kMaxFeedback);
        const float mixTarget = clampf(ctl(kMixPct) * 0.01f, 0.0f, 1.0f);
        snapAll({{&mDelay, delayTarget}, {&mFb, fbTarget}, {&mMix, mixTarget}});

        float* bufL = mBuf;
        float* bufR = mBuf + mLen;

        for (unsigned long done = 0; done < count;) {
            const unsigned long n = std::min<unsigned long>(kChunk, count - done);
            const float fn = static_cast<float>(n);
            // Everything ramps per sample: a stepped delay time clicks, and a
            // stepped mix or feedback zippers.
            float d = mDelay.value();
            float fb = mFb.value();
            float mix = mMix.value();
            const float dInc = (mDelay.step(delayTarget) - d) / fn;
            const float fbInc = (mFb.step(fbTarget) - fb) / fn;
            const float mixInc = (mMix.step(mixTarget) - mix) / fn;

            for (unsigned long i = done; i < done + n; ++i) {
                d += dInc;
                fb += fbInc;
                mix += mixInc;
                const float dc = clampf(d, 1.0f, maxDelay);

                const float l = io.inL[i];
                const float r = io.inR[i];
                const float wl = tap(bufL, dc);
                const float wr = tap(bufR, dc);
                bufL[mWrite] = flush(l + wl * fb);
                bufR[mWrite] = flush(r + wr * fb);
                io.outL[i] = l + (wl - l) * mix;
                io.outR[i] = r + (wr - r) * mix;
                mWrite = wrapInc(mWrite, mLen);
            }
            done += n;
        }
    }
};

/*** Reverb ****************************************************************/

// Freeverb topology (Jezar at Dreampoint, public domain): eight parallel
// damped comb filters into four series allpasses, per channel, with the right
// channel's lines lengthened by a fixed spread so the two decorrelate. The
// tunings are quoted at 44.1 kHz and scaled to whatever rate we're given.
constexpr int kCombCount = 8;
constexpr int kAllpassCount = 4;
constexpr int kCombTuning[kCombCount] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
constexpr int kAllpassTuning[kAllpassCount] = {556, 441, 341, 225};
constexpr int kStereoSpread = 23;
constexpr float kTuningRate = 44100.0f;

constexpr float kReverbInGain = 0.015f;   // freeverb's fixed send level
constexpr float kAllpassFeedback = 0.5f;
// Comb feedback runs 0.70 .. 0.95. The upper end is short of freeverb's 0.98:
// a minute-long tail is not a musical setting and the extra headroom keeps the
// wet path from piling up at extreme sizes.
constexpr float kRoomScale = 0.25f;
constexpr float kRoomOffset = 0.70f;
constexpr float kDampScale = 0.4f;
constexpr float kMaxPredelaySec = 0.2f;

struct Reverb : EffectBase<8> {
    enum Port { kSizePct, kDampPct, kPredelayMs, kMixPct, kInL, kInR, kOutL, kOutR };

    float* mBuf = nullptr;  // every line, laid end to end
    float* mComb[2][kCombCount] = {};
    unsigned long mCombLen[2][kCombCount] = {};
    unsigned long mCombIdx[2][kCombCount] = {};
    float mCombStore[2][kCombCount] = {};  // one-pole damping state per comb
    float* mAllpass[2][kAllpassCount] = {};
    unsigned long mAllpassLen[2][kAllpassCount] = {};
    unsigned long mAllpassIdx[2][kAllpassCount] = {};
    float* mPre[2] = {};
    unsigned long mPreLen = 0;
    unsigned long mPreWrite = 0;
    unsigned long mTotal = 0;
    Smooth mSize, mDamp, mPredelay, mMix;

    ~Reverb() { delete[] mBuf; }

    bool init(float rate) {
        initBase(rate);
        mSize.configure(mSmoothCoeff);
        mDamp.configure(mSmoothCoeff);
        mPredelay.configure(mSmoothCoeff);
        mMix.configure(mSmoothCoeff);

        const float scale = rate / kTuningRate;
        const unsigned long spread =
                static_cast<unsigned long>(kStereoSpread * scale + 0.5f);
        auto scaled = [scale](int tuning, int ch, unsigned long spr) {
            const unsigned long n =
                    static_cast<unsigned long>(tuning * scale + 0.5f) + (ch ? spr : 0);
            return n < 4 ? 4ul : n;
        };

        mTotal = 0;
        for (int c = 0; c < 2; ++c) {
            for (int k = 0; k < kCombCount; ++k) {
                mCombLen[c][k] = scaled(kCombTuning[k], c, spread);
                mTotal += mCombLen[c][k];
            }
            for (int k = 0; k < kAllpassCount; ++k) {
                mAllpassLen[c][k] = scaled(kAllpassTuning[k], c, spread);
                mTotal += mAllpassLen[c][k];
            }
        }
        mPreLen = static_cast<unsigned long>(kMaxPredelaySec * rate) + 8;
        mTotal += 2 * mPreLen;

        mBuf = new (std::nothrow) float[mTotal];
        if (!mBuf) return false;
        std::memset(mBuf, 0, sizeof(float) * mTotal);

        float* p = mBuf;
        for (int c = 0; c < 2; ++c) {
            for (int k = 0; k < kCombCount; ++k) {
                mComb[c][k] = p;
                p += mCombLen[c][k];
            }
            for (int k = 0; k < kAllpassCount; ++k) {
                mAllpass[c][k] = p;
                p += mAllpassLen[c][k];
            }
        }
        mPre[0] = p;
        p += mPreLen;
        mPre[1] = p;
        return true;
    }

    void activate() {
        std::memset(mBuf, 0, sizeof(float) * mTotal);
        for (int c = 0; c < 2; ++c) {
            for (int k = 0; k < kCombCount; ++k) {
                mCombIdx[c][k] = 0;
                mCombStore[c][k] = 0.0f;
            }
            for (int k = 0; k < kAllpassCount; ++k) mAllpassIdx[c][k] = 0;
        }
        mPreWrite = 0;
        mSnap = true;
    }

    // One channel of the comb bank + allpass chain, advanced by one sample.
    inline float tank(int c, float send, float feedback, float damp1, float damp2) {
        float acc = 0.0f;
        for (int k = 0; k < kCombCount; ++k) {
            float* b = mComb[c][k];
            const unsigned long idx = mCombIdx[c][k];
            const float out = b[idx];
            mCombStore[c][k] = flush(out * damp2 + mCombStore[c][k] * damp1);
            b[idx] = flush(send + mCombStore[c][k] * feedback);
            mCombIdx[c][k] = wrapInc(idx, mCombLen[c][k]);
            acc += out;
        }
        for (int k = 0; k < kAllpassCount; ++k) {
            float* b = mAllpass[c][k];
            const unsigned long idx = mAllpassIdx[c][k];
            const float stored = b[idx];
            b[idx] = flush(acc + stored * kAllpassFeedback);
            acc = stored - acc;
            mAllpassIdx[c][k] = wrapInc(idx, mAllpassLen[c][k]);
        }
        return acc;
    }

    void run(unsigned long count) {
        const AudioPorts io = ports();
        if (!io.connected() || !mBuf) return;

        const float maxPre = static_cast<float>(mPreLen - 2);
        const float sizeTarget = clampf(ctl(kSizePct) * 0.01f, 0.0f, 1.0f);
        const float dampTarget = clampf(ctl(kDampPct) * 0.01f, 0.0f, 1.0f);
        const float preTarget =
                clampf(ctl(kPredelayMs) * 0.001f * mRate, 1.0f, maxPre);
        const float mixTarget = clampf(ctl(kMixPct) * 0.01f, 0.0f, 1.0f);
        snapAll({{&mSize, sizeTarget},
                 {&mDamp, dampTarget},
                 {&mPredelay, preTarget},
                 {&mMix, mixTarget}});

        for (unsigned long done = 0; done < count;) {
            const unsigned long n = std::min<unsigned long>(kChunk, count - done);
            const float fn = static_cast<float>(n);
            // Size and damping are filter coefficients, so chunk rate is enough.
            // Pre-delay and mix ramp per sample, same as the delay's do.
            const float feedback = mSize.step(sizeTarget) * kRoomScale + kRoomOffset;
            const float damp1 = mDamp.step(dampTarget) * kDampScale;
            const float damp2 = 1.0f - damp1;
            float pre = mPredelay.value();
            float mix = mMix.value();
            const float preInc = (mPredelay.step(preTarget) - pre) / fn;
            const float mixInc = (mMix.step(mixTarget) - mix) / fn;

            for (unsigned long i = done; i < done + n; ++i) {
                pre += preInc;
                mix += mixInc;
                const float pd = clampf(pre, 1.0f, maxPre);

                const float l = io.inL[i];
                const float r = io.inR[i];
                const float pl = tapLine(mPre[0], mPreLen, mPreWrite, pd);
                const float pr = tapLine(mPre[1], mPreLen, mPreWrite, pd);
                mPre[0][mPreWrite] = flush(l);
                mPre[1][mPreWrite] = flush(r);
                mPreWrite = wrapInc(mPreWrite, mPreLen);

                const float send = (pl + pr) * kReverbInGain;
                const float wl = tank(0, send, feedback, damp1, damp2);
                const float wr = tank(1, send, feedback, damp1, damp2);
                io.outL[i] = l + (wl - l) * mix;
                io.outR[i] = r + (wr - r) * mix;
            }
            done += n;
        }
    }
};

/*** Chorus ****************************************************************/

// Delay of kChorusBaseMs, swept +/- depth * kChorusSwingMs by a sine LFO. The
// right channel reads a quarter-cycle ahead, which is what opens the image up.
constexpr float kChorusBaseMs = 12.0f;
constexpr float kChorusSwingMs = 7.0f;
constexpr float kChorusMaxSec = 0.05f;
constexpr float kChorusPhaseOffset = 0.25f;

struct Chorus : EffectBase<7> {
    enum Port { kRateHz, kDepthPct, kMixPct, kInL, kInR, kOutL, kOutR };

    float* mBuf = nullptr;  // two channels, mLen frames each, end to end
    unsigned long mLen = 0;
    unsigned long mWrite = 0;
    float mPhase = 0.0f;  // LFO position in cycles, [0, 1)
    Smooth mRateS, mDepth, mMix;

    ~Chorus() { delete[] mBuf; }

    bool init(float rate) {
        initBase(rate);
        mRateS.configure(mSmoothCoeff);
        mDepth.configure(mSmoothCoeff);
        mMix.configure(mSmoothCoeff);
        mLen = static_cast<unsigned long>(kChorusMaxSec * rate) + 8;
        mBuf = new (std::nothrow) float[mLen * 2];
        if (!mBuf) return false;
        std::memset(mBuf, 0, sizeof(float) * mLen * 2);
        return true;
    }

    void activate() {
        std::memset(mBuf, 0, sizeof(float) * mLen * 2);
        mWrite = 0;
        mPhase = 0.0f;
        mSnap = true;
    }

    void run(unsigned long count) {
        const AudioPorts io = ports();
        if (!io.connected() || !mBuf) return;

        const float maxDelay = static_cast<float>(mLen - 2);
        const float base = clampf(kChorusBaseMs * 0.001f * mRate, 1.0f, maxDelay);
        const float swing = kChorusSwingMs * 0.001f * mRate;
        const float rateTarget = clampf(ctl(kRateHz), 0.0f, 20.0f);
        const float depthTarget = clampf(ctl(kDepthPct) * 0.01f, 0.0f, 1.0f);
        const float mixTarget = clampf(ctl(kMixPct) * 0.01f, 0.0f, 1.0f);
        snapAll({{&mRateS, rateTarget}, {&mDepth, depthTarget}, {&mMix, mixTarget}});

        float* bufL = mBuf;
        float* bufR = mBuf + mLen;

        for (unsigned long done = 0; done < count;) {
            const unsigned long n = std::min<unsigned long>(kChunk, count - done);
            const float fn = static_cast<float>(n);
            float depth = mDepth.value();
            float mix = mMix.value();
            const float step = mRateS.step(rateTarget) / mRate;
            const float depthInc = (mDepth.step(depthTarget) - depth) / fn;
            const float mixInc = (mMix.step(mixTarget) - mix) / fn;

            for (unsigned long i = done; i < done + n; ++i) {
                depth += depthInc;
                mix += mixInc;
                const float dl = clampf(base + swing * depth * std::sin(kTwoPi * mPhase),
                                        1.0f, maxDelay);
                const float dr = clampf(
                        base + swing * depth *
                                       std::sin(kTwoPi * (mPhase + kChorusPhaseOffset)),
                        1.0f, maxDelay);
                mPhase += step;
                if (mPhase >= 1.0f) mPhase -= 1.0f;

                const float l = io.inL[i];
                const float r = io.inR[i];
                const float wl = tapLine(bufL, mLen, mWrite, dl);
                const float wr = tapLine(bufR, mLen, mWrite, dr);
                bufL[mWrite] = flush(l);
                bufR[mWrite] = flush(r);
                io.outL[i] = l + (wl - l) * mix;
                io.outR[i] = r + (wr - r) * mix;
                mWrite = wrapInc(mWrite, mLen);
            }
            done += n;
        }
    }
};

/*** Noise gate ************************************************************/

// Below the open threshold by this much, the gate commits to closing. The
// deadband stops a signal sitting on the threshold from chattering.
constexpr float kGateHysteresis = 0.708f;  // -3 dB
constexpr float kGateDetectRelMs = 10.0f;

struct Gate : EffectBase<8> {
    enum Port {
        kThreshDb, kAttackMs, kHoldMs, kReleaseMs, kInL, kInR, kOutL, kOutR
    };

    float mEnv = 0.0f;    // peak detector, instant attack
    float mGain = 0.0f;   // smoothed gate gain; never steps, so never clicks
    unsigned long mHold = 0;
    bool mOpen = false;
    Smooth mThresh;
    float mSetAttack = -1.0f, mSetRelease = -1.0f;
    float mAttackCoeff = 0.0f, mReleaseCoeff = 0.0f, mDetectCoeff = 0.0f;

    bool init(float rate) {
        initBase(rate);
        mThresh.configure(mSmoothCoeff);
        mDetectCoeff = ballistic(kGateDetectRelMs);
        return true;
    }

    void activate() {
        mEnv = 0.0f;
        mGain = 0.0f;
        mHold = 0;
        mOpen = false;
        mSetAttack = mSetRelease = -1.0f;
        mSnap = true;
    }

    void run(unsigned long count) {
        const AudioPorts io = ports();
        if (!io.connected()) return;

        const float threshTarget = clampf(ctl(kThreshDb), -80.0f, 0.0f);
        const float attackMs = clampf(ctl(kAttackMs), 0.1f, 100.0f);
        const float holdMs = clampf(ctl(kHoldMs), 0.0f, 500.0f);
        const float releaseMs = clampf(ctl(kReleaseMs), 1.0f, 1000.0f);
        snapAll({{&mThresh, threshTarget}});
        if (attackMs != mSetAttack) {
            mAttackCoeff = ballistic(attackMs);
            mSetAttack = attackMs;
        }
        if (releaseMs != mSetRelease) {
            mReleaseCoeff = ballistic(releaseMs);
            mSetRelease = releaseMs;
        }
        const unsigned long holdSamples =
                static_cast<unsigned long>(holdMs * 0.001f * mRate);

        for (unsigned long done = 0; done < count;) {
            const unsigned long n = std::min<unsigned long>(kChunk, count - done);
            const float openAt = dbToGain(mThresh.step(threshTarget));
            const float closeAt = openAt * kGateHysteresis;

            for (unsigned long i = done; i < done + n; ++i) {
                const float l = io.inL[i];
                const float r = io.inR[i];
                const float det = std::fmax(std::fabs(l), std::fabs(r));
                mEnv = det > mEnv ? det : flush(det + mDetectCoeff * (mEnv - det));

                if (mEnv >= openAt) {
                    mOpen = true;
                    mHold = holdSamples;
                } else if (mEnv < closeAt) {
                    if (mHold > 0) {
                        --mHold;
                    } else {
                        mOpen = false;
                    }
                }

                const float target = mOpen ? 1.0f : 0.0f;
                const float coeff = target > mGain ? mAttackCoeff : mReleaseCoeff;
                mGain = flush(target + coeff * (mGain - target));
                io.outL[i] = l * mGain;
                io.outR[i] = r * mGain;
            }
            done += n;
        }
    }
};

/*** Limiter ***************************************************************/

// Peak limiter for the master bus. The detector attacks instantly, so the gain
// is already down on the sample that needs it: no lookahead, and no overshoot
// to clip afterwards. Only the recovery is smoothed.
struct Limiter : EffectBase<7> {
    enum Port { kThreshDb, kCeilingDb, kReleaseMs, kInL, kInR, kOutL, kOutR };

    float mEnv = 0.0f;
    Smooth mThresh, mCeiling;
    float mSetRelease = -1.0f;
    float mReleaseCoeff = 0.0f;

    bool init(float rate) {
        initBase(rate);
        mThresh.configure(mSmoothCoeff);
        mCeiling.configure(mSmoothCoeff);
        return true;
    }

    void activate() {
        mEnv = 0.0f;
        mSetRelease = -1.0f;
        mSnap = true;
    }

    void run(unsigned long count) {
        const AudioPorts io = ports();
        if (!io.connected()) return;

        const float threshTarget = clampf(ctl(kThreshDb), -24.0f, 0.0f);
        const float ceilTarget = clampf(ctl(kCeilingDb), -24.0f, 0.0f);
        const float releaseMs = clampf(ctl(kReleaseMs), 1.0f, 1000.0f);
        snapAll({{&mThresh, threshTarget}, {&mCeiling, ceilTarget}});
        if (releaseMs != mSetRelease) {
            mReleaseCoeff = ballistic(releaseMs);
            mSetRelease = releaseMs;
        }

        for (unsigned long done = 0; done < count;) {
            const unsigned long n = std::min<unsigned long>(kChunk, count - done);
            const float threshLin = dbToGain(mThresh.step(threshTarget));
            // Threshold maps to ceiling, so lowering the threshold makes the
            // whole signal louder rather than just squashing the top off.
            const float ceilFrom = dbToGain(mCeiling.value());
            const float ceilTo = dbToGain(mCeiling.step(ceilTarget));
            const float ceilInc = (ceilTo - ceilFrom) / static_cast<float>(n);
            float ceiling = ceilFrom;

            for (unsigned long i = done; i < done + n; ++i) {
                const float l = io.inL[i];
                const float r = io.inR[i];
                const float det = std::fmax(std::fabs(l), std::fabs(r));
                mEnv = det > mEnv ? det : flush(det + mReleaseCoeff * (mEnv - det));

                float g = ceiling / threshLin;
                if (mEnv > threshLin) g *= threshLin / mEnv;
                // Exact by construction; the clamp only catches a control
                // value that moved mid-chunk.
                io.outL[i] = clampf(l * g, -ceiling, ceiling);
                io.outR[i] = clampf(r * g, -ceiling, ceiling);
                ceiling += ceilInc;
            }
            done += n;
        }
    }
};

/*** Resonant filter *******************************************************/

// Topology-preserving 2-pole state variable filter (Zavalishin / Simper),
// stable for any cutoff and Q the user can dial in, including cutoffs pushed
// at Nyquist, where a naive Chamberlin SVF is not.
struct Filter : EffectBase<7> {
    enum Port { kMode, kCutoffHz, kResonance, kInL, kInR, kOutL, kOutR };

    float mIc1[2] = {0.0f, 0.0f};
    float mIc2[2] = {0.0f, 0.0f};
    Smooth mCutoff, mRes;
    float mSetCutoff = -1.0f, mSetRes = -1.0f;
    float mA1 = 0.0f, mA2 = 0.0f, mA3 = 0.0f, mK = 0.0f;

    bool init(float rate) {
        initBase(rate);
        mCutoff.configure(mSmoothCoeff);
        mRes.configure(mSmoothCoeff);
        return true;
    }

    void activate() {
        mIc1[0] = mIc1[1] = mIc2[0] = mIc2[1] = 0.0f;
        mSetCutoff = mSetRes = -1.0f;
        mSnap = true;
    }

    void setCoeffs(float hz, float q) {
        const float g = std::tan(kPi * hz / mRate);
        mK = 1.0f / q;
        mA1 = 1.0f / (1.0f + g * (g + mK));
        mA2 = g * mA1;
        mA3 = g * mA2;
    }

    inline float process(int ch, float x, bool highPass) {
        const float v3 = x - mIc2[ch];
        const float v1 = mA1 * mIc1[ch] + mA2 * v3;
        const float v2 = mIc2[ch] + mA2 * mIc1[ch] + mA3 * v3;
        mIc1[ch] = flush(2.0f * v1 - mIc1[ch]);
        mIc2[ch] = flush(2.0f * v2 - mIc2[ch]);
        return highPass ? x - mK * v1 - v2 : v2;
    }

    void run(unsigned long count) {
        const AudioPorts io = ports();
        if (!io.connected()) return;

        // 0.49 * rate keeps tan() well short of its pole at Nyquist.
        const float maxHz = 0.49f * mRate;
        const bool highPass = ctl(kMode) >= 0.5f;
        const float cutoffTarget = clampf(ctl(kCutoffHz), 10.0f, maxHz);
        const float resTarget = clampf(ctl(kResonance), 0.5f, 16.0f);
        snapAll({{&mCutoff, cutoffTarget}, {&mRes, resTarget}});

        for (unsigned long done = 0; done < count;) {
            const unsigned long n = std::min<unsigned long>(kChunk, count - done);
            const float hz = mCutoff.step(cutoffTarget);
            const float q = mRes.step(resTarget);
            if (hz != mSetCutoff || q != mSetRes) {
                setCoeffs(hz, q);
                mSetCutoff = hz;
                mSetRes = q;
            }

            for (unsigned long i = done; i < done + n; ++i) {
                const float l = io.inL[i];
                const float r = io.inR[i];
                io.outL[i] = process(0, l, highPass);
                io.outR[i] = process(1, r, highPass);
            }
            done += n;
        }
    }
};

/*** Descriptors ***********************************************************/

constexpr LADSPA_PortDescriptor kCtlIn = LADSPA_PORT_INPUT | LADSPA_PORT_CONTROL;
constexpr LADSPA_PortDescriptor kAudioIn = LADSPA_PORT_INPUT | LADSPA_PORT_AUDIO;
constexpr LADSPA_PortDescriptor kAudioOut = LADSPA_PORT_OUTPUT | LADSPA_PORT_AUDIO;

constexpr LADSPA_PortRangeHintDescriptor kBounded =
        LADSPA_HINT_BOUNDED_BELOW | LADSPA_HINT_BOUNDED_ABOVE;
constexpr LADSPA_PortRangeHintDescriptor kBoundedLog = kBounded | LADSPA_HINT_LOGARITHMIC;

// Ranges are chosen so the LADSPA default rules land on the intended value: a
// logarithmic 125..8000 Hz port with DEFAULT_MIDDLE is 1 kHz.
const LADSPA_PortDescriptor kEq3Ports[Eq3::kPortCount] = {
        kCtlIn, kCtlIn, kCtlIn, kCtlIn, kAudioIn, kAudioIn, kAudioOut, kAudioOut};
const char* const kEq3PortNames[Eq3::kPortCount] = {
        "Low (dB)", "Mid (dB)", "Mid Freq (Hz)", "High (dB)",
        "Input L", "Input R", "Output L", "Output R"};
const LADSPA_PortRangeHint kEq3Hints[Eq3::kPortCount] = {
        {kBounded | LADSPA_HINT_DEFAULT_0, -24.0f, 24.0f},
        {kBounded | LADSPA_HINT_DEFAULT_0, -24.0f, 24.0f},
        {kBoundedLog | LADSPA_HINT_DEFAULT_MIDDLE, 125.0f, 8000.0f},
        {kBounded | LADSPA_HINT_DEFAULT_0, -24.0f, 24.0f},
        {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}};

const LADSPA_PortDescriptor kCompPorts[Comp::kPortCount] = {
        kCtlIn, kCtlIn, kCtlIn, kCtlIn, kCtlIn,
        kAudioIn, kAudioIn, kAudioOut, kAudioOut};
const char* const kCompPortNames[Comp::kPortCount] = {
        "Threshold (dB)", "Ratio (:1)", "Attack (ms)", "Release (ms)", "Makeup (dB)",
        "Input L", "Input R", "Output L", "Output R"};
const LADSPA_PortRangeHint kCompHints[Comp::kPortCount] = {
        {kBounded | LADSPA_HINT_DEFAULT_HIGH, -60.0f, 0.0f},
        {kBoundedLog | LADSPA_HINT_DEFAULT_MIDDLE, 1.0f, 16.0f},
        {kBoundedLog | LADSPA_HINT_DEFAULT_MIDDLE, 1.0f, 100.0f},
        {kBoundedLog | LADSPA_HINT_DEFAULT_MIDDLE, 10.0f, 1000.0f},
        {kBounded | LADSPA_HINT_DEFAULT_0, 0.0f, 24.0f},
        {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}};

const LADSPA_PortDescriptor kDelayPorts[Delay::kPortCount] = {
        kCtlIn, kCtlIn, kCtlIn, kAudioIn, kAudioIn, kAudioOut, kAudioOut};
const char* const kDelayPortNames[Delay::kPortCount] = {
        "Time (ms)", "Feedback (%)", "Mix (%)",
        "Input L", "Input R", "Output L", "Output R"};
const LADSPA_PortRangeHint kDelayHints[Delay::kPortCount] = {
        {kBoundedLog | LADSPA_HINT_DEFAULT_MIDDLE, 20.0f, 2000.0f},
        {kBounded | LADSPA_HINT_DEFAULT_LOW, 0.0f, 95.0f},
        {kBounded | LADSPA_HINT_DEFAULT_LOW, 0.0f, 100.0f},
        {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}};

const LADSPA_PortDescriptor kReverbPorts[Reverb::kPortCount] = {
        kCtlIn, kCtlIn, kCtlIn, kCtlIn, kAudioIn, kAudioIn, kAudioOut, kAudioOut};
const char* const kReverbPortNames[Reverb::kPortCount] = {
        "Size (%)", "Damping (%)", "Pre-delay (ms)", "Mix (%)",
        "Input L", "Input R", "Output L", "Output R"};
const LADSPA_PortRangeHint kReverbHints[Reverb::kPortCount] = {
        {kBounded | LADSPA_HINT_DEFAULT_MIDDLE, 0.0f, 100.0f},
        {kBounded | LADSPA_HINT_DEFAULT_MIDDLE, 0.0f, 100.0f},
        {kBounded | LADSPA_HINT_DEFAULT_0, 0.0f, 200.0f},
        {kBounded | LADSPA_HINT_DEFAULT_LOW, 0.0f, 100.0f},
        {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}};

const LADSPA_PortDescriptor kChorusPorts[Chorus::kPortCount] = {
        kCtlIn, kCtlIn, kCtlIn, kAudioIn, kAudioIn, kAudioOut, kAudioOut};
const char* const kChorusPortNames[Chorus::kPortCount] = {
        "Rate (Hz)", "Depth (%)", "Mix (%)",
        "Input L", "Input R", "Output L", "Output R"};
const LADSPA_PortRangeHint kChorusHints[Chorus::kPortCount] = {
        {kBoundedLog | LADSPA_HINT_DEFAULT_MIDDLE, 0.05f, 10.0f},
        {kBounded | LADSPA_HINT_DEFAULT_MIDDLE, 0.0f, 100.0f},
        {kBounded | LADSPA_HINT_DEFAULT_LOW, 0.0f, 100.0f},
        {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}};

const LADSPA_PortDescriptor kGatePorts[Gate::kPortCount] = {
        kCtlIn, kCtlIn, kCtlIn, kCtlIn, kAudioIn, kAudioIn, kAudioOut, kAudioOut};
const char* const kGatePortNames[Gate::kPortCount] = {
        "Threshold (dB)", "Attack (ms)", "Hold (ms)", "Release (ms)",
        "Input L", "Input R", "Output L", "Output R"};
const LADSPA_PortRangeHint kGateHints[Gate::kPortCount] = {
        {kBounded | LADSPA_HINT_DEFAULT_LOW, -80.0f, 0.0f},
        {kBoundedLog | LADSPA_HINT_DEFAULT_LOW, 0.1f, 100.0f},
        {kBounded | LADSPA_HINT_DEFAULT_LOW, 0.0f, 500.0f},
        {kBoundedLog | LADSPA_HINT_DEFAULT_MIDDLE, 10.0f, 1000.0f},
        {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}};

// Threshold and ceiling both default to 0 dB: out of the box the limiter is a
// transparent brick wall that only ever stops the master clipping.
const LADSPA_PortDescriptor kLimiterPorts[Limiter::kPortCount] = {
        kCtlIn, kCtlIn, kCtlIn, kAudioIn, kAudioIn, kAudioOut, kAudioOut};
const char* const kLimiterPortNames[Limiter::kPortCount] = {
        "Threshold (dB)", "Ceiling (dB)", "Release (ms)",
        "Input L", "Input R", "Output L", "Output R"};
const LADSPA_PortRangeHint kLimiterHints[Limiter::kPortCount] = {
        {kBounded | LADSPA_HINT_DEFAULT_0, -24.0f, 0.0f},
        {kBounded | LADSPA_HINT_DEFAULT_0, -24.0f, 0.0f},
        {kBoundedLog | LADSPA_HINT_DEFAULT_MIDDLE, 1.0f, 1000.0f},
        {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}};

// Mode is an INTEGER port rather than TOGGLED: LADSPA forbids combining
// TOGGLED with bounds, and the UI renders every control port off its bounds.
const LADSPA_PortDescriptor kFilterPorts[Filter::kPortCount] = {
        kCtlIn, kCtlIn, kCtlIn, kAudioIn, kAudioIn, kAudioOut, kAudioOut};
const char* const kFilterPortNames[Filter::kPortCount] = {
        "Mode (0=LP 1=HP)", "Cutoff (Hz)", "Resonance (Q)",
        "Input L", "Input R", "Output L", "Output R"};
const LADSPA_PortRangeHint kFilterHints[Filter::kPortCount] = {
        {kBounded | LADSPA_HINT_INTEGER | LADSPA_HINT_DEFAULT_0, 0.0f, 1.0f},
        {kBoundedLog | LADSPA_HINT_DEFAULT_MAXIMUM, 20.0f, 20000.0f},
        {kBoundedLog | LADSPA_HINT_DEFAULT_MINIMUM, 0.707f, 16.0f},
        {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}, {0, 0.0f, 0.0f}};

template <class T>
LADSPA_Handle instantiateEffect(const LADSPA_Descriptor*, unsigned long rate) {
    T* self = new (std::nothrow) T();
    if (!self) return nullptr;
    if (!self->init(static_cast<float>(rate))) {
        delete self;
        return nullptr;
    }
    return self;
}

template <class T>
void connectEffectPort(LADSPA_Handle handle, unsigned long port, LADSPA_Data* data) {
    if (port < T::kPortCount) static_cast<T*>(handle)->mPort[port] = data;
}

template <class T>
void activateEffect(LADSPA_Handle handle) {
    static_cast<T*>(handle)->activate();
}

template <class T>
void runEffect(LADSPA_Handle handle, unsigned long count) {
    static_cast<T*>(handle)->run(count);
}

template <class T>
void cleanupEffect(LADSPA_Handle handle) {
    delete static_cast<T*>(handle);
}

template <class T>
LADSPA_Descriptor describe(unsigned long id, const char* label, const char* name,
                           const LADSPA_PortDescriptor* ports,
                           const char* const* portNames,
                           const LADSPA_PortRangeHint* hints) {
    LADSPA_Descriptor d = {};
    d.UniqueID = id;
    d.Label = label;
    d.Properties = LADSPA_PROPERTY_HARD_RT_CAPABLE;
    d.Name = name;
    d.Maker = "PAW!";
    d.Copyright = "GPL-3.0-or-later";
    d.PortCount = T::kPortCount;
    d.PortDescriptors = ports;
    d.PortNames = portNames;
    d.PortRangeHints = hints;
    d.instantiate = &instantiateEffect<T>;
    d.connect_port = &connectEffectPort<T>;
    d.activate = &activateEffect<T>;
    d.run = &runEffect<T>;
    d.cleanup = &cleanupEffect<T>;
    return d;
}

}  // namespace

const LADSPA_Descriptor* pawBuiltinDescriptor(unsigned long index) {
    // IDs are local to the built-in set; nothing dlopens these, so they need
    // no registration with the LADSPA ID authority.
    static const LADSPA_Descriptor kEq3 = describe<Eq3>(
            0xD0B01, "paw_eq3", "PAW! 3-Band EQ",
            kEq3Ports, kEq3PortNames, kEq3Hints);
    static const LADSPA_Descriptor kComp = describe<Comp>(
            0xD0B02, "paw_comp", "PAW! Compressor",
            kCompPorts, kCompPortNames, kCompHints);
    static const LADSPA_Descriptor kDelay = describe<Delay>(
            0xD0B03, "paw_delay", "PAW! Delay",
            kDelayPorts, kDelayPortNames, kDelayHints);
    static const LADSPA_Descriptor kReverb = describe<Reverb>(
            0xD0B04, "paw_reverb", "PAW! Reverb",
            kReverbPorts, kReverbPortNames, kReverbHints);
    static const LADSPA_Descriptor kChorus = describe<Chorus>(
            0xD0B05, "paw_chorus", "PAW! Chorus",
            kChorusPorts, kChorusPortNames, kChorusHints);
    static const LADSPA_Descriptor kGate = describe<Gate>(
            0xD0B06, "paw_gate", "PAW! Noise Gate",
            kGatePorts, kGatePortNames, kGateHints);
    static const LADSPA_Descriptor kLimiter = describe<Limiter>(
            0xD0B07, "paw_limiter", "PAW! Limiter",
            kLimiterPorts, kLimiterPortNames, kLimiterHints);
    static const LADSPA_Descriptor kFilter = describe<Filter>(
            0xD0B08, "paw_filter", "PAW! Filter",
            kFilterPorts, kFilterPortNames, kFilterHints);

    switch (index) {
        case 0: return &kEq3;
        case 1: return &kComp;
        case 2: return &kDelay;
        case 3: return &kReverb;
        case 4: return &kChorus;
        case 5: return &kGate;
        case 6: return &kLimiter;
        case 7: return &kFilter;
        default: return nullptr;
    }
}
