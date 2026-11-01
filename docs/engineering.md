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
caught by it. And the user can delete a recording from disk in the Takes panel,
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

Overlapping clips on an audio track do not sum. A track's clip list is a stack in
list order, and new takes are appended, so after a punch-in only the new take is heard
where it covers the old one, and the old clip plays on wherever it is uncovered. Both
clips stay in the song. Where the audible take changes inside an older clip, the top
clip's edge ramps over at least 240 frames (5 ms at 48 kHz) so the switch is a linear
crossfade rather than a click; a longer fade on that edge is the crossfade itself and
reveals the take underneath instead of dipping to silence. An edge with nothing under
it keeps exactly its own fade, and clips that only abut are untouched, so a split
still renders bit for bit. The order is carried unchanged from the song model through
`Engine.syncClips` and the JNI bridge, and the timeline draws in the same order, last
on top. `TimelineRenderer` does the compositing on the disk thread, and the offline
mixdown uses the same renderer, so a bounce matches playback. Pattern clips on beat
tracks play through the sampler and are not part of this.

Recording is capture-first: input lands in take files regardless of playback state, a
yanked cable finalizes the takes rather than losing them, and the controller collects
them into clips afterwards.

Software input monitoring is a per-track opt-in. Armed tracks always meter their
input; with monitoring on, the input also passes through the chain, the fader and the
master. The Duo's hardware direct-monitor knob remains the zero-latency path.

Latency compensation when placing takes is per output route. The Duo uses a measured
constant (about 10 ms, 480 frames at 48 kHz); other interfaces have not been measured
and use what their streams report. Every other route
uses what its open streams report, learned on first use and remembered per route.
Bluetooth runs 150 to 300 ms against USB's 10, so one global constant would drop a
Bluetooth overdub a fifth of a second late.

I/O is device-aware. A track's input is a (device, channel) pair; the inspector
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
output 2 channels, 16-bit, 32/44.1/48 kHz. So the engine runs at 48 kHz, and Duo
takes land as 16-bit WAVs. The engine takes whatever sample format the input stream
delivers and writes the take in that format, so a 24-bit interface gives 24-bit takes.

The Xvive XV1-R is the second interface in view. Its USB Audio mode is UAC2, stereo in
and out, fixed at 48 kHz, 24-bit; it records from its line in when something is
plugged in and from its own mics otherwise, so the same mode covers "USB mic" use. Its
mass storage mode turns its microSD card into a drive, and recordings are imported
from there (`/XVIVE/XV_0001.wav` and on). The mode is chosen on the recorder at power
on, so it cannot be an interface and a card reader at once.

## Sampler

Finger drumming has to answer within a few milliseconds of the touch, so samples play
from memory, not from the disk thread's rings, which run up to about 0.7 s ahead.
A control thread reads every beat track's samples into float buffers and publishes
them as one bank. The audio callback owns a fixed pool of voices and never allocates;
a voice is a bank index, a read position, a start delay and a gain. When too many
sound at once, the oldest one fades out over 2 ms.

A touch reaches the callback as a trigger on a lock-free queue (sample, velocity), and
the next burst starts a voice at its first frame. Programmed hits go through the same
voices. `SongController` expands every pattern clip into hits in timeline frames
(repeating the pattern over the clip, offset by the clip's start into it) and pushes
the sorted list whenever the song changes; the callback starts each hit that falls in
the burst at its exact frame, wrapping with the loop the same way the playhead does.
Each voice mixes into its beat track ahead of the effect chain, the fader and the
master. Offline mixdown runs the same voice pool over the same list.

A pattern being edited loops on its own through a second hit list with a loop length.
It plays only while the transport is stopped, and replacing it keeps its place, so
editing the grid never restarts it.

Pattern hits are stored in ticks (960 to a quarter note), so a pattern keeps its shape
at any tempo. A pattern clip's own fields are frames, like any clip, so the clip edits
stay shared with audio clips; a tempo change rescales pattern clips and leaves audio
clips alone.

The bank and the hit list are pushed on one serial background dispatcher, so a slow
bank load can never land after the hits computed for the next one.

The weak link is the touch, not the engine. Android delivers touch events tens of
milliseconds after contact, and nothing in the app can recover that.

In the UI a sampler sound is a sample. In code a sampler sound is a `Sample` and one
value of audio is a frame or a value, so the two never share a word.

## Effects

Per-track serial chains, processed in the engine. Each slot has bypass and wet/dry
(smoothed engine-side), and the chain can be reordered, which rebuilds it and briefly
resets plugin state. Third-party effects load from a folder of `.so` files. The format
is LADSPA: a stable single-header C ABI, a large body of GPL plugins that compile for
arm64, and control ports only, so no plugin GUIs.

## Model and storage

Kotlin owns the truth. `Song` is an immutable model, serialized to `project.json` on
every edit (conflated background writes, atomic rename, `.bak` rotation). Settings that
belong to one song (click, count-in, mic boost) are in the model; settings that apply
to every song are in `AppPrefs`, written the moment they change. One folder per song
beside an `audio/` directory of takes.

Songs live in `Documents/PAW` so the Files app and a computer can reach them. The app
creates them without a storage permission, which Android allows for files an app owns.
The catch: after an uninstall, a reinstalled app no longer owns those files and cannot
read the manifests, so songs would need to be brought back by hand. `adb install -r`
and in-place updates are fine. Older installs kept songs in the app's private folder;
`ProjectStore.migrate()` copies each song over, checks every file's size, and only
then removes the old copy. A song that fails stays where it was and is still listed.
A `.nomedia` file keeps music players from listing takes. Imported files go through
`model/TakeImport.kt`. The engine only ever sees a flattened
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
`/sdcard/Documents/PAW/` with `adb pull` before an install you are not sure about.

`tools/snapshots.sh` renders every screen on the JVM (Robolectric plus Roborazzi, no
device) to `app/snapshots/`, both orientations at Pixel 9a size. It is the fast loop
for UI work; only touch feel needs the phone. `ShadowEngine` in the test sources
replaces the JNI engine with deterministic fakes; keep its effect catalog in step with
`BuiltinEffects.cpp`.

Host-side C++ checks live in `tools/`: `engine_sanity.cpp` (WAV round trips, timeline
placement, take stacking, mixdown), `effects_sanity.cpp` (DSP behaviour per effect) and
`sampler_sanity.cpp` (slice loading, the voice pool). The build line for each is in
its header. Run them after touching the pure C++ layers.

`WaveLinesTest` pins the waveform column arithmetic for trimmed clips. `TakeImportTest`
covers the WAV import paths (bit exact copies, extensible headers, odd chunks, the
resampler's accuracy and aliasing) with synthesized files.
