# Status

What is built, what has been tried on hardware, what has not, and what to check
first.

## Built

Everything in `docs/scope.md`: songs as folders of immutable takes, a persistent
full-duplex engine with a transport, per-track arming and capture, a timeline with
waveforms and reference-only clip editing (move, trim, split, duplicate, fades, move
between tracks), undo and redo, per-track volume, pan, mute, solo and monitoring, a
master bus, device-aware input and output routing, latency-compensated take placement
per output route, punch-in, count-in, metronome, gapless loop playback, eight LADSPA
built-ins plus a plugin folder, mixdown to the share sheet, and a recordings browser.

## Verified on the phone

On a Pixel 9a with an M-Audio M-Track Duo, in daily use:

- Full-duplex audio at 48 kHz, 16-bit, about 10 ms round trip on both channels.
- Recording to takes, playback, timeline editing, arming, input metering.
- Phone-mic recording with the boost; device-aware input selection.
- Touch handling: reliable clip select, move and trim after repeated edits, scroll
  from anywhere, pinch zoom on lanes.
- The Tracks and Mix views, the clip handles and the playhead chip, in both
  orientations.

## Not yet verified on the phone

Built, and passing whatever host-side check exists, but nobody has listened to them or
pressed them on the device. In rough order of risk:

1. Ruler gestures: tap to seek, drag to pan, hold and drag to paint a loop, dragging a
   loop tab to resize it.
2. Mix faders under playback. Bluetooth playback while recording through the Duo (two
   clock domains; listen for drift over a long take). Backgrounding the app returns
   the rest of the phone to Bluetooth. Backgrounding mid-take keeps recording.
3. Effects: live parameter sweeps without zipper noise, bypass and wet/dry without
   clicks, reorder mid-play, a third-party `.so` loading, mixdown with reverb,
   chorus, gate, limiter and filter in the chain.
4. Count-in take placement against the click (record to the click, check alignment
   in another DAW), and the same over a Bluetooth route.
5. Long loop runs (drift, seam, underruns over minutes).
6. Unplugging the Duo mid-record: takes finalize, no ANR, reopen works. `kill -9`
   mid-record: the take is readable and placeable afterwards.
7. Rotation mid-play and mid-record.
8. The debug build's real-time allocation tripwire staying quiet through Oboe's read
   path. An abort in the callback would be a real finding.
9. The dropdown menus (song menu, song-row menu), which the snapshot harness cannot
   capture.

## Known issues

- Playback stalls briefly (ring re-prime) after seeks and timeline edits. During
  recording, capture continues through such stalls, so a stall mid-record shifts what
  the musician hears, not what lands on disk.
- Timeline edits during recording sync clips but skip the ring flush, so a moved clip
  keeps playing its old audio for up to about 0.7 s.
- Chain reorder rebuilds the chain, so reverb tails and delay lines reset. Bypassed
  units freeze their state and resume stale.
- Count-in only applies when recording from stop; punch-ins are live.
- Take audition on the Recordings screen uses MediaPlayer on whatever route Android
  picks; with the engine holding an exclusive USB stream this path is unverified.
- The Recordings screen reads WAV headers per file on open. Fine at tens of takes,
  revisit at hundreds.
- Fades crossing a split point clamp rather than translate; the split invariant only
  holds exactly for fade-free cut points.
- Mixdown blocks its I/O thread with no progress readout.
- There is no in-app way to measure the interface latency constant. The value lives in
  preferences, default 480 frames.
- `abiFilters` is arm64-v8a only.
- `versionCode` has never been bumped from 1, so installed builds are only
  distinguishable by APK hash.

## Code map

- `app/src/main/cpp/AudioEngine.{h,cpp}`: the persistent engine. Read the header
  comment first; it documents the thread contracts.
- `app/src/main/cpp/Timeline.{h,cpp}`: `ClipRef` and the clip-window renderer shared
  by the disk thread and mixdown.
- `app/src/main/cpp/WavFile.{h,cpp}`, `Peaks.{h,cpp}`, `RingBuffer.h`, `Mixdown.{h,cpp}`,
  `EffectHost.{h,cpp}` (LADSPA host), `effects/` (the built-ins and `ladspa.h`),
  `jni_bridge.cpp`.
- `app/src/main/kotlin/org/paw/app/SongController.kt`: every edit goes through
  here: model update, persist, push to engine. Also record collection, peaks cache,
  mixdown, undo.
- `model/`: `Song.kt` (model and JSON), `ProjectStore.kt`, `InputDevice.kt`,
  `EffectCatalog.kt`.
- `Engine.kt` (JNI), `AudioDevices.kt` (endpoint enumeration), `AppPrefs.kt`,
  `Latency.kt`, `MainActivity.kt` (navigation, permissions, stream lifecycle).
- `ui/`: `SongScreen.kt` (top bar, tracks view, selection row, transport),
  `Timeline.kt` (view window, ruler, lanes, waveforms), `MixerView.kt`,
  `TrackDetailScreen.kt`, `RecordingsScreen.kt`, `OptionsScreen.kt`,
  `ProjectListScreen.kt`, `Widgets.kt`, `Theme.kt`.
- `app/src/test/kotlin/.../snap/`: the JVM snapshot harness (`ShadowEngine`,
  `Fixtures`, `ScreenSnapshots`). `app/src/test/kotlin/.../ui/WaveLinesTest.kt`.
- `tools/`: `snapshots.sh`, `engine_sanity.cpp`, `effects_sanity.cpp`.
