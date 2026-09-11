#include "WavFile.h"

#include <algorithm>
#include <cstring>

namespace {

struct ChunkHeader {
    char id[4];
    uint32_t size;
};

bool readExact(FILE* f, void* dst, size_t n) { return fread(dst, 1, n, f) == n; }

bool writeU32(FILE* f, uint32_t v) { return fwrite(&v, 4, 1, f) == 1; }
bool writeU16(FILE* f, uint16_t v) { return fwrite(&v, 2, 1, f) == 1; }

}  // namespace

bool loadWav16(const std::string& path, WavData& out, std::string& err) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) {
        err = "cannot open " + path;
        return false;
    }
    char riff[12];
    if (!readExact(f, riff, 12) || memcmp(riff, "RIFF", 4) != 0 || memcmp(riff + 8, "WAVE", 4) != 0) {
        err = "not a RIFF/WAVE file";
        fclose(f);
        return false;
    }
    uint16_t audioFormat = 0, channels = 0, bitsPerSample = 0;
    uint32_t sampleRate = 0;
    bool haveFmt = false;
    std::vector<int16_t> pcm;

    ChunkHeader ch;
    while (readExact(f, &ch, 8)) {
        if (memcmp(ch.id, "fmt ", 4) == 0 && ch.size >= 16) {
            uint8_t fmt[16];
            if (!readExact(f, fmt, 16)) break;
            memcpy(&audioFormat, fmt + 0, 2);
            memcpy(&channels, fmt + 2, 2);
            memcpy(&sampleRate, fmt + 4, 4);
            memcpy(&bitsPerSample, fmt + 14, 2);
            if (ch.size > 16) fseek(f, ch.size - 16, SEEK_CUR);
            haveFmt = true;
        } else if (memcmp(ch.id, "data", 4) == 0) {
            pcm.resize(ch.size / 2);
            if (!readExact(f, pcm.data(), pcm.size() * 2)) {
                err = "truncated data chunk";
                fclose(f);
                return false;
            }
        } else {
            fseek(f, ch.size + (ch.size & 1), SEEK_CUR);
        }
    }
    fclose(f);

    if (!haveFmt || pcm.empty()) {
        err = "missing fmt or data chunk";
        return false;
    }
    if (audioFormat != 1 || bitsPerSample != 16) {
        err = "expected 16-bit PCM";
        return false;
    }
    out.sampleRate = static_cast<int>(sampleRate);
    out.channels = channels;
    out.interleaved.resize(pcm.size());
    for (size_t i = 0; i < pcm.size(); ++i) out.interleaved[i] = pcm[i] / 32768.0f;
    return true;
}

bool WavWriter::open(const std::string& path, int sampleRate, Format fmt, int channels) {
    mFile = fopen(path.c_str(), "wb");
    if (!mFile) return false;

    uint16_t tag = (fmt == Format::Float32) ? 3 : 1;
    uint16_t bits;
    switch (fmt) {
        case Format::I16: bits = 16; break;
        case Format::I24Packed: bits = 24; break;
        default: bits = 32; break;
    }
    const uint16_t blockAlign = static_cast<uint16_t>(bits / 8 * channels);
    mBytesPerFrame = blockAlign;

    fwrite("RIFF", 4, 1, mFile);
    mRiffSizePos = ftell(mFile);
    writeU32(mFile, 0);  // patched in finalize()
    fwrite("WAVE", 4, 1, mFile);

    fwrite("fmt ", 4, 1, mFile);
    writeU32(mFile, 16);
    writeU16(mFile, tag);
    writeU16(mFile, static_cast<uint16_t>(channels));
    writeU32(mFile, static_cast<uint32_t>(sampleRate));
    writeU32(mFile, static_cast<uint32_t>(sampleRate) * blockAlign);
    writeU16(mFile, blockAlign);
    writeU16(mFile, bits);

    if (tag == 3) {  // float WAVs should carry a fact chunk
        fwrite("fact", 4, 1, mFile);
        writeU32(mFile, 4);
        writeU32(mFile, 0);  // frame count, patched in finalize()
    }

    fwrite("data", 4, 1, mFile);
    mDataSizePos = ftell(mFile);
    writeU32(mFile, 0);  // patched in finalize()
    return true;
}

void WavWriter::writeSamples(const uint8_t* data, size_t bytes) {
    if (!mFile) return;
    // Count what actually landed, not what was attempted: the header patch
    // must describe the bytes on disk, and a short write (disk full) must
    // surface in finalize() instead of reporting a clean take.
    const size_t got = fwrite(data, 1, bytes, mFile);
    mDataBytes += got;
    if (got != bytes) mIoError = true;
}

bool WavWriter::patchSizes(long end) {
    bool ok = fseek(mFile, mRiffSizePos, SEEK_SET) == 0 &&
              writeU32(mFile, static_cast<uint32_t>(end - mRiffSizePos - 4));
    if (mDataSizePos > 50) {  // float layout: fact frame-count sits 8 bytes before data size
        ok = fseek(mFile, mDataSizePos - 8, SEEK_SET) == 0 &&
             writeU32(mFile, static_cast<uint32_t>(mDataBytes / mBytesPerFrame)) && ok;
    }
    ok = fseek(mFile, mDataSizePos, SEEK_SET) == 0 &&
         writeU32(mFile, static_cast<uint32_t>(mDataBytes)) && ok;
    return ok;
}

