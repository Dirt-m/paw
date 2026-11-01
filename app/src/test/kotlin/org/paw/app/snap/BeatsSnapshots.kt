package org.paw.app.snap

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.paw.app.SongController
import org.paw.app.ui.MeterPanel
import org.paw.app.ui.SongMode
import org.paw.app.ui.SongPanel
import org.paw.app.ui.SongUi
import org.robolectric.annotation.Config

// The Beats mode on the demo song's beat track (id 5, pattern 1), and the
// pattern meter panel it opens.

class BeatsSnapshots : SnapshotBase() {

    private fun beatsUi() = SongUi().apply { openBeats(5, 1) }

    /** The demo kit grown to eight sounds, cut from the drum take, with a
     *  few steps for the new ones. */
    private fun eightSoundController(): SongController {
        val c = controller()
        for (name in listOf("Clap", "Rim", "Low tom", "Shaker", "Open hat")) {
            val id = c.sampleFromClip(3, 4, 5, name)!!
            c.setStep(1, id, (id * 3) % 14, true)
        }
        c.message = null
        return c
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    @Test fun beats_portrait() { songWith(controller(), beatsUi()); snap("beats-portrait") }

    @Test fun beats_pads_portrait() {
        songWith(controller(), beatsUi()); click("Pads"); snap("beats-pads-portrait")
    }

    @Test fun beats_kit_portrait() {
        songWith(controller(), beatsUi()); click("Kit"); snap("beats-kit-portrait")
    }

    @Test fun beats_eight_portrait() { songWith(eightSoundController(), beatsUi()); snap("beats-eight-portrait") }

    @Test fun beats_eight_pads_portrait() {
        songWith(eightSoundController(), beatsUi()); click("Pads"); snap("beats-eight-pads-portrait")
    }

    @Test fun beats_patterns_portrait() {
        val c = controller()
        c.newPattern(5, from = c.song.pattern(1))
        c.message = null
        songWith(c, beatsUi()); click("Groove"); snap("beats-patterns-portrait")
    }

    @Test fun beats_no_track_portrait() {
        songWith(emptyController(app), SongUi(SongMode.Beats)); snap("beats-no-track-portrait")
    }

    @Test fun beats_empty_kit_portrait() {
        val c = controller()
        val id = c.addBeatTrack()
        songWith(c, SongUi().apply { openBeats(id) }); snap("beats-empty-kit-portrait")
    }

    @Test fun beats_no_pattern_portrait() {
        val c = controller()
        val id = c.sampleFromClip(3, 4, null, "Kick")!!
        c.message = null
        val track = c.song.tracks.first { t -> t.samples.any { it.id == id } }.id
        songWith(c, SongUi().apply { openBeats(track) }); snap("beats-no-pattern-portrait")
    }

    @Test @Config(qualifiers = "+land")
    fun beats_landscape() { songWith(controller(), beatsUi()); snap("beats-landscape") }

    @Test @Config(qualifiers = "+land")
    fun beats_eight_landscape() { songWith(eightSoundController(), beatsUi()); snap("beats-eight-landscape") }

    @Test @Config(qualifiers = "+land")
    fun beats_kit_landscape() {
        songWith(controller(), beatsUi()); click("Kit"); snap("beats-kit-landscape")
    }

    @Test fun meter_panel_portrait() {
        songWith(controller(), SongUi(SongMode.Beats).apply { openBeats(5, 1); open(SongPanel.Meter(1)) })
        snap("beats-meter-portrait")
    }

    @Test @Config(qualifiers = "+land")
    fun meter_panel_landscape() {
        songWith(controller(), SongUi(SongMode.Beats).apply { openBeats(5, 1); open(SongPanel.Meter(1)) })
        snap("beats-meter-landscape")
    }

    /** The panel on its own, whatever the panel host does with it. */
    @Test fun meter_panel_direct() {
        val c = controller()
        content { MeterPanel(c, SongUi(SongMode.Beats), 1) }
        snap("beats-meter-direct")
    }

    /** At the width the panel gets beside the workspace in landscape. */
    @Test @Config(qualifiers = "+land")
    fun meter_panel_direct_landscape() {
        val c = controller()
        content {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.width(340.dp).fillMaxHeight(),
            ) { MeterPanel(c, SongUi(SongMode.Beats), 1) }
        }
        snap("beats-meter-direct-landscape")
    }
}
