package org.paw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ln
import kotlin.math.pow
import org.paw.app.SongController
import org.paw.app.model.INPUT_CH1
import org.paw.app.model.INPUT_CH2
import org.paw.app.model.INPUT_NONE
import org.paw.app.model.INPUT_STEREO
import org.paw.app.model.InputDevice
import org.paw.app.model.MASTER_TRACK_ID
import org.paw.app.model.PluginInfo
import org.paw.app.model.PluginPort

// One track, one screen: level, input, effects, and (last, and small) remove.
// The master gets the same screen with output routing where input would be.
// Wide windows put level + I/O beside the effects; phones stack them.

@Composable
fun TrackDetailScreen(
    controller: SongController,
    trackId: Int,
    onBack: () -> Unit,
    initialPicking: Boolean = false,
) {
    val track = controller.song.track(trackId)
    if (track == null) {
        onBack()
        return
    }
    val isMaster = trackId == MASTER_TRACK_ID
    var picking by remember { mutableStateOf(initialPicking) }
    var editingName by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Colors.bg)) {
        val stacked = maxWidth < COMPACT_WIDTH

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            val editing = editingName
            if (editing == null) {
                ScreenTopBar(
                    title = track.name,
                    onBack = onBack,
                    backLabel = "Song",
                    titleColor = if (isMaster) Colors.amber else Colors.text,
                ) {
                    if (!isMaster) {
                        AppButton("Rename", accent = Colors.dim, onClick = { editingName = track.name })
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).background(Colors.panel).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NameField(editing, { editingName = it }, Modifier.weight(1f), maxLength = 24)
                    AppButton("Save", accent = Colors.amber, filled = true, enabled = editing.isNotBlank(), onClick = {
                        controller.renameTrack(trackId, editing)
                        editingName = null
                    })
                    AppButton("Cancel", accent = Colors.dim, onClick = { editingName = null })
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))

            val levelAndIo: @Composable () -> Unit = {
                Stack(gap = 20.dp) {
                    LevelSection(controller, trackId, track.gain, track.pan, track.monitor, isMaster)
                    if (isMaster) OutputSection(controller) else InputSection(controller, trackId)
                }
            }
            val effects: @Composable () -> Unit = {
                EffectsSection(controller, trackId, picking, onPicking = { picking = it })
            }
            val remove: @Composable () -> Unit = {
                if (!isMaster) RemoveTrackRow(controller, trackId, onBack)
            }

            if (stacked) {
                val scroll = rememberScrollState()
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScrollbar(scroll)
                        .verticalScroll(scroll)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    levelAndIo()
                    effects()
                    remove()
                    VSpace(8.dp)
                }
            } else {
                Row(
                    Modifier.weight(1f).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val left = rememberScrollState()
                    Column(
                        Modifier
                            .width(340.dp)
                            .fillMaxHeight()
                            .verticalScrollbar(left)
                            .verticalScroll(left),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        levelAndIo()
                        remove()
                    }
                    val right = rememberScrollState()
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScrollbar(right)
                            .verticalScroll(right),
                    ) {
                        effects()
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- level --

@Composable
private fun LevelSection(
    controller: SongController,
    trackId: Int,
    gain: Float,
    pan: Float,
    monitor: Boolean,
    isMaster: Boolean,
) {
    var liveGain by remember { mutableStateOf<Float?>(null) }
    var livePan by remember { mutableStateOf<Float?>(null) }
    Column {
        SectionLabel("Level")
        VSpace(6.dp)
        Column(Modifier.fillMaxWidth().panel().padding(horizontal = 12.dp, vertical = 6.dp)) {
            SettingRow("Volume", labelWidth = 72.dp) {
                GainSlider(controller, trackId, gain, Modifier.weight(1f), onLive = { liveGain = it })
                HSpace(10.dp)
                Text(formatDb(liveGain ?: gain), style = MonoStyle, modifier = Modifier.width(64.dp))
            }
            if (!isMaster) {
                SettingRow("Pan", labelWidth = 72.dp) {
                    PanSlider(controller, trackId, pan, Modifier.weight(1f), onLive = { livePan = it })
                    HSpace(10.dp)
                    Text(formatPan(livePan ?: pan), style = MonoStyle, modifier = Modifier.width(64.dp))
                }
                SettingRow("Monitor", labelWidth = 72.dp) {
                    Spacer(Modifier.weight(1f))
                    Segmented(
                        listOf("Off", "On"),
                        selected = if (monitor) 1 else 0,
                        onSelect = { i ->
                            controller.updateTrack(trackId) { it.copy(monitor = i == 1) }
                        },
                        modifier = Modifier.width(140.dp),
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------- input --

@Composable
private fun InputSection(controller: SongController, trackId: Int) {
    val track = controller.song.track(trackId) ?: return
    val usbOptions = controller.inputOptions.filter { it.device.isUsb }
    Column {
        SectionLabel("Input")
        VSpace(6.dp)
        Column(Modifier.fillMaxWidth().panel().padding(6.dp)) {
            fun choose(device: InputDevice, mode: Int) {
                controller.updateTrack(trackId) { it.copy(inputDevice = device, inputMode = mode) }
            }
            usbOptions.forEachIndexed { index, opt ->
                val onThis = track.inputDevice == opt.device ||
                    (track.inputDevice == InputDevice.AnyUsb && index == 0)
                val name = opt.label
                RadioRow("$name · Input 1", onThis && track.inputMode == INPUT_CH1) { choose(opt.device, INPUT_CH1) }
                RadioRow("$name · Input 2", onThis && track.inputMode == INPUT_CH2) { choose(opt.device, INPUT_CH2) }
                RadioRow("$name · Inputs 1+2", onThis && track.inputMode == INPUT_STEREO, detail = "stereo") {
                    choose(opt.device, INPUT_STEREO)
                }
            }
            val micOn = track.inputDevice == InputDevice.Mic
            RadioRow("Phone microphone", micOn) { choose(InputDevice.Mic, INPUT_CH1) }
            if (micOn) {
                SettingRow("Boost", labelWidth = 72.dp, modifier = Modifier.padding(start = 44.dp, end = 12.dp)) {
                    MicBoostSlider(controller, Modifier.weight(1f))
                }
            }
            RadioRow(
                "No input",
                track.inputDevice == InputDevice.None || track.inputMode == INPUT_NONE,
                accent = Colors.dim,
            ) { choose(InputDevice.None, INPUT_NONE) }

            // A saved device that isn't connected right now.
            val resolvable = when (val dev = track.inputDevice) {
                InputDevice.None, InputDevice.Mic -> true
                InputDevice.AnyUsb -> usbOptions.isNotEmpty()
                is InputDevice.Usb -> usbOptions.any { it.device == dev }
            }
            if (!resolvable && track.inputMode != INPUT_NONE) {
                val savedName = (track.inputDevice as? InputDevice.Usb)?.name ?: "interface"
                Text(
                    "$savedName is not connected",
                    style = DimStyle.copy(color = Colors.red),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------- output --

@Composable
private fun OutputSection(controller: SongController) {
    Column {
        SectionLabel("Output")
        VSpace(6.dp)
        Column(Modifier.fillMaxWidth().panel().padding(6.dp)) {
            RadioRow("Automatic", controller.outputDevice == "", detail = autoDetail(controller)) {
                controller.setOutputRoute("")
            }
            controller.outputOptions.forEach { opt ->
                RadioRow(opt.label, controller.outputDevice == opt.key) {
                    controller.setOutputRoute(opt.key)
                }
            }
        }
    }
}

/** Which endpoint AUTO resolves to right now, so the choice isn't blind. */
private fun autoDetail(controller: SongController): String? {
    val active = controller.activeOutputRoute
    if (controller.outputDevice != "" || active.isEmpty()) return null
    return controller.outputOptions.find { it.key == active }?.label
}

// ------------------------------------------------------------------ effects --

@Composable
private fun EffectsSection(
    controller: SongController,
    trackId: Int,
    picking: Boolean,
    onPicking: (Boolean) -> Unit,
) {
    val track = controller.song.track(trackId) ?: return
    Column {
        SectionLabel("Effects")
        VSpace(6.dp)
        if (picking) {
            EffectPicker(controller, onPick = { plugin ->
                controller.addEffect(trackId, plugin)
                onPicking(false)
            }, onCancel = { onPicking(false) })
        } else {
            Stack(gap = 8.dp) {
                track.effects.forEachIndexed { index, _ ->
                    EffectCard(controller, trackId, index)
                }
                AppButton(
                    "+  Add effect",
                    onClick = { onPicking(true) },
                    accent = Colors.amber,
                    filled = track.effects.isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    height = 44.dp,
                )
            }
        }
    }
}

@Composable
private fun EffectPicker(
    controller: SongController,
    onPick: (PluginInfo) -> Unit,
    onCancel: () -> Unit,
) {
    val catalog = controller.catalog
    Column(Modifier.fillMaxWidth().panel().padding(6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Add effect", style = NameStyle, modifier = Modifier.weight(1f))
            AppButton("Cancel", accent = Colors.dim, onClick = onCancel)
        }
        if (catalog.isEmpty()) {
            Text("Scanning effects…", style = DimStyle, modifier = Modifier.padding(12.dp))
            return@Column
        }
        val builtins = catalog.filter { it.isBuiltin }
        val external = catalog.filterNot { it.isBuiltin }
        builtins.forEach { PickRow(it, onPick) }
        if (external.isNotEmpty()) {
            SectionLabel("From effects folder", Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
            external.forEach { PickRow(it, onPick) }
        }
    }
}

@Composable
private fun PickRow(plugin: PluginInfo, onPick: (PluginInfo) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable { onPick(plugin) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            plugin.name.removePrefix("PAW! ").removePrefix("paw! "),
            style = BodyStyle,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(if (plugin.stereo) "stereo" else "mono", style = MonoDimStyle)
        HSpace(10.dp)
        Text("+", style = TitleStyle.copy(color = Colors.amber))
    }
}

@Composable
private fun EffectCard(controller: SongController, trackId: Int, index: Int) {
    val track = controller.song.track(trackId) ?: return
    val effect = track.effects.getOrNull(index) ?: return
    val plugin = controller.plugin(effect.pluginId)
    val bypassed = effect.bypass
    val many = track.effects.size > 1

    Column(
        Modifier
            .fillMaxWidth()
            .panel()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                plugin?.name?.removePrefix("PAW! ") ?: (effect.pluginId.substringAfterLast(':') + " (missing)"),
                style = NameStyle.copy(color = if (plugin == null || bypassed) Colors.dim else Colors.text),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (many) {
                // Chain order: the slot moves, the chain rebuilds (brief state reset).
                AppButton("▲", enabled = index > 0, accent = Colors.dim, fontSize = 11.sp,
                    onClick = { controller.moveEffect(trackId, index, -1) })
                AppButton("▼", enabled = index < track.effects.lastIndex, accent = Colors.dim, fontSize = 11.sp,
                    onClick = { controller.moveEffect(trackId, index, +1) })
            }
            if (plugin != null) {
                Toggle(if (bypassed) "Off" else "On", !bypassed, Colors.green, onClick = {
                    controller.setEffectState(trackId, index, !bypassed, effect.mix)
                }, modifier = Modifier.width(56.dp))
            }
        }
        if (plugin == null) {
            Text("Plugin file missing, kept but not running", style = DimStyle, modifier = Modifier.padding(top = 6.dp))
        } else {
            VSpace(4.dp)
            plugin.ports.forEachIndexed { portIndex, port ->
                PortSlider(controller, trackId, index, portIndex, port, effect.values.getOrNull(portIndex) ?: port.def)
            }
            MixSlider(controller, trackId, index, bypassed, effect.mix)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AppButton("Remove", accent = Colors.dim, fontSize = 12.sp, height = 34.dp,
                onClick = { controller.removeEffect(trackId, index) })
        }
    }
}

@Composable
private fun MixSlider(controller: SongController, trackId: Int, index: Int, bypassed: Boolean, mix: Float) {
    // Wet/dry under the plugin's own controls; 100% = fully wet.
    var liveMix by remember { mutableStateOf<Float?>(null) }
    val shown = liveMix ?: mix
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Wet / dry", style = DimStyle, modifier = Modifier.width(110.dp), maxLines = 1)
        MiniSlider(
            value = shown,
            accent = Colors.green,
            onStart = { controller.beginGesture() },
            onChange = { pos ->
                liveMix = pos
                controller.previewEffectMix(trackId, index, bypassed, pos)
            },
            onCommit = {
                liveMix?.let { controller.setEffectState(trackId, index, bypassed, it, undoable = false) }
                liveMix = null
            },
            onReset = {
                liveMix = null
                controller.setEffectState(trackId, index, bypassed, 1f)
            },
            modifier = Modifier.weight(1f),
        )
        HSpace(8.dp)
        Text("%.0f%%".format(shown * 100), style = MonoStyle, modifier = Modifier.width(60.dp))
    }
}

@Composable
private fun PortSlider(
    controller: SongController,
    trackId: Int,
    index: Int,
    portIndex: Int,
    port: PluginPort,
    value: Float,
) {
    var live by remember { mutableStateOf<Float?>(null) }
    val pos = live ?: portToPos(port, value)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            port.name,
            style = DimStyle,
            modifier = Modifier.width(110.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MiniSlider(
            value = pos,
            onStart = { controller.beginGesture() },
            onChange = { p ->
                live = p
                controller.previewEffectValue(trackId, index, portIndex, posToPort(port, p))
            },
            onCommit = {
                live?.let { controller.setEffectValue(trackId, index, portIndex, posToPort(port, it)) }
                live = null
            },
            onReset = {
                live = null
                controller.beginGesture()
                controller.setEffectValue(trackId, index, portIndex, port.def)
            },
            modifier = Modifier.weight(1f),
        )
        HSpace(8.dp)
        Text(
            formatPortValue(port, live?.let { posToPort(port, it) } ?: value),
            style = MonoStyle,
            modifier = Modifier.width(60.dp),
        )
    }
}

// ------------------------------------------------------------------- remove --

@Composable
private fun RemoveTrackRow(controller: SongController, trackId: Int, onBack: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        ConfirmRow(
            prompt = "Remove this track? Its recordings stay on disk.",
            confirmLabel = "Remove",
            onConfirm = {
                controller.removeTrack(trackId)
                onBack()
            },
            onCancel = { confirming = false },
        )
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AppButton("Remove track", accent = Colors.red, onClick = { confirming = true })
        }
    }
}

// Slider position <-> port value, honoring the LADSPA log/integer hints.
private fun portToPos(port: PluginPort, value: Float): Float {
    if (port.toggled) return if (value > 0.5f) 1f else 0f
    return if (port.logarithmic && port.min > 0f) {
        (ln(value.coerceIn(port.min, port.max) / port.min) / ln(port.max / port.min))
    } else {
        ((value - port.min) / (port.max - port.min)).coerceIn(0f, 1f)
    }
}

private fun posToPort(port: PluginPort, pos: Float): Float {
    if (port.toggled) return if (pos > 0.5f) 1f else 0f
    var v = if (port.logarithmic && port.min > 0f) {
        port.min * (port.max / port.min).pow(pos)
    } else {
        port.min + pos * (port.max - port.min)
    }
    if (port.integer) v = kotlin.math.round(v)
    return v.coerceIn(port.min, port.max)
}

private fun formatPortValue(port: PluginPort, value: Float): String = when {
    port.toggled -> if (value > 0.5f) "ON" else "OFF"
    port.integer -> "%.0f".format(value)
    kotlin.math.abs(value) >= 100f -> "%.0f".format(value)
    else -> "%.1f".format(value)
}
