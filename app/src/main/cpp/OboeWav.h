#pragma once

#include <oboe/Oboe.h>

#include "WavFile.h"

// The one place the Oboe input format maps to a take format, so a take lands
// bit-identical to what the hardware delivered. It lives here rather than in
// WavFile.h so the host-side sanity tools can use WavWriter without Oboe on
// the include path.
inline WavWriter::Format wavFormatFor(oboe::AudioFormat f) {
    switch (f) {
        case oboe::AudioFormat::I16: return WavWriter::Format::I16;
        case oboe::AudioFormat::I24: return WavWriter::Format::I24Packed;
        case oboe::AudioFormat::I32: return WavWriter::Format::I32;
        default: return WavWriter::Format::Float32;
    }
}
