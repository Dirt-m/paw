#include <jni.h>

#include <algorithm>
#include <memory>
#include <string>
#include <vector>

#include "AudioEngine.h"
#include "EffectHost.h"
#include "Mixdown.h"
#include "Peaks.h"

namespace {

std::string toStdString(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

std::vector<std::string> toStringVector(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (!arr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        out.push_back(toStdString(env, s));
        env->DeleteLocalRef(s);
    }
    return out;
}

std::vector<int64_t> toLongVector(JNIEnv* env, jlongArray arr) {
    std::vector<int64_t> out;
    if (!arr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.resize(n);
    env->GetLongArrayRegion(arr, 0, n, reinterpret_cast<jlong*>(out.data()));
    return out;
}

std::vector<std::vector<float>> toFloatArrays(JNIEnv* env, jobjectArray arr) {
    std::vector<std::vector<float>> out;
    if (!arr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.resize(n);
    for (jsize i = 0; i < n; ++i) {
        auto fa = static_cast<jfloatArray>(env->GetObjectArrayElement(arr, i));
        if (fa) {
            const jsize m = env->GetArrayLength(fa);
            out[i].resize(m);
            env->GetFloatArrayRegion(fa, 0, m, out[i].data());
            env->DeleteLocalRef(fa);
        }
    }
    return out;
}

std::vector<uint8_t> toBoolVector(JNIEnv* env, jbooleanArray arr) {
    std::vector<uint8_t> out;
    if (!arr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.resize(n);
    env->GetBooleanArrayRegion(arr, 0, n, reinterpret_cast<jboolean*>(out.data()));
    return out;
}

std::vector<float> toFloatVector(JNIEnv* env, jfloatArray arr) {
    std::vector<float> out;
    if (!arr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.resize(n);
    env->GetFloatArrayRegion(arr, 0, n, out.data());
    return out;
}

std::vector<ClipRef> toClips(JNIEnv* env, jobjectArray paths, jlongArray srcStarts,
                             jlongArray lengths, jlongArray timelineStarts,
                             jlongArray fadeIns, jlongArray fadeOuts) {
    const std::vector<std::string> p = toStringVector(env, paths);
    const std::vector<int64_t> ss = toLongVector(env, srcStarts);
    const std::vector<int64_t> ln = toLongVector(env, lengths);
    const std::vector<int64_t> ts = toLongVector(env, timelineStarts);
    const std::vector<int64_t> fi = toLongVector(env, fadeIns);
    const std::vector<int64_t> fo = toLongVector(env, fadeOuts);
    std::vector<ClipRef> clips;
    const size_t n = std::min(std::min(p.size(), ss.size()), std::min(ln.size(), ts.size()));
    clips.reserve(n);
    for (size_t i = 0; i < n; ++i)
        clips.push_back({p[i], ss[i], ln[i], ts[i], i < fi.size() ? fi[i] : 0,
                         i < fo.size() ? fo[i] : 0});
    return clips;
}

// Mixdown is assembled across several JNI calls (avoids JSON parsing in C++);
// control-side single-threaded by the Kotlin caller.
std::vector<MixdownTrack> gMixTracks;
MixdownTrack gMixMaster;

}  // namespace

extern "C" {

// ---- Persistent recorder engine ----

JNIEXPORT jstring JNICALL Java_org_paw_app_Engine_openStreams(
        JNIEnv* env, jobject, jint inputDeviceId, jint outputDeviceId, jint sampleRate,
        jboolean builtinMic) {
    return env->NewStringUTF(AudioEngine::instance()
                                     .openStreams(inputDeviceId, outputDeviceId, sampleRate,
                                                  builtinMic)
                                     .c_str());
}

JNIEXPORT void JNICALL Java_org_paw_app_Engine_closeStreams(JNIEnv*, jobject) {
    AudioEngine::instance().closeStreams();
}

JNIEXPORT jint JNICALL Java_org_paw_app_Engine_routeLatencyFrames(JNIEnv*, jobject) {
    return AudioEngine::instance().routeLatencyFrames();
}

JNIEXPORT void JNICALL Java_org_paw_app_Engine_setInputGain(JNIEnv*, jobject, jfloat gain) {
    AudioEngine::instance().setInputGain(gain);
}

// Song-scoped mutations all carry the caller's song generation: the engine
// drops (and counts) anything issued by a controller whose song has been
// closed. See AudioEngine.h.

JNIEXPORT void JNICALL Java_org_paw_app_Engine_openSong(JNIEnv*, jobject, jlong gen) {
    AudioEngine::instance().openSong(gen);
}

JNIEXPORT void JNICALL Java_org_paw_app_Engine_syncClips(
        JNIEnv* env, jobject, jlong gen, jint trackId, jobjectArray paths, jlongArray srcStarts,
        jlongArray lengths, jlongArray timelineStarts, jlongArray fadeIns, jlongArray fadeOuts) {
    AudioEngine::instance().syncTrackClips(
            gen, trackId,
            toClips(env, paths, srcStarts, lengths, timelineStarts, fadeIns, fadeOuts));
}

JNIEXPORT void JNICALL Java_org_paw_app_Engine_setMetronome(
        JNIEnv*, jobject, jlong gen, jboolean enabled, jfloat bpm, jint beatsPerBar) {
    AudioEngine::instance().setMetronome(gen, enabled, bpm, beatsPerBar);
}

JNIEXPORT void JNICALL Java_org_paw_app_Engine_setLoop(
        JNIEnv*, jobject, jlong gen, jboolean enabled, jlong startFrame, jlong endFrame) {
    AudioEngine::instance().setLoop(gen, enabled, startFrame, endFrame);
}

JNIEXPORT void JNICALL Java_org_paw_app_Engine_removeTrack(JNIEnv*, jobject, jlong gen,
                                                               jint trackId) {
    AudioEngine::instance().removeTrack(gen, trackId);
}

JNIEXPORT void JNICALL Java_org_paw_app_Engine_setTrackParams(
        JNIEnv*, jobject, jlong gen, jint trackId, jfloat gain, jfloat pan, jboolean mute,
        jboolean solo, jboolean armed, jint inputMode, jboolean monitor) {
    AudioEngine::instance().setTrackParams(gen, trackId, gain, pan, mute, solo, armed, inputMode,
                                           monitor);
}

JNIEXPORT jint JNICALL Java_org_paw_app_Engine_transportPlayPause(JNIEnv*, jobject) {
    return AudioEngine::instance().transportPlayPause();
}

JNIEXPORT void JNICALL Java_org_paw_app_Engine_transportStopAll(JNIEnv*, jobject) {
    AudioEngine::instance().transportStopAll();
}

JNIEXPORT void JNICALL Java_org_paw_app_Engine_transportSeek(JNIEnv*, jobject, jlong frame) {
    AudioEngine::instance().seek(frame);
}

JNIEXPORT jlong JNICALL Java_org_paw_app_Engine_playheadFrame(JNIEnv*, jobject) {
    return AudioEngine::instance().playhead();
}

JNIEXPORT jint JNICALL Java_org_paw_app_Engine_toggleRecordSession(
        JNIEnv* env, jobject, jlong gen, jstring dir, jstring baseName,
        jlong countInFramesIfStopped) {
    return AudioEngine::instance().toggleRecordSession(gen, toStdString(env, dir),
                                                       toStdString(env, baseName),
                                                       countInFramesIfStopped);
}

JNIEXPORT jstring JNICALL Java_org_paw_app_Engine_lastRecordError(JNIEnv* env, jobject) {
    return env->NewStringUTF(AudioEngine::instance().lastRecordError().c_str());
}

JNIEXPORT jboolean JNICALL Java_org_paw_app_Engine_finishRecordAsync(
        JNIEnv*, jobject, jboolean keepRolling) {
    return AudioEngine::instance().finishRecordAsync(keepRolling) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_org_paw_app_Engine_finishRecordSync(
        JNIEnv*, jobject, jboolean keepRolling) {
    return AudioEngine::instance().finishRecordSync(keepRolling) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL Java_org_paw_app_Engine_recordResultLongs(JNIEnv* env, jobject) {
    const std::vector<int64_t> vals = AudioEngine::instance().recordResultLongs();
    jlongArray out = env->NewLongArray(static_cast<jsize>(vals.size()));
    env->SetLongArrayRegion(out, 0, static_cast<jsize>(vals.size()),
                            reinterpret_cast<const jlong*>(vals.data()));
    return out;
}

JNIEXPORT jobjectArray JNICALL Java_org_paw_app_Engine_recordResultPaths(JNIEnv* env, jobject) {
    const std::vector<std::string> paths = AudioEngine::instance().recordResultPaths();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out =
            env->NewObjectArray(static_cast<jsize>(paths.size()), stringClass, nullptr);
    for (size_t i = 0; i < paths.size(); ++i) {
        jstring s = env->NewStringUTF(paths[i].c_str());
        env->SetObjectArrayElement(out, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return out;
}

// Binary state seam: the poll fills caller-allocated arrays, so a tick costs
// no allocation on either side and never touches the engine's control mutex.
JNIEXPORT void JNICALL Java_org_paw_app_Engine_engineState(JNIEnv* env, jobject,
                                                               jlongArray dst) {
    if (!dst || env->GetArrayLength(dst) < AudioEngine::kStateSlots) return;
    int64_t buf[AudioEngine::kStateSlots];
    AudioEngine::instance().stateSnapshot(buf);
    env->SetLongArrayRegion(dst, 0, AudioEngine::kStateSlots,
                            reinterpret_cast<const jlong*>(buf));
}

JNIEXPORT jint JNICALL Java_org_paw_app_Engine_engineMeters(JNIEnv* env, jobject,
                                                                jintArray ids,
                                                                jfloatArray peaks) {
    if (!ids || !peaks) return 0;
    constexpr int kSlots = AudioEngine::kMaxMeters + 1;  // + the master
    jsize cap = std::min(env->GetArrayLength(ids), env->GetArrayLength(peaks));
    if (cap > kSlots) cap = kSlots;
    int idBuf[kSlots];
    float peakBuf[kSlots];
    const int n = AudioEngine::instance().meterSnapshot(idBuf, peakBuf, cap);
    env->SetIntArrayRegion(ids, 0, n, reinterpret_cast<const jint*>(idBuf));
    env->SetFloatArrayRegion(peaks, 0, n, peakBuf);
    return n;
}

JNIEXPORT void JNICALL Java_org_paw_app_Engine_engineShutdown(JNIEnv*, jobject) {
    AudioEngine::instance().shutdown();
}

// ---- Peaks / file info ----

JNIEXPORT jbyteArray JNICALL Java_org_paw_app_Engine_computePeaks(
        JNIEnv* env, jobject, jstring path, jint bucketFrames) {
    const std::vector<int8_t> peaks = computePeaks(toStdString(env, path), bucketFrames);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(peaks.size()));
    if (!peaks.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(peaks.size()),
                                reinterpret_cast<const jbyte*>(peaks.data()));
    }
    return out;
}

JNIEXPORT jlongArray JNICALL Java_org_paw_app_Engine_wavInfo(JNIEnv* env, jobject,
                                                                 jstring path) {
    const WavInfo info = wavInfo(toStdString(env, path));
    const jlong vals[3] = {info.frames, info.channels, info.sampleRate};
    jlongArray out = env->NewLongArray(3);
    env->SetLongArrayRegion(out, 0, 3, vals);
    return out;
}

// ---- Effects ----

JNIEXPORT jstring JNICALL Java_org_paw_app_Engine_scanEffects(JNIEnv* env, jobject,
                                                                  jstring dir) {
    EffectHost::instance().scan(toStdString(env, dir));
    return env->NewStringUTF(EffectHost::instance().catalogJson().c_str());
}

JNIEXPORT void JNICALL Java_org_paw_app_Engine_setChain(
        JNIEnv* env, jobject, jlong gen, jint trackId, jint sampleRate, jobjectArray effectIds,
        jobjectArray controlValues, jbooleanArray bypasses, jfloatArray mixes) {
    const std::vector<std::string> ids = toStringVector(env, effectIds);
    EffectChain* chain = nullptr;
    if (!ids.empty()) {
        chain = EffectHost::instance().buildChain(ids, toFloatArrays(env, controlValues),
                                                  toBoolVector(env, bypasses),
                                                  toFloatVector(env, mixes),
                                                  sampleRate, 8192 /* engine kMaxBurst */);
    }
    // Ownership goes to the engine either way; a stale generation deletes it.
    AudioEngine::instance().setTrackChain(gen, trackId, chain);
}

JNIEXPORT void JNICALL Java_org_paw_app_Engine_setEffectParam(
        JNIEnv*, jobject, jlong gen, jint trackId, jint unitIdx, jint controlIdx, jfloat value) {
    AudioEngine::instance().setChainControl(gen, trackId, unitIdx, controlIdx, value);
}

JNIEXPORT void JNICALL Java_org_paw_app_Engine_setEffectUnitState(
        JNIEnv*, jobject, jlong gen, jint trackId, jint unitIdx, jboolean bypass, jfloat mix) {
    AudioEngine::instance().setChainUnitState(gen, trackId, unitIdx, bypass, mix);
}

// ---- Mixdown ----

JNIEXPORT void JNICALL Java_org_paw_app_Engine_mixdownBegin(JNIEnv*, jobject) {
    gMixTracks.clear();
    gMixMaster = MixdownTrack{};
}

JNIEXPORT void JNICALL Java_org_paw_app_Engine_mixdownAddTrack(
        JNIEnv* env, jobject, jboolean isMaster, jfloat gain, jfloat pan, jboolean mute,
        jboolean solo, jobjectArray paths, jlongArray srcStarts, jlongArray lengths,
        jlongArray timelineStarts, jlongArray fadeIns, jlongArray fadeOuts,
        jobjectArray effectIds, jobjectArray controlValues,
        jbooleanArray effectBypasses, jfloatArray effectMixes) {
    MixdownTrack t;
    t.gain = gain;
    t.pan = pan;
    t.mute = mute;
    t.solo = solo;
    t.clips = toClips(env, paths, srcStarts, lengths, timelineStarts, fadeIns, fadeOuts);
    t.effectIds = toStringVector(env, effectIds);
    t.effectValues = toFloatArrays(env, controlValues);
    t.effectBypasses = toBoolVector(env, effectBypasses);
    t.effectMixes = toFloatVector(env, effectMixes);
    if (isMaster) gMixMaster = std::move(t);
    else gMixTracks.push_back(std::move(t));
}

JNIEXPORT jstring JNICALL Java_org_paw_app_Engine_mixdownRun(
        JNIEnv* env, jobject, jint sampleRate, jstring outPath) {
    const std::string err =
            renderMixdown(gMixTracks, gMixMaster, sampleRate, toStdString(env, outPath));
    gMixTracks.clear();
    return env->NewStringUTF(err.c_str());
}

}  // extern "C"
