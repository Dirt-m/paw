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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.paw.app.PeakData
import org.paw.app.SongController
import org.paw.app.TRANSPORT_STOPPED
import org.paw.app.model.INPUT_CH2
import org.paw.app.model.INPUT_NONE
import org.paw.app.model.INPUT_STEREO
import org.paw.app.model.InputDevice
import org.paw.app.model.MASTER_TRACK_ID
import org.paw.app.model.SnapGrid
import org.paw.app.model.TRACK_BEAT
import org.paw.app.model.Track
import org.paw.app.model.barStartFrame

// The Tracks mode: the ruler block, the lanes with their headers, the add row.

/** Portrait headers hold their toggles 2 by 2; a wide window has room for one row of four. */
internal fun tracksHeaderWidth(compact: Boolean): Dp = if (compact) 116.dp else 200.dp

private val OVERVIEW_ROW_H = 40.dp
private val OVERVIEW_STRIP_H = 16.dp
private val TRACKS_RULER_H = 28.dp
private val ZOOM_W = 86.dp
private val LANE_MIN = 104.dp
private val LANE_MAX = 128.dp
private val LANE_MIN_SHORT = 64.dp
private val LANE_MAX_SHORT = 88.dp

/** Makes [trackId] the one the panels act on, and the one Beats opens on
 *  when it is a beat track. */
internal fun selectTrack(controller: SongController, ui: SongUi, trackId: Int) {
    controller.selectedTrack = trackId
    if (controller.song.track(trackId)?.kind == TRACK_BEAT && ui.beatsTrack != trackId) {
        ui.beatsTrack = trackId
        if (controller.song.pattern(ui.beatsPattern)?.track != trackId) ui.beatsPattern = null
    }
}

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
    val song = controller.song
    val headerW = tracksHeaderWidth(compact)
    val laneMin = if (short) LANE_MIN_SHORT else LANE_MIN
    val laneMax = if (short) LANE_MAX_SHORT else LANE_MAX
    val addRowH = if (short) 44.dp else 48.dp

    Column(Modifier.fillMaxSize()) {
        RulerBlock(controller, ui, tl, headerW, wide = !compact)

        BoxWithConstraints(Modifier.weight(1f)) {
            // A handful of tracks grows to fill the viewport; past the cap
            // the list scrolls at the standard height instead.
            val laneH =
                if (song.tracks.isEmpty()) laneMin
                else ((maxHeight - addRowH) / song.tracks.size).coerceIn(laneMin, laneMax)
            val laneHPx = with(LocalDensity.current) { laneH.toPx() }
            val laneScroll = rememberScrollState()
            var viewportPx by remember { mutableIntStateOf(0) }

            // The timeline continues under the last track: grid and playhead
            // run the full height, so leftover space reads as workspace.
            Canvas(Modifier.fillMaxSize().padding(start = headerW)) {
                drawGrid(tl, song, ui.clockAsTime)
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
                        val selected = controller.selectedTrack == track.id
                        Row(Modifier.fillMaxWidth().height(laneH)) {
                            TrackHeader(
                                track, controller, ui, selected, wide = !compact,
                                modifier = Modifier.width(headerW).fillMaxHeight(),
                            )
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                ClipLane(
                                    track, controller, ui, tl, peaks,
                                    laneScroll, index * laneHPx, viewportPx,
                                    trackSelected = selected,
                                    onSelectTrack = { selectTrack(controller, ui, it) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                // Only while nothing on the lane is selected: the ghost would
                                // sit on a selected clip's trim grips.
                                if (selected && track.kind == TRACK_BEAT && controller.selectedClip?.first != track.id) {
                                    PatternGhost(controller, ui, tl, track)
                                }
                            }
                        }
                    }
                }
                AddRow(controller, ui, Modifier.fillMaxWidth().height(addRowH))
            }
        }
    }
}

