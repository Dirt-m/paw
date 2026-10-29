package org.paw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.paw.app.AppPrefs
import org.paw.app.KeepAwakeMode
import org.paw.app.SongController
import org.paw.app.model.InputDevice

// Settings: only what applies to every song. Screen, timeline follow, meters,
// then where things live on the phone and, with a song open, the read-only
// audio status. Anything one song owns (the metronome, the mic boost) is on
// that song's own screens.

@Composable
fun OptionsScreen(
    prefs: AppPrefs,
    songsPath: String,
    effectsPath: String,
    onBack: () -> Unit,
    backLabel: String,
    controller: SongController? = null,
    latencyMs: Float = 0f,
) {
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize().background(Colors.bg).safeDrawingPadding()) {
        ScreenTopBar(title = "Settings", onBack = onBack, backLabel = backLabel)
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
                        selected = when (prefs.keepAwakeMode) {
                            KeepAwakeMode.Off -> 0
                            KeepAwakeMode.FiveMinutes -> 1
                            KeepAwakeMode.On -> 2
                        },
                        onSelect = {
                            prefs.updateKeepAwakeMode(
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
                        selected = if (prefs.followSmooth) 1 else 0,
                        onSelect = { prefs.updateFollowSmooth(it == 1) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Section("Meters") {
                SettingRow("Peak hold") {
                    Segmented(
                        listOf("Off", "2 s", "6 s", "∞"),
                        selected = when (prefs.meterHold) {
                            null -> 0
                            2.seconds -> 1
                            6.seconds -> 2
                            Duration.INFINITE -> 3
                            else -> 1
                        },
                        onSelect = {
                            prefs.updateMeterHold(
                                when (it) {
                                    0 -> null
                                    1 -> 2.seconds
                                    2 -> 6.seconds
                                    else -> Duration.INFINITE
                                },
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (controller != null) {
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
            }

            Section("Songs folder") {
                Text(songsPath, style = MonoDimStyle, modifier = Modifier.padding(vertical = 8.dp))
            }

            Section("Effects folder") {
                Text(effectsPath, style = MonoDimStyle, modifier = Modifier.padding(vertical = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
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
