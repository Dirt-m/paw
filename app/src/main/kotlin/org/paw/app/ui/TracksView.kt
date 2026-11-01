package org.paw.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.paw.app.AppPrefs
import org.paw.app.PeakData
import org.paw.app.SongController
import org.paw.app.TRANSPORT_RECORDING
import org.paw.app.TRANSPORT_STOPPED
import org.paw.app.model.Clip
import org.paw.app.model.INPUT_CH2
import org.paw.app.model.INPUT_NONE
import org.paw.app.model.INPUT_STEREO
import org.paw.app.model.MASTER_TRACK_ID
import org.paw.app.model.MIN_CLIP_FRAMES
import org.paw.app.model.TRACK_BEAT
import org.paw.app.model.InputDevice
import org.paw.app.model.Track

// The Tracks mode: ruler block, lanes with their headers, the add row.

// --------------------------------------------------------------- tracks view --

@Composable
internal fun TracksView(
    controller: SongController,
    ui: SongUi,
    tl: TimelineState,
    peaks: Map<String, PeakData>,
    compact: Boolean,
    short: Boolean,
) {
    val onOpenTrack: (Int) -> Unit = { ui.open(SongPanel.Inspector(it)) }
    val onOpenSampler: (Int, Int?) -> Unit = { t, p -> ui.openBeats(t, p) }
    val song = controller.song
    val headerW = if (compact) HEADER_W_COMPACT else HEADER_W
    val laneMin = if (short) 72.dp else 84.dp

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(OVERVIEW_H + RULER_H)) {
            ZoomControls(controller, tl, Modifier.width(headerW).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Overview(tl, controller, Modifier.fillMaxWidth().height(OVERVIEW_H))
                Ruler(tl, controller, Modifier.fillMaxWidth().weight(1f))
            }
        }

        BoxWithConstraints(Modifier.weight(1f)) {
            // A handful of tracks grows to fill the viewport; past the cap
            // the list scrolls at the standard height instead.
            val laneH =
                if (song.tracks.isEmpty()) laneMin
                else ((maxHeight - 48.dp) / song.tracks.size).coerceIn(laneMin, LANE_H_MAX)
            val laneHPx = with(LocalDensity.current) { laneH.toPx() }
            val laneScroll = rememberScrollState()
            var viewportPx by remember { mutableIntStateOf(0) }

            // The timeline continues under the last track: grid and playhead
            // run the full height, so leftover space reads as workspace.
            Canvas(Modifier.fillMaxSize().padding(start = headerW)) {
                drawGrid(tl, song.sampleRate)
                val px = tl.pxOf(controller.playheadFrame)
                if (px in 0f..size.width) {
                    drawLine(Colors.accent, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2f)
                }
            }

            val headerPx = with(LocalDensity.current) { headerW.toPx() }
            val trackCount = song.tracks.size
            Column(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportPx = it.height }
                    .emptyAreaGestures(tl, controller, headerPx) {
                        trackCount * laneHPx - laneScroll.value
                    }
                    .verticalScroll(laneScroll),
            ) {
                song.tracks.forEachIndexed { index, track ->
                    key(track.id) {
                        Row(Modifier.fillMaxWidth().height(laneH)) {
                            TrackHeader(
                                track, controller, onOpenTrack, onOpenSampler, compact = compact, short = short,
                                modifier = Modifier.width(headerW).fillMaxHeight(),
                            )
                            ClipLane(
                                track, controller, tl, peaks,
                                laneScroll, index * laneHPx, viewportPx,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
                // New tracks are made where the tracks live, both kinds on
                // one row so neither scrolls away under a full song.
                Row(
                    Modifier
                        .width(headerW)
                        .height(48.dp)
                        .background(Colors.panel)
                        .border(1.dp, Colors.line),
                ) {
                    for ((label, add) in listOf<Pair<String, () -> Unit>>(
                        "+ Track" to { controller.addTrack() },
                        "+ Beats" to { controller.addBeatTrack() },
                    )) {
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clickable(onClick = add),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, style = ButtonStyle.copy(color = Colors.accent), maxLines = 1)
                        }
                        if (label == "+ Track") {
                            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(Colors.line))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Zoom out, fit the song, zoom in: the visible way to do what a pinch does.
 * Zooming keeps the playhead where it is on screen when it is in view, else
 * the middle of the view.
 */
@Composable
private fun ZoomControls(controller: SongController, tl: TimelineState, modifier: Modifier) {
    fun zoom(factor: Float) {
        val ph = tl.pxOf(controller.playheadFrame)
        val center = if (ph in 0f..tl.laneWidthPx) ph else tl.laneWidthPx / 2f
        tl.zoom(factor, center)
    }
    Row(
        modifier.background(Colors.panel).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppButton("−", onClick = { zoom(1f / 1.6f) }, accent = Colors.dim, fontSize = 18.sp,
            modifier = Modifier.weight(1f), padding = 0.dp)
        AppButton("Fit", onClick = {
            tl.fitTo(maxOf(controller.song.endFrame, FIT_MIN_SECONDS * controller.song.sampleRate))
        }, accent = Colors.dim, fontSize = 12.sp, modifier = Modifier.weight(1.3f), padding = 0.dp)
        AppButton("+", onClick = { zoom(1.6f) }, accent = Colors.dim, fontSize = 18.sp,
            modifier = Modifier.weight(1f), padding = 0.dp)
    }
}

/**
 * The lane's left panel: name, FX, then arm / monitor / mute / solo, and the
 * level meter. FX opens the track's own screen (input, level, effects); the
 * rest of the header opens it too, so a near miss still lands there.
 */
@Composable
private fun TrackHeader(
    track: Track,
    controller: SongController,
    onOpenTrack: (Int) -> Unit,
    onOpenSampler: (trackId: Int, patternId: Int?) -> Unit,
    compact: Boolean,
    short: Boolean,
    modifier: Modifier = Modifier,
) {
    val btn = if (short) 30.dp else 34.dp
    Column(
        modifier
            .background(Colors.panel)
            .border(1.dp, Colors.line)
            .clickable { onOpenTrack(track.id) }
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                track.name,
                style = NameStyle.copy(fontSize = if (compact) 14.sp else 15.sp),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.armed) {
                // What lands on this track when REC goes; only relevant while
                // armed.
                Text(
                    inputBadge(track),
                    style = LabelStyle.copy(color = Colors.red, fontSize = 10.sp, letterSpacing = 0.5.sp),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            HSpace(4.dp)
            FxChip { onOpenTrack(track.id) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (track.kind == TRACK_BEAT) {
                // A beat track has no input to arm or monitor; its two slots
                // open the sampler instead.
                AppButton(
                    "Grid", onClick = { onOpenSampler(track.id, null) },
                    accent = Colors.accent, height = btn, fontSize = 12.sp,
                    modifier = Modifier.weight(2f),
                )
            } else {
                Toggle("●", track.armed, Colors.red, size = btn, fontSize = 14.sp, modifier = Modifier.weight(1f), onClick = {
                    controller.updateTrack(track.id) { it.copy(armed = !it.armed) }
                })
                MonitorToggle(track, controller, btn, Modifier.weight(1f))
            }
            Toggle("M", track.mute, Colors.accent, size = btn, modifier = Modifier.weight(1f), onClick = {
                controller.updateTrack(track.id) { it.copy(mute = !it.mute) }
            })
            Toggle("S", track.solo, Colors.green, size = btn, modifier = Modifier.weight(1f), onClick = {
                controller.updateTrack(track.id) { it.copy(solo = !it.solo) }
            })
        }
        LiveMeter(controller, track.id, Modifier.fillMaxWidth(), thickness = 6.dp)
    }
}

/** Software input monitoring: the armed input through the chain to the mix. */
@Composable
internal fun MonitorToggle(track: Track, controller: SongController, size: Dp, modifier: Modifier = Modifier) {
    Toggle("", track.monitor, Colors.green, size = size, modifier = modifier, icon = HeadphonesGlyph, onClick = {
        controller.updateTrack(track.id) { it.copy(monitor = !it.monitor) }
    })
}

/** The way into a track's own screen, labelled for what most visits are for. */
@Composable
internal fun FxChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.corner)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, Colors.accent.copy(alpha = 0.55f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("FX", style = LabelStyle.copy(color = Colors.accent, fontSize = 11.sp, letterSpacing = 1.sp), maxLines = 1)
    }
}

// Levels move at the poll rate, so the read lives in its own composable: a
// meter change repaints one bar instead of recomposing a whole header.
@Composable
internal fun LiveMeter(
    controller: SongController,
    trackId: Int,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    thickness: Dp = 8.dp,
) {
    MeterBar(
        if (trackId == MASTER_TRACK_ID) controller.masterPeak
        else controller.meters[trackId] ?: 0f,
        modifier,
        hold = controller.meterHold,
        resetKey = controller.status.transport != TRANSPORT_STOPPED,
        vertical = vertical,
        thickness = thickness,
    )
}

// The badge names the source device, not just a channel number:
// "DUO 1", "DUO 1+2", "MIC", "NO INPUT".
internal fun inputBadge(track: Track): String {
    val device = track.inputDevice
    if (device == InputDevice.None || track.inputMode == INPUT_NONE) return "NO INPUT"
    if (device == InputDevice.Mic) return "MIC"
    val name = (device as? InputDevice.Usb)?.name ?: "USB"
    val short = shortDeviceName(name)
    val ch = when (track.inputMode) {
        INPUT_CH2 -> "2"
        INPUT_STEREO -> "1+2"
        else -> "1"
    }
    return if (short.isEmpty()) "IN $ch" else "$short $ch"
}

/**
 * Appears only while the playhead is off screen, as an arrow on the lane edge it
 * went past; one tap brings the view back and re-engages follow. Reads the
 * playhead itself so only this chip recomposes per tick.
 */
@Composable
internal fun BoxScope.PlayheadChip(controller: SongController, tl: TimelineState, compact: Boolean) {
    val ph = controller.playheadFrame
    if (!tl.isOffscreen(ph)) return
    val left = tl.pxOf(ph) < 0f
    val headerW = if (compact) HEADER_W_COMPACT else HEADER_W
    val shape = RoundedCornerShape(Dimens.corner)
    Box(
        Modifier
            .align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(start = if (left) headerW + 6.dp else 0.dp, end = if (left) 0.dp else 6.dp)
            .size(width = 36.dp, height = 32.dp)
            .background(Colors.bg, shape)
            .background(Colors.accent.copy(alpha = 0.22f), shape)
            .border(1.dp, Colors.accent, shape)
            .clickable {
                tl.follow = true
                tl.showFrame(controller.playheadFrame)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (left) "◀" else "▶",
            style = ButtonStyle.copy(color = Colors.accent, fontSize = 14.sp),
        )
    }
}

@Composable
internal fun MessageBanner(controller: SongController, modifier: Modifier) {
    val msg = controller.message ?: return
    Box(
        modifier
            .background(Colors.raised, androidx.compose.foundation.shape.RoundedCornerShape(Dimens.corner))
            .border(1.dp, Colors.accent, androidx.compose.foundation.shape.RoundedCornerShape(Dimens.corner))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(msg, style = BodyStyle.copy(color = Colors.accent), maxLines = 2)
    }
}
