#include "AudioEngine.h"

#include <android/log.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstring>

#include "EffectHost.h"
#include "Mix.h"
#include "OboeWav.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PawEngine", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PawEngine", __VA_ARGS__)

namespace {

void sleepMs(int ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }

// Ring refill and take writing must outrank UI work: at default priority they
// compete with Compose recomposition, which turns a busy screen into underrun
// risk. -16 is Android's THREAD_PRIORITY_AUDIO.
void raiseServicePriority() {
    (void)setpriority(PRIO_PROCESS, static_cast<id_t>(gettid()), -16);
}

// Denormals in the reverb/filter tails cost tens of times a normal multiply on
// some cores. Flushing them to zero is a per-thread FPU mode, so the audio
// thread has to set it itself, once, on its first burst.
void enableFlushToZero() {
#if defined(__aarch64__)
    uint64_t fpcr = 0;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= 1ull << 24;  // FZ: flush denormal inputs and results to zero
    asm volatile("msr fpcr, %0" : : "r"(fpcr));
#endif
}

void floatToPcm(float v, uint8_t* p, int bytesPerSample, bool isFloat) {
    if (isFloat) {
        std::memcpy(p, &v, 4);
        return;
    }
    switch (bytesPerSample) {
        case 2: {
            const auto s = static_cast<int16_t>(
                    std::lround(std::clamp(v, -1.0f, 1.0f) * 32767.0f));
            std::memcpy(p, &s, 2);
            break;
        }
        case 3: {
            const auto s = static_cast<int32_t>(
                    std::lround(std::clamp(v, -1.0f, 1.0f) * 8388607.0f));
            p[0] = static_cast<uint8_t>(s);
            p[1] = static_cast<uint8_t>(s >> 8);
            p[2] = static_cast<uint8_t>(s >> 16);
            break;
        }
        default: {
            const auto s = static_cast<int32_t>(
                    std::lround(std::clamp(v, -1.0f, 1.0f) * 2147483647.0));
            std::memcpy(p, &s, 4);
            break;
        }
    }
}

}  // namespace

AudioEngine::TrackRT::TrackRT(int trackId, bool withRing) : id(trackId) {
    if (withRing) {
        ring = std::make_unique<RingBuffer>(kRingBytes);
        scratch.assign(static_cast<size_t>(kMaxBurst) * 2, 0.0f);
    }
}

AudioEngine::TrackRT::~TrackRT() {
    delete chain.load(std::memory_order_relaxed);
}

AudioEngine& AudioEngine::instance() {
    static AudioEngine engine;
    return engine;
}

AudioEngine::AudioEngine() {
    mMixBuf.assign(static_cast<size_t>(kMaxBurst) * 2, 0.0f);
    mRecRing = std::make_unique<RingBuffer>(1u << 21);
    mTracksRT.store(new TrackArray());
    mDiskThread = std::thread([this] { diskLoop(); });
    mRecordThread = std::thread([this] { recordLoop(); });
}

AudioEngine::~AudioEngine() { shutdown(); }

void AudioEngine::shutdown() {
    PAW_ASSERT_CONTROL();
    if (mQuit.exchange(true)) return;
    {
        std::lock_guard<std::mutex> lock(mControlMutex);
        mRecCapture.store(false);
        closeStreamsLocked();
        wakeDiskLocked();  // both loops may be parked on their CV
        wakeRecordLocked();
    }
    if (mDiskThread.joinable()) mDiskThread.join();
    if (mRecordThread.joinable()) mRecordThread.join();
    // Threads are gone and the callback with them; free the graph directly.
    delete mTracksRT.exchange(nullptr);
    {
        std::lock_guard<std::mutex> lock(mControlMutex);
        drainRetiredLocked(true);
    }
    if (mRecSessionOwned) finalizeRecordSession(*mRecSessionOwned);
}

// ---------------------------------------------------------------- streams --

std::string AudioEngine::openStreams(int inputDeviceId, int outputDeviceId, int sampleRate,
                                     bool builtinMic) {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    mTransport.store(kTransportStopped, std::memory_order_relaxed);
    mRecCapture.store(false, std::memory_order_release);
    closeStreamsLocked();

    mSampleRate.store(sampleRate, std::memory_order_relaxed);

    oboe::AudioStreamBuilder outB;
    outB.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setUsage(oboe::Usage::Media)
            ->setFormat(oboe::AudioFormat::Float)
            ->setFormatConversionAllowed(true)
            ->setSampleRate(sampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setChannelCount(2)
            ->setDeviceId(outputDeviceId)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    oboe::Result r = outB.openStream(mOutStream);
    if (r != oboe::Result::OK) {
        outB.setSharingMode(oboe::SharingMode::Shared);
        r = outB.openStream(mOutStream);
    }
    if (r != oboe::Result::OK)
        return std::string("output stream open failed: ") + oboe::convertToText(r);

    if (inputDeviceId != 0) {
        oboe::AudioStreamBuilder inB;
        inB.setDirection(oboe::Direction::Input)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Exclusive)
                ->setInputPreset(builtinMic ? oboe::InputPreset::Camcorder
                                            : oboe::InputPreset::Unprocessed)
                ->setSampleRate(mOutStream->getSampleRate())
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setChannelCount(2)
                ->setDeviceId(inputDeviceId);
        r = inB.openStream(mInStream);
        if (r != oboe::Result::OK) {
            inB.setSharingMode(oboe::SharingMode::Shared);
            r = inB.openStream(mInStream);
        }
        if (r != oboe::Result::OK) {
            mOutStream->close();
            mOutStream.reset();
            return std::string("input stream open failed: ") + oboe::convertToText(r);
        }
        // Interfaces give 2 channels; the phone's own mic may give 1.
        const int inCh = mInStream->getChannelCount();
        mInChannels.store(inCh, std::memory_order_relaxed);
        if (inCh < 1 || inCh > 2) {
            const std::string msg = "input stream has " + std::to_string(inCh) +
                                    " channels, expected 1 or 2";
            mInStream->close();
            mOutStream->close();
            mInStream.reset();
            mOutStream.reset();
            return msg;
        }
        mInFormat = mInStream->getFormat();
        mInDeviceId.store(mInStream->getDeviceId(), std::memory_order_relaxed);
        mInBytesPerFrame = mInStream->getBytesPerFrame();
        mInBytesPerSample = mInStream->getBytesPerSample();
        // The input buffers are fixed capacity, sized for the widest frame
        // Oboe can hand us: the callback must never grow one to meet a wider
        // format.
        if (mInBytesPerFrame <= 0 || mInBytesPerFrame > kMaxInFrameBytes) {
            const std::string msg = "input frame is " + std::to_string(mInBytesPerFrame) +
                                    " bytes, expected at most " +
                                    std::to_string(kMaxInFrameBytes);
            closeStreamsLocked();
            return msg;
        }
        // Written in place, with the streams stopped: the callback reads both.
        mInBuf.fill(0);
        mInFloat.fill(0.0f);
    }

    mInDrainReadsLeft = kMaxDrainReads;
    // A reopen gets a fresh callback thread with a default FPU mode, and both
    // flags are callback-owned with the callback stopped right now. The debug
    // role check forgets the old callback thread for the same reason.
    mFpuConfigured = false;
    PAW_RT_FORGET_THREAD();
    mDeviceLost.store(false, std::memory_order_relaxed);
    buildClickBuffersLocked();  // streams are closed: no callback reads these

    if (mInStream) {
        r = mInStream->requestStart();
        if (r != oboe::Result::OK) {
            closeStreamsLocked();
            return std::string("input start failed: ") + oboe::convertToText(r);
        }
    }
    // Publish the input pointer only when the stream is live.
    mInRaw.store(mInStream.get(), std::memory_order_release);
    r = mOutStream->requestStart();
    if (r != oboe::Result::OK) {
        closeStreamsLocked();
        return std::string("output start failed: ") + oboe::convertToText(r);
    }

    mInputOpen.store(mInStream != nullptr, std::memory_order_release);
    mStreamsOpen.store(true, std::memory_order_release);
    wakeDiskLocked();  // there is a consumer again
    LOGI("streams open: rate=%d in=%s", mOutStream->getSampleRate(),
         mInStream ? "yes" : "no");
    return "";
}

