package org.paw.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.paw.app.SongController
import org.paw.app.model.INPUT_CH1
import org.paw.app.model.INPUT_CH2
import org.paw.app.model.INPUT_NONE
import org.paw.app.model.INPUT_STEREO
import org.paw.app.model.InputDevice
import org.paw.app.model.MASTER_TRACK_ID
import org.paw.app.model.TRACK_BEAT
import org.paw.app.model.Track

// One track at a time: the strip on top names it and walks to the next one,
// shows its meter and its mute, solo and monitor switches, so those stay in
// reach on either tab. The Track tab holds level, input, name and removal;
// the FX tab its effect chain. The master gets the same panel with its
// output where an audio track has its input.

@Composable
internal fun InspectorPanel(
    controller: SongController,
    ui: SongUi,
    p: SongPanel.Inspector,
    compact: Boolean,
    modifier: Modifier,
) {
    val track = controller.song.track(p.trackId)
    if (track == null) {
        // Removed under the panel (undo of an add, or Remove itself).
        LaunchedEffect(p.trackId) { ui.close() }
        return
    }
    Column(modifier) {
        InspectorStrip(controller, ui, p, track)
        InspectorTabs(controller, ui, p, track, compact)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))
        when (p.tab) {
            InspectorTab.Track -> InspectorTrackTab(controller, ui, p, track, Modifier.weight(1f))
            InspectorTab.Fx -> InspectorFxTab(controller, track.id, Modifier.weight(1f))
        }
    }
}

