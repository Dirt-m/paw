package org.paw.app.snap

import org.junit.Test
import org.paw.app.ui.InspectorTab
import org.paw.app.ui.SongMode
import org.paw.app.ui.SongPanel
import org.paw.app.ui.SongUi
import org.robolectric.annotation.Config

// The Tracks mode: lanes, headers, ruler, clip bar, transport.

class TracksSnapshots : SnapshotBase() {

    @Test fun song_portrait() { songWith(controller()); snap("song-portrait") }

    @Test @Config(qualifiers = "+land")
    fun song_landscape() { songWith(controller()); snap("song-landscape") }

    /** Guitar's first clip selected: the clip bar in the mode bar's place. */
    @Test fun song_selected_portrait() {
        val c = controller(); c.selectedClip = 1 to 1
        songWith(c); snap("song-selected-portrait")
    }

    @Test @Config(qualifiers = "+land")
    fun song_selected_landscape() {
        val c = controller(); c.selectedClip = 1 to 1
        songWith(c); snap("song-selected-landscape")
    }

    /** The beat track's pattern clip selected. */
    @Test fun song_pattern_selected_portrait() {
        val c = controller(); c.selectedClip = 5 to 5
        songWith(c); snap("song-pattern-selected-portrait")
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
}
