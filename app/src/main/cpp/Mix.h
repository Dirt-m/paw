#pragma once

// The one gain/pan law, shared by the audio callback (per-track and master)
// and the offline mixdown, so live playback and a bounced file cannot drift
// apart.
//
// Constant-gain balance, not constant-power: pan pulls the far channel down
// and leaves the near one alone, so centre is unity on both and a hard-panned
// track is exactly as loud as it was in the middle.

struct PanGains {
    float l;
    float r;
};

inline PanGains panGains(float gain, float pan) {
    return {gain * (pan >= 0.0f ? 1.0f - pan : 1.0f),
            gain * (pan <= 0.0f ? 1.0f + pan : 1.0f)};
}
