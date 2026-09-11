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
import org.paw.app.model.InputDevice
import org.paw.app.model.Track

// The song workspace. Middle: TRACKS (timeline) or MIX (faders), switched by
// tabs. Bottom: the transport under the thumb, clock above it, with a selected
// clip's action row between the two. Nothing depends on a hidden gesture: every
// action has a control, and gestures are shortcuts.

private val HEADER_W = 168.dp
private val HEADER_W_COMPACT = 124.dp
private val LANE_H_MAX = 128.dp

enum class SongView { Tracks, Mix }

@Composable
fun SongScreen(
    controller: SongController,
    onBack: () -> Unit,
    onOpenTrack: (Int) -> Unit,
    onOpenTakes: () -> Unit,
    onOpenOptions: () -> Unit,
    initialView: SongView = SongView.Tracks,
) {
    val context = LocalContext.current
    val prefs = remember(context) { AppPrefs.of(context) }
    val tl = remember(controller) {
        TimelineState().also { t ->
            prefs.zoomFor(controller.projectDir.name)?.let { t.framesPerPx = it }
        }
    }
    // Zoom survives leaving the song; the write sits out the pinch.
    LaunchedEffect(tl) {
        snapshotFlow { tl.framesPerPx }.collectLatest { fpp ->
            delay(400)
            prefs.setZoomFor(controller.projectDir.name, fpp)
        }
    }
    val song = controller.song
    var view by rememberSaveable { mutableStateOf(initialView) }

    // Waveform peaks, loaded off the UI thread and keyed by take path.
    val peaks = remember(controller) { mutableStateMapOf<String, PeakData>() }
    val takeSet = remember(song.tracks) {
        song.tracks.flatMapTo(mutableSetOf()) { t -> t.clips.map { it.take } }
    }
    LaunchedEffect(takeSet) {
        for (take in takeSet) {
            if (take !in peaks) {
                controller.peaksFor(take)?.let { peaks[take] = it }
            }
        }
    }

    // Watched as a flow: reading the playhead in composition here would
    // recompose the whole screen per tick.
    LaunchedEffect(controller, tl) {
        snapshotFlow { controller.playheadFrame }.collect { ph ->
            if (!tl.follow || controller.status.transport == TRANSPORT_STOPPED) return@collect
            val w = tl.laneWidthPx
            if (w <= 0f) return@collect
            val px = tl.pxOf(ph)
            if (controller.followSmooth) {
                if (px < 0f || px > w * 0.5f) {
                    tl.originFrame = (ph - (w * 0.5f * tl.framesPerPx).toLong()).coerceAtLeast(0L)
                }
            } else if (px < 0f || px > w * 0.88f) {
                tl.originFrame = (ph - (w * 0.12f * tl.framesPerPx).toLong()).coerceAtLeast(0L)
            }
        }
    }

    // Transient messages clear themselves.
    LaunchedEffect(controller.message) {
        if (controller.message != null) {
            delay(4000)
            controller.message = null
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Colors.bg)) {
        val compact = maxWidth < COMPACT_WIDTH
        val short = maxHeight < SHORT_HEIGHT

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            SongTopBar(
                controller, view, onView = { view = it }, onBack, onOpenTrack,
                onOpenTakes, onOpenOptions, compact = compact,
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (view) {
                    SongView.Tracks -> TracksView(controller, tl, peaks, onOpenTrack, compact, short)
                    SongView.Mix -> MixerView(controller, onOpenTrack, short)
                }
                if (view == SongView.Tracks) PlayheadChip(controller, tl, compact)
                MessageBanner(controller, Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }

            if (view == SongView.Tracks) SelectionBar(controller, compact)
            TransportBar(controller, tl, compact = compact, short = short)
        }
    }
}

// ------------------------------------------------------------------ top bar --

