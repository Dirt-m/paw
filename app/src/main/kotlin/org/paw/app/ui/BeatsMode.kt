package org.paw.app.ui

import androidx.compose.runtime.Composable
import org.paw.app.SongController

// Beats mode: a beat track's patterns, steps, kit and pads, with the song's
// transport still underneath.

@Composable
internal fun BeatsMode(controller: SongController, ui: SongUi, compact: Boolean, short: Boolean) {
    val track = ui.beatsTrack ?: controller.song.tracks.firstOrNull { it.kind == org.paw.app.model.TRACK_BEAT }?.id ?: return
    SamplerScreen(controller, track, ui.beatsPattern, onBack = { ui.mode = SongMode.Tracks })
}