bool WavWriter::flushHeader() {
    if (!mFile) return false;
    const long end = ftell(mFile);
    if (end < 0 || !patchSizes(end) || fseek(mFile, end, SEEK_SET) != 0 ||
        fflush(mFile) != 0) {
        mIoError = true;
    }
    return !mIoError;
}

bool WavWriter::finalize() {
    if (!mFile) return false;
    const long end = ftell(mFile);
    bool ok = end >= 0 && patchSizes(end);
    ok = fflush(mFile) == 0 && ok;
    ok = fclose(mFile) == 0 && ok;
    mFile = nullptr;
    return ok && !mIoError;
}

WavWriter::~WavWriter() {
    if (mFile) fclose(mFile);
}

bool WavReader::open(const std::string& path, std::string& err) {
    close();
    mFile = fopen(path.c_str(), "rb");
    if (!mFile) {
        err = "cannot open " + path;
        return false;
    }
    char riff[12];
    if (!readExact(mFile, riff, 12) || memcmp(riff, "RIFF", 4) != 0 ||
        memcmp(riff + 8, "WAVE", 4) != 0) {
        err = "not a RIFF/WAVE file";
        close();
        return false;
    }
    uint16_t audioFormat = 0, channels = 0, bitsPerSample = 0;
    uint32_t sampleRate = 0;
    bool haveFmt = false;
    uint32_t dataSize = 0;

    ChunkHeader ch;
    while (readExact(mFile, &ch, 8)) {
        if (memcmp(ch.id, "fmt ", 4) == 0 && ch.size >= 16) {
            uint8_t fmt[16];
            if (!readExact(mFile, fmt, 16)) break;
            memcpy(&audioFormat, fmt + 0, 2);
            memcpy(&channels, fmt + 2, 2);
            memcpy(&sampleRate, fmt + 4, 4);
            memcpy(&bitsPerSample, fmt + 14, 2);
            if (ch.size > 16) fseek(mFile, ch.size - 16, SEEK_CUR);
            haveFmt = true;
        } else if (memcmp(ch.id, "data", 4) == 0) {
            mDataOffset = ftell(mFile);
            dataSize = ch.size;
            // Seek past; a WAV can (rarely) put fmt after data.
            fseek(mFile, ch.size + (ch.size & 1), SEEK_CUR);
        } else {
            fseek(mFile, ch.size + (ch.size & 1), SEEK_CUR);
        }
    }

    if (!haveFmt || mDataOffset == 0 || channels == 0) {
        err = "missing fmt or data chunk";
        close();
        return false;
    }
    mFloat = (audioFormat == 3);
    const bool intPcm = (audioFormat == 1) &&
                        (bitsPerSample == 16 || bitsPerSample == 24 || bitsPerSample == 32);
    if (!intPcm && !(mFloat && bitsPerSample == 32)) {
        err = "unsupported WAV format";
        close();
        return false;
    }
    mChannels = channels;
    mSampleRate = static_cast<int>(sampleRate);
    mBytesPerSample = bitsPerSample / 8;
    const int bytesPerFrame = mBytesPerSample * mChannels;
    int64_t dataBytes = static_cast<int64_t>(dataSize);
    // A recorder that died mid-take never patched its header: the data size
    // still reads zero (or, torn, more than the file holds) while the audio
    // bytes are on disk. Trust the file over the header in exactly those two
    // cases; a well-formed header (even with trailing chunks) is untouched.
    if (fseek(mFile, 0, SEEK_END) == 0) {
        const long fileEnd = ftell(mFile);
        const int64_t avail = fileEnd > mDataOffset ? fileEnd - mDataOffset : 0;
        if (dataBytes == 0 || dataBytes > avail) dataBytes = avail;
    }
    mFrames = dataBytes / bytesPerFrame;
    return true;
}

void WavReader::close() {
    if (mFile) fclose(mFile);
    mFile = nullptr;
    mFrames = 0;
}

int64_t WavReader::readFrames(int64_t frameOffset, int64_t numFrames, float* dst,
                              int dstChannels) {
    if (!mFile || frameOffset >= mFrames || frameOffset < 0) return 0;
    const int64_t n = std::min(numFrames, mFrames - frameOffset);
    if (n <= 0) return 0;

    const int bytesPerFrame = mBytesPerSample * mChannels;
    mBuf.resize(static_cast<size_t>(n) * bytesPerFrame);
    if (fseek(mFile, mDataOffset + frameOffset * bytesPerFrame, SEEK_SET) != 0) return 0;
    const size_t got = fread(mBuf.data(), 1, mBuf.size(), mFile);
    const int64_t gotFrames = static_cast<int64_t>(got) / bytesPerFrame;

    for (int64_t f = 0; f < gotFrames; ++f) {
        const uint8_t* frame = mBuf.data() + f * bytesPerFrame;
        if (dstChannels == 1 && mChannels >= 2) {
            dst[f] = 0.5f * (pcmSampleToFloat(frame, mBytesPerSample, mFloat) +
                             pcmSampleToFloat(frame + mBytesPerSample, mBytesPerSample, mFloat));
        } else {
            for (int c = 0; c < dstChannels; ++c) {
                const int srcC = std::min(c, mChannels - 1);
                dst[f * dstChannels + c] =
                        pcmSampleToFloat(frame + srcC * mBytesPerSample, mBytesPerSample, mFloat);
            }
        }
    }
    return gotFrames;
}
