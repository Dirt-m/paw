package org.paw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.paw.app.Engine
import org.paw.app.Latency
import org.paw.app.SongController
import org.paw.app.model.INPUT_NONE
import org.paw.app.model.InputDevice
import org.paw.app.model.SAMPLE_RATE

// What the engine hears and where it plays: the input in use, the phone mic
// boost when the mic is in play, the output route and the latency that
// recordings are shifted by.

@Composable
internal fun IoPanel(controller: SongController, ui: SongUi, modifier: Modifier) {
    val st = controller.status
    val micInUse = controller.currentInput == InputDevice.Mic ||
        controller.song.tracks.any { it.inputDevice == InputDevice.Mic && it.inputMode != INPUT_NONE }
    // The route playing now: the chosen one, or what Automatic landed on.
    val route = controller.outputDevice.ifEmpty { controller.activeOutputRoute }
    val bluetooth = route.startsWith("bt:")

    Column(modifier) {
        PanelHeader("Audio I/O", onClose = ui::close)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))
        PanelBody(Modifier.weight(1f)) {
            SectionLabel("Input", Modifier.padding(top = 4.dp, bottom = 4.dp))
            val (inputName, inputDetail, inputColor) = when {
                st.deviceLost -> Triple("Device lost", "", Colors.red)
                !st.inputOpen -> Triple("No input open", "", Colors.dim)
                controller.currentInput == InputDevice.Mic -> Triple("Phone microphone", "${st.inChannels} ch", Colors.text)
                else -> Triple(
                    controller.inputOptions.find { it.device == controller.currentInput }?.label
                        ?: (controller.currentInput as? InputDevice.Usb)?.name ?: "USB interface",
                    "${st.inChannels} ch",
                    Colors.text,
                )
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    inputName,
                    style = NameStyle.copy(color = inputColor),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(inputDetail, style = MonoDimStyle.copy(fontSize = 13.sp))
            }
            if (micInUse) {
                SettingRow("Mic boost", labelWidth = 96.dp) {
                    MicBoostSlider(controller, Modifier.weight(1f))
                }
            }
            PanelDivider()
            SectionLabel("Output", Modifier.padding(top = 4.dp, bottom = 4.dp))
            val auto = controller.outputOptions.find { it.key == controller.activeOutputRoute }?.label
            RadioRow(
                "Automatic",
                controller.outputDevice == "",
                detail = if (controller.outputDevice == "" && auto != null) auto else null,
            ) { controller.setOutputRoute("") }
            controller.outputOptions.forEach { opt ->
                RadioRow(opt.label, controller.outputDevice == opt.key) { controller.setOutputRoute(opt.key) }
            }
            if (bluetooth) {
                Text(
                    "Bluetooth output lags. Record with wired headphones.",
                    style = BodyStyle.copy(color = Colors.warn),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            PanelDivider()
            val context = LocalContext.current
            // A JNI call that takes the engine's control lock: once per route,
            // and again when the stream reopens on it.
            val latencyFrames = remember(controller.activeOutputRoute, st.outputOpen) {
                Latency.frames(context, controller.activeOutputRoute, Engine.routeLatencyFrames())
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Latency", style = BodyStyle, modifier = Modifier.weight(1f))
                Text("%.1f ms".format(latencyFrames * 1000f / SAMPLE_RATE), style = MonoStyle)
            }
        }
    }
}
