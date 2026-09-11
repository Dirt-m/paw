# Interface

How the app is laid out and why. `docs/scope.md` says what the app does; this file
says where each thing lives on the phone and what rule put it there. Read it before
moving a control.

## The one rule

Every action has a visible control. Gestures are shortcuts, never the only way.

The rest follows from the phone. The reference device is a Pixel 9a, 411 dp wide in
portrait. That width holds about three finger-sized controls beside a track name, so
anything beyond arm, mute, solo and a meter moves off the timeline row and onto a
screen of its own.

## Screens

**Songs.** The list. One primary action, New song. Each row opens on tap; rename and
delete sit behind the row's own menu, and delete asks whether the recordings go with
the song or stay on disk.

**Song.** Three bands, top to bottom:

- Top bar: back to Songs, the song name, what the engine is listening to (a green DUO
  or MIC, red LOST when the interface goes away), undo, redo, and a menu with
  Recordings, Export mixdown and Settings. Under it, on a phone, the Tracks / Mix tabs.
- The workspace, either the timeline or the mixer.
- The transport, at the bottom where the thumb rests: the clock, Click and Loop above,
  then return-to-start, Play/Stop and Rec as the biggest buttons on the screen. A thin
  master meter runs along the top edge of the transport and turns red at the far right
  when the mix clips.

When a clip is selected, a row of clip actions (Split, Duplicate, move up or down a
track, Delete) appears between the workspace and the transport. It exists only while
something is selected, so it can never be mistaken for a permanent control.

**Tracks (timeline).** Each lane has a header with the track name, arm, mute, solo and
a level meter. The whole header is a button, and the chevron after the name says so;
it opens the track's screen. Armed tracks show their input source next to the name
(DUO 1, MIC), which is the moment that information matters. Volume is not in the
header: at phone width a header fader is 90 dp long and imprecise, and it crowds
everything else. Add track is a labelled row under the last lane.

The ruler seeks on tap. The loop region is drawn as a green band with a tab at each
end; drag a tab to resize it. Hold and drag anywhere on the ruler to paint a new one.
With no region marked, the Loop button makes one around the selected clip, or around
the whole song, so Loop never does nothing.

Clip gestures: tap to select, drag the body of the selected clip to move it, its lower
edges to trim, its top corners to fade; elsewhere a drag pans and a pinch zooms. The
selected clip draws what it offers: an amber grip pill straddles each edge on the
lower half (trim), a green ring sits at the top end of each fade ramp (drag it
sideways), and the silent wedge under a fade is shaded. When the playhead leaves the
view, a small arrow chip appears on the lane edge nearest to it; tapping it brings the
view back and re-engages follow. There is no permanent Follow toggle.

**Mix.** One channel strip per track, master pinned on the right. The strips exist
because a phone is tall: a vertical fader gets 500 dp of travel in portrait, which
makes fine level work possible in a way a header fader never could. Each strip has the
name (a button, same chevron), a long fader beside a meter, the dB readout, pan, arm,
mute and solo. The master strip has the fader, meter and mute. Strip width is computed
so a whole number of strips fills the space beside the master (three on the 9a in
portrait); the next strip starts exactly at the master's edge, never half visible. In
landscape the buttons sit beside the fader instead of under it, so the fader keeps its
height.

**Track.** Opened from either view. Level (volume, pan, monitor), then Input as a
single radio list (each connected interface's Input 1, Input 2 and Inputs 1+2, then
Phone microphone with its boost slider, then No input), then Effects. Effect cards show
the plugin's controls, an On/Off toggle, reorder arrows when there is more than one,
and a small Remove. Add effect is a full-width button under the chain; it opens the
plugin list in place. Remove track is the last thing on the screen and confirms inline.
Rename is a button in the top bar. Wide windows put level and input beside the chain.

**Master.** The same screen with Output (Automatic, showing what it resolves to, then
every connected playback endpoint) where Input would be, and no pan.

**Recordings.** Every recording in the song, grouped by track, newest first. A row is a
length, a date, and whether it is on the timeline; file names stay on disk. Play
auditions, Add puts it on a track at the playhead, and ✕ (only on unused recordings)
deletes from disk after a confirmation.

**Settings.** Segmented controls, one per row: keep awake, follow style, click, tempo
(stepper plus slider), beats per bar, count-in, peak hold, mic boost. Then a read-only
Audio block (input, output, latency compensation) and the effects folder path.

## Conventions

- Touch targets are 40 dp or more; transport buttons are 52 dp.
- Controls are rounded (6 dp) so they read as pressable; surfaces (lanes, panels,
  lists) are square so they read as workspace.
- Amber is the accent and means "this is the primary thing here". Red means recording
  or clipping. Green means an active mode (solo, loop, an input that is open).
- Words are set in the system sans; numbers that change under the eye (the clock, dB,
  times) are monospace so they do not jitter.
- Destructive actions (remove track, delete recording, delete song) confirm inline,
  never in a dialog, and never sit at the top of a screen.
- No explainer text. A control's label is its whole explanation; if that is not
  enough, the control is wrong.

## Layout rules

`COMPACT_WIDTH` (640 dp) decides whether panels stack; `SHORT_HEIGHT` (520 dp) decides
whether the transport collapses to one row and the lanes shrink. A phone is compact in
portrait and short in landscape. Lanes grow to fill the viewport up to 128 dp and never
go below 84 dp (72 dp when short), after which the list scrolls.

## Verifying

`tools/snapshots.sh` renders every screen, both orientations, at Pixel 9a size to
`app/snapshots/`. It covers layout and state; it does not cover gesture feel, which
needs the phone.