void AudioEngine::closeStreamsLocked() {
    mInRaw.store(nullptr, std::memory_order_release);
    if (mOutStream) mOutStream->stop();  // synchronous: no callback in flight after this
    if (mInStream) {
        mInStream->stop();
        mInStream->close();
        mInStream.reset();
    }
    if (mOutStream) {
        mOutStream->close();
        mOutStream.reset();
    }
    mInputOpen.store(false, std::memory_order_release);
    mStreamsOpen.store(false, std::memory_order_release);
    PAW_RT_FORGET_THREAD();  // that callback thread is gone
}

int AudioEngine::routeLatencyFrames() {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!mOutStream) return -1;
    const int rate = mOutStream->getSampleRate();
    if (rate <= 0) return -1;
    // calculateLatencyMillis needs a stream timestamp, which AAudio only has
    // once the route has produced frames; a just-started stream reports
    // nothing, hence -1 rather than 0.
    auto out = mOutStream->calculateLatencyMillis();
    if (!out) return -1;
    double ms = out.value();
    if (mInStream) {
        auto in = mInStream->calculateLatencyMillis();
        if (in) ms += in.value();
    }
    if (ms <= 0.0) return -1;
    return static_cast<int>(ms * rate / 1000.0 + 0.5);
}

void AudioEngine::closeStreams() {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    mTransport.store(kTransportStopped, std::memory_order_relaxed);
    mRecCapture.store(false, std::memory_order_release);
    closeStreamsLocked();
    // The callback is stopped, so nothing can still be reading the retired
    // graph: free it here rather than leave it to the disk thread's next wake.
    drainRetiredLocked(true);
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    LOGE("stream error: %s", oboe::convertToText(error));
    // Oboe has already stopped and closed the stream, so there is no callback
    // left to advance mTicks or drain the input. Clearing the liveness state is
    // what unblocks everything that waits on it: the record thread's drain
    // condition (mInRaw), the retire list (mStreamsOpen), and the state poll.
    // Without it an unplug mid-record parks the main thread for seconds.
    mInRaw.store(nullptr, std::memory_order_release);
    mInputOpen.store(false, std::memory_order_release);
    mStreamsOpen.store(false, std::memory_order_release);
    // Keep whatever was captured: stop capture so the record thread finalizes
    // the takes, flag the loss, and let Kotlin reopen streams.
    mRecCapture.store(false, std::memory_order_release);
    mRecStopTick.store(mTicks.load(std::memory_order_relaxed), std::memory_order_relaxed);
    mDeviceLost.store(true, std::memory_order_release);
    mTransport.store(kTransportStopped, std::memory_order_relaxed);
}

// -------------------------------------------------------------- metronome --

void AudioEngine::buildClickBuffersLocked() {
    // Short sine bursts with an exponential decay; the accent (bar start) is a
    // fifth up and a touch louder. The buffers are fixed capacity, so this
    // writes in place and publishes the live length.
    const int rate = mSampleRate.load(std::memory_order_relaxed);
    const int len = std::min(rate * 3 / 100, kMaxClickFrames);  // 30 ms
    mClickFrames.store(0, std::memory_order_release);
    const float twoPi = 6.28318530718f;
    for (int i = 0; i < len; ++i) {
        const float t = static_cast<float>(i) / static_cast<float>(rate);
        const float env = std::exp(-t * 180.0f);
        mClickAccent[i] = 0.5f * env * std::sin(twoPi * 1320.0f * t);
        mClickNormal[i] = 0.4f * env * std::sin(twoPi * 880.0f * t);
    }
    mClickFrames.store(len, std::memory_order_release);
}

void AudioEngine::renderClickInto(float* stereoOut, int frames, int64_t gridStart) {
    const int64_t fpb = mFramesPerBeat.load(std::memory_order_relaxed);
    const auto clickLen = static_cast<int64_t>(mClickFrames.load(std::memory_order_acquire));
    if (fpb <= 0 || clickLen == 0) return;
    const int bpb = std::max(1, mBeatsPerBar.load(std::memory_order_relaxed));
    // The grid is walked, not recomputed: one floor division and two modulos
    // for the whole burst instead of a 64-bit division per output sample. Floor
    // division, because count-in positions are negative.
    int64_t beat = gridStart / fpb;
    if (gridStart < 0 && beat * fpb != gridStart) --beat;
    int64_t rel = gridStart - beat * fpb;  // in [0, fpb)
    int barBeat = static_cast<int>(((beat % bpb) + bpb) % bpb);
    for (int i = 0; i < frames; ++i) {
        if (rel < clickLen) {
            const float s = (barBeat == 0 ? mClickAccent : mClickNormal)[rel];
            stereoOut[2 * i] += s;
            stereoOut[2 * i + 1] += s;
        }
        if (++rel >= fpb) {
            rel = 0;
            if (++barBeat >= bpb) barBeat = 0;
        }
    }
}

int64_t AudioEngine::loopTimelineOf(int64_t virtualPos, int64_t genOrigin) const {
    if (!mLoopEnabled.load(std::memory_order_acquire)) return virtualPos;
    const int64_t ls = mLoopStart.load(std::memory_order_relaxed);
    const int64_t le = mLoopEnd.load(std::memory_order_relaxed);
    if (le <= ls || genOrigin >= le || virtualPos < le) return virtualPos;
    return ls + (virtualPos - le) % (le - ls);
}

// --------------------------------------------------------------- callback --

// Runs regardless of transport state, so a take can never be lost to a
// playback stall.
void AudioEngine::captureInput(oboe::AudioStream* in, bool countInDone) {
    mInFramesThisBurst = 0;
    if (!in) {
        mInPeak[0].store(0.0f, std::memory_order_relaxed);
        mInPeak[1].store(0.0f, std::memory_order_relaxed);
        return;
    }

    if (mInDrainReadsLeft > 0) {
        // Discard the backlog that accumulated before the stream started, so
        // capture lines up with playback. A few reads per burst: the whole
        // allowance inside one callback blows the deadline outright.
        for (int i = 0; i < kDrainReadsPerBurst && mInDrainReadsLeft > 0; ++i) {
            --mInDrainReadsLeft;
            auto res = in->read(mInBuf.data(), kInBufFrames, 0);
            if (!res || res.value() < kInBufFrames) {
                mInDrainReadsLeft = 0;  // caught up
                break;
            }
        }
        return;
    }

    auto res = in->read(mInBuf.data(), kInBufFrames, 0);
    if (!res) {
        if (res.error() == oboe::Result::ErrorDisconnected)
            mDeviceLost.store(true, std::memory_order_relaxed);
        return;
    }
    const int32_t got = res.value();
    if (got <= 0) return;
    mInFramesThisBurst = got;

    float pl = 0.0f, pr = 0.0f;
    const float inGain = mInputGain.load(std::memory_order_relaxed);
    const bool fl = mInFormat == oboe::AudioFormat::Float;
    const int inCh = mInChannels.load(std::memory_order_relaxed);
    for (int32_t f = 0; f < got; ++f) {  // mono duplicated to both channels
        const uint8_t* frame = mInBuf.data() + f * mInBytesPerFrame;
        const float l = inGain * pcmSampleToFloat(frame, mInBytesPerSample, fl);
        const float r = inCh > 1 ? inGain * pcmSampleToFloat(frame + mInBytesPerSample,
                                                             mInBytesPerSample, fl)
                                 : l;
        mInFloat[2 * f] = l;
        mInFloat[2 * f + 1] = r;
        pl = std::max(pl, std::fabs(l));
        pr = std::max(pr, std::fabs(r));
    }
    mInPeak[0].store(pl, std::memory_order_relaxed);
    mInPeak[1].store(pr, std::memory_order_relaxed);

    if (!mRecCapture.load(std::memory_order_acquire) || !countInDone) return;
    if (mRecStartFrame.load(std::memory_order_relaxed) < 0) {
        mRecStartFrame.store(mPlayhead.load(std::memory_order_relaxed),
                             std::memory_order_release);
    }
    const auto frames = static_cast<size_t>(got);
    if (mRecRing->write(mInBuf.data(), frames * mInBytesPerFrame)) {
        mRecFramesCaptured.fetch_add(static_cast<int64_t>(frames), std::memory_order_relaxed);
    } else {
        mRecDropped.fetch_add(static_cast<int64_t>(frames), std::memory_order_relaxed);
    }
}

