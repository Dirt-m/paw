# Interface

How the app is laid out and why. `docs/scope.md` says what the app does; this file
says where each thing lives on the phone and what rule put it there. Read it before
moving a control.

## The rules

The song never leaves the screen while music is being made. Everything about a song
happens on one screen: one mode fills the middle, at most one panel opens over it, and
the transport stays at the bottom through all of it. Nothing a musician opens stops the
song, hides the playhead or loses the meters. Only the song list and Settings are
screens of their own.

Every action has a visible control. Gestures are shortcuts, never the only way.

A control means one thing. Track headers hold toggles and nothing that navigates;
navigation lives in the mode bar and in names you can tap.

Controls sit next to what they change: a sample's level on its row in the kit, a
track's input on its header, a clip's pattern on the clip's bar.

The reference phone is a Pixel 9a, 411 by 923 dp, used mostly in portrait. That leaves
about 866 dp of height under the status bar, and every layout is checked against it
with real content. Landscape leaves about 360 dp, and every layout works there too.

## The song screen

Top to bottom in portrait:

- **Top bar.** Back (one step, see below), the song's name (tap to rename), what the engine
  hears (a green DUO or MIC, red LOST when the interface goes away; tap for the I/O
  panel), undo, redo, share (renders the mixdown and opens the share sheet) and a menu
  with the three rare things: Rename song, Import audio, Settings.
- **The mode bar.** Tracks, Mix, Beats, right under the top bar.
- **The workspace.** The current mode, or a panel over it.
- **The clip bar.** Only while a clip is selected: the clip's actions, over the
  transport.
- **The transport.** The clock, the tempo, Click and Loop, then return to start, Play
  and Rec as the biggest buttons on the screen. A thin master meter runs along its top
  edge and turns red at the right when the mix clips.

In landscape the mode tabs move into the top bar and the transport becomes one row,
so the lanes keep their height. The clip bar stays over the transport as one row.

A panel covers the whole workspace in portrait and is a 340 dp column on the right in
landscape, with the mode still working beside it. The mode stays alive under a panel:
its scroll position, its choices and a looping pattern are there when the panel closes.
Back closes the panel first, then clears the clip selection, then returns to Tracks,
and only then leaves the song. The back button in the top bar takes the same steps as
the phone's back gesture, so it never leaves the song while there is something to
close.

## Tracks

The ruler block sits over the lanes. Its corner holds Fit (show the whole song) and
Snap (Bar, Beat or Off, tap to cycle); − and + zoom around the playhead. Above the
ruler in portrait (landscape gives that height to the lanes), the overview strip draws the whole song with a frame around the part
on screen; touch or drag it to move there. The ruler counts bars from the song's tempo
and meter, with beats as ticks when there is room, and minutes and seconds when the
clock is switched to time. Tap the ruler to seek. The loop is a green band with a tab
at each end; drag a tab to resize, hold and drag anywhere to paint a new one, and the
✕ beside it clears it. Loop edges snap.

Each lane has a header, 128 dp wide in portrait, with room for a name on two lines. The
name opens the track's inspector; beside it, an audio track's input button shows the
channel (1, 2, 1+2, MIC, OFF; the device is on the top bar) and opens the inspector at
the input list. Under them, four 40 dp toggles: arm, monitor, mute and solo, and a
level meter down the right edge. In landscape the input button and the four toggles
make one row under the name. A beat track's header has its name, how
many sounds its kit holds, mute and solo. Tapping anywhere else on a header, or on
empty lane space, selects the track, which is what Beats, Takes and new samples act on.
The add row (+ Audio track, + Beat track, Takes) comes after the last lane and
scrolls with the lanes.

Clips: tap to select, drag the body to move, the lower edges to trim, the top corners
to fade. Moving an audio clip snaps the distance it moves, so the take stays in time
with itself; trims, splits, loop edges and pattern clips snap to the grid. A selected
beat lane shows a ghost button at the next bar that places the current pattern there.
Elsewhere a drag pans and a pinch zooms. When the playhead leaves the view, an arrow
chip on the lane edge brings it back; starting the transport or seeking brings it
back too.

Where audio clips overlap on a track, the newest is on top and the only one heard. The
clip's bar lists the takes under it and brings any of them back to the top.

The clip bar shows every action at once: two rows in portrait, one when the window
is wide. Nothing hides behind a More button. The first row starts with ✕ to deselect.
For an audio clip it then has Split, Duplicate and Delete, and the second row has
Sample to kit, Loop clip, ▲ and ▼ to move the clip a track up or down, and Takes when
there are takes underneath. For a pattern clip the first row has its pattern (swap, or
a new variation for this clip only), how many times it repeats (− and +) and Edit,
which opens Beats on it; the second row has Split, Duplicate, Delete, Loop clip, ▲
and ▼.

The clock reads bars and beats (3.2 with the hundredths small beside it); tap it to
read minutes and seconds, and the ruler follows. During a count-in it shows the beats
left. The tempo button shows the tempo over the meter and opens the Tempo panel.

## Mix

One strip per track, the master pinned on the right, and a column with + Audio and
+ Beat after the last strip. When everything fits, a whole number of strips fills
the space (three and the master in portrait). When it does not, the strips widen so
about a third of the next one shows at the edge (two and a third in portrait), so the
row plainly goes on. In portrait a row of track chips above the strips shows every
track, outlines the ones on screen and scrolls there on a tap. A strip has the name (opens the inspector),
an FX button with the effect count (opens the inspector on its effects), a long fader
beside a meter, the dB value, pan, and mute and solo. Arm and monitor are not here:
they live on the lanes and in the inspector. In landscape mute and solo stack beside the
fader so it keeps its height.

Faders and sliders move by the distance the finger travels, from wherever they are, so
grabbing one never makes it jump. A double tap on a slider or on its value resets it.

## Beats

Beats works on one beat track at a time, chosen from the chips along the top (+ Beat
track adds one; nothing is created by opening the mode). Under them, the pattern (tap
for the list: New, Copy, Rename, Delete, refused while the pattern is placed), its meter
(7/8 · 2+2+3 · 1 bar, tap for the meter panel), ▶ to loop the pattern on its own, and
Place at bar n.

In portrait Steps and Pads are two views, because one surface cannot hold a normal kit.
Steps is the grid: a row per sound, a cell per step, groups shaded and beats numbered,
filling the width. Tapping a sound's name plays it and never touches a step. The Kit
toggle turns each row into the sound's name (tap to rename), its level, ▶ and ✕, so a
kit is balanced where it is programmed. Pads shows the kit as large pads that sound the
moment they are touched, one finger each. In landscape the pads sit on the left and the
grid on the right.

The transport's Play always plays the song, and stops a pattern loop.

## Panels

**Inspector.** A track's name with ‹ and › to step through the tracks and the master,
a level bar, then Track and FX tabs beside mute, solo and monitor. Track: volume, pan,
the input list (each interface's inputs, the phone microphone, no input; the mic links
to its boost), rename, and Remove track last, confirmed in place. A beat track has Open
in Beats instead of inputs. The master has volume, mute and its output. FX: the effect
chain as cards with On, every control, wet and dry, reorder and Remove, then Add effect,
which lists every effect in place.

**Takes.** Every recording in the song, grouped by track, newest first, imported files
in their own group, each with its waveform, length and date. Play auditions; Add puts it
on a chosen track at the playhead, or at its original time when that is known; a take on
the timeline says where and goes there on a tap; ✕ deletes an unused take from disk
after a confirmation, never while a take is being recorded. Import opens Android's file
picker.

**Tempo.** The tempo large, with − and +, a slider and Tap. Beats per bar, the beat
unit (4 or 8), count-in and click.

**I/O.** The input in use, the phone mic boost, the output list (Automatic and every
connected endpoint), the latency, and a warning when the output is Bluetooth.

**Sample to kit.** From a selected audio clip: a name, which beat track to put it on
(or a new one), and Make sample, which opens Beats on that kit.

**Meter.** A pattern's beats, beat unit, grouping, bars and steps per beat.

## Songs and Settings

**Songs.** The list. New song makes a song and opens it at once, named after the moment
("Idea 1 Nov, 14.05"), with one track armed when an interface is connected. Each row
shows the song in miniature beside its name, track count, length and when it changed;
its ⋮ opens Rename and Delete in the row, and delete asks whether the recordings go
with the song.

**Settings.** Only what applies to every song: keep awake, follow style, peak hold, a
read only Audio block, the songs folder and the effects folder.

## Conventions

- Touch targets are 40 dp or more; the transport's buttons are 52 dp.
- Controls are rounded (6 dp) so they read as pressable; surfaces are square so they
  read as workspace.
- Lilac is the accent: the selected thing and the primary action. Green means an
  active mode (solo, loop, click, monitoring, an open input). Red means recording,
  clipping or removal. Yellow means mute and warnings. Messages are neutral.
- Words are set in the system sans; numbers that change under the eye (the clock, dB,
  times) are monospace so they do not jitter.
- Panels, pickers and confirmations are drawn in the layout, never as popups.
- A list that runs past its edge fades into the background on that edge. Lists of
  big items (lanes, mixer strips) also size them so the last one on screen is cut part
  way, never exactly at the edge, where it would look like the end.
- No explainer text. A control's label is its whole explanation; an empty state may say
  in one line how to fill it.

## Layout rules

`COMPACT_WIDTH` (640 dp) decides whether a panel covers the workspace or sits beside
it; `SHORT_HEIGHT` (520 dp) decides whether the mode tabs and clip actions move into
the top bar and the transport collapses to one row. A phone is compact in portrait and
short in landscape.

## Verifying

`tools/snapshots.sh` renders every mode, panel and state in both orientations at Pixel
9a size to `app/snapshots/`. The snapshot tests live in one file per area on a shared
harness. They cover layout and state; touch feel needs the phone.