/**
 * New tracks are made where the tracks live, and the song's recordings are
 * one tap from the same row, so none of it scrolls away under a full song.
 */
@Composable
private fun AddRow(controller: SongController, ui: SongUi, modifier: Modifier) {
    Row(
        modifier
            .background(Colors.panel)
            .drawBehind { drawLine(Colors.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppButton("+ Audio", accent = Colors.accent, padding = 12.dp, onClick = { controller.addTrack() })
        AppButton("+ Beat", accent = Colors.accent, padding = 12.dp, onClick = {
            selectTrack(controller, ui, controller.addBeatTrack())
        })
        AppButton("Takes", padding = 12.dp, onClick = { ui.open(SongPanel.Takes) })
    }
}

// ---------------------------------------------------------------- ruler block --

/**
 * Fit and Snap in the corner over the headers; the overview strip and the
 * bar ruler over the lanes. Portrait puts − and + beside the overview, so the
 * ruler keeps the full lane width; a wide window lines all four up in the
 * corner.
 */
@Composable
private fun RulerBlock(controller: SongController, ui: SongUi, tl: TimelineState, headerW: Dp, wide: Boolean) {
    fun zoom(factor: Float) {
        // Zooming keeps the playhead where it is on screen when it is in
        // view, else the middle of the view.
        val ph = tl.pxOf(controller.playheadFrame)
        val center = if (ph in 0f..tl.laneWidthPx) ph else tl.laneWidthPx / 2f
        tl.zoom(factor, center)
    }
    val zoomOut: @Composable (Modifier) -> Unit = { m ->
        AppButton("−", onClick = { zoom(1f / 1.6f) }, accent = Colors.dim, fontSize = 18.sp, modifier = m, padding = 0.dp)
    }
    val zoomIn: @Composable (Modifier) -> Unit = { m ->
        AppButton("+", onClick = { zoom(1.6f) }, accent = Colors.dim, fontSize = 18.sp, modifier = m, padding = 0.dp)
    }
    val corner: @Composable RowScope.() -> Unit = {
        AppButton("Fit", onClick = {
            tl.fitTo(maxOf(controller.song.endFrame, FIT_MIN_SECONDS * controller.song.sampleRate))
        }, accent = Colors.dim, fontSize = 12.sp, modifier = Modifier.weight(1f), padding = 0.dp)
        SnapButton(ui, Modifier.weight(1.35f))
    }

    if (wide) {
        Row(Modifier.fillMaxWidth().height(44.dp)) {
            Row(
                Modifier.width(headerW).fillMaxHeight().background(Colors.panel).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                corner()
                zoomOut(Modifier.width(Dimens.touch))
                zoomIn(Modifier.width(Dimens.touch))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Overview(tl, controller, Modifier.fillMaxWidth().height(OVERVIEW_STRIP_H))
                Ruler(tl, controller, ui, Modifier.fillMaxWidth().weight(1f))
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().height(OVERVIEW_ROW_H + TRACKS_RULER_H)) {
            Row(
                Modifier.width(headerW).fillMaxHeight().background(Colors.panel).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                content = corner,
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().height(OVERVIEW_ROW_H)) {
                    Overview(tl, controller, Modifier.weight(1f).fillMaxHeight())
                    Row(
                        Modifier.width(ZOOM_W).fillMaxHeight().background(Colors.panel).padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        zoomOut(Modifier.width(Dimens.touch))
                        zoomIn(Modifier.width(Dimens.touch))
                    }
                }
                Ruler(tl, controller, ui, Modifier.fillMaxWidth().height(TRACKS_RULER_H))
            }
        }
    }
}

/** Cycles what edits land on: bar, beat, nothing. Shows the current one. */
@Composable
private fun SnapButton(ui: SongUi, modifier: Modifier) {
    val shape = RoundedCornerShape(Dimens.corner)
    val on = ui.snap != SnapGrid.Off
    Column(
        modifier
            .heightIn(min = Dimens.touch)
            .clip(shape)
            .background(if (on) Colors.accent.copy(alpha = 0.12f) else Colors.panel)
            .border(1.dp, if (on) Colors.accent.copy(alpha = 0.55f) else Colors.line, shape)
            .clickable {
                ui.snap = when (ui.snap) {
                    SnapGrid.Bar -> SnapGrid.Beat
                    SnapGrid.Beat -> SnapGrid.Off
                    SnapGrid.Off -> SnapGrid.Bar
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("SNAP", style = LabelStyle.copy(fontSize = 8.sp, letterSpacing = 1.sp), maxLines = 1)
        Text(
            ui.snap.name,
            style = ButtonStyle.copy(color = if (on) Colors.accent else Colors.dim, fontSize = 13.sp),
            maxLines = 1,
        )
    }
}

// -------------------------------------------------------------- track header --

/**
 * The lane's left panel: name, input, arm / monitor / mute / solo, and the
 * level meter down the right edge. The name or any bare part of the header
 * selects the track and opens its inspector; the input badge opens it at the
 * input list.
 */
@Composable
private fun TrackHeader(
    track: Track,
    controller: SongController,
    ui: SongUi,
    selected: Boolean,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val open: (Boolean) -> Unit = { focusInput ->
        selectTrack(controller, ui, track.id)
        ui.open(SongPanel.Inspector(track.id, focusInput = focusInput))
    }
    Row(
        modifier
            .background(if (selected) Colors.raised else Colors.panel)
            .border(1.dp, Colors.line)
            .then(if (selected) Modifier.border(2.dp, Colors.accent) else Modifier)
            .clickable { open(false) }
            .padding(start = 6.dp, end = 4.dp, top = 2.dp, bottom = 3.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val beat = track.kind == TRACK_BEAT
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (beat) BeatTitle(track, wide)
                else AudioTitle(track, wide) { open(true) }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (beat) {
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.weight(1f))
                    } else {
                        InputToggles(track, controller)
                    }
                    MixToggles(track, controller)
                }
            } else {
                if (!beat) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { InputToggles(track, controller) }
                    Spacer(Modifier.height(2.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { MixToggles(track, controller) }
            }
        }
        Spacer(Modifier.width(4.dp))
        LiveMeter(controller, track.id, Modifier.fillMaxHeight().padding(vertical = 3.dp), vertical = true, thickness = 5.dp)
    }
}

/** Name and input badge: stacked when the header has the height, side by
 *  side when it does not. */
@Composable
private fun BoxScope.AudioTitle(track: Track, wide: Boolean, onInput: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (!wide && maxHeight >= 38.dp) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                TrackName(track.name, Modifier.fillMaxWidth())
                InputBadge(track, onInput)
            }
        } else {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                TrackName(track.name, Modifier.weight(1f))
                InputBadge(track, onInput)
            }
        }
    }
}

@Composable
private fun BoxScope.BeatTitle(track: Track, wide: Boolean) {
    val n = track.samples.size
    val sounds = when (n) {
        0 -> "No sounds"
        1 -> "1 sound"
        else -> "$n sounds"
    }
    val soundsStyle = DimStyle.copy(fontSize = 11.sp)
    if (wide) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            TrackName(track.name, Modifier.weight(1f))
            Text(sounds, style = soundsStyle, maxLines = 1)
        }
    } else {
        Column(Modifier.fillMaxSize().padding(top = 2.dp)) {
            TrackName(track.name, Modifier.fillMaxWidth())
            Text(sounds, style = soundsStyle, maxLines = 1)
        }
    }
}

@Composable
private fun TrackName(name: String, modifier: Modifier) {
    Text(
        name,
        style = NameStyle.copy(fontSize = 13.sp),
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** What lands on the track when Rec goes. Red while armed, yellow when
 *  nothing can. */
@Composable
private fun InputBadge(track: Track, onClick: () -> Unit) {
    val label = inputBadge(track)
    val color = when {
        track.inputDevice == InputDevice.None || track.inputMode == INPUT_NONE -> Colors.warn
        track.armed -> Colors.red
        else -> Colors.dim
    }
    Text(
        "$label ▾",
        style = LabelStyle.copy(color = color, fontSize = 10.sp, letterSpacing = 0.5.sp),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    )
}

@Composable
private fun RowScope.InputToggles(track: Track, controller: SongController) {
    Toggle("", track.armed, Colors.red, icon = ArmGlyph, modifier = Modifier.weight(1f).height(Dimens.touch), onClick = {
        controller.setArmed(track.id, !track.armed)
    })
    MonitorToggle(track, controller, Dimens.touch, Modifier.weight(1f).height(Dimens.touch))
}

@Composable
private fun RowScope.MixToggles(track: Track, controller: SongController) {
    Toggle("M", track.mute, Colors.accent, modifier = Modifier.weight(1f).height(Dimens.touch), onClick = {
        controller.updateTrack(track.id) { it.copy(mute = !it.mute) }
    })
    Toggle("S", track.solo, Colors.green, modifier = Modifier.weight(1f).height(Dimens.touch), onClick = {
        controller.updateTrack(track.id) { it.copy(solo = !it.solo) }
    })
}

/** Software input monitoring: the armed input through the chain to the mix. */
@Composable
internal fun MonitorToggle(track: Track, controller: SongController, size: Dp, modifier: Modifier = Modifier) {
    Toggle("", track.monitor, Colors.green, size = size, modifier = modifier, icon = HeadphonesGlyph, onClick = {
        controller.setMonitor(track.id, !track.monitor)
    })
}

/** The way into a track's effects, where the mixer shows one. */
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

// ------------------------------------------------------------- pattern ghost --

/**
 * On the selected beat lane: one tap puts the pattern Beats last showed (or
 * the track's first) at the bar after the playhead. Reads the playhead, so it
 * lives apart from the lane and only it follows the clock.
 */
@Composable
private fun BoxScope.PatternGhost(controller: SongController, ui: SongUi, tl: TimelineState, track: Track) {
    val patterns = controller.patternsFor(track.id)
    val pattern = patterns.find { it.id == ui.beatsPattern } ?: patterns.firstOrNull() ?: return
    val bar = controller.nextBarIndex()
    val x = tl.pxOf(controller.song.barStartFrame(bar))
    if (x < 0f || x > tl.laneWidthPx - with(LocalDensity.current) { 48.dp.toPx() }) return
    val shape = RoundedCornerShape(Dimens.corner)
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .offset { IntOffset(x.toInt(), 0) }
            .padding(start = 2.dp, bottom = 6.dp)
            .height(Dimens.touch)
            .clip(shape)
            .background(Colors.bg.copy(alpha = 0.88f))
            .drawBehind {
                drawRoundRect(
                    Colors.accent,
                    cornerRadius = CornerRadius(Dimens.corner.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))),
                )
            }
            .clickable { controller.placePatternAt(track.id, pattern.id, bar) }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("+ ${pattern.name}", style = ButtonStyle.copy(color = Colors.accent), maxLines = 1)
    }
}

// ------------------------------------------------------------ overlays -------

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
    val headerW = tracksHeaderWidth(compact)
    val shape = RoundedCornerShape(Dimens.corner)
    Box(
        Modifier
            .align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(start = if (left) headerW + 6.dp else 0.dp, end = if (left) 0.dp else 6.dp)
            .size(width = 40.dp, height = 40.dp)
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
    val shape = RoundedCornerShape(Dimens.corner)
    Box(
        modifier
            .background(Colors.raised, shape)
            .border(1.dp, Colors.accent, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(msg, style = BodyStyle.copy(color = Colors.accent), maxLines = 2)
    }
}
