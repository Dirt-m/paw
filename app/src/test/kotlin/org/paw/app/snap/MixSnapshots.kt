package org.paw.app.snap

import org.junit.Test
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

    @Test @Config(qualifiers = "+land")
    fun mix_crowded_landscape() {
        songWith(crowdedController(app), SongUi(SongMode.Mix)); snap("mix-crowded-landscape")
    }

    @Test fun mix_selected_portrait() {
        val c = controller()
        c.selectedTrack = c.song.tracks[1].id
        songWith(c, SongUi(SongMode.Mix)); snap("mix-selected-portrait")
    }

    /** A new song: one strip, the rest of the row free for adding. */
    @Test fun mix_new_song_portrait() {
        songWith(emptyController(app), SongUi(SongMode.Mix)); snap("mix-new-song-portrait")
    }

    /** The inspector beside the strips: they narrow to what is left. */
    @Test @Config(qualifiers = "+land")
    fun mix_panel_landscape() {
        val c = controller()
        val id = c.song.tracks[0].id
        c.selectedTrack = id
        val ui = SongUi(SongMode.Mix).apply { open(SongPanel.Inspector(id)) }
        songWith(c, ui); snap("mix-panel-landscape")
    }
}
