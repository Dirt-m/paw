package org.paw.app.snap

import org.junit.Test
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

    @Test fun inspector_mic_portrait() { songWith(controller(), panel(SongPanel.Inspector(4))); snap("inspector-mic-portrait") }

    @Test fun inspector_beat_portrait() { songWith(controller(), panel(SongPanel.Inspector(5))); snap("inspector-beat-portrait") }

    @Test fun inspector_master_portrait() {
        songWith(controller(), panel(SongPanel.Inspector(org.paw.app.model.MASTER_TRACK_ID))); snap("inspector-master-portrait")
    }

    @Test fun takes_portrait() { songWith(controller(), panel(SongPanel.Takes)); snap("takes-portrait") }

    @Test @Config(qualifiers = "+land")
    fun takes_landscape() { songWith(controller(), panel(SongPanel.Takes)); snap("takes-landscape") }

    @Test fun tempo_portrait() { songWith(controller(), panel(SongPanel.Tempo)); snap("tempo-portrait") }

    @Test @Config(qualifiers = "+land")
    fun tempo_landscape() { songWith(controller(), panel(SongPanel.Tempo)); snap("tempo-landscape") }

    @Test fun io_portrait() { songWith(controller(), panel(SongPanel.Io)); snap("io-portrait") }

    @Test fun sample_to_kit_portrait() {
        val c = controller(); c.selectedClip = 1 to 1
        songWith(c, panel(SongPanel.SampleToKit(1, 1))); snap("sample-to-kit-portrait")
    }

    @Test fun meter_portrait() { songWith(controller(), panel(SongPanel.Meter(1), SongMode.Beats)); snap("meter-portrait") }
}
