package org.paw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.paw.app.SongController
import org.paw.app.model.MASTER_TRACK_ID

// The one panel open over the song, whichever it is.

@Composable
internal fun PanelHost(
    controller: SongController,
    ui: SongUi,
    tl: TimelineState,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Colors.bg)) {
        when (val p = ui.panel) {
            is SongPanel.Inspector -> TrackDetailScreen(controller, p.trackId, onBack = ui::close)
            SongPanel.Takes -> RecordingsScreen(controller, onBack = ui::close)
            SongPanel.Tempo -> MetronomeScreen(controller, onBack = ui::close)
            SongPanel.Io -> TrackDetailScreen(controller, MASTER_TRACK_ID, onBack = ui::close)
            is SongPanel.SampleToKit -> PanelHeader("Sample", onClose = ui::close)
            is SongPanel.Meter -> PanelHeader("Pattern", onClose = ui::close)
            null -> {}
        }
    }
}