uint64_t AudioEngine::adoptSeek() {
    const uint64_t gen = mSeekGen.load(std::memory_order_acquire);
    if (gen != mCbGen) {
        mCbGen = gen;
        mPlayhead.store(mPendingSeek.load(std::memory_order_relaxed),
                        std::memory_order_release);
        // Tells the disk thread this callback will no longer pop the rings,
        // making the flush handoff safe.
        mCbGenSeen.store(gen, std::memory_order_release);
    }
    return gen;
}

// The smoother is per-track state, so this owns the read-modify-write of it.
float AudioEngine::applyFaderPanMeter(TrackRT& tr, const float* in, float* out, int frames,
                                      float gain, float pan, bool accumulate) {
    const PanGains target = panGains(gain, pan);
    float sl = tr.smoothL, sr = tr.smoothR;
    float peak = 0.0f;
    for (int i = 0; i < frames; ++i) {
        sl += kSmooth * (target.l - sl);
        sr += kSmooth * (target.r - sr);
        const float l = in[2 * i] * sl;
        const float r = in[2 * i + 1] * sr;
        if (accumulate) {
            out[2 * i] += l;
            out[2 * i + 1] += r;
        } else {
            out[2 * i] = l;
            out[2 * i + 1] = r;
        }
        peak = std::max(peak, std::max(std::fabs(l), std::fabs(r)));
    }
    tr.smoothL = sl;
    tr.smoothR = sr;
    return peak;
}

// Runs even when stopped, because armed tracks meter their input and (with
// monitor on) pass it through to the output.
void AudioEngine::mixTracks(int frames, bool rolling, bool inputLive) {
    std::memset(mMixBuf.data(), 0, sizeof(float) * 2 * frames);
    TrackArray* arr = mTracksRT.load(std::memory_order_acquire);
    const bool anySolo = mSoloCount.load(std::memory_order_relaxed) > 0;

    int meterCount = 0;
    for (TrackRT* tr : arr->tracks) {
        auto* dst = reinterpret_cast<uint8_t*>(tr->scratch.data());
        const size_t wantBytes = static_cast<size_t>(frames) * 8;
        if (rolling) {
            const size_t got = tr->ring->read(dst, wantBytes);
            if (got < wantBytes) std::memset(dst + got, 0, wantBytes - got);
        } else {
            std::memset(dst, 0, wantBytes);  // rings stay primed for play
        }

        const bool armed = tr->armed.load(std::memory_order_relaxed);
        const int mode = tr->inputMode.load(std::memory_order_relaxed);
        float inPeak = 0.0f;
        if (armed && mode != kInputNone && inputLive) {
            inPeak = mode == kInputCh2   ? mInPeak[1].load(std::memory_order_relaxed)
                     : mode == kInputCh1 ? mInPeak[0].load(std::memory_order_relaxed)
                                         : std::max(mInPeak[0].load(std::memory_order_relaxed),
                                                    mInPeak[1].load(std::memory_order_relaxed));
            if (tr->monitor.load(std::memory_order_relaxed)) {
                const int n = std::min(frames, static_cast<int>(mInFramesThisBurst));
                for (int i = 0; i < n; ++i) {
                    // Ch1 and stereo both take the left sample; ch2 takes the
                    // right. Mirror image for the right output.
                    const float l = mode == kInputCh2 ? mInFloat[2 * i + 1] : mInFloat[2 * i];
                    const float r = mode == kInputCh1 ? mInFloat[2 * i] : mInFloat[2 * i + 1];
                    tr->scratch[2 * i] += l;
                    tr->scratch[2 * i + 1] += r;
                }
            }
        }

        EffectChain* chain = tr->chain.load(std::memory_order_acquire);
        if (chain) chain->process(tr->scratch.data(), frames);

        const bool silent = tr->mute.load(std::memory_order_relaxed) ||
                            (anySolo && !tr->solo.load(std::memory_order_relaxed));
        const float peak = applyFaderPanMeter(
                *tr, tr->scratch.data(), mMixBuf.data(), frames,
                silent ? 0.0f : tr->gain.load(std::memory_order_relaxed),
                tr->pan.load(std::memory_order_relaxed), true);

        // Armed tracks always show what the input is doing, even when the
        // fader is down or monitoring is off.
        if (meterCount < kMaxMeters) {
            mMeterSlots[meterCount].id.store(tr->id, std::memory_order_relaxed);
            mMeterSlots[meterCount].peak.store(std::max(peak, inPeak),
                                               std::memory_order_relaxed);
            ++meterCount;
        }
    }
    mMeterCount.store(meterCount, std::memory_order_release);
}

void AudioEngine::advancePlayhead(int frames) {
    int64_t ph = mPlayhead.load(std::memory_order_relaxed) + frames;
    // Loop wrap: only when this burst crossed the loop end from below. The
    // rings already hold the wrapped audio (the disk thread renders across the
    // boundary), so this is bookkeeping, not a seek.
    if (mLoopEnabled.load(std::memory_order_acquire)) {
        const int64_t ls = mLoopStart.load(std::memory_order_relaxed);
        const int64_t le = mLoopEnd.load(std::memory_order_relaxed);
        if (le > ls && ph >= le && ph - frames < le) ph = ls + (ph - le);
    }
    mPlayhead.store(ph, std::memory_order_release);
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream*, void* audioData,
                                                   int32_t numFrames) {
    // Claims this thread as the real-time one on the first burst, and makes any
    // allocation or free reached from here an abort, the helpers below included,
    // since the scope lives for the whole burst. See RtCheck.h.
    PAW_ASSERT_RT();
    PAW_RT_SCOPE();
    if (!mFpuConfigured) {  // first burst on this callback thread
        enableFlushToZero();
        mFpuConfigured = true;
    }
    float* out = static_cast<float*>(audioData);
    std::memset(out, 0, sizeof(float) * 2 * numFrames);
    mTicks.fetch_add(1, std::memory_order_release);

    // Count-in: while frames remain, capture and the playhead hold and only
    // the click sounds. A remainder smaller than this burst collapses to zero
    // (the count-in shortens by <1 burst rather than splitting the burst).
    int64_t countdown = mCountdown.load(std::memory_order_acquire);
    if (countdown > 0 && countdown < numFrames) {
        mCountdown.store(0, std::memory_order_relaxed);
        countdown = 0;
    }

    oboe::AudioStream* in = mInRaw.load(std::memory_order_acquire);
    captureInput(in, countdown == 0);

    const uint64_t gen = adoptSeek();
    const int transport = mTransport.load(std::memory_order_relaxed);
    const bool primed = mPrimedGen.load(std::memory_order_acquire) == gen;
    const bool rolling = transport != kTransportStopped && primed && countdown == 0;

    const int frames = std::min(numFrames, static_cast<int32_t>(kMaxBurst));
    mixTracks(frames, rolling, in != nullptr);

    // Master bus: its own chain and control surface.
    EffectChain* masterChain = mMaster.chain.load(std::memory_order_acquire);
    if (masterChain) masterChain->process(mMixBuf.data(), frames);
    const float masterGain = mMaster.mute.load(std::memory_order_relaxed)
                                     ? 0.0f
                                     : mMaster.gain.load(std::memory_order_relaxed);
    mMasterPeak.store(applyFaderPanMeter(mMaster, mMixBuf.data(), out, frames, masterGain,
                                         mMaster.pan.load(std::memory_order_relaxed), false),
                      std::memory_order_relaxed);

    // Metronome, post-master so faders can't swallow it. During count-in the
    // click always sounds; while rolling it follows the toggle. Grid positions
    // are timeline frames, negative in count-in.
    if (transport != kTransportStopped) {
        const int64_t ph = mPlayhead.load(std::memory_order_relaxed);
        if (countdown > 0) {
            renderClickInto(out, frames, ph - countdown);
            mCountdown.store(countdown - frames, std::memory_order_release);
        } else if (rolling && mClickEnabled.load(std::memory_order_relaxed)) {
            renderClickInto(out, frames, ph);
        }
    }

    if (rolling) advancePlayhead(frames);
    return oboe::DataCallbackResult::Continue;
}

