package org.paw.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.paw.app.SongController

// Stand-in so the panel host builds; the real meter panel replaces this file.

@Composable
internal fun MeterPanel(controller: SongController, ui: SongUi, patternId: Int, modifier: Modifier) {
    Column(modifier) {
        PanelHeader("Pattern", onClose = ui::close)
    }
}
