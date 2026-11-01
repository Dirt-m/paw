package org.paw.app.snap

import org.junit.Test
import org.paw.app.ui.InspectorTab
import org.paw.app.ui.SongMode
import org.paw.app.ui.SongPanel
import org.paw.app.ui.SongUi
import org.robolectric.annotation.Config

// The Mix mode.

class MixSnapshots : SnapshotBase() {

    @Test fun mix_portrait() { songWith(controller(), SongUi(SongMode.Mix)); snap("mix-portrait") }

    @Test @Config(qualifiers = "+land")
    fun mix_landscape() { songWith(controller(), SongUi(SongMode.Mix)); snap("mix-landscape") }

    @Test fun mix_crowded_portrait() {
        songWith(crowdedController(app), SongUi(SongMode.Mix)); snap("mix-crowded-portrait")
    }
}
