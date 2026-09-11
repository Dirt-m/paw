#pragma once

#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

// Single-producer single-consumer lock-free byte ring. The producer is the
// audio callback: write() only memcpys into preallocated storage.
class RingBuffer {
public:
    explicit RingBuffer(size_t capacityPow2) : mBuf(capacityPow2), mMask(capacityPow2 - 1) {}

    // Returns false (and writes nothing) if there is not enough free space.
    bool write(const uint8_t* data, size_t n) {
        const size_t head = mHead.load(std::memory_order_relaxed);
        const size_t tail = mTail.load(std::memory_order_acquire);
        if (mBuf.size() - (head - tail) < n) return false;
        const size_t idx = head & mMask;
        const size_t first = std::min(n, mBuf.size() - idx);
        std::memcpy(mBuf.data() + idx, data, first);
        std::memcpy(mBuf.data(), data + first, n - first);
        mHead.store(head + n, std::memory_order_release);
        return true;
    }

    size_t read(uint8_t* out, size_t maxN) {
        const size_t tail = mTail.load(std::memory_order_relaxed);
        const size_t head = mHead.load(std::memory_order_acquire);
        const size_t n = std::min(maxN, head - tail);
        const size_t idx = tail & mMask;
        const size_t first = std::min(n, mBuf.size() - idx);
        std::memcpy(out, mBuf.data() + idx, first);
        std::memcpy(out + first, mBuf.data(), n - first);
        mTail.store(tail + n, std::memory_order_release);
        return n;
    }

    size_t availableToRead() const {
        return mHead.load(std::memory_order_acquire) - mTail.load(std::memory_order_acquire);
    }

    size_t freeSpace() const { return mBuf.size() - availableToRead(); }
    size_t capacity() const { return mBuf.size(); }

    // Drops everything buffered. Only the consumer may call this, or a thread
    // the consumer role has been handed to, as the engine's seek protocol does
    // (the callback stops reading a ring whose generation is stale before the
    // disk thread flushes it).
    void discardAll() {
        mTail.store(mHead.load(std::memory_order_acquire), std::memory_order_release);
    }

private:
    std::vector<uint8_t> mBuf;
    const size_t mMask;
    std::atomic<size_t> mHead{0};
    std::atomic<size_t> mTail{0};
};
