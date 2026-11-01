#pragma once

#include <map>
#include <memory>
#include <set>
#include <string>
#include <vector>

#include "WavFile.h"

// A clip is a window into an immutable take file placed on the timeline.
// All edits (trim/split/duplicate/drag/fade) are arithmetic on these fields;
// the take on disk is never touched.
struct ClipRef {
    std::string path;       // take WAV
    int64_t srcStart = 0;   // first frame of the take that plays
    int64_t length = 0;     // frames
    int64_t timelineStart = 0;
    // Linear edge fades in frames, 0 = none. Zero-fade rendering is bit-exact
    // (the ramp code never touches those samples), which the split invariant
    // depends on.
    int64_t fadeIn = 0;
    int64_t fadeOut = 0;
};

// Renders windows of a clip list into interleaved stereo float buffers.
// Owns its readers and scratch, so each consumer (prefetch thread, offline
// mixdown) keeps its own instance and no locking is needed, because take
// files are immutable. Missing/broken takes are counted and render nothing:
// silence, or whatever older take lies under them.
//
// Both caches are bounded by what the song currently references, not by what
// it has ever referenced: retain() drops the reader (an open FILE* plus a
// grown buffer) and the remembered open failure for every path no clip names
// any more, and clear() forgets the lot. Without that a long session trends
// toward EMFILE, and a take that failed to open once (a transient EMFILE, say)
// stays silent for the life of the process.
//
// Overlapping clips do not sum. The clip list is a stack, first entry at the
// bottom: where clips overlap only the one latest in the list is heard, and
// the older ones stay referenced and play again wherever nothing covers them.
// New takes are appended, so the newest is on top. The order is the song
// model's own clip order, carried unchanged through Engine.syncClips (and the
// mixdown's track list) and the JNI bridge; the timeline UI draws in that
// same order, last on top. A change of the audible take inside an older clip
// crossfades over kTakeCrossfadeFrames (see render()).
class TimelineRenderer {
public:
    // Length of the crossfade where one take gives way to another: 5 ms at
    // 48 kHz.
    static constexpr int64_t kTakeCrossfadeFrames = 240;

    // Overwrites dst (numFrames * 2 floats) with the clips overlapping
    // [startFrame, startFrame + numFrames), each composited over the ones
    // before it in the list. The result depends only on the clip list and the
    // frame, never on how the timeline is cut into windows.
    void render(const std::vector<ClipRef>& clips, int64_t startFrame, int numFrames,
                float* dstStereo);

    // Distinct take paths that are referenced by the clips rendered so far and
    // currently fail to open. Not cumulative: a path drops out of the count
    // when retain() forgets it, and a retry after that can succeed.
    int missingTakes() const { return static_cast<int>(mFailed.size()); }

    // Forgets a single path, reader and failure alike, so the next render of
    // it starts from a fresh open().
    void dropReader(const std::string& path) {
        mReaders.erase(path);
        mFailed.erase(path);
    }
    // Forgets every path not named in keep (which need be neither sorted nor
    // unique). Call it whenever the clip lists change.
    void retain(const std::vector<std::string>& keep);
    void clear() {
        mReaders.clear();
        mFailed.clear();
    }

private:
    WavReader* readerFor(const std::string& path);

    std::map<std::string, std::unique_ptr<WavReader>> mReaders;
    std::set<std::string> mFailed;  // don't retry a broken path every chunk
    std::vector<float> mScratch;
};
