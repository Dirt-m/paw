package org.paw.app.snap

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Test
import org.paw.app.model.MASTER_TRACK_ID
import org.paw.app.ui.InspectorTab
import org.paw.app.ui.SongMode
import org.paw.app.ui.SongPanel
import org.paw.app.ui.SongUi
import org.robolectric.annotation.Config

// The panels that open over the song.

class PanelSnapshots : SnapshotBase() {

    private fun panel(p: SongPanel, mode: SongMode = SongMode.Tracks) = SongUi(mode).apply { open(p) }

    @Test fun inspector_portrait() { songWith(controller(), panel(SongPanel.Inspector(1))); snap("inspector-portrait") }

    @Test @Config(qualifiers = "+land")
    fun inspector_landscape() { songWith(controller(), panel(SongPanel.Inspector(1))); snap("inspector-landscape") }

    @Test fun inspector_fx_portrait() {
        songWith(controller(), panel(SongPanel.Inspector(1, InspectorTab.Fx))); snap("inspector-fx-portrait")
    }

    @Test @Config(qualifiers = "+land")
    fun inspector_fx_landscape() {
        songWith(controller(), panel(SongPanel.Inspector(1, InspectorTab.Fx))); snap("inspector-fx-landscape")
    }

    @Test fun inspector_fx_empty_portrait() {
        songWith(controller(), panel(SongPanel.Inspector(3, InspectorTab.Fx))); snap("inspector-fx-empty-portrait")
    }

    @Test fun inspector_fx_picker_portrait() {
        songWith(controller(), panel(SongPanel.Inspector(2, InspectorTab.Fx)))
        compose.onNodeWithText("+ Add effect").performClick()
        snap("inspector-fx-picker-portrait")
    }

    @Test fun inspector_input_focus_portrait() {
        songWith(crowdedController(app), panel(SongPanel.Inspector(3, focusInput = true)))
        snap("inspector-input-focus-portrait")
    }

    @Test fun inspector_mic_portrait() { songWith(controller(), panel(SongPanel.Inspector(4))); snap("inspector-mic-portrait") }

    @Test fun inspector_beat_portrait() { songWith(controller(), panel(SongPanel.Inspector(5))); snap("inspector-beat-portrait") }

    @Test fun inspector_master_portrait() {
        songWith(controller(), panel(SongPanel.Inspector(MASTER_TRACK_ID))); snap("inspector-master-portrait")
    }

    @Test fun inspector_crowded_portrait() {
        songWith(crowdedController(app), panel(SongPanel.Inspector(1))); snap("inspector-crowded-portrait")
    }

    @Test fun takes_portrait() {
        val c = controller(); c.selectedTrack = 2
        // A placed take has an original time to go back to.
        c.addTakeToTrack(2, "audio/take_20260805-143950_t2.wav", 20L * c.song.sampleRate)
        songWith(c, panel(SongPanel.Takes)); snap("takes-portrait")
    }

    /** The ▾ on a row lists every audio track to add the take to. */
    @Test fun takes_chooser_portrait() {
        songWith(controller(), panel(SongPanel.Takes))
        compose.onAllNodesWithText("▾")[0].performClick()
        snap("takes-chooser-portrait")
    }

    /** Two tracks with one name keep separate groups. */
    @Test fun takes_same_names_portrait() {
        val c = controller()
        c.renameTrack(2, "Guitar")
        c.message = null
        songWith(c, panel(SongPanel.Takes)); snap("takes-same-names-portrait")
    }

    @Test @Config(qualifiers = "+land")
    fun takes_landscape() { songWith(controller(), panel(SongPanel.Takes)); snap("takes-landscape") }

    @Test fun takes_empty_portrait() { songWith(emptyController(app), panel(SongPanel.Takes)); snap("takes-empty-portrait") }

    @Test fun tempo_portrait() { songWith(controller(), panel(SongPanel.Tempo)); snap("tempo-portrait") }

    @Test fun tempo_click_on_portrait() {
        val c = controller()
        c.setClick(true)
        c.setMeter(7, 8)
        songWith(c, panel(SongPanel.Tempo)); snap("tempo-click-on-portrait")
    }

    @Test @Config(qualifiers = "+land")
    fun tempo_landscape() { songWith(controller(), panel(SongPanel.Tempo)); snap("tempo-landscape") }

    @Test fun io_portrait() { songWith(controller(), panel(SongPanel.Io)); snap("io-portrait") }

    @Test fun io_bluetooth_portrait() {
        val c = controller()
        c.setOutputRoute("bt:WH-1000XM4")
        c.openStreams(7, 9, org.paw.app.model.InputDevice.Usb("M-Track Duo"), "bt:WH-1000XM4")
        songWith(c, panel(SongPanel.Io)); snap("io-bluetooth-portrait")
    }

    @Test @Config(qualifiers = "+land")
    fun io_landscape() { songWith(controller(), panel(SongPanel.Io)); snap("io-landscape") }

    @Test fun sample_to_kit_portrait() {
        val c = controller(); c.selectedClip = 1 to 1
        songWith(c, panel(SongPanel.SampleToKit(1, 1))); snap("sample-to-kit-portrait")
    }

    @Test @Config(qualifiers = "+land")
    fun sample_to_kit_landscape() {
        val c = controller(); c.selectedClip = 2 to 3
        songWith(c, panel(SongPanel.SampleToKit(2, 3))); snap("sample-to-kit-landscape")
    }

    @Test fun meter_portrait() { songWith(controller(), panel(SongPanel.Meter(1), SongMode.Beats)); snap("meter-portrait") }
}
