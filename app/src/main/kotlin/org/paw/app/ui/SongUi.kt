package org.paw.app.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.paw.app.model.SnapGrid

// What the song screen is showing: one mode, and at most one panel over it.
// Everything a musician does inside a song happens here, so the transport,
// the playhead and the meters never leave the screen.

enum class SongMode { Tracks, Mix, Beats }

enum class InspectorTab { Track, Fx }

sealed interface SongPanel {
    /** A track's level, input and effects; [focusInput] scrolls to the input list. */
    data class Inspector(
        val trackId: Int,
        val tab: InspectorTab = InspectorTab.Track,
        val focusInput: Boolean = false,
    ) : SongPanel

    /** Every recording in the song, with import. */
    data object Takes : SongPanel

    /** Tempo, meter, count-in and click. */
    data object Tempo : SongPanel

    /** Input device, output route, latency and the phone mic boost. */
    data object Io : SongPanel

    /** Turns the selected audio clip into a sample on a beat track. */
    data class SampleToKit(val trackId: Int, val clipId: Int) : SongPanel

    /** A pattern's time signature, grouping, length and grid. */
    data class Meter(val patternId: Int) : SongPanel
}

@Stable
class SongUi(initialMode: SongMode = SongMode.Tracks) {
    var mode: SongMode by mutableStateOf(initialMode)
    var panel: SongPanel? by mutableStateOf(null)

    /** The beat track Beats mode shows; null until one is chosen. */
    var beatsTrack: Int? by mutableStateOf(null)

    /** The pattern Beats mode edits; null picks the track's first. */
    var beatsPattern: Int? by mutableStateOf(null)

    /** What clip edits, loop edges and placement snap to. */
    var snap: SnapGrid by mutableStateOf(SnapGrid.Beat)

    /** The clock and ruler read bars.beats, or minutes and seconds. */
    var clockAsTime: Boolean by mutableStateOf(false)

    fun open(p: SongPanel) {
        panel = p
    }

    fun close() {
        panel = null
    }

    /** Beats mode on [trackId] (or the last one shown), optionally on a pattern. */
    fun openBeats(trackId: Int?, patternId: Int? = null) {
        if (trackId != null) beatsTrack = trackId
        if (patternId != null) beatsPattern = patternId
        panel = null
        mode = SongMode.Beats
    }
}