// -------------------------------------------------------------- disk loop --

void AudioEngine::diskLoop() {
    raiseServicePriority();
    TimelineRenderer renderer;
    std::vector<float> chunk(static_cast<size_t>(kChunkFrames) * 2);
    std::vector<ClipRef> clips;
    std::vector<std::string> referenced;  // scratch for the renderer's retain
    // Held across wakes and rebuilt only when the registry changes, so an idle
    // loop allocates nothing.
    std::vector<std::shared_ptr<TrackRT>> tracks;
    uint64_t seenRegistry = 0;
    uint64_t diskGen = 0;
    int64_t diskSongGen = mSongGen.load(std::memory_order_relaxed);
    bool ringsFull = true;

    constexpr size_t chunkBytes = sizeof(float) * 2 * kChunkFrames;

    while (!mQuit.load(std::memory_order_acquire)) {
        {
            // Every path that creates work here writes under mControlMutex and
            // sets mDiskWake, so no wake can slip between the check and the
            // wait. (onErrorAfterClose writes outside the lock, but it only ever
            // removes work.) The timeout is a fallback, not the mechanism.
            auto haveWork = [&] {
                return mQuit.load(std::memory_order_relaxed) || mDiskWake || !ringsFull ||
                       !mRetired.empty() ||
                       mTransport.load(std::memory_order_relaxed) != kTransportStopped ||
                       mSeekGen.load(std::memory_order_relaxed) != diskGen ||
                       mPrimedGen.load(std::memory_order_relaxed) != diskGen;
            };
            std::unique_lock<std::mutex> lock(mControlMutex);
            mDiskCv.wait_for(lock, std::chrono::milliseconds(kIdleWaitMs), haveWork);
            mDiskWake = false;
            drainRetiredLocked(false);
            const uint64_t registry = mRegistryVersion.load(std::memory_order_relaxed);
            if (registry != seenRegistry) {
                seenRegistry = registry;
                tracks.clear();
                tracks.reserve(mRegistry.size());
                for (auto& [id, tr] : mRegistry) tracks.push_back(tr);
            }
        }
        if (mQuit.load(std::memory_order_acquire)) break;

        // A new song wipes the renderer whole: none of the closed song's takes
        // can be referenced by the one that just opened, and open file handles
        // would keep deleted takes audible.
        const int64_t songGen = mSongGen.load(std::memory_order_relaxed);
        if (songGen != diskSongGen) {
            diskSongGen = songGen;
            renderer.clear();
        }

        const uint64_t gen = mSeekGen.load(std::memory_order_acquire);
        if (gen != diskGen) {
            // Consumer handoff: don't flush until the callback has adopted
            // this generation (it might still be popping the previous burst),
            // or is provably not running (ticks frozen / streams closed).
            const uint64_t t0 = mTicks.load(std::memory_order_acquire);
            for (int i = 0; i < 50; ++i) {
                if (mCbGenSeen.load(std::memory_order_acquire) >= gen) break;
                if (i >= 10 && mTicks.load(std::memory_order_acquire) == t0) break;
                sleepMs(1);
            }
            const int64_t pos = mPendingSeek.load(std::memory_order_relaxed);
            for (auto& tr : tracks) {
                tr->ring->discardAll();
                tr->renderPos = pos;
            }
            mDiskGenOrigin = pos;
            diskGen = gen;

            // A bump is the one moment the clip lists can have changed, so it
            // is where the renderer's caches get scoped back to what the song
            // still references: take handles for deleted clips are released (a
            // long session would otherwise trend toward EMFILE), and a take
            // that failed to open once is retried rather than staying silent
            // for the life of the process.
            referenced.clear();
            {
                std::lock_guard<std::mutex> lock(mControlMutex);
                for (auto& tr : tracks)
                    for (const ClipRef& c : tr->clips) referenced.push_back(c.path);
            }
            renderer.retain(referenced);
        }

        // Two-stage fill. Until this generation is primed every track only has
        // to reach kMinPrimeBytes, so playback resumes after ~170 ms instead of
        // a full 682 ms ring per track; the passes after that top the rings up.
        const bool primed = mPrimedGen.load(std::memory_order_relaxed) == diskGen;
        bool aborted = false;
        bool allMin = true;
        ringsFull = true;
        for (auto& tr : tracks) {
            {
                std::lock_guard<std::mutex> lock(mControlMutex);
                clips = tr->clips;
            }
            const size_t target = primed ? tr->ring->capacity() : kMinPrimeBytes;
            while (tr->ring->freeSpace() >= chunkBytes &&
                   tr->ring->availableToRead() < target) {
                // A seek or edit arriving mid-fill makes the rest of this pass
                // useless; give it up between chunks.
                if (mSeekGen.load(std::memory_order_acquire) != diskGen) {
                    aborted = true;
                    break;
                }
                // renderPos is a virtual (monotonic) position; with a loop
                // active it maps back into the region, and a chunk straddling
                // the wrap renders in segments so the ring never sees a seam.
                int filled = 0;
                while (filled < kChunkFrames) {
                    const int64_t tl = loopTimelineOf(tr->renderPos + filled, mDiskGenOrigin);
                    int seg = kChunkFrames - filled;
                    // Acquire on the flag first, then the bounds: setLoop
                    // publishes in that order (disable, move, enable), and
                    // reading the end first can pair it with a new start.
                    const bool looping = mLoopEnabled.load(std::memory_order_acquire);
                    const int64_t le = mLoopEnd.load(std::memory_order_relaxed);
                    if (looping && tl < le && mDiskGenOrigin < le) {
                        seg = static_cast<int>(
                                std::min<int64_t>(seg, le - tl));
                    }
                    renderer.render(clips, tl, seg, chunk.data() + filled * 2);
                    filled += seg;
                }
                tr->ring->write(reinterpret_cast<const uint8_t*>(chunk.data()), chunkBytes);
                tr->renderPos += kChunkFrames;
            }
            if (aborted) break;
            if (tr->ring->availableToRead() < kMinPrimeBytes) allMin = false;
            if (tr->ring->freeSpace() >= chunkBytes) ringsFull = false;
        }
        // Takes the song references but the renderer cannot open: the UI's only
        // signal that a clip is silent because its file is gone.
        mMissingTakes.store(renderer.missingTakes(), std::memory_order_relaxed);
        if (aborted) {
            ringsFull = false;
            continue;  // the new generation flushes on the next pass
        }

        if (allMin && mSeekGen.load(std::memory_order_acquire) == diskGen &&
            mPrimedGen.load(std::memory_order_relaxed) != diskGen) {
            mPrimedGen.store(diskGen, std::memory_order_release);
        }
        // Keep the 2 ms cadence while there is filling left or the transport
        // is draining the rings; a truly idle loop parks on the CV instead.
        if (!ringsFull || mTransport.load(std::memory_order_relaxed) != kTransportStopped)
            sleepMs(2);
    }
}