@Composable
private fun SongTopBar(
    controller: SongController,
    view: SongView,
    onView: (SongView) -> Unit,
    onBack: () -> Unit,
    onOpenTrack: (Int) -> Unit,
    onOpenTakes: () -> Unit,
    onOpenOptions: () -> Unit,
    compact: Boolean,
) {
    val song = controller.song
    val scope = rememberCoroutineScope()
    val hasAudio = remember(song) { song.endFrame > 0 }
    var menuOpen by remember { mutableStateOf(false) }

    val tabs: @Composable (Modifier) -> Unit = { m ->
        Segmented(
            options = listOf("Tracks", "Mix"),
            selected = if (view == SongView.Tracks) 0 else 1,
            onSelect = { onView(if (it == 0) SongView.Tracks else SongView.Mix) },
            modifier = m,
        )
    }
    val rightSide: @Composable RowScope.() -> Unit = {
        IoChip(controller) { onOpenTrack(MASTER_TRACK_ID) }
        HSpace(4.dp)
        AppButton("↶", enabled = controller.canUndo, onClick = { controller.undo() },
            accent = Colors.dim, fontSize = 18.sp)
        HSpace(4.dp)
        AppButton("↷", enabled = controller.canRedo, onClick = { controller.redo() },
            accent = Colors.dim, fontSize = 18.sp)
        HSpace(4.dp)
        Box {
            AppButton("⋮", onClick = { menuOpen = true }, accent = Colors.text, fontSize = 20.sp)
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Recordings", style = BodyStyle) },
                    onClick = { menuOpen = false; onOpenTakes() },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (controller.exporting) "Exporting…" else "Export mixdown",
                            style = BodyStyle.copy(color = if (hasAudio) Colors.text else Colors.dim),
                        )
                    },
                    enabled = hasAudio && !controller.exporting,
                    onClick = { menuOpen = false; scope.launch { controller.exportMixdown() } },
                )
                DropdownMenuItem(
                    text = { Text("Settings", style = BodyStyle) },
                    onClick = { menuOpen = false; onOpenOptions() },
                )
            }
        }
    }

    Column(Modifier.fillMaxWidth().background(Colors.panel)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton("Songs", onBack)
            HSpace(4.dp)
            Text(
                song.name,
                style = TitleStyle.copy(color = Colors.amber, fontSize = 18.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!compact) {
                tabs(Modifier.width(200.dp))
                HSpace(12.dp)
            }
            rightSide()
        }
        if (compact) {
            tabs(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))
    }
}

/** What the engine is listening to right now: DUO / MIC, or the trouble. */
@Composable
private fun IoChip(controller: SongController, onClick: () -> Unit) {
    val st = controller.status
    val (label, color) = when {
        st.deviceLost -> "LOST" to Colors.red
        !st.inputOpen -> "NO INPUT" to Colors.dim
        controller.currentInput == InputDevice.Mic -> "MIC" to Colors.green
        else -> shortDeviceName((controller.currentInput as? InputDevice.Usb)?.name ?: "USB") to Colors.green
    }
    Row(
        Modifier
            .heightIn(min = Dimens.touch)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color)
        HSpace(6.dp)
        Text(label, style = LabelStyle.copy(color = color, letterSpacing = 1.sp), maxLines = 1)
    }
}

/** "M-Track Duo" -> "DUO": last word if it's short, else the first five letters. */
fun shortDeviceName(name: String): String {
    val short = name.split(" ").lastOrNull()?.takeIf { it.length in 2..5 }
        ?: name.take(5).trimEnd()
    return short.uppercase()
}

// --------------------------------------------------------------- tracks view --

@Composable
private fun TracksView(
    controller: SongController,
    tl: TimelineState,
    peaks: Map<String, PeakData>,
    onOpenTrack: (Int) -> Unit,
    compact: Boolean,
    short: Boolean,
) {
    val song = controller.song
    val headerW = if (compact) HEADER_W_COMPACT else HEADER_W
    val laneMin = if (short) 72.dp else 84.dp

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(28.dp)) {
            Box(Modifier.width(headerW).fillMaxHeight().background(Colors.panel))
            Ruler(tl, controller, Modifier.weight(1f).fillMaxHeight())
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
                    drawLine(Colors.amber, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2f)
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportPx = it.height }
                    .verticalScroll(laneScroll),
            ) {
                song.tracks.forEachIndexed { index, track ->
                    key(track.id) {
                        Row(Modifier.fillMaxWidth().height(laneH)) {
                            TrackHeader(
                                track, controller, onOpenTrack, compact = compact, short = short,
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
                // New tracks are made where the tracks live.
                Row(
                    Modifier
                        .width(headerW)
                        .height(48.dp)
                        .background(Colors.panel)
                        .clickable { controller.addTrack() }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "+  Add track",
                        style = ButtonStyle.copy(color = Colors.amber),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The lane's left panel: name, arm / mute / solo, and the level meter. The whole
 * header is the button that opens the track's own screen; the chevron says so.
 */
@Composable
private fun TrackHeader(
    track: Track,
    controller: SongController,
    onOpenTrack: (Int) -> Unit,
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
            Text("›", style = TitleStyle.copy(color = Colors.dim, fontSize = 18.sp), modifier = Modifier.padding(start = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Toggle("●", track.armed, Colors.red, size = btn, fontSize = 14.sp, onClick = {
                controller.updateTrack(track.id) { it.copy(armed = !it.armed) }
            })
            Toggle("M", track.mute, Colors.amber, size = btn, onClick = {
                controller.updateTrack(track.id) { it.copy(mute = !it.mute) }
            })
            Toggle("S", track.solo, Colors.green, size = btn, onClick = {
                controller.updateTrack(track.id) { it.copy(solo = !it.solo) }
            })
        }
        LiveMeter(controller, track.id, Modifier.fillMaxWidth(), thickness = 6.dp)
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
private fun BoxScope.PlayheadChip(controller: SongController, tl: TimelineState, compact: Boolean) {
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
            .background(Colors.amber.copy(alpha = 0.22f), shape)
            .border(1.dp, Colors.amber, shape)
            .clickable {
                tl.follow = true
                tl.showFrame(controller.playheadFrame)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (left) "◀" else "▶",
            style = ButtonStyle.copy(color = Colors.amber, fontSize = 14.sp),
        )
    }
}

@Composable
private fun MessageBanner(controller: SongController, modifier: Modifier) {
    val msg = controller.message ?: return
    Box(
        modifier
            .background(Colors.raised, androidx.compose.foundation.shape.RoundedCornerShape(Dimens.corner))
            .border(1.dp, Colors.amber, androidx.compose.foundation.shape.RoundedCornerShape(Dimens.corner))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(msg, style = BodyStyle.copy(color = Colors.amber), maxLines = 2)
    }
}

// ------------------------------------------------------------ selection bar --

// The one control on the bar that depends on where the playhead is; reading
// it here keeps the rest of the bar off the transport's clock.
@Composable
private fun SplitButton(controller: SongController, trackId: Int, clip: Clip, modifier: Modifier) {
    val playhead = controller.playheadFrame
    AppButton(
        "Split",
        enabled = playhead > clip.start + MIN_CLIP_FRAMES && playhead < clip.end - MIN_CLIP_FRAMES,
        onClick = { controller.splitClip(trackId, clip.id, playhead) },
        modifier = modifier,
        fontSize = 12.sp,
    )
}

/** Actions for the selected clip. Only exists while something is selected. */
@Composable
private fun SelectionBar(controller: SongController, compact: Boolean) {
    val sel = controller.selectedClip ?: return
    val song = controller.song
    val (trackId, clipId) = sel
    val clip = song.track(trackId)?.clips?.find { it.id == clipId } ?: return
    val trackIdx = song.tracks.indexOfFirst { it.id == trackId }

    Row(
        Modifier
            .fillMaxWidth()
            .background(Colors.amber.copy(alpha = 0.08f))
            .border(1.dp, Colors.amber.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!compact) {
            Text(
                "CLIP  ·  ${formatTime(clip.length, song.sampleRate)}",
                style = LabelStyle.copy(color = Colors.amber),
                modifier = Modifier.padding(horizontal = 6.dp),
                maxLines = 1,
            )
        }
        SplitButton(controller, trackId, clip, Modifier.weight(1.1f))
        AppButton("Duplicate", onClick = { controller.duplicateClip(trackId, clipId) },
            modifier = Modifier.weight(1.4f), fontSize = 12.sp)
        AppButton("▲", enabled = trackIdx > 0, fontSize = 12.sp,
            onClick = { controller.moveClipToTrack(trackId, clipId, -1) })
        AppButton("▼", enabled = trackIdx in 0 until song.tracks.size - 1, fontSize = 12.sp,
            onClick = { controller.moveClipToTrack(trackId, clipId, 1) })
        AppButton("Delete", accent = Colors.red, fontSize = 12.sp, modifier = Modifier.weight(1.1f),
            onClick = { controller.deleteClip(trackId, clipId) })
    }
}

// ---------------------------------------------------------------- transport --

@Composable
private fun TransportBar(
    controller: SongController,
    tl: TimelineState,
    compact: Boolean,
    short: Boolean,
) {
    val status = controller.status
    val song = controller.song
    val rolling = status.transport != TRANSPORT_STOPPED

    val toStart: @Composable (Modifier) -> Unit = { m ->
        AppButton("|◀", onClick = {
            // Already at the start: snap the view there instead.
            if (controller.playheadFrame == 0L) tl.originFrame = 0 else controller.seek(0)
        }, modifier = m, height = Dimens.touchBig, fontSize = 16.sp)
    }
    val play: @Composable (Modifier) -> Unit = { m ->
        AppButton(
            if (rolling) "■   Stop" else "▶   Play",
            onClick = { controller.playPause() },
            filled = true,
            accent = Colors.text,
            modifier = m,
            height = Dimens.touchBig,
            fontSize = 15.sp,
        )
    }
    val rec: @Composable (Modifier) -> Unit = { m ->
        AppButton(
            if (status.recording) "●   Recording" else "●   Rec",
            onClick = { controller.toggleRecord() },
            filled = status.recording,
            accent = Colors.red,
            modifier = m,
            height = Dimens.touchBig,
            fontSize = 15.sp,
        )
    }
    val modes: @Composable RowScope.() -> Unit = {
        Toggle("Click", controller.clickEnabled, Colors.amber, onClick = {
            controller.setClick(!controller.clickEnabled)
        })
        Toggle("Loop", song.loopEnabled, Colors.green, onClick = {
            toggleLoop(controller)
        })
    }

    Column(Modifier.fillMaxWidth().background(Colors.panel)) {
        // Master level along the top edge: always visible, never in the way.
        LiveMeter(controller, MASTER_TRACK_ID, Modifier.fillMaxWidth(), thickness = 4.dp)
        if (short) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                toStart(Modifier.width(56.dp))
                play(Modifier.width(132.dp))
                rec(Modifier.width(150.dp))
                Spacer(Modifier.weight(1f))
                TransportClock(controller)
                Spacer(Modifier.weight(1f))
                modes()
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TransportClock(controller)
                Spacer(Modifier.weight(1f))
                modes()
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                toStart(Modifier.width(56.dp))
                play(Modifier.weight(1f))
                rec(Modifier.weight(1f))
            }
        }
    }
}

/**
 * LOOP with no region marked yet makes one instead of doing nothing: around the
 * selected clip if there is one, else around the whole song.
 */
private fun toggleLoop(controller: SongController) {
    val song = controller.song
    if (song.loopEnd > song.loopStart) {
        controller.setLoop(song.loopStart, song.loopEnd, !song.loopEnabled)
        return
    }
    val sel = controller.selectedClip?.let { (t, c) -> song.track(t)?.clips?.find { it.id == c } }
    val (lo, hi) = when {
        sel != null -> sel.start to sel.end
        song.endFrame > 0 -> 0L to song.endFrame
        else -> {
            controller.message = "Nothing to loop yet"
            return
        }
    }
    controller.setLoop(lo, hi, true)
    if (hi - lo < song.sampleRate) controller.message = "Loop needs at least one second"
}

// The only thing on the bar that moves with the transport, so it reads the
// playhead on its own: a tick repaints the clock, not the whole bar.
@Composable
private fun TransportClock(controller: SongController) {
    val song = controller.song
    val status = controller.status
    // During count-in the clock shows the beats left, where the musician is
    // already looking.
    if (status.countdown > 0 && status.transport == TRANSPORT_RECORDING) {
        val fpb = controller.framesPerBeat
        val beatsLeft = ((status.countdown + fpb - 1) / fpb).toInt()
        Text("· $beatsLeft ·", style = ClockStyle.copy(color = Colors.red))
    } else {
        Text(
            formatTime(controller.playheadFrame, song.sampleRate),
            style = ClockStyle.copy(color = if (status.recording) Colors.red else Colors.text),
        )
    }
}
