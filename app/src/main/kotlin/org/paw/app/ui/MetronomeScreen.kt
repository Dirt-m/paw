package org.paw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.round
import org.paw.app.SongController

// The song's metronome: click, tempo, beats per bar and count-in. All of it
// is stored in the song, so each song keeps its own.

@Composable
fun MetronomeScreen(controller: SongController, onBack: () -> Unit) {
    val song = controller.song
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize().background(Colors.bg).safeDrawingPadding()) {
        ScreenTopBar(title = "Metronome", onBack = onBack, backLabel = "Song")
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))

        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .verticalScrollbar(scroll)
                .verticalScroll(scroll)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section("Click") {
                SettingRow("Click") {
                    Segmented(
                        listOf("Off", "On"),
                        selected = if (controller.clickEnabled) 1 else 0,
                        onSelect = { controller.setClick(it == 1) },
                        modifier = Modifier.weight(1f),
                    )
                }
                SettingRow("Count-in") {
                    Segmented(
                        listOf("Off", "1 bar", "2 bars"),
                        selected = controller.countInBars.coerceIn(0, 2),
                        onSelect = { controller.setCountIn(it) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Section("Tempo") {
                SettingRow("Tempo") {
                    val bpm = song.tempoBpm
                    Stepper(
                        value = "%.0f".format(bpm),
                        onDown = { controller.setTempo((bpm - 1f).coerceAtLeast(30f)) },
                        onUp = { controller.setTempo((bpm + 1f).coerceAtMost(300f)) },
                    )
                    HSpace(8.dp)
                    Text("BPM", style = DimStyle)
                }
                // A slider under the stepper for big jumps; the stepper for the last few.
                var liveTempo by remember { mutableStateOf<Float?>(null) }
                Row(Modifier.padding(start = 110.dp), verticalAlignment = Alignment.CenterVertically) {
                    MiniSlider(
                        value = ((liveTempo ?: song.tempoBpm) - 30f) / 270f,
                        onChange = { pos ->
                            val v = 30f + pos * 270f
                            liveTempo = v
                            controller.previewTempo(v)
                        },
                        onCommit = {
                            liveTempo?.let { controller.setTempo(round(it)) }
                            liveTempo = null
                        },
                        onReset = {
                            liveTempo = null
                            controller.setTempo(120f)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                SettingRow("Beats per bar") {
                    Segmented(
                        listOf("2", "3", "4", "5", "6", "7"),
                        selected = (song.beatsPerBar - 2).coerceIn(0, 5),
                        onSelect = { controller.setTempo(song.tempoBpm, it + 2) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