// ---------------------------------------------------------------- control --

void AudioEngine::retireLocked(TrackArray* array, EffectChain* chain,
                               std::shared_ptr<TrackRT> track) {
    Retired r;
    r.array = array;
    r.chain = chain;
    r.track = std::move(track);
    // Sampled after the caller published the replacement: any burst that can
    // still hold the old pointer started at or before this tick, so it has
    // finished by the time two further bursts have started.
    r.tick = mTicks.load(std::memory_order_acquire);
    mRetired.push_back(std::move(r));
    wakeDiskLocked();
}

void AudioEngine::drainRetiredLocked(bool force) {
    if (mRetired.empty()) return;
    // Streams closed means the output stream has been stopped (or closed
    // under us by Oboe on device loss): no callback is in flight, so nothing
    // has to wait out its ticks.
    const bool dead = force || !mStreamsOpen.load(std::memory_order_acquire);
    const uint64_t now = mTicks.load(std::memory_order_acquire);
    size_t keep = 0;
    for (size_t i = 0; i < mRetired.size(); ++i) {
        Retired& r = mRetired[i];
        if (!dead && now < r.tick + 2) {
            if (keep != i) mRetired[keep] = std::move(r);
            ++keep;
            continue;
        }
        delete r.array;
        delete r.chain;
        r.track.reset();  // last reference: ~TrackRT deletes its chain and ring
    }
    mRetired.resize(keep);
}

void AudioEngine::wakeDiskLocked() {
    mDiskWake = true;
    mDiskCv.notify_one();
}

void AudioEngine::wakeRecordLocked() {
    mRecWake = true;
    mRecCv.notify_one();
}

void AudioEngine::rebuildTrackArrayLocked() {
    auto* next = new TrackArray();
    next->tracks.reserve(mRegistry.size());
    for (auto& [id, tr] : mRegistry) next->tracks.push_back(tr.get());
    TrackArray* old = mTracksRT.exchange(next, std::memory_order_acq_rel);
    // Drop the published meters: with no callback running (streams closed)
    // they would otherwise name tracks that no longer exist.
    mMeterCount.store(0, std::memory_order_release);
    // The disk thread re-snapshots the registry on this bump, and frees the
    // array the callback may still be walking once its ticks have passed.
    mRegistryVersion.fetch_add(1, std::memory_order_relaxed);
    retireLocked(old, nullptr, nullptr);
}

void AudioEngine::bumpGenLocked(int64_t position) {
    mPendingSeek.store(position, std::memory_order_relaxed);
    const uint64_t gen = mSeekGen.fetch_add(1, std::memory_order_release) + 1;
    if (!mStreamsOpen.load(std::memory_order_relaxed)) {
        // No callback to adopt the position; do it on its behalf.
        mPlayhead.store(position, std::memory_order_release);
        mCbGenSeen.store(gen, std::memory_order_release);
    }
    wakeDiskLocked();
}

// Creates the track if the current song has not pushed it yet. Every caller has
// passed the generation check first, so the only code that reaches here is the
// controller that owns the open song: an unknown id is a track it is about to
// sync, not a straggler from another song.
AudioEngine::TrackRT* AudioEngine::trackForParamsLocked(int trackId) {
    if (trackId == -1) return &mMaster;
    auto it = mRegistry.find(trackId);
    if (it != mRegistry.end()) return it->second.get();
    auto tr = std::make_shared<TrackRT>(trackId, true);
    TrackRT* raw = tr.get();
    mRegistry.emplace(trackId, std::move(tr));
    rebuildTrackArrayLocked();
    return raw;
}

bool AudioEngine::acceptGenLocked(int64_t gen, const char* what) {
    const int64_t open = mSongGen.load(std::memory_order_relaxed);
    if (open != 0 && gen == open) return true;
    mDroppedCalls.fetch_add(1, std::memory_order_relaxed);
    // One line per generation: a straggler usually arrives in bursts (a whole
    // catalog rescan re-pushing every chain), and the count in the state
    // snapshot carries the rest.
    if (!mDropLogged) {
        mDropLogged = true;
        LOGI("dropped %s from song gen %" PRId64 "; open gen is %" PRId64, what, gen, open);
    }
    return false;
}

// Everything song-scoped, reset in one place: a new song-scoped member gets its
// reset here, not at every call site that opens a song.
void AudioEngine::resetSongScopeLocked() {
    // The registry survives closeStreams (rotation relies on that), so without
    // this, tracks whose ids exist only in the previous song keep their clips,
    // and their open WavReaders keep even deleted takes audible.
    std::vector<std::shared_ptr<TrackRT>> doomed;
    doomed.reserve(mRegistry.size());
    for (auto& [id, tr] : mRegistry) doomed.push_back(tr);
    mRegistry.clear();
    mSoloCount.store(0, std::memory_order_relaxed);

    // The master is a track like any other and needs the same wipe.
    mMaster.clips.clear();
    mMaster.gain.store(1.0f, std::memory_order_relaxed);
    mMaster.pan.store(0.0f, std::memory_order_relaxed);
    mMaster.mute.store(false, std::memory_order_relaxed);
    mMaster.solo.store(false, std::memory_order_relaxed);
    mMaster.armed.store(false, std::memory_order_relaxed);
    mMaster.monitor.store(false, std::memory_order_relaxed);
    mMaster.inputMode.store(kInputCh1, std::memory_order_relaxed);
    EffectChain* masterChain = mMaster.chain.exchange(nullptr, std::memory_order_acq_rel);

    rebuildTrackArrayLocked();  // the new array is empty
    if (masterChain) retireLocked(nullptr, masterChain, nullptr);
    for (auto& tr : doomed) retireLocked(nullptr, nullptr, std::move(tr));

    // Metronome, loop, transport, playhead.
    mClickEnabled.store(false, std::memory_order_relaxed);
    mFramesPerBeat.store(std::max<int64_t>(1, mSampleRate.load(std::memory_order_relaxed) / 2),
                         std::memory_order_relaxed);  // 120 bpm
    mBeatsPerBar.store(4, std::memory_order_relaxed);
    mCountdown.store(0, std::memory_order_release);
    mLoopEnabled.store(false, std::memory_order_release);
    mLoopStart.store(0, std::memory_order_relaxed);
    mLoopEnd.store(0, std::memory_order_relaxed);
    mTransport.store(kTransportStopped, std::memory_order_release);
    // The disk thread wipes its renderer on the song-generation change; clear
    // the published count here so the poll can't show the old song's takes.
    mMissingTakes.store(0, std::memory_order_relaxed);
    // A take finalized under the previous song must not be collected by this
    // one; the file itself stays on disk and in the take browser.
    mRecResult = RecordResultData{};
    mRecResultReady.store(false, std::memory_order_release);
    mRecError.clear();
    bumpGenLocked(0);  // every song opens at the top
}

void AudioEngine::openSong(int64_t gen) {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    // A capture still running is punched out rather than left live across the
    // wipe: the take finalizes on the record thread and stays on disk.
    if (mRecSessionOwned) {
        LOGE("openSong while a take is running; punching out first");
        finishRecordAsyncLocked(false);
    }
    resetSongScopeLocked();
    mSongGen.store(gen, std::memory_order_release);
    mDropLogged = false;
    LOGI("song gen %" PRId64 " open", gen);
}

