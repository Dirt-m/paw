#include "RtCheck.h"

#ifndef NDEBUG

#include <android/log.h>

#include <cstdlib>
#include <new>

namespace rtcheck {

thread_local bool gInRtCallback = false;
std::atomic<uintptr_t> gRtThread{0};

void fail(const char* what, const char* detail) {
    __android_log_print(ANDROID_LOG_FATAL, "PawRT", "threading contract violated: %s() %s",
                        what, detail);
    std::abort();
}

}  // namespace rtcheck

// Global operator new/delete replacement, debug builds only. The audio
// callback must never allocate or free; raising the scoped flag turns any
// attempt into an immediate abort with a stack the debugger can read, instead
// of an occasional underrun. Everything off the callback thread takes the plain
// malloc/free path.
//
// The aligned (std::align_val_t) overloads are deliberately not replaced: the
// default ones are self-consistent, and leaving them alone keeps this file from
// reimplementing aligned allocation. An aligned allocation on the callback
// thread therefore slips through. Nothing in the engine makes one.
namespace {

void* rtCheckedAlloc(std::size_t bytes) {
    if (rtcheck::gInRtCallback)
        rtcheck::fail("operator new", "allocated on the audio callback thread");
    return std::malloc(bytes ? bytes : 1);
}

void rtCheckedFree(void* p) {
    if (p && rtcheck::gInRtCallback)
        rtcheck::fail("operator delete", "freed on the audio callback thread");
    std::free(p);
}

[[noreturn]] void outOfMemory() {
#if defined(__cpp_exceptions) && __cpp_exceptions
    throw std::bad_alloc();
#else
    std::abort();
#endif
}

}  // namespace

void* operator new(std::size_t n) {
    void* p = rtCheckedAlloc(n);
    if (!p) outOfMemory();
    return p;
}

void* operator new[](std::size_t n) {
    void* p = rtCheckedAlloc(n);
    if (!p) outOfMemory();
    return p;
}

void* operator new(std::size_t n, const std::nothrow_t&) noexcept { return rtCheckedAlloc(n); }

void* operator new[](std::size_t n, const std::nothrow_t&) noexcept { return rtCheckedAlloc(n); }

void operator delete(void* p) noexcept { rtCheckedFree(p); }

void operator delete[](void* p) noexcept { rtCheckedFree(p); }

void operator delete(void* p, std::size_t) noexcept { rtCheckedFree(p); }

void operator delete[](void* p, std::size_t) noexcept { rtCheckedFree(p); }

void operator delete(void* p, const std::nothrow_t&) noexcept { rtCheckedFree(p); }

void operator delete[](void* p, const std::nothrow_t&) noexcept { rtCheckedFree(p); }

#endif  // NDEBUG
