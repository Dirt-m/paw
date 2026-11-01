package org.paw.app.snap

import org.junit.Test
import org.paw.app.ui.InspectorTab
import org.paw.app.ui.SongMode
import org.paw.app.ui.SongPanel
import org.paw.app.ui.SongUi
import org.robolectric.annotation.Config

// The Beats mode on the demo song's beat track (id 5, pattern 1).

class BeatsSnapshots : SnapshotBase() {

    private fun beatsUi() = SongUi().apply { openBeats(5, 1) }

    @Test fun beats_portrait() { songWith(controller(), beatsUi()); snap("beats-portrait") }

    @Test @Config(qualifiers = "+land")
    fun beats_landscape() { songWith(controller(), beatsUi()); snap("beats-landscape") }
}