void AudioEngine::syncTrackClips(int64_t gen, int trackId, std::vector<ClipRef> clips) {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!acceptGenLocked(gen, "syncClips")) return;
    TrackRT* tr = trackForParamsLocked(trackId);
    // An identical list must not flush: callers resync clips as a side effect
    // of param edits, and each flush is an audible re-prime stall mid-play.
    const bool changed =
            clips.size() != tr->clips.size() ||
            !std::equal(clips.begin(), clips.end(), tr->clips.begin(),
                        [](const ClipRef& a, const ClipRef& b) {
                            return a.srcStart == b.srcStart && a.length == b.length &&
                                   a.timelineStart == b.timelineStart && a.fadeIn == b.fadeIn &&
                                   a.fadeOut == b.fadeOut && a.path == b.path;
                        });
    if (!changed) return;
    tr->clips = std::move(clips);
    // Flush so already-rendered audio can't play stale. Skipped while
    // recording: capture must not be disturbed, and the controller defers clip
    // syncs until the take is finished.
    if (mTransport.load(std::memory_order_relaxed) != kTransportRecording)
        bumpGenLocked(mPlayhead.load(std::memory_order_relaxed));
    else
        wakeDiskLocked();  // new material to render even without a flush
}

void AudioEngine::removeTrack(int64_t gen, int trackId) {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!acceptGenLocked(gen, "removeTrack")) return;
    auto it = mRegistry.find(trackId);
    if (it == mRegistry.end()) return;
    std::shared_ptr<TrackRT> doomed = it->second;
    if (doomed->solo.load(std::memory_order_relaxed))
        mSoloCount.fetch_sub(1, std::memory_order_relaxed);
    mRegistry.erase(it);
    rebuildTrackArrayLocked();  // the new array no longer names it
    // The callback may still be walking the old array, so the track is retired
    // too: its last reference (and the chain its destructor deletes) drops on
    // the disk thread, once the callback has provably moved on.
    retireLocked(nullptr, nullptr, std::move(doomed));
}

void AudioEngine::setTrackParams(int64_t gen, int trackId, float gain, float pan, bool mute,
                                 bool solo, bool armed, int inputMode, bool monitor) {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!acceptGenLocked(gen, "setTrackParams")) return;
    TrackRT* tr = trackForParamsLocked(trackId);
    const bool wasSolo = tr->solo.load(std::memory_order_relaxed);
    tr->gain.store(gain, std::memory_order_relaxed);
    tr->pan.store(pan, std::memory_order_relaxed);
    tr->mute.store(mute, std::memory_order_relaxed);
    tr->solo.store(solo, std::memory_order_relaxed);
    tr->armed.store(armed, std::memory_order_relaxed);
    tr->inputMode.store(inputMode, std::memory_order_relaxed);
    tr->monitor.store(monitor, std::memory_order_relaxed);
    if (trackId != -1 && wasSolo != solo)
        mSoloCount.fetch_add(solo ? 1 : -1, std::memory_order_relaxed);
}

void AudioEngine::setTrackChain(int64_t gen, int trackId, EffectChain* chain) {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!acceptGenLocked(gen, "setChain")) {
        // Ownership passed to us at the call: a chain built for a song that is
        // not open any more is deleted here rather than installed on whatever
        // track happens to carry that id now.
        delete chain;
        return;
    }
    TrackRT* tr = trackForParamsLocked(trackId);
    EffectChain* old = tr->chain.exchange(chain, std::memory_order_acq_rel);
    // Returns immediately: the old chain waits out the callback on the retire
    // list instead of stalling the UI poll and the ring refill under the lock.
    if (old) retireLocked(nullptr, old, nullptr);
}

// -------------------------------------------------------------- transport --

void AudioEngine::playLocked() {
    if (mTransport.load(std::memory_order_relaxed) == kTransportStopped)
        mTransport.store(kTransportPlaying, std::memory_order_release);
    wakeDiskLocked();  // the rings are about to start draining
}

void AudioEngine::stopAllLocked() {
    // A live take ends through the one finalize path there is: capture flips
    // off, the record thread drains and parks the result, the poll collects
    // it. finishRecordAsyncLocked stops the transport itself.
    if (finishRecordAsyncLocked(false)) return;
    // The callback only counts the count-in down while rolling; a leftover
    // value would freeze on screen until the next record.
    mCountdown.store(0, std::memory_order_release);
    mTransport.store(kTransportStopped, std::memory_order_release);
}

int AudioEngine::transportPlayPause() {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mTransport.load(std::memory_order_relaxed) == kTransportStopped) playLocked();
    else stopAllLocked();
    return mTransport.load(std::memory_order_relaxed);
}

void AudioEngine::transportStopAll() {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    stopAllLocked();
}

void AudioEngine::seek(int64_t frame) {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    // Only this side can tell whether a take is being captured or finalized
    // right now.
    if (mRecPending.load(std::memory_order_acquire) ||
        mRecCapture.load(std::memory_order_relaxed) ||
        mTransport.load(std::memory_order_relaxed) == kTransportRecording)
        return;
    bumpGenLocked(std::max<int64_t>(0, frame));
}

void AudioEngine::setMetronome(int64_t gen, bool enabled, float bpm, int beatsPerBar) {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!acceptGenLocked(gen, "setMetronome")) return;
    mClickEnabled.store(enabled, std::memory_order_relaxed);
    if (bpm > 0.0f) {
        const int rate = mSampleRate.load(std::memory_order_relaxed);
        mFramesPerBeat.store(std::max<int64_t>(1, std::llround(60.0 * rate / bpm)),
                             std::memory_order_relaxed);
    }
    mBeatsPerBar.store(std::max(1, beatsPerBar), std::memory_order_relaxed);
}

void AudioEngine::setLoop(int64_t gen, bool enabled, int64_t startFrame, int64_t endFrame) {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!acceptGenLocked(gen, "setLoop")) return;
    const bool wasEnabled = mLoopEnabled.load(std::memory_order_relaxed);
    const bool changed = wasEnabled != enabled ||
                         mLoopStart.load(std::memory_order_relaxed) != startFrame ||
                         mLoopEnd.load(std::memory_order_relaxed) != endFrame;
    if (!changed) return;
    // Order matters for lock-free readers: disable, move the bounds, then
    // enable, so no reader ever pairs a new start with an old end.
    mLoopEnabled.store(false, std::memory_order_release);
    mLoopStart.store(std::max<int64_t>(0, startFrame), std::memory_order_relaxed);
    mLoopEnd.store(endFrame, std::memory_order_relaxed);
    mLoopEnabled.store(enabled && endFrame > startFrame, std::memory_order_release);
    // Rings may hold audio rendered under the old mapping; recording defers
    // (capture is linear, the controller re-syncs the loop afterwards).
    if (mTransport.load(std::memory_order_relaxed) != kTransportRecording)
        bumpGenLocked(mPlayhead.load(std::memory_order_relaxed));
}

// -------------------------------------------------------------- recording --

int AudioEngine::toggleRecordSession(int64_t gen, const std::string& dir,
                                     const std::string& baseName,
                                     int64_t countInFramesIfStopped) {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!acceptGenLocked(gen, "toggleRecordSession")) return kRecToggleDropped;
    // Punch-in and punch-out are one decision under one lock: decided from a
    // polled copy of "is it recording", a fast double tap can start a second
    // session or collect a take twice.
    if (mRecSessionOwned || mRecPending.load(std::memory_order_acquire)) {
        finishRecordAsyncLocked(true);  // punch-out; the transport keeps rolling
        return kRecToggleStopped;
    }
    // Count-in only when starting from stop: a punch-in mid-play must not
    // freeze the transport under the musician.
    const int64_t countIn = mTransport.load(std::memory_order_relaxed) == kTransportStopped
                                    ? std::max<int64_t>(0, countInFramesIfStopped)
                                    : 0;
    mRecError = startRecordLocked(dir, baseName, countIn);
    return mRecError.empty() ? kRecToggleStarted : kRecToggleError;
}

std::string AudioEngine::lastRecordError() {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    return mRecError;
}

