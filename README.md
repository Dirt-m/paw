# PAW!

A pocket audio workstation: a multitrack recorder for Android that works with class-compliant USB audio
interfaces. Plug in an interface, arm a track, press record. Non-destructive editing,
per-track mixing, a master bus, LADSPA effects, and mixdown to WAV. Nothing else.

## Status

Pre-alpha. The planned feature set is complete and in daily use on one rig, a Pixel 9a
with an M-Audio M-Track Duo. Other phones and interfaces are untested. `docs/status.md`
lists what has been checked on hardware, what has not, and the known rough edges.

## What it does

- Songs are folders of immutable recordings. A take is written to disk as the hardware
  delivered it and never rewritten. All editing is by reference, and undo covers every
  edit.
- A timeline with waveforms: move, trim, split, duplicate, fade, move between tracks.
- Per-track volume, pan, mute, solo, arm and software monitoring, plus a master bus.
- Two-channel full-duplex recording at 48 kHz, 16-bit, with punch-in and
  latency-compensated placement of takes.
- Metronome with count-in, and gapless loop playback.
- Input from any channel of a connected interface or from the phone mic. Output to an
  interface, wired or Bluetooth headphones, or the speaker.
- Eight built-in LADSPA effects (3-band EQ, compressor, delay, reverb, chorus, gate,
  limiter, filter) and a folder for third-party arm64 plugins. Each slot has bypass,
  wet/dry and reordering.
- Mixdown of the master bus to a WAV, handed to the share sheet.
- A recordings browser listing every take by track, with audition, place on track,
  and delete from disk behind a confirmation.

## Building

JDK 17 or newer, an Android SDK with NDK 28 and CMake 3.31, and a device on Android 12
or newer.

    ./gradlew assembleDebug

USB audio does not work in the emulator, so duplex testing needs a phone and an
interface. `tools/snapshots.sh` renders every screen on the JVM without a device.

## Docs

- `docs/scope.md`: what is in and what is out.
- `docs/engineering.md`: architecture, the audio path, the rules the code follows,
  and the things that bite.
- `docs/ui.md`: where every control lives and why.
- `docs/status.md`: what is verified on hardware, what is not, known issues, and a
  code map.

## License

GPL-3.0. See `LICENSE`.
