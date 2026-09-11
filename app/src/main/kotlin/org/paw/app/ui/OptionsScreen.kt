package org.paw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.paw.app.KeepAwakeMode
import org.paw.app.SongController
import org.paw.app.model.InputDevice

// Settings. One screen, sections in the order they come up: screen, timeline
// follow, metronome, meters, phone mic, then the read-only audio status and
// the effects folder for people who bring their own plugins.

@Composable
fun OptionsScreen(
    controller: SongController,
    keepAwakeMode: KeepAwakeMode,
    onKeepAwakeMode: (KeepAwakeMode) -> Unit,
    latencyMs: Float,
    effectsPath: String,
    onBack: () -> Unit,
) {
    val song = controller.song
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize().background(Colors.bg).safeDrawingPadding()) {
        ScreenTopBar(title = "Settings", onBack = onBack, backLabel = "Song")
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
            Section("Screen") {
                SettingRow("Keep awake") {
                    Segmented(
                        listOf("Off", "5 min", "On"),
                        selected = when (keepAwakeMode) {
                            KeepAwakeMode.Off -> 0
                            KeepAwakeMode.FiveMinutes -> 1
                            KeepAwakeMode.On -> 2
                        },
                        onSelect = {
                            onKeepAwakeMode(
                                when (it) {
                                    0 -> KeepAwakeMode.Off
                                    1 -> KeepAwakeMode.FiveMinutes
                                    else -> KeepAwakeMode.On
                                },
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Section("Timeline") {
                SettingRow("Follow playhead") {
                    Segmented(
                        listOf("Jump", "Smooth"),
                        selected = if (controller.followSmooth) 1 else 0,
                        onSelect = { controller.followSmooth = it == 1 },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Section("Metronome") {
                SettingRow("Click") {
                    Segmented(
                        listOf("Off", "On"),
                        selected = if (controller.clickEnabled) 1 else 0,
                        onSelect = { controller.setClick(it == 1) },
                        modifier = Modifier.weight(1f),
                    )
                }
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
                            liveTempo?.let { controller.setTempo(kotlin.math.round(it)) }
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
                SettingRow("Count-in") {
                    Segmented(
                        listOf("Off", "1 bar", "2 bars"),
                        selected = controller.countInBars.coerceIn(0, 2),
                        onSelect = { controller.setCountIn(it) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Section("Meters") {
                SettingRow("Peak hold") {
                    Segmented(
                        listOf("Off", "2 s", "6 s", "∞"),
                        selected = when (controller.meterHold) {
                            null -> 0
                            2.seconds -> 1
                            6.seconds -> 2
                            Duration.INFINITE -> 3
                            else -> 1
                        },
                        onSelect = {
                            controller.meterHold = when (it) {
                                0 -> null
                                1 -> 2.seconds
                                2 -> 6.seconds
                                else -> Duration.INFINITE
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Section("Phone microphone") {
                SettingRow("Boost") {
                    MicBoostSlider(controller, Modifier.weight(1f))
                }
            }

            Section("Audio") {
                val st = controller.status
                val input = when {
                    st.deviceLost -> "device lost"
                    !st.inputOpen -> "closed"
                    else -> {
                        val label = controller.inputOptions.find { it.device == controller.currentInput }?.label
                            ?: controller.currentInput.serialize()
                        "$label · ${st.inChannels} ch" +
                            if (controller.currentInput == InputDevice.Mic) " · boost +%.0f dB".format(controller.micBoostDb)
                            else " · bit-perfect"
                    }
                }
                val output = controller.outputOptions.find { it.key == controller.activeOutputRoute }?.label
                    ?: if (st.outputOpen) controller.activeOutputRoute.ifEmpty { "open" } else "closed"
                ReadoutRow("Input", input)
                ReadoutRow("Output", output)
                ReadoutRow("Latency", "%.1f ms compensated".format(latencyMs))
            }

            Section("Effects folder") {
                Text(effectsPath, style = MonoDimStyle, modifier = Modifier.padding(vertical = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column {
        SectionLabel(title)
        VSpace(6.dp)
        Column(
            Modifier.fillMaxWidth().panel().padding(horizontal = 12.dp, vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
private fun ReadoutRow(label: String, value: String) {
    SettingRow(label) {
        Text(value, style = MonoStyle, modifier = Modifier.weight(1f), maxLines = 2)
    }
}