// Opens one take per armed track and starts capture. Take files are created
// here, under the same lock that decided to start, so the punch-out branch
// cannot leave an unopened file behind.
std::string AudioEngine::startRecordLocked(const std::string& dir, const std::string& baseName,
                                           int64_t countInFrames) {
    if (!mStreamsOpen.load(std::memory_order_relaxed) || !mInStream) return "no input device";
    if (mRecSessionOwned) return "already recording";

    const int inCh = mInChannels.load(std::memory_order_relaxed);
    const int rate = mSampleRate.load(std::memory_order_relaxed);
    auto session = std::make_unique<RecordSession>();
    for (auto& [id, tr] : mRegistry) {
        if (!tr->armed.load(std::memory_order_relaxed)) continue;
        const int mode = tr->inputMode.load(std::memory_order_relaxed);
        if (mode == kInputNone) continue;
        auto target = std::make_unique<RecordTarget>();
        target->trackId = id;
        target->mode = mode;
        target->path = dir + "/" + baseName + "_t" + std::to_string(id) + ".wav";
        // A stereo take needs a stereo source; the phone mic gives one channel.
        const int channels = (mode == kInputStereo && inCh >= 2) ? 2 : 1;
        if (!target->wav.open(target->path, rate, wavFormatFor(mInFormat), channels)) {
            return "cannot create " + target->path;
        }
        session->targets.push_back(std::move(target));
    }
    if (session->targets.empty()) return "no armed track";

    // The record thread is idle (no session), so the ring is safely ours.
    mRecRing->discardAll();
    mRecResult = RecordResultData{};  // drop the previous take list
    mRecResultReady.store(false, std::memory_order_release);
    mRecStartFrame.store(-1, std::memory_order_relaxed);
    mRecFramesCaptured.store(0, std::memory_order_relaxed);
    mRecDropped.store(0, std::memory_order_relaxed);
    // Capture is linear; the controller re-syncs the loop after collecting.
    mLoopEnabled.store(false, std::memory_order_release);
    mCountdown.store(std::max<int64_t>(0, countInFrames), std::memory_order_release);
    mRecSessionGen = mSongGen.load(std::memory_order_relaxed);
    mRecSessionOwned = std::move(session);
    mRecSession.store(mRecSessionOwned.get(), std::memory_order_release);
    mRecPending.store(true, std::memory_order_release);
    mRecCapture.store(true, std::memory_order_release);
    mTransport.store(kTransportRecording, std::memory_order_release);
    wakeRecordLocked();
    wakeDiskLocked();
    return "";
}

bool AudioEngine::finishRecordAsync(bool keepRolling) {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    return finishRecordAsyncLocked(keepRolling);
}

bool AudioEngine::finishRecordAsyncLocked(bool keepRolling) {
    // No live session: say so rather than re-serving the parked result, which
    // a fast double tap would place on the timeline twice.
    if (!mRecSessionOwned) return false;

    if (mRecCapture.load(std::memory_order_relaxed)) {
        mRecCapture.store(false, std::memory_order_release);
        mRecStopTick.store(mTicks.load(std::memory_order_relaxed), std::memory_order_relaxed);
    }
    mCountdown.store(0, std::memory_order_release);  // a stop during count-in
    mTransport.store(keepRolling ? kTransportPlaying : kTransportStopped,
                     std::memory_order_release);
    wakeDiskLocked();
    return true;
}

bool AudioEngine::finishRecordSync(bool keepRolling) {
    PAW_ASSERT_CONTROL();
    const bool hadSession = finishRecordAsync(keepRolling);
    if (!hadSession && !mRecResultReady.load(std::memory_order_acquire)) return false;

    std::unique_lock<std::mutex> lock(mControlMutex);
    // The record thread's drain condition is always satisfiable here: either
    // the callback advances mTicks past the stop tick, or the streams are gone
    // and mInRaw is null (device loss included). The bound only exists so
    // teardown can't hang.
    for (int i = 0; i < kFinishDrainMs && mRecSessionOwned; ++i) {
        lock.unlock();
        sleepMs(1);
        lock.lock();
    }
    if (mRecSessionOwned) {
        // Should not happen. The session stays with the record thread rather
        // than being finalized from under it; the take is already valid on
        // disk (the writers flush their headers as they go).
        LOGE("record drain timed out; take stays with the record thread");
        return false;
    }
    return true;
}

std::vector<int64_t> AudioEngine::recordResultLongs() {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    std::vector<int64_t> out;
    // Consuming the flag is what stops a take landing on the timeline twice:
    // a poll tick racing the teardown collect gets the empty header.
    if (!mRecResultReady.exchange(false, std::memory_order_acq_rel))
        return {-1, 0, 1, 0};
    out.reserve(4 + mRecResult.takes.size() * 2);
    out.push_back(mRecResult.startFrame);
    out.push_back(mRecResult.dropped);
    out.push_back(mRecResult.ioOk ? 1 : 0);
    out.push_back(static_cast<int64_t>(mRecResult.takes.size()));
    for (const RecordedTakeInfo& t : mRecResult.takes) {
        out.push_back(t.trackId);
        out.push_back(t.frames);
    }
    return out;
}

std::vector<std::string> AudioEngine::recordResultPaths() {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    std::vector<std::string> out;
    out.reserve(mRecResult.takes.size());
    for (const RecordedTakeInfo& t : mRecResult.takes) out.push_back(t.path);
    return out;
}

void AudioEngine::demuxAppend(RecordSession& s, const uint8_t* data, size_t bytes) {
    const size_t frames = bytes / mInBytesPerFrame;
    // With a mono source (phone mic) every mode falls back to channel 0.
    const int inCh = mInChannels.load(std::memory_order_relaxed);
    const size_t frameBytes = static_cast<size_t>(mInBytesPerFrame);
    const size_t ch2Off = static_cast<size_t>(std::min(1, inCh - 1)) * mInBytesPerSample;
    for (auto& target : s.targets) {
        // A stereo target keeps the whole input frame; a mono one keeps one
        // sample of it. Either way: size the staging buffer once and copy with
        // a stride, rather than a vector::insert per frame.
        const bool stereo = target->mode != kInputCh1 && target->mode != kInputCh2;
        const size_t sampleBytes = stereo ? frameBytes
                                          : static_cast<size_t>(mInBytesPerSample);
        const size_t srcOff = target->mode == kInputCh2 ? ch2Off : 0;
        target->staging.resize(frames * sampleBytes);
        uint8_t* dst = target->staging.data();
        if (srcOff == 0 && sampleBytes == frameBytes) {
            std::memcpy(dst, data, frames * sampleBytes);
        } else {
            for (size_t f = 0; f < frames; ++f)
                std::memcpy(dst + f * sampleBytes, data + f * frameBytes + srcOff, sampleBytes);
        }
        // Input boost (phone mic). Exactly 1.0 leaves the bytes untouched so
        // interface takes stay bit-perfect.
        const float gain = mInputGain.load(std::memory_order_relaxed);
        if (gain != 1.0f) {
            const bool fl = mInFormat == oboe::AudioFormat::Float;
            for (size_t off = 0; off + mInBytesPerSample <= target->staging.size();
                 off += mInBytesPerSample) {
                uint8_t* p = target->staging.data() + off;
                floatToPcm(gain * pcmSampleToFloat(p, mInBytesPerSample, fl), p,
                           mInBytesPerSample, fl);
            }
        }
        target->wav.writeSamples(target->staging.data(), target->staging.size());
        target->frames += static_cast<int64_t>(frames);
    }
}

void AudioEngine::finalizeRecordSession(RecordSession& s) {
    // One finalize per session, whoever gets there first: finalizing a writer
    // twice would rewrite a header over a header.
    if (s.finalizing.exchange(true, std::memory_order_acq_rel)) return;
    s.result.startFrame = mRecStartFrame.load(std::memory_order_relaxed);
    s.result.dropped = mRecDropped.load(std::memory_order_relaxed);
    bool ok = true;
    for (auto& target : s.targets) {
        RecordTarget& t = *target;
        ok = t.wav.finalize() && ok;
        // A take that never got a frame (count-in aborted, instant punch-out)
        // is a header-only file; deleting it can't lose audio, and keeping it
        // litters the take browser with 0:00 entries.
        if (t.frames == 0) {
            std::remove(t.path.c_str());
            continue;
        }
        s.result.takes.push_back({t.trackId, t.frames, t.path});
    }
    s.result.ioOk = ok;
    s.done.store(true, std::memory_order_release);
}

