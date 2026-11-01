package org.paw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.paw.app.SongController

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
        val fill = Modifier.fillMaxSize()
        when (val p = ui.panel) {
            is SongPanel.Inspector -> InspectorPanel(controller, ui, p, compact, fill)
            SongPanel.Takes -> TakesPanel(controller, ui, fill)
            SongPanel.Tempo -> TempoPanel(controller, ui, compact, fill)
            SongPanel.Io -> IoPanel(controller, ui, fill)
            is SongPanel.SampleToKit -> SampleToKitPanel(controller, ui, p, fill)
            is SongPanel.Meter -> MeterPanel(controller, ui, p.patternId, fill)
            null -> {}
        }
    }
}
