package org.paw.app.snap

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Test
import org.paw.app.SongController
import org.paw.app.ui.SongUi
import org.robolectric.annotation.Config

// The Tracks mode: lanes, headers, ruler, clip bar, transport.

class TracksSnapshots : SnapshotBase() {

    @Test fun song_portrait() { songWith(controller()); snap("song-portrait") }

    @Test @Config(qualifiers = "+land")
    fun song_landscape() { songWith(controller()); snap("song-landscape") }

    /** Guitar's first clip selected: the clip bar in the mode bar's place. */
    @Test fun song_selected_portrait() {
        val c = controller(); c.selectedClip = 1 to 1; c.selectedTrack = 1
        songWith(c); snap("song-selected-portrait")
    }

    @Test @Config(qualifiers = "+land")
    fun song_selected_landscape() {
        val c = controller(); c.selectedClip = 1 to 1; c.selectedTrack = 1
        songWith(c); snap("song-selected-landscape")
    }

    /** The beat track's pattern clip selected. */
    @Test fun song_pattern_selected_portrait() {
        val c = controller(); c.selectedClip = 5 to 5; c.selectedTrack = 5
        songWith(c); showBeatLane(); snap("song-pattern-selected-portrait")
    }

    @Test @Config(qualifiers = "+land")
    fun song_pattern_selected_landscape() {
        val c = controller(); c.selectedClip = 5 to 5; c.selectedTrack = 5
        songWith(c); showBeatLane(); snap("song-pattern-selected-landscape")
    }

    /** The pattern chooser swapped into the clip bar. */
    @Test fun song_pattern_chooser_portrait() {
        val c = controller(); c.selectedClip = 5 to 5; c.selectedTrack = 5
        songWith(c); showBeatLane()
        compose.onNodeWithText("Groove ▾").performClick()
        snap("song-pattern-chooser-portrait")
    }

    /** More on an audio clip: the rarer actions in the same one-row slot. */
    @Test fun song_clip_more_portrait() {
        val c = stackedController(); c.selectedClip = 1 to c.song.track(1)!!.clips.last().id
        songWith(c)
        compose.onNodeWithContentDescription("More").performClick()
        snap("song-clip-more-portrait")
    }

    @Test fun song_pattern_more_portrait() {
        val c = controller(); c.selectedClip = 5 to 5; c.selectedTrack = 5
        songWith(c); showBeatLane()
        compose.onNodeWithContentDescription("More").performClick()
        snap("song-pattern-more-portrait")
    }

    @Test @Config(qualifiers = "+land")
    fun song_clip_more_landscape() {
        val c = stackedController(); c.selectedClip = 1 to c.song.track(1)!!.clips.last().id
        songWith(c)
        compose.onNodeWithContentDescription("More").performClick()
        snap("song-clip-more-landscape")
    }

    /** The older takes under a newer one, from More. */
    @Test fun song_clip_takes_portrait() {
        val c = stackedController(); c.selectedClip = 1 to c.song.track(1)!!.clips.last().id
        songWith(c)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Takes 2").performClick()
        snap("song-clip-takes-portrait")
    }

    @Test @Config(qualifiers = "+land")
    fun song_clip_takes_landscape() {
        val c = stackedController(); c.selectedClip = 1 to c.song.track(1)!!.clips.last().id
        songWith(c)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Takes 2").performClick()
        snap("song-clip-takes-landscape")
    }

    /** The beat lane selected with nothing on it: the ghost offers the pattern. */
    @Test fun song_beat_lane_ghost_portrait() {
        val c = demoController(app) { ShadowEngine.playheadFrame = 48000L * 11 }
        c.selectedTrack = 5
        songWith(c); showBeatLane(); snap("song-beat-ghost-portrait")
    }

    @Test @Config(qualifiers = "+land")
    fun song_beat_lane_ghost_landscape() {
        val c = demoController(app) { ShadowEngine.playheadFrame = 48000L * 11 }
        c.selectedTrack = 5
        songWith(c); showBeatLane(); snap("song-beat-ghost-landscape")
    }

    private fun recordingController() = demoController(app) {
        ShadowEngine.transport = org.paw.app.TRANSPORT_RECORDING
        ShadowEngine.recording = true
        ShadowEngine.recStartFrame = 48000L * 2
        ShadowEngine.recFrames = (48000L * 3.5).toLong()
        ShadowEngine.playheadFrame = (48000L * 5.5).toLong()
        ShadowEngine.meters = ShadowEngine.meters + (1 to 0.94f)
    }

    @Test fun song_recording_portrait() { songWith(recordingController()); snap("song-recording-portrait") }

    @Test @Config(qualifiers = "+land")
    fun song_recording_landscape() { songWith(recordingController()); snap("song-recording-landscape") }

    @Test fun song_empty_portrait() { songWith(emptyController(app)); snap("song-empty-portrait") }

    @Test @Config(qualifiers = "+land")
    fun song_empty_landscape() { songWith(emptyController(app)); snap("song-empty-landscape") }

    @Test fun song_message_portrait() {
        val c = controller(); c.message = "Recording dropped 480 frames"
        songWith(c); snap("song-message-portrait")
    }

    @Test fun song_crowded_portrait() { songWith(crowdedController(app)); snap("song-crowded-portrait") }

    @Test @Config(qualifiers = "+land")
    fun song_crowded_landscape() { songWith(crowdedController(app)); snap("song-crowded-landscape") }

    @Test fun song_playhead_offscreen_portrait() {
        val c = demoController(app) { ShadowEngine.playheadFrame = 48000L * 40 }
        songWith(c); snap("song-playhead-offscreen-portrait")
    }

    /** The clock and the ruler in minutes and seconds. */
    @Test fun song_clock_time_portrait() {
        val c = demoController(app) { ShadowEngine.playheadFrame = (48000L * 6.4).toLong() }
        songWith(c, SongUi().apply { clockAsTime = true }); snap("song-clock-time-portrait")
    }

    /** A 7/8 song: bars of seven eighths on the ruler, the grid and the clock. */
    @Test fun song_seven_eight_portrait() {
        val c = demoController(app) { ShadowEngine.playheadFrame = (48000L * 6.4).toLong() }
        c.setMeter(7, 8)
        songWith(c); snap("song-seven-eight-portrait")
    }

    /** Zoomed in close enough for the ruler to number the beats. */
    @Test fun song_zoomed_in_portrait() {
        val c = demoController(app) { ShadowEngine.playheadFrame = (48000L * 2.2).toLong() }
        songWith(c)
        repeat(4) { compose.onNodeWithText("+").performClick() }
        snap("song-zoomed-in-portrait")
    }

    /** The demo's beat track is the last lane, below the fold at full lane height. */
    private fun showBeatLane() {
        // The insets first: they shrink the viewport, and a scroll made
        // before them would stop short of the end.
        applyPhoneInsets()
        compose.waitForIdle()
        val scrollers = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().indices
        for (i in scrollers) {
            compose.onAllNodes(hasScrollAction())[i]
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        }
        compose.waitForIdle()
    }

    /**
     * The demo song with Guitar's first take recorded over twice: two newer
     * clips stacked on clip 1, the newest selected.
     */
    private fun stackedController(): SongController {
        val c = controller()
        val take = c.song.track(1)!!.clips.first().take
        c.addTakeToTrack(1, take, 24000L)
        c.addTakeToTrack(1, take, 48000L)
        return c
    }
}