void AudioEngine::publishRecordResultLocked(RecordSession& s) {
    // A take whose song has been closed under it (openSong punches out) is
    // finalized on disk like any other and shows up in the take browser, but
    // it is never offered to the song that is open now, because its track ids
    // mean something else there.
    const bool sameSong = mRecSessionGen == mSongGen.load(std::memory_order_relaxed);
    if (sameSong) {
        mRecResult = std::move(s.result);
    } else {
        mRecResult = RecordResultData{};
        LOGI("record result from song gen %" PRId64 " not offered; open gen is %" PRId64,
             mRecSessionGen, mSongGen.load(std::memory_order_relaxed));
    }
    mRecSessionOwned.reset();  // destroys s
    mRecPending.store(false, std::memory_order_release);
    mRecResultReady.store(sameSong, std::memory_order_release);
}

void AudioEngine::recordLoop() {
    raiseServicePriority();
    std::vector<uint8_t> buf(1u << 16);
    // ~2 s at the 2 ms loop cadence: frequent enough that a crash loses only
    // the last stdio buffer, rare enough that the seek-back never competes with
    // the drain.
    constexpr int kHeaderFlushIters = 1000;
    int headerFlushIn = kHeaderFlushIters;
    // Empties the ring into the session's writers: both the steady
    // per-iteration drain and the final tail drain before finalize.
    auto drainRing = [&](RecordSession& session) {
        const size_t maxBytes =
                mInBytesPerFrame > 0 ? buf.size() - (buf.size() % mInBytesPerFrame) : buf.size();
        for (;;) {
            const size_t n = mRecRing->read(buf.data(), maxBytes);
            if (n == 0) break;
            demuxAppend(session, buf.data(), n);
        }
    };
    while (!mQuit.load(std::memory_order_acquire)) {
        RecordSession* s = mRecSession.load(std::memory_order_acquire);
        if (!s || s->done.load(std::memory_order_acquire)) {
            // Nothing to drain: park until a take starts (or shutdown says
            // otherwise). Both set mRecWake under the mutex this waits on.
            std::unique_lock<std::mutex> lock(mControlMutex);
            mRecCv.wait_for(lock, std::chrono::milliseconds(kIdleWaitMs), [&] {
                if (mQuit.load(std::memory_order_relaxed) || mRecWake) return true;
                RecordSession* cur = mRecSession.load(std::memory_order_acquire);
                return cur != nullptr && !cur->done.load(std::memory_order_acquire);
            });
            mRecWake = false;
            continue;
        }
        drainRing(*s);
        if (mRecCapture.load(std::memory_order_acquire) && --headerFlushIn <= 0) {
            headerFlushIn = kHeaderFlushIters;
            // Keeps every in-flight take valid on disk: if the process dies
            // (a third-party plugin can take it down), the header already
            // describes the audio written so far instead of claiming zero.
            for (auto& t : s->targets) t->wav.flushHeader();
        }
        if (!mRecCapture.load(std::memory_order_acquire)) {
            // Wait until the callback has certainly passed the stop point,
            // then drain the tail and finalize so no captured audio is lost.
            const uint64_t stopTick = mRecStopTick.load(std::memory_order_relaxed);
            const bool callbackDone =
                    !mInRaw.load(std::memory_order_acquire) ||
                    mTicks.load(std::memory_order_acquire) >= stopTick + 2;
            if (callbackDone) {
                drainRing(*s);
                finalizeRecordSession(*s);
                // Nobody else reads mRecSession, and publishing destroys the
                // session, so drop the pointer first.
                mRecSession.store(nullptr, std::memory_order_release);
                std::lock_guard<std::mutex> lock(mControlMutex);
                if (mRecSessionOwned.get() == s) publishRecordResultLocked(*s);
                continue;  // s is gone
            }
        }
        sleepMs(2);
    }
}

void AudioEngine::setChainControl(int64_t gen, int trackId, int unitIdx, int controlIdx,
                                  float value) {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!acceptGenLocked(gen, "setEffectParam")) return;
    TrackRT* tr = trackForParamsLocked(trackId);
    EffectChain* chain = tr->chain.load(std::memory_order_acquire);
    if (chain) chain->setControl(static_cast<size_t>(unitIdx), static_cast<size_t>(controlIdx),
                                 value);
}

void AudioEngine::setChainUnitState(int64_t gen, int trackId, int unitIdx, bool bypass,
                                    float mix) {
    PAW_ASSERT_CONTROL();
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!acceptGenLocked(gen, "setEffectUnitState")) return;
    TrackRT* tr = trackForParamsLocked(trackId);
    EffectChain* chain = tr->chain.load(std::memory_order_acquire);
    if (chain) chain->setUnitState(static_cast<size_t>(unitIdx), bypass, mix);
}

// ------------------------------------------------------------------ state --

// Both polls are lock-free by construction: every field they read is an atomic
// written by the callback or by the control thread at open/close. They run on
// the UI poll thread and must never be reached from the callback, as the debug
// assert enforces.
void AudioEngine::stateSnapshot(int64_t* out) {
    PAW_ASSERT_CONTROL();
    const bool open = mStreamsOpen.load(std::memory_order_acquire);
    const bool inOpen = open && mInputOpen.load(std::memory_order_acquire);
    out[kStTransport] = mTransport.load(std::memory_order_relaxed);
    out[kStPlayhead] = mPlayhead.load(std::memory_order_relaxed);
    out[kStCountdown] = mCountdown.load(std::memory_order_relaxed);
    out[kStRecStartFrame] = mRecStartFrame.load(std::memory_order_relaxed);
    out[kStRecFrames] = mRecFramesCaptured.load(std::memory_order_relaxed);
    out[kStRecording] = mRecCapture.load(std::memory_order_relaxed) ? 1 : 0;
    out[kStRecPending] = mRecPending.load(std::memory_order_acquire) ? 1 : 0;
    out[kStRecResultReady] = mRecResultReady.load(std::memory_order_acquire) ? 1 : 0;
    out[kStOutputOpen] = open ? 1 : 0;
    out[kStInputOpen] = inOpen ? 1 : 0;
    out[kStDeviceLost] = mDeviceLost.load(std::memory_order_relaxed) ? 1 : 0;
    out[kStInChannels] = inOpen ? mInChannels.load(std::memory_order_relaxed) : 0;
    out[kStInDeviceId] = inOpen ? mInDeviceId.load(std::memory_order_relaxed) : 0;
    out[kStSampleRate] = mSampleRate.load(std::memory_order_relaxed);
    out[kStDroppedCalls] = mDroppedCalls.load(std::memory_order_relaxed);
    out[kStMissingTakes] = mMissingTakes.load(std::memory_order_relaxed);
}

int AudioEngine::meterSnapshot(int* ids, float* peaks, int cap) {
    PAW_ASSERT_CONTROL();
    int n = mMeterCount.load(std::memory_order_acquire);
    if (n > kMaxMeters) n = kMaxMeters;
    if (n > cap) n = cap;
    for (int i = 0; i < n; ++i) {
        ids[i] = mMeterSlots[i].id.load(std::memory_order_relaxed);
        peaks[i] = mMeterSlots[i].peak.load(std::memory_order_relaxed);
    }
    // The master rides along as id -1 so one call serves the whole poll.
    if (n < cap) {
        ids[n] = -1;
        peaks[n] = mMasterPeak.load(std::memory_order_relaxed);
        ++n;
    }
    return n;
}
