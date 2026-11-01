#pragma once

#include <string>
#include <vector>

#include "Sampler.h"
#include "Timeline.h"

// Offline render of the master bus: same clip render, gain/pan law and effect
// hosting as live playback, pulled straight from the take files with no
// real-time constraint. Output is a 16-bit stereo WAV for the share sheet.

struct MixdownTrack {
    int id = 0;  // what the sampler's samples name as their track
    std::vector<ClipRef> clips;
    float gain = 1.0f;
    float pan = 0.0f;
    bool mute = false;
    bool solo = false;
    std::vector<std::string> effectIds;
    std::vector<std::vector<float>> effectValues;
    std::vector<uint8_t> effectBypasses;
    std::vector<float> effectMixes;
};

// Blocking; call off the UI thread. Renders from frame 0 to the last clip end
// or the last hit's end, plus a one-second effect tail. The sampler's hits play
// through the same voice pool the callback uses, into their track ahead of its
// chain. Returns "" on success, else an error.
std::string renderMixdown(const std::vector<MixdownTrack>& tracks, const MixdownTrack& master,
                          const SampleBank& bank, const HitList& hits, int sampleRate,
                          const std::string& outPath);
