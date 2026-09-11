# Engineering notes

The rules and decisions that shape the code, and the things that bite. `docs/scope.md`
says what the app does, `docs/ui.md` says where each control lives, `docs/status.md`
says what has been verified.

Kotlin and Jetpack Compose for the UI, a C++ audio engine on Oboe (AAudio backend),
JNI between them. Reference hardware is an M-Audio M-Track Duo (class-compliant USB,
two in, two out) on a Pixel 9a.

## Rules that do not bend

Recorded audio is immutable. A take is written to disk exactly as the hardware
delivered it and is never rewritten. Trim, split, duplicate, drag and fades are all
references into the original file. No edit, crash or bug may destroy a recording.
Deleting a clip only drops the reference, and undo restores it. Two deliberate
exceptions. A record pass that captured zero frames leaves a header-only file, which
is deleted when the pass finalizes; the startup sweep for leftovers also requires the
file to be unreferenced and exactly header-sized, so a corrupt real take can never be
caught by it. And the user can delete a recording from disk on the Recordings screen,
behind a confirmation, refused while any clip still uses it.

Both orientations. Layouts adapt in place. Rotation must not recreate the activity or
the engine; `configChanges` in the manifest keeps the activity alive.

Sensible defaults over settings screens. If a feature needs explaining, simplify it. No
persistent explainer text in the UI. Transient errors and stateful readouts are fine.

The master bus is a separate track, Ardour-style. No automation and no MIDI yet.
Design so they can be added without a rewrite, but do not build them.

## Audio path

Stereo full-duplex over Android's native USB audio path (AAudio via Oboe). The Duo is
two-channel, so no custom USB driver is needed. More than two channels would need one,
and that is out of scope.

The audio callback is real-time C++: no allocation, no locks, no JNI, no I/O. It pops
per-track rings, runs the effect chains, applies smoothed gain and pan, sums into the
master, and pushes raw input to the record ring. A disk thread renders clips from take
files into the per-track rings ahead of the playhead. A record thread drains the record
ring into WAV writers. Control threads (JNI) edit the track registry under a mutex;
structural changes swap an atomic pointer and wait a callback tick before freeing.
Seeks and timeline edits bump a generation counter: the callback adopts the new
position and stops popping, the disk thread flushes and re-renders, then publishes
"primed". UI and engine talk through lock-free queues and atomics. The header comment
in `AudioEngine.h` has the full thread contracts.

Recording is capture-first: input lands in take files regardless of playback state, a
yanked cable finalizes the takes rather than losing them, and the controller collects
them into clips afterwards.

Software input monitoring is a per-track opt-in. Armed tracks always meter their
input; with monitoring on, the input also passes through the chain, the fader and the
master. The Duo's hardware direct-monitor knob remains the zero-latency path.

Latency compensation when placing takes is per output route. The interface path uses a
measured constant (about 10 ms on the Duo, 480 frames at 48 kHz). Every other route
uses what its open streams report, learned on first use and remembered per route.
Bluetooth runs 150 to 300 ms against USB's 10, so one global constant would drop a
Bluetooth overdub a fifth of a second late.

I/O is device-aware. A track's input is a (device, channel) pair; the track screen
lists every connected interface with its channels plus the phone mic, and the engine's
single input stream follows whichever device the armed tracks reference. Output is a
list on the master screen: Automatic plus every connected playback endpoint
(interfaces, wired headphones, Bluetooth A2DP and LE, HDMI, the phone speaker).
Bluetooth SCO is excluded on purpose: it is the 8/16 kHz call path, and opening it
drags the whole phone into communication routing. Automatic takes the first in this
order: interface, wired, Bluetooth, speaker. Device ids must be explicit, because
Android routes "default" to USB. The phone mic opens with the Camcorder preset plus a
software boost (default +16 dB, up to +32) applied to monitoring, meters and takes;
interfaces keep the Unprocessed preset with gain exactly 1.0, which is the bit-perfect
path.

The Duo over Android's USB stack: input 1 or 2 channels, 16-bit, 11.025 to 48 kHz;
output 2 channels, 16-bit, 32/44.1/48 kHz. So the engine runs at 48 kHz, 16-bit, and
takes land as 16-bit WAVs.

## Effects

Per-track serial chains, processed in the engine. Each slot has bypass and wet/dry
(smoothed engine-side), and the chain can be reordered, which rebuilds it and briefly
resets plugin state. Third-party effects load from a folder of `.so` files. The format
is LADSPA: a stable single-header C ABI, a large body of GPL plugins that compile for
arm64, and control ports only, so no plugin GUIs.

## Model and storage

Kotlin owns the truth. `Song` is an immutable model, serialized to `project.json` on
every edit (conflated background writes, atomic rename, `.bak` rotation). One folder
per song beside an `audio/` directory of takes. The engine only ever sees a flattened
copy per track. On disk and in code, recordings are called takes.

## Things that bite

AAudio does no device enumeration. Enumerate USB devices in Kotlin through
`AudioManager.getDevices()` and pass the id to the engine.

Streams must be released when the app leaves the foreground. An Oboe stream opened
Exclusive on an explicit device id pins the platform's routing there for every app on
the phone, and an open capture stream keeps policy off A2DP. A backgrounded app that
keeps its streams forces the whole phone onto whatever it last opened.
`MainActivity.onStop` closes them and `onStart` reopens them. Never close mid-take: a
live capture stream closed under a recording truncates it, so a busy transport keeps
its streams. Rotation does not go through `onStop`.

The engine and its track registry outlive song switches and stream closes; rotation
depends on that. Anything that opens a song must go through `SongController.start()`,
which calls `Engine.openSong(gen)` first. That wipes everything song-scoped and adopts
the controller's generation. Skipping it leaves the previous song's take readers open,
and its clips stay audible under an empty timeline.

USB audio does not work in the emulator. Duplex work needs a phone and an interface.
Anything that has not run on the device is unverified and should be reported as such.

`abiFilters` is arm64-v8a only. Widen it before a wider release.

Third-party plugin `.so` files load into the app process. A broken plugin can take the
app down with it.

## License and naming

GPL-3.0, which keeps the Ardour `a-*` DSP port open. The package id is `org.paw.app`.
minSdk 31 (Android
12), because AAudio's 24/32-bit formats need it; compileSdk and targetSdk 36.

## Building and testing

JDK 17 or newer, an Android SDK with NDK 28 and CMake 3.31, and a device on Android 12
or newer. `./gradlew assembleDebug` builds; `adb install -r` upgrades in place and
keeps the songs as long as the signing key matches. Back up
`/sdcard/Android/data/org.paw.app/files/projects/` with `adb pull` before an install
you are not sure about.

`tools/snapshots.sh` renders every screen on the JVM (Robolectric plus Roborazzi, no
device) to `app/snapshots/`, both orientations at Pixel 9a size. It is the fast loop
for UI work; only touch feel needs the phone. `ShadowEngine` in the test sources
replaces the JNI engine with deterministic fakes; keep its effect catalog in step with
`BuiltinEffects.cpp`.

Host-side C++ checks live in `tools/`: `engine_sanity.cpp` (WAV round trips, timeline
placement, mixdown) and `effects_sanity.cpp` (DSP behaviour per effect). The build line
for each is in its header. Run them after touching the pure C++ layers.

`WaveLinesTest` pins the waveform column arithmetic for trimmed clips.
