# Scope

What PAW! does before it can be called finished. Everything here is in. Everything
not here is out until this ships.

## The idea

A minimal recorder for Android that works with a class-compliant USB audio interface.
Plug in, arm a track, press record. It has the DAW essentials (recordings,
non-destructive editing, per-track mixing, a master bus) and nothing else. Reference
hardware is an M-Audio M-Track Duo: two in, two out, with a hardware direct-monitor
knob.

## Tracks

No track-count limit. The master is a separate track with the same control surface,
and every track routes to it. No buses or sends beyond the master.

## Recording

Two tracks at once (one per interface input, or one stereo track). Per-track arming;
armed tracks capture on record. Punch-in and punch-out while playing. A count-in of
0, 1 or 2 bars when recording from stop.

A recording is never lost by editing. Every take lands on disk as an immutable file
before any edit touches it, and all editing is by reference. The only path that
removes audio is the user's own confirmed delete on the Recordings screen, which
refuses while any clip still uses the file. A record pass that captured nothing leaves
no file.

## Per-track controls

Volume, pan, mute, solo, arm. Software input monitoring as a per-track switch: armed
tracks always meter their input, and with monitoring on the input passes through the
effects, the fader and the master.

Input is device-aware. A track's input is one channel, or the stereo pair, of any
connected interface, or the phone's own microphone. The phone mic and speaker are
usable while an interface is connected and used automatically when none is. The phone
mic gets an adjustable software boost (default +16 dB, up to +32) because it is
otherwise inaudible. Interface takes stay bit-perfect.

Output is chosen on the master screen: automatic, or any connected playback endpoint
(interfaces, wired and Bluetooth headphones, the phone speaker).

## Editing

All non-destructive: drag a clip, trim its edges, split it at the playhead, duplicate
it, move it to the neighbouring track, and give it linear fades at either end. Undo
and redo cover timeline, mixer and effect edits, one entry per gesture. Long-pressing
any slider resets it to its default.

## Song

Both orientations. Track lanes with waveforms that stay readable on quiet takes.
Transport: play, stop, record, return to start, seek. A metronome with tempo and beats
per bar stored in the song, and a loop region that plays back without a gap at the
wrap (recording stays linear). Timeline zoom is remembered per song. The screen stays
awake while recording and for five minutes after the last touch, adjustable.

Tracks and songs can be renamed. Deleting a song asks whether its recordings go with
it.

## Recordings

Every take in a song is browsable, grouped per track, newest first, with its length.
Each can be auditioned, placed on a track at the playhead, or deleted from disk behind
a confirmation.

## Effects

Per-track serial chain. Eight built-ins: 3-band EQ, compressor, delay, reverb, chorus,
gate, limiter, filter. Each slot has bypass and wet/dry, and the chain can be
reordered. Third-party effects load from a folder on device storage. The format is
LADSPA: a stable C ABI, a large body of GPL plugins that compile for arm64, control
ports only, no plugin GUIs.

## Mixdown

An offline render of the master bus to one stereo WAV, handed to the Android share
sheet. Compressed formats come later.

## Meters

dB scale from -48 to +6 dBFS: green below -12, orange up to the red 0 dBFS line, red
past it, with the overload zone tinted even when idle. A peak-hold tick marks the
loudest recent peak and latches red once it clipped. The hold time is a setting.

## Interface

Minimal, very readable, and not generic. Sensible defaults over settings screens. No
persistent explainer text: no inline tutorials, no captions restating what a control
does. Touch behaves like every other Android app: scrolling works from wherever the
finger lands, drags lock to a direction, pinch zooms. Every action has a visible
control, and gestures are shortcuts, never the only way. `docs/ui.md` has the layout.

## Settled technical decisions

48 kHz, 16-bit, which is what the M-Track Duo delivers over Android's USB path; takes
are written exactly as the stream delivers them. A project is one folder per song: a
`project.json` manifest beside an `audio/` directory of takes. Minimum Android version
is 12 (API 31). License is GPL-3.0.

## Out of scope for now

Automation. MIDI. Buses and sends beyond the master. Background recording through a
foreground service. Wiring beyond the serial effect chain. Plugin GUIs.
