// PAW! built-in effects: 3-band EQ, compressor, stereo delay, reverb,
// chorus, noise gate, peak limiter, resonant filter.
//
// Copyright (C) 2026 PAW! contributors
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation, either version 3 of the License, or (at your option)
// any later version.
//
// This program is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
// FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
// more details. You should have received a copy of the GNU General Public
// License along with this program. If not, see <https://www.gnu.org/licenses/>.
//
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include "ladspa.h"

// The built-ins are plain LADSPA descriptors compiled into the engine, with
// no dlopen, so the host code, the parameter UI and the preset format have a
// single path for our effects and for third-party plugins alike.
//
// All of them are stereo (2 audio in, 2 audio out), in-place safe and hard
// real-time capable. Control ports come first; see BuiltinEffects.cpp for the
// port tables, which are the authority the UI renders from.
constexpr unsigned long kPawBuiltinCount = 8;

// Descriptor for built-in `index`; nullptr at or past kPawBuiltinCount.
// Mirrors the LADSPA `ladspa_descriptor` entry point so callers can walk it
// the same way.
const LADSPA_Descriptor* pawBuiltinDescriptor(unsigned long index);