/** ‹ name › and close, with the track's live meter along the bottom edge. */
@Composable
private fun InspectorStrip(controller: SongController, ui: SongUi, p: SongPanel.Inspector, track: Track) {
    val order = controller.song.tracks.map { it.id } + MASTER_TRACK_ID
    fun step(by: Int) {
        val i = order.indexOf(track.id).coerceAtLeast(0)
        val next = order[(i + by + order.size) % order.size]
        ui.open(p.copy(trackId = next, focusInput = false))
    }
    val isMaster = track.id == MASTER_TRACK_ID
    Column(Modifier.fillMaxWidth().background(Colors.panel)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(ChevronLeftGlyph, onClick = { step(-1) }, enabled = order.size > 1)
            Text(
                track.name,
                style = TitleStyle.copy(fontSize = 17.sp, color = if (isMaster) Colors.accent else Colors.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            IconButton(ChevronRightGlyph, onClick = { step(+1) }, enabled = order.size > 1)
            HSpace(4.dp)
            Box(Modifier.width(1.dp).height(28.dp).background(Colors.line))
            HSpace(4.dp)
            IconButton(CloseGlyph, onClick = ui::close)
        }
        val peak = if (isMaster) controller.masterPeak else controller.meters[track.id] ?: 0f
        MeterBar(peak, Modifier.fillMaxWidth(), hold = controller.meterHold, thickness = 4.dp)
    }
}

/** Track | FX n, and the track's switches beside them. */
@Composable
private fun InspectorTabs(controller: SongController, ui: SongUi, p: SongPanel.Inspector, track: Track, compact: Boolean) {
    val isMaster = track.id == MASTER_TRACK_ID
    Row(
        Modifier.fillMaxWidth().background(Colors.panel).padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Segmented(
            listOf("Track", if (track.effects.isEmpty()) "FX" else "FX ${track.effects.size}"),
            selected = if (p.tab == InspectorTab.Track) 0 else 1,
            onSelect = { ui.open(p.copy(tab = if (it == 0) InspectorTab.Track else InspectorTab.Fx, focusInput = false)) },
            modifier = Modifier.weight(1f),
        )
        HSpace(6.dp)
        Toggle("M", track.mute, Colors.red, onClick = {
            controller.updateTrack(track.id) { it.copy(mute = !it.mute) }
        })
        if (!isMaster) {
            Toggle("S", track.solo, Colors.green, onClick = {
                controller.updateTrack(track.id) { it.copy(solo = !it.solo) }
            })
        }
        if (!isMaster && track.kind != TRACK_BEAT) {
            Toggle("", track.monitor, Colors.green, icon = HeadphonesGlyph, onClick = {
                controller.setMonitor(track.id, !track.monitor)
            })
        }
    }
}

// ---------------------------------------------------------------- track tab --

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InspectorTrackTab(
    controller: SongController,
    ui: SongUi,
    p: SongPanel.Inspector,
    track: Track,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    val inputView = remember { BringIntoViewRequester() }
    if (p.focusInput) {
        LaunchedEffect(p) { inputView.bringIntoView() }
    }
    val isMaster = track.id == MASTER_TRACK_ID
    Column(
        modifier
            .fillMaxWidth()
            .verticalScrollbar(scroll)
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        LevelRows(controller, track, withPan = !isMaster)
        when {
            isMaster -> {
                Divider()
                val route = controller.outputOptions.find { it.key == controller.activeOutputRoute }?.label
                    ?: if (controller.outputDevice == "") "Automatic" else "Not connected"
                PanelLinkRow("Output", route, onClick = { ui.open(SongPanel.Io) })
            }
            track.kind == TRACK_BEAT -> {
                Divider()
                val n = track.samples.size
                PanelLinkRow("Open in Beats", if (n == 1) "1 sound" else "$n sounds", onClick = { ui.openBeats(track.id) })
            }
            else -> {
                Divider()
                Column(Modifier.bringIntoViewRequester(inputView)) {
                    InputList(controller, ui, track)
                }
            }
        }
        if (!isMaster) {
            Divider()
            RenameRow(controller, track)
            Divider()
            RemoveRow(controller, ui, track)
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(Colors.line))
}

/** Volume and pan; each value resets on a double tap, like its slider. */
@Composable
private fun LevelRows(controller: SongController, track: Track, withPan: Boolean) {
    var liveGain by remember(track.id) { mutableStateOf<Float?>(null) }
    var livePan by remember(track.id) { mutableStateOf<Float?>(null) }
    SettingRow("Volume", labelWidth = 72.dp) {
        GainSlider(controller, track.id, track.gain, Modifier.weight(1f), onLive = { liveGain = it })
        HSpace(10.dp)
        ValueReadout(
            formatDb(liveGain ?: track.gain),
            Modifier.width(72.dp),
            style = MonoStyle.copy(textAlign = TextAlign.End),
            onReset = { controller.updateTrack(track.id) { it.copy(gain = 1f) } },
        )
    }
    if (withPan) {
        SettingRow("Pan", labelWidth = 72.dp) {
            PanSlider(controller, track.id, track.pan, Modifier.weight(1f), onLive = { livePan = it })
            HSpace(10.dp)
            ValueReadout(
                formatPan(livePan ?: track.pan),
                Modifier.width(72.dp),
                style = MonoStyle.copy(textAlign = TextAlign.End),
                onReset = { controller.updateTrack(track.id) { it.copy(pan = 0f) } },
            )
        }
    }
}

/** A row that opens somewhere else: label, what is there now, chevron. */
@Composable
internal fun PanelLinkRow(label: String, detail: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = BodyStyle, modifier = Modifier.weight(1f), maxLines = 1)
        Text(
            detail,
            style = MonoDimStyle.copy(fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
        HSpace(6.dp)
        androidx.compose.foundation.Canvas(Modifier.width(16.dp).height(16.dp)) { ChevronRightGlyph(Colors.dim) }
    }
}

/** Where an audio track records from: each interface's inputs, the phone
 *  mic, or nothing. */
@Composable
private fun InputList(controller: SongController, ui: SongUi, track: Track) {
    val usbOptions = controller.inputOptions.filter { it.device.isUsb }
    fun choose(device: InputDevice, mode: Int) {
        controller.updateTrack(track.id) { it.copy(inputDevice = device, inputMode = mode) }
    }
    SectionLabel("Input", Modifier.padding(top = 4.dp, bottom = 4.dp))
    usbOptions.forEachIndexed { index, opt ->
        val onThis = track.inputDevice == opt.device ||
            (track.inputDevice == InputDevice.AnyUsb && index == 0)
        RadioRow("${opt.label} · Input 1", onThis && track.inputMode == INPUT_CH1) { choose(opt.device, INPUT_CH1) }
        RadioRow("${opt.label} · Input 2", onThis && track.inputMode == INPUT_CH2) { choose(opt.device, INPUT_CH2) }
        RadioRow("${opt.label} · Inputs 1+2", onThis && track.inputMode == INPUT_STEREO, detail = "stereo") {
            choose(opt.device, INPUT_STEREO)
        }
    }
    val micOn = track.inputDevice == InputDevice.Mic && track.inputMode != INPUT_NONE
    RadioRow("Phone mic", micOn) { choose(InputDevice.Mic, INPUT_CH1) }
    if (micOn) {
        PanelLinkRow(
            "Mic boost",
            "+%.0f dB".format(controller.micBoostDb),
            onClick = { ui.open(SongPanel.Io) },
            modifier = Modifier.padding(start = 44.dp),
        )
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
        val savedName = (track.inputDevice as? InputDevice.Usb)?.name ?: "Interface"
        Text(
            "$savedName is not connected",
            style = DimStyle.copy(color = Colors.red),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun RenameRow(controller: SongController, track: Track) {
    var editing by remember(track.id) { mutableStateOf<String?>(null) }
    val name = editing
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (name == null) {
            Text("Name", style = BodyStyle, modifier = Modifier.width(72.dp))
            Text(
                track.name,
                style = BodyStyle.copy(color = Colors.dim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AppButton("Rename", accent = Colors.text, onClick = { editing = track.name })
        } else {
            NameField(name, { editing = it }, Modifier.weight(1f), maxLength = 24)
            AppButton("Save", accent = Colors.accent, filled = true, enabled = name.isNotBlank(), onClick = {
                controller.renameTrack(track.id, name)
                editing = null
            })
            IconButton(CloseGlyph, onClick = { editing = null }, tint = Colors.dim)
        }
    }
}
/** Last in the panel and quiet until asked. Undo brings the track back,
 *  but it still asks first: a track can hold a lot of work. */
 *  it still asks, because a track can hold a lot of work. */
@Composable
private fun RemoveRow(controller: SongController, ui: SongUi, track: Track) {
    var confirming by remember(track.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
        if (confirming) {
            ConfirmRow(
                prompt = "Remove ${track.name}?",
                confirmLabel = "Remove",
                onConfirm = {
                    controller.removeTrack(track.id)
                    ui.close()
                },
                onCancel = { confirming = false },
            )
        } else {
            AppButton("Remove track", accent = Colors.red, onClick = { confirming = true })
        }
    }
}

/** Column body helper for the other panels: the scrolling content area
 *  under a header, padded like the inspector's. */
@Composable
internal fun PanelBody(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .verticalScrollbar(scroll)
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        content = content,
    )
}

@Composable
internal fun PanelDivider() = Divider()