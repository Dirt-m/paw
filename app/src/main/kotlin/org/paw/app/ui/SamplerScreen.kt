package org.paw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.paw.app.SongController
import org.paw.app.TRANSPORT_STOPPED

// Samples played by hand. Each tile fires on touch down, not on release, and
// every tile takes its own finger, so two can be hit at once.

private val TileShape = RoundedCornerShape(Dimens.corner)

@Composable
fun SamplerScreen(controller: SongController, onBack: () -> Unit) {
    LaunchedEffect(Unit) { controller.loadClipSamples() }
    val tiles = controller.sampleTiles
    val playing = controller.status.transport != TRANSPORT_STOPPED

    Column(Modifier.fillMaxSize().background(Colors.bg).safeDrawingPadding()) {
        ScreenTopBar(title = "Sampler", onBack = onBack, backLabel = "Song") {
            AppButton(
                if (playing) "Stop" else "Play",
                onClick = { controller.playPause() },
                accent = if (playing) Colors.amber else Colors.green,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))

        if (tiles.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Put a clip on the timeline to play it here", style = DimStyle)
            }
            return@Column
        }

        BoxWithConstraints(Modifier.fillMaxSize().padding(Dimens.gap)) {
            // Tiles about thumb-and-a-half wide: three across a portrait phone.
            val columns = (maxWidth / 128.dp).toInt().coerceIn(2, 8)
            val scroll = rememberScrollState()
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(Dimens.gap),
            ) {
                for (row in tiles.indices.chunked(columns)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                        for (i in row) {
                            SampleTileView(
                                label = tiles[i].label,
                                onHit = { controller.triggerSample(i) },
                                modifier = Modifier.weight(1f).height(112.dp),
                            )
                        }
                        repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SampleTileView(label: String, onHit: () -> Unit, modifier: Modifier = Modifier) {
    var down by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(TileShape)
            .background(if (down) Colors.amber else Colors.raised)
            .border(1.dp, if (down) Colors.amber else Colors.line, TileShape)
            .pointerInput(onHit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onHit()
                    down = true
                    waitForUpOrCancellation()
                    down = false
                }
            }
            .padding(10.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            label,
            style = NameStyle.copy(color = if (down) Colors.bg else Colors.text),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
