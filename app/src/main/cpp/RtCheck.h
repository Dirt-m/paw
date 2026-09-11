#pragma once

#include <atomic>
#include <cstdint>

// Debug-only enforcement of the threading contract AudioEngine.h documents.
// Everything here compiles to nothing when NDEBUG is defined, which the
// release / RelWithDebInfo native configurations do and the debug one does not,
// so a debug APK carries the checks and a release APK carries none.
//
// Three things get enforced:
//  - Role. The audio callback claims its own thread on the first burst
//    (PAW_ASSERT_RT); every control-side entry point asserts it is *not* on
//    that thread (PAW_ASSERT_CONTROL), the poll included.
//  - Allocation. PAW_RT_SCOPE() raises a thread-local flag for the duration of
//    the callback, and the global operator new/delete replacements in
//    RtCheck.cpp abort while it is raised. Any allocation or free reached from
//    the callback, whether ours, Oboe's or a third-party plugin's, then fails
//    loudly instead of surfacing as an occasional glitch.
//  - By construction, not by check: the buffers the callback reads are
//    fixed-capacity arrays (AudioEngine's mClickAccent/mClickNormal/mInBuf/
//    mInFloat), so no edit can reallocate storage under the callback.
//
// Cost on the real-time thread: two thread-local writes and two relaxed loads
// per burst. No syscall, no lock, no allocation. Thread identity is the address
// of this module's thread-local flag, which is unique per thread and needs no
// gettid(). (The first access on a new thread may materialise the module's TLS
// block, which does allocate; that happens inside PAW_ASSERT_RT, before the
// flag is raised, so it cannot self-trigger.)

namespace rtcheck {

#ifndef NDEBUG

extern thread_local bool gInRtCallback;
extern std::atomic<uintptr_t> gRtThread;  // 0 = no callback thread claimed yet

[[noreturn]] void fail(const char* what, const char* detail);

inline uintptr_t threadToken() { return reinterpret_cast<uintptr_t>(&gInRtCallback); }

// First burst on a callback thread claims the role; every later burst must be
// the same thread.
inline void assertRt(const char* what) {
    const uintptr_t me = threadToken();
    uintptr_t owner = gRtThread.load(std::memory_order_relaxed);
    if (owner == 0) {
        gRtThread.compare_exchange_strong(owner, me, std::memory_order_relaxed);
        owner = gRtThread.load(std::memory_order_relaxed);
    }
    if (owner != me) fail(what, "must run on the audio callback thread");
}

inline void assertControl(const char* what) {
    const uintptr_t owner = gRtThread.load(std::memory_order_relaxed);
    if (owner != 0 && owner == threadToken())
        fail(what, "must never run on the audio callback thread");
}

// A reopened stream gets a fresh callback thread, and a dead thread's TLS
// block can be handed to some other thread later; the control side clears the
// claim while the streams are down so neither turns into a false positive.
inline void resetRtThread() { gRtThread.store(0, std::memory_order_relaxed); }

struct RtScope {
    RtScope() { gInRtCallback = true; }
    ~RtScope() { gInRtCallback = false; }
    RtScope(const RtScope&) = delete;
    RtScope& operator=(const RtScope&) = delete;
};

#endif  // NDEBUG

}  // namespace rtcheck

#ifndef NDEBUG
#define PAW_ASSERT_RT() ::rtcheck::assertRt(__func__)
#define PAW_ASSERT_CONTROL() ::rtcheck::assertControl(__func__)
#define PAW_RT_SCOPE() ::rtcheck::RtScope odbRtScope_
#define PAW_RT_FORGET_THREAD() ::rtcheck::resetRtThread()
#else
#define PAW_ASSERT_RT() ((void)0)
#define PAW_ASSERT_CONTROL() ((void)0)
#define PAW_RT_SCOPE() ((void)0)
#define PAW_RT_FORGET_THREAD() ((void)0)
#endif
