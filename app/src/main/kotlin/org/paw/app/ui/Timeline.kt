package org.paw.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import org.paw.app.PeakData
import org.paw.app.SongController
import org.paw.app.TRANSPORT_STOPPED
import org.paw.app.model.Clip
import org.paw.app.model.SnapGrid
import org.paw.app.model.Song
import org.paw.app.model.barStartFrame
import org.paw.app.model.framesPerBar
import org.paw.app.model.framesPerSongBeat
import org.paw.app.model.snapDelta
import org.paw.app.model.snapFrame
import org.paw.app.model.Track
import org.paw.app.model.movedTo
import org.paw.app.model.trimmedLeft
import org.paw.app.model.trimmedRight
import org.paw.app.model.withFades

// The timeline: view window, ruler, clip lanes, waveform drawing.

/** Horizontal view window over the timeline, in audio frames. */
class TimelineState {
    var framesPerPx by mutableFloatStateOf(300f)
    var originFrame by mutableLongStateOf(0L)

    /** Keep the playhead in view while rolling. A manual pan or pinch during
     *  playback turns it off: a view that snaps back against the finger reads
     *  as glitching, not following. */
    var follow by mutableStateOf(true)

    /** Width of the lane viewport, reported by the first lane to lay out. */
    var laneWidthPx by mutableFloatStateOf(0f)

    fun pxOf(frame: Long): Float = (frame - originFrame) / framesPerPx
    fun frameAt(px: Float): Long = originFrame + (px * framesPerPx).toLong()

    fun pan(dxPx: Float) {
        originFrame = (originFrame - (dxPx * framesPerPx).toLong()).coerceAtLeast(0L)
    }

    fun zoom(factor: Float, centerPx: Float) {
        val anchor = frameAt(centerPx)
        framesPerPx = (framesPerPx / factor).coerceIn(20f, 24000f)
        originFrame = (anchor - (centerPx * framesPerPx).toLong()).coerceAtLeast(0L)
    }

    fun isOffscreen(frame: Long): Boolean {
        val px = pxOf(frame)
        return laneWidthPx > 0f && (px < 0f || px > laneWidthPx)
    }

    /** Show [frames] from the start across the whole lane width, with a
     *  little room after the end so the last clip's edge is grabbable. */
    fun fitTo(frames: Long) {
        if (laneWidthPx <= 0f) return
        framesPerPx = (frames * 1.06f / laneWidthPx).coerceIn(20f, 24000f)
        originFrame = 0L
    }

    /** Bring [frame] to a fifth of the way in from the left. */
    fun showFrame(frame: Long) {
        if (laneWidthPx <= 0f) return
        originFrame = (frame - (laneWidthPx * 0.2f * framesPerPx).toLong()).coerceAtLeast(0L)
    }
}

// Follow only drops while rolling: panning a stopped transport is just editing.
internal fun releaseFollow(tl: TimelineState, controller: SongController) {
    if (controller.status.transport != TRANSPORT_STOPPED) tl.follow = false
}

/**
 * Pan and pinch for the empty workspace under the last lane and beside Add
 * track, which on a phone in portrait is the biggest and safest place to
 * swipe. A touch that starts left of [lanesLeftPx] (the headers) or above
 * [lanesBottomPx] (a lane, which has its own handler) is not ours, and a
 * vertical drag belongs to the track-list scroll. A tap deselects.
 */
internal fun Modifier.emptyAreaGestures(
    tl: TimelineState,
    controller: SongController,
    lanesLeftPx: Float,
    lanesBottomPx: () -> Float,
): Modifier = pointerInput(tl, lanesLeftPx) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.position.x < lanesLeftPx || down.position.y < lanesBottomPx()) {
            return@awaitEachGesture
        }
        val slop = viewConfiguration.touchSlop
        var acc = Offset.Zero
        var panning = false
        var pinching = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                if (!panning && !pinching) controller.selectedClip = null
                break
            }
            if (pressed.size >= 2) {
                pinching = true
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (zoomChange != 1f) tl.zoom(zoomChange, event.calculateCentroid().x - lanesLeftPx)
                if (panChange.x != 0f) tl.pan(panChange.x)
                if (zoomChange != 1f || panChange.x != 0f) releaseFollow(tl, controller)
                event.changes.forEach { it.consume() }
                continue
            }
            val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
            val delta = change.positionChange()
            if (!panning && !pinching) {
                acc += delta
                if (acc.getDistance() > slop) {
                    if (kotlin.math.abs(acc.y) > kotlin.math.abs(acc.x)) return@awaitEachGesture
                    panning = true
                }
            } else if (panning) {
                change.consume()
                tl.pan(delta.x)
                if (delta.x != 0f) releaseFollow(tl, controller)
            }
        }
    }
}

// -------------------------------------------------------------------- ruler --

private sealed interface RulerMode {
    object Undecided : RulerMode
    object Pan : RulerMode
    object Pinch : RulerMode
    /** Long-press-and-drag paints a fresh loop region. */
    data class Mark(val anchor: Long) : RulerMode
    /** Dragging one edge of the existing region. */
    data class Edge(val left: Boolean, val otherEdge: Long) : RulerMode
}

/**
 * The ruler over the lanes, in bars (or seconds when the clock reads time).
 * Tap seeks, drag pans, pinch zooms. Drag a loop tab to move that edge, or
 * hold anywhere and drag to paint a new region; both land on the snap grid.
 * The ✕ beside the right tab clears the region.
 */
@Composable
fun Ruler(
    tl: TimelineState,
    controller: SongController,
    ui: SongUi,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val song = controller.song
    val labels = remember { HashMap<String, TextLayoutResult>() }
    val density = LocalDensity.current
    val edgeGrabPx = with(density) { 22.dp.toPx() }
    // Loop-drag preview; committed on release.
    var loopDrag by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    BoxWithConstraints(modifier.clipToBounds()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .background(Colors.panel)
                .pointerInput(tl) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val slop = viewConfiguration.touchSlop
                        val longPressMs = viewConfiguration.longPressTimeoutMillis
                        var acc = Offset.Zero
                        var mode: RulerMode = RulerMode.Undecided
                        val s = controller.song
                        fun snapped(x: Float) = s.snapFrame(tl.frameAt(x).coerceAtLeast(0), ui.snap)
                        val hasRegion = s.loopEnd > s.loopStart
                        // A finger landing on a loop edge grabs it straight away.
                        if (hasRegion) {
                            val dl = kotlin.math.abs(down.position.x - tl.pxOf(s.loopStart))
                            val dr = kotlin.math.abs(down.position.x - tl.pxOf(s.loopEnd))
                            if (dl < edgeGrabPx && dl <= dr) {
                                mode = RulerMode.Edge(left = true, otherEdge = s.loopEnd)
                                loopDrag = s.loopStart to s.loopEnd
                            } else if (dr < edgeGrabPx) {
                                mode = RulerMode.Edge(left = false, otherEdge = s.loopStart)
                                loopDrag = s.loopStart to s.loopEnd
                            }
                        }
                        val pressStart = down.uptimeMillis

                        while (true) {
                            // Undecided fingers race the long-press clock: the
                            // timeout firing with no movement paints a loop.
                            val event = if (mode is RulerMode.Undecided) {
                                val left = pressStart + longPressMs - android.os.SystemClock.uptimeMillis()
                                withTimeoutOrNull(left.coerceAtLeast(1L)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                val f = snapped(down.position.x)
                                mode = RulerMode.Mark(f)
                                loopDrag = f to f
                                continue
                            }
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                val region = loopDrag?.let { (a, b) -> minOf(a, b) to maxOf(a, b) }
                                when (mode) {
                                    is RulerMode.Undecided -> controller.seek(tl.frameAt(down.position.x))
                                    is RulerMode.Mark -> region?.let { (lo, hi) ->
                                        // A hold without a drag marks nothing.
                                        if (hi > lo) controller.setLoopRegion(lo, hi)
                                    }
                                    is RulerMode.Edge -> region?.let { (lo, hi) ->
                                        val cur = controller.song
                                        if (hi - lo < cur.sampleRate) {
                                            controller.message = "Loop needs at least one second"
                                        } else {
                                            controller.setLoop(lo, hi, cur.loopEnabled)
                                        }
                                    }
                                    else -> {}
                                }
                                loopDrag = null
                                break
                            }

                            if (pressed.size >= 2 && (mode is RulerMode.Undecided || mode is RulerMode.Pan || mode is RulerMode.Pinch)) {
                                mode = RulerMode.Pinch
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid()
                                if (zoomChange != 1f) tl.zoom(zoomChange, centroid.x)
                                if (panChange.x != 0f) tl.pan(panChange.x)
                                if (zoomChange != 1f || panChange.x != 0f) releaseFollow(tl, controller)
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                            val delta = change.positionChange()
                            val x = change.position.x
                            when (val m = mode) {
                                is RulerMode.Undecided -> {
                                    acc += delta
                                    if (acc.getDistance() > slop) mode = RulerMode.Pan
                                }
                                is RulerMode.Pan -> {
                                    change.consume()
                                    tl.pan(delta.x)
                                    if (delta.x != 0f) releaseFollow(tl, controller)
                                }
                                is RulerMode.Mark -> {
                                    change.consume()
                                    loopDrag = m.anchor to snapped(x)
                                }
                                is RulerMode.Edge -> {
                                    change.consume()
                                    val f = snapped(x)
                                    loopDrag = if (m.left) f to m.otherEdge else m.otherEdge to f
                                }
                                else -> {}
                            }
                        }
                    }
                },
        ) {
            // Loop region band (preview while dragging, else the committed one).
            val region = loopDrag?.let { (a, b) -> minOf(a, b) to maxOf(a, b) }
                ?: (song.loopStart to song.loopEnd).takeIf { song.loopEnd > song.loopStart }
            if (region != null) {
                val (ls, le) = region
                val x0 = tl.pxOf(ls)
                val x1 = tl.pxOf(le)
                val on = song.loopEnabled || loopDrag != null
                val band = Colors.green.copy(alpha = if (on) 0.22f else 0.10f)
                val bracket = Colors.green.copy(alpha = if (on) 1f else 0.45f)
                if (x1 > x0) {
                    drawRect(
                        band,
                        topLeft = Offset(x0.coerceAtLeast(0f), 0f),
                        size = Size(x1.coerceAtMost(size.width) - x0.coerceAtLeast(0f), size.height),
                    )
                }
                // A fat tab at each end, so the edges read as grabbable.
                val tabW = 6.dp.toPx()
                drawRect(bracket, Offset(x0 - tabW / 2f, 0f), Size(tabW, size.height))
                drawRect(bracket, Offset(x1 - tabW / 2f, 0f), Size(tabW, size.height))
            }

            val tickH = 6.dp.toPx()
            // Numbers under the clear button would read as part of it.
            val clearAt = if (loopDrag == null && song.loopEnd > song.loopStart) {
                clearLoopX(tl.pxOf(song.loopEnd), size.width, 40.dp.toPx(), 3.dp.toPx())
            } else null
            fun label(text: String, x: Float, style: androidx.compose.ui.text.TextStyle) {
                if (labels.size > 256) labels.clear()
                val l = labels.getOrPut(text + style.color.value) { measurer.measure(AnnotatedString(text), style) }
                val x0 = x + 5f
                val x1 = x0 + l.size.width
                if (x < 0f) return
                if (clearAt != null && x1 > clearAt && x0 < clearAt + 40.dp.toPx()) return
                if (x1 <= size.width) drawText(l, topLeft = Offset(x0, 2f))
            }
            if (ui.clockAsTime) {
                val sr = song.sampleRate
                val stepFrames = gridStepFrames(tl.framesPerPx, sr)
                var f = (tl.originFrame / stepFrames) * stepFrames
                while (true) {
                    val x = tl.pxOf(f)
                    if (x > size.width) break
                    if (x >= 0f) {
                        drawLine(Colors.dim, Offset(x, size.height - tickH), Offset(x, size.height))
                        label(formatRulerTime(f, sr), x, RulerLabelStyle)
                    }
                    f += stepFrames
                }
            } else {
                forEachBarLine(tl, song, size.width) { frame, bar, beat, kind ->
                    val x = tl.pxOf(frame)
                    when (kind) {
                        LineKind.LabelledBar -> {
                            drawLine(Colors.dim, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
                            label("${bar + 1}", x, RulerBarStyle)
                        }
                        LineKind.Bar -> drawLine(Colors.dim, Offset(x, size.height - tickH * 1.4f), Offset(x, size.height))
                        LineKind.Beat -> drawLine(Colors.line, Offset(x, size.height - tickH), Offset(x, size.height), strokeWidth = 1.5f)
                        LineKind.LabelledBeat -> {
                            drawLine(Colors.line, Offset(x, size.height - tickH), Offset(x, size.height), strokeWidth = 1.5f)
                            label("${bar + 1}.${beat + 1}", x, RulerLabelStyle)
                        }
                    }
                }
            }
            drawLine(Colors.line, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f))
            val px = tl.pxOf(controller.playheadFrame)
            if (px in 0f..size.width) {
                drawLine(Colors.accent, Offset(px, 0f), Offset(px, size.height), strokeWidth = 3f)
                val w = 5.dp.toPx()
                drawRect(Colors.accent, Offset(px - w, 0f), Size(2 * w, 4.dp.toPx()))
            }
        }

        if (loopDrag == null && song.loopEnd > song.loopStart) {
            ClearLoopButton(tl, controller, constraints.maxWidth.toFloat())
        }
    }
}

/**
 * Sits just outside the loop's right tab, or just inside it when the tab is
 * near the right edge; gone while that edge is off screen. Placed in the
 * layout phase, so panning moves it without recomposing the ruler.
 */
@Composable
private fun ClearLoopButton(tl: TimelineState, controller: SongController, widthPx: Float) {
    val w = 40.dp
    Box(
        Modifier
            .offset {
                val x = clearLoopX(tl.pxOf(controller.song.loopEnd), widthPx, w.toPx(), 3.dp.toPx())
                IntOffset((x ?: -10_000f).toInt(), 0)
            }
            .width(w)
            .fillMaxHeight()
            .clickable { controller.clearLoop() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .background(Colors.bg, CircleShape)
                .border(1.dp, Colors.green, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(10.dp)) { CloseGlyph(Colors.green) }
        }
    }
}

/** Left edge of the clear button for a loop ending at [xr], or null when
 *  that end is off screen. */
private fun clearLoopX(xr: Float, widthPx: Float, buttonPx: Float, tabPx: Float): Float? = when {
    xr < 0f || xr > widthPx -> null
    xr + tabPx + buttonPx <= widthPx -> xr + tabPx
    else -> xr - tabPx - buttonPx
}

private val RulerLabelStyle = MonoDimStyle.copy(fontSize = 10.sp)
private val RulerBarStyle = MonoStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold)
private val GridSteps = floatArrayOf(0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f)

fun gridStepFrames(framesPerPx: Float, sampleRate: Int): Long {
    val targetPx = 90f
    val targetSec = framesPerPx * targetPx / sampleRate
    val sec = GridSteps.firstOrNull { it >= targetSec } ?: 600f
    return (sec * sampleRate).toLong()
}

internal enum class LineKind { LabelledBar, Bar, LabelledBeat, Beat }

private val BarSteps = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128, 256)

/**
 * Every bar and beat line in view, thinned to what the zoom can show: bars
 * get a number every so many bars, beats appear once they are far enough
 * apart to tell, and get a number of their own when zoomed right in.
 */
internal inline fun DrawScope.forEachBarLine(
    tl: TimelineState,
    song: Song,
    widthPx: Float,
    line: (frame: Long, bar: Int, beat: Int, kind: LineKind) -> Unit,
) {
    val fpBar = song.framesPerBar
    if (fpBar <= 0.0) return
    val barPx = (fpBar / tl.framesPerPx).toFloat()
    val labelEvery = BarSteps.firstOrNull { barPx * it >= 44.dp.toPx() } ?: 512
    val lineEvery = BarSteps.firstOrNull { barPx * it >= 12.dp.toPx() } ?: 512
    val bpb = song.beatsPerBar.coerceAtLeast(1)
    val beatPx = barPx / bpb
    val beats = beatPx >= 10.dp.toPx()
    val beatLabels = beatPx >= 34.dp.toPx()
    val first = Math.floor(tl.originFrame / fpBar).toInt().coerceAtLeast(0)
    var bar = first - first % lineEvery
    while (true) {
        val f = song.barStartFrame(bar)
        if (tl.pxOf(f) > widthPx) break
        if (bar % lineEvery == 0) {
            line(f, bar, 0, if (bar % labelEvery == 0) LineKind.LabelledBar else LineKind.Bar)
        }
        if (beats) {
            for (b in 1 until bpb) {
                val bf = Math.round(bar * fpBar + b * song.framesPerSongBeat)
                line(bf, bar, b, if (beatLabels) LineKind.LabelledBeat else LineKind.Beat)
            }
        }
        bar += if (beats) 1 else lineEvery
    }
}

/** The grid behind the lanes: bars and beats, or seconds when the clock
 *  reads time. */
fun DrawScope.drawGrid(tl: TimelineState, song: Song, asTime: Boolean) {
    if (asTime) {
        val step = gridStepFrames(tl.framesPerPx, song.sampleRate)
        var f = (tl.originFrame / step) * step
        while (true) {
            val x = tl.pxOf(f)
            if (x > size.width) break
            if (x >= 0f) drawLine(Colors.line.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height))
            f += step
        }
    } else {
        forEachBarLine(tl, song, size.width) { frame, _, _, kind ->
            val x = tl.pxOf(frame)
            if (x >= 0f) {
                val alpha = when (kind) {
                    LineKind.LabelledBar -> 0.6f
                    LineKind.Bar -> 0.4f
                    else -> 0.18f
                }
                drawLine(Colors.line.copy(alpha = alpha), Offset(x, 0f), Offset(x, size.height))
            }
        }
    }
    drawLine(Colors.line, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f))
}

// ----------------------------------------------------------------- overview --

/**
 * The whole song in one strip: a row per track with its clips, the loop, the
 * playhead, and a frame around the part the lanes show. A touch moves that
 * frame to the finger, so any point in the song is one drag away at any zoom.
 */
@Composable
fun Overview(
    tl: TimelineState,
    controller: SongController,
    modifier: Modifier = Modifier,
) {
    val song = controller.song
    val sr = song.sampleRate

    // The strip spans the song plus whatever the view or the playhead has
    // reached past it, and never less than ten seconds. Reads the controller,
    // not [song], because the gesture outlives the composition it began in.
    fun spanFrames(widthPx: Float): Long = maxOf(
        controller.song.endFrame,
        controller.song.loopEnd,
        controller.playheadFrame,
        tl.frameAt(tl.laneWidthPx.takeIf { it > 0f } ?: widthPx),
        10L * sr,
    ).let { (it * 1.04).toLong() }

    Canvas(
        modifier
            .clipToBounds()
            .background(Colors.bg)
            .onSizeChanged { if (tl.laneWidthPx <= 0f) tl.laneWidthPx = it.width.toFloat() }
            .pointerInput(tl) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val span = spanFrames(size.width.toFloat())
                    fun center(x: Float) {
                        val visible = (tl.laneWidthPx * tl.framesPerPx).toLong()
                        val f = (x / size.width * span).toLong()
                        tl.originFrame = (f - visible / 2).coerceAtLeast(0L)
                        releaseFollow(tl, controller)
                    }
                    center(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        center(change.position.x)
                    }
                }
            },
    ) {
        val span = spanFrames(size.width).toFloat()
        fun x(frame: Long) = frame / span * size.width

        if (song.loopEnd > song.loopStart) {
            drawRect(
                Colors.green.copy(alpha = if (song.loopEnabled) 0.22f else 0.08f),
                topLeft = Offset(x(song.loopStart), 0f),
                size = Size(x(song.loopEnd) - x(song.loopStart), size.height),
            )
        }

        val pad = 3.dp.toPx()
        val n = song.tracks.size.coerceAtLeast(1)
        val rowH = ((size.height - 2 * pad) / n).coerceAtMost(6.dp.toPx())
        val top0 = (size.height - rowH * n) / 2f
        song.tracks.forEachIndexed { i, t ->
            val y = top0 + i * rowH
            for (c in t.clips) {
                drawRect(
                    if (c.isPattern) Colors.accent.copy(alpha = 0.7f) else Colors.wave.copy(alpha = 0.6f),
                    topLeft = Offset(x(c.start), y + rowH * 0.15f),
                    size = Size((x(c.end) - x(c.start)).coerceAtLeast(1.5f), rowH * 0.7f),
                )
            }
        }

        // The window the lanes show.
        if (tl.laneWidthPx > 0f) {
            val x0 = x(tl.originFrame)
            val x1 = x(tl.frameAt(tl.laneWidthPx)).coerceAtMost(size.width - 1f)
            drawRect(
                Colors.text.copy(alpha = 0.07f),
                topLeft = Offset(x0, 0f),
                size = Size((x1 - x0).coerceAtLeast(3f), size.height),
            )
            drawRect(
                Colors.text.copy(alpha = 0.55f),
                topLeft = Offset(x0, 1f),
                size = Size((x1 - x0).coerceAtLeast(3f), size.height - 2f),
                style = Stroke(1.dp.toPx()),
            )
        }
        val ph = x(controller.playheadFrame)
        drawLine(Colors.accent, Offset(ph, 0f), Offset(ph, size.height), strokeWidth = 2.dp.toPx())
        drawLine(Colors.line, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f))
    }
}

// -------------------------------------------------------------------- lanes --

private sealed interface DragMode {
    data class Move(val clip: Clip, val startFrame: Long) : DragMode
    data class TrimL(val clip: Clip, val edge: Long) : DragMode
    data class TrimR(val clip: Clip, val edge: Long) : DragMode
    data class FadeL(val clip: Clip, val startFade: Long) : DragMode
    data class FadeR(val clip: Clip, val startFade: Long) : DragMode
    object Pan : DragMode
}

private val DragMode.clipId: Int?
    get() = when (this) {
        is DragMode.Move -> clip.id
        is DragMode.TrimL -> clip.id
        is DragMode.TrimR -> clip.id
        is DragMode.FadeL -> clip.id
        is DragMode.FadeR -> clip.id
        DragMode.Pan -> null
    }

// Gesture contract. One handler for all of it: separate tap and drag detectors
// race each other and capture stale clip lists.
//  - tap selects/deselects
//  - a vertical drag is never ours: it falls through to the track-list scroll
//  - a horizontal drag on the selected clip moves (body), trims (edges) or
//    fades (top corners); anywhere else it pans the timeline
//  - a second finger pinch-zooms, unless a clip edit is already in progress
//  - a clip too narrow for three zones keeps its middle third for moving
private fun hitTest(
    sel: Clip?,
    ofsX: Float,
    ofsY: Float,
    laneH: Float,
    edgePx: Float,
    tl: TimelineState,
): DragMode? {
    sel ?: return null
    val x0 = tl.pxOf(sel.start)
    val x1 = tl.pxOf(sel.end)
    val width = x1 - x0
    val edge = minOf(edgePx, width / 3f)
    val fadeZone = ofsY < laneH * 0.38f && !sel.isPattern
    val fadeLx = x0 + sel.fadeIn / tl.framesPerPx
    val fadeRx = x1 - sel.fadeOut / tl.framesPerPx
    return when {
        fadeZone && kotlin.math.abs(fadeLx - ofsX) < edgePx && ofsX <= (fadeLx + fadeRx) / 2f ->
            DragMode.FadeL(sel, sel.fadeIn)
        fadeZone && kotlin.math.abs(fadeRx - ofsX) < edgePx ->
            DragMode.FadeR(sel, sel.fadeOut)
        ofsX >= x0 - edgePx && ofsX < x0 + edge -> DragMode.TrimL(sel, sel.start)
        ofsX > x1 - edge && ofsX <= x1 + edgePx -> DragMode.TrimR(sel, sel.end)
        ofsX in x0..x1 -> DragMode.Move(sel, sel.start)
        else -> null
    }
}

/**
 * Where a clip drag lands on [grid]: a moved take keeps its own timing and
 * moves by whole grid steps, while pattern clips and trim edges land on the
 * grid itself. The drag preview and the commit both come through here, so
 * nothing jumps on release.
 */
private fun snappedTarget(m: DragMode, delta: Long, song: Song, grid: SnapGrid): Long = when (m) {
    is DragMode.Move ->
        if (m.clip.isPattern) song.snapFrame(m.startFrame + delta, grid)
        else m.startFrame + song.snapDelta(delta, grid)
    is DragMode.TrimL -> song.snapFrame(m.edge + delta, grid)
    is DragMode.TrimR -> song.snapFrame(m.edge + delta, grid)
    is DragMode.FadeL -> m.startFade + delta
    is DragMode.FadeR -> m.startFade - delta
    DragMode.Pan -> 0L
}

/**
 * One track's clips. A tap on a clip selects it, a tap on empty space
 * selects the track alone ([onSelectTrack] runs for both).
 */
@Composable
fun ClipLane(
    track: Track,
    controller: SongController,
    ui: SongUi,
    tl: TimelineState,
    peaks: Map<String, PeakData>,
    scroll: ScrollState,
    laneTop: Float,
    viewportPx: Int,
    trackSelected: Boolean,
    onSelectTrack: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = controller.selectedClip
    val density = LocalDensity.current
    val edgeGrabPx = with(density) { 40.dp.toPx() }
    val wavePaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(191, 185, 192, 199)
            strokeWidth = 1f
        }
    }
    val waveLines = remember { FloatArray(MAX_WAVE_COLS * 4) }
    val labelPaint = remember(density) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(230, 236, 234, 228)
            textSize = with(density) { 11.sp.toPx() }
            isAntiAlias = true
        }
    }
    val waveCache = remember { WaveCache() }

    // Drag preview: applied to the model only on release. Both states must be
    // unkeyed: the gesture coroutine captures the delegates from the
    // composition it launched in.
    var dragMode by remember { mutableStateOf<DragMode?>(null) }
    var dragDeltaFrames by remember { mutableLongStateOf(0L) }

    Canvas(
        modifier
            .clipToBounds()
            .background(Colors.bg)
            .then(if (trackSelected) Modifier.background(Colors.accent.copy(alpha = 0.05f)) else Modifier)
            // Measured in layout, not draw: a draw-phase write isn't reliably
            // observed by the composition that reads this.
            .onSizeChanged { tl.laneWidthPx = it.width.toFloat() }
            .pointerInput(track.id, tl) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val slop = viewConfiguration.touchSlop
                    var acc = Offset.Zero
                    var pastSlop = false
                    var pinching = false

                    fun commit() {
                        val m = dragMode ?: return
                        val to = snappedTarget(m, dragDeltaFrames, controller.song, ui.snap)
                        when (m) {
                            is DragMode.Move -> controller.moveClip(track.id, m.clip.id, to)
                            is DragMode.TrimL -> controller.trimClipLeft(track.id, m.clip.id, to)
                            is DragMode.TrimR -> controller.trimClipRight(track.id, m.clip.id, to)
                            is DragMode.FadeL -> controller.setClipFades(track.id, m.clip.id, to, m.clip.fadeOut)
                            is DragMode.FadeR -> controller.setClipFades(track.id, m.clip.id, m.clip.fadeIn, to)
                            DragMode.Pan -> {}
                        }
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }

                        if (pressed.isEmpty()) {
                            if (!pastSlop && !pinching) {
                                val frame = tl.frameAt(down.position.x)
                                val clips = controller.song.track(track.id)?.clips.orEmpty()
                                val hit = clips.lastOrNull { frame >= it.start && frame < it.end }
                                controller.selectedClip = hit?.let { track.id to it.id }
                                onSelectTrack(track.id)
                            } else {
                                commit()
                            }
                            dragMode = null
                            break
                        }

                        if (pressed.size >= 2) {
                            val m = dragMode
                            if (pinching || m == null || m is DragMode.Pan) {
                                pinching = true
                                pastSlop = true
                                dragMode = null
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid()
                                if (zoomChange != 1f) tl.zoom(zoomChange, centroid.x)
                                if (panChange.x != 0f) tl.pan(panChange.x)
                                if (zoomChange != 1f || panChange.x != 0f) {
                                    releaseFollow(tl, controller)
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            // Clip edit in progress: ignore the extra finger.
                        }

                        val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                        val delta = change.positionChange()

                        if (!pastSlop) {
                            acc += delta
                            if (acc.getDistance() > slop) {
                                if (kotlin.math.abs(acc.y) > kotlin.math.abs(acc.x)) {
                                    // Vertical: the track list scroll owns it.
                                    dragMode = null
                                    return@awaitEachGesture
                                }
                                pastSlop = true
                                val sel = controller.song.track(track.id)?.clips?.find {
                                    controller.selectedClip == track.id to it.id
                                }
                                dragMode = hitTest(
                                    sel, down.position.x, down.position.y,
                                    size.height.toFloat(), edgeGrabPx, tl,
                                ) ?: DragMode.Pan
                                dragDeltaFrames = 0L
                            }
                        } else if (!pinching) {
                            change.consume()
                            if (dragMode is DragMode.Pan) {
                                tl.pan(delta.x)
                                if (delta.x != 0f) releaseFollow(tl, controller)
                            } else {
                                dragDeltaFrames += (delta.x * tl.framesPerPx).toLong()
                            }
                        }
                    }
                }
            },
    ) {
        // The lane list isn't lazy, so a scrolled-away lane would still run its
        // whole draw under the parent's clip.
        if (viewportPx > 0) {
            val top = scroll.value
            if (laneTop + size.height <= top || laneTop >= top + viewportPx) return@Canvas
        }
        drawGrid(tl, controller.song, ui.clockAsTime)

        for (clip in track.clips) {
            // The in-flight drag previews through the same snap and clamp
            // functions the commit uses, so what is drawn is what will land.
            val m = dragMode
            val preview = if (m == null || m is DragMode.Pan || m.clipId != clip.id) clip else {
                val to = snappedTarget(m, dragDeltaFrames, controller.song, ui.snap)
                when (m) {
                    is DragMode.Move -> clip.movedTo(to)
                    is DragMode.TrimL -> clip.trimmedLeft(to)
                    is DragMode.TrimR -> clip.trimmedRight(to, controller.takeFramesCached(clip.take))
                    is DragMode.FadeL -> clip.withFades(to, clip.fadeOut)
                    is DragMode.FadeR -> clip.withFades(clip.fadeIn, to)
                    DragMode.Pan -> clip
                }
            }
            val start = preview.start
            val srcStart = preview.srcStart
            val length = preview.length
            val fadeIn = preview.fadeIn
            val fadeOut = preview.fadeOut

            val x0 = tl.pxOf(start)
            val x1 = tl.pxOf(start + length)
            if (x1 < 0f || x0 > size.width) continue
            val isSel = selected == track.id to clip.id
            val top = 3f
            val bot = size.height - 3f

            drawRect(
                when {
                    isSel -> Colors.accent.copy(alpha = 0.12f)
                    clip.isPattern -> Colors.green.copy(alpha = 0.10f)
                    else -> Colors.clip
                },
                topLeft = Offset(x0, top),
                size = Size(x1 - x0, bot - top),
            )
            if (clip.isPattern) {
                drawPatternClip(controller.song, track, preview, tl, top, bot, labelPaint)
            } else {
                drawWaveform(
                    waveCache, peaks[clip.take], clip.take, srcStart, length,
                    x0, x1, tl.framesPerPx, wavePaint, waveLines,
                )
            }

            // Shade the silent wedge under the ramp, so a fade reads as a
            // region and not a stray diagonal.
            val fxL = x0 + fadeIn / tl.framesPerPx
            val fxR = x1 - fadeOut / tl.framesPerPx
            val rampColor = if (isSel) Colors.green else Colors.dim.copy(alpha = 0.85f)
            val wedge = (if (isSel) Colors.green else Colors.dim).copy(alpha = 0.16f)
            if (fadeIn > 0) {
                drawPath(
                    Path().apply { moveTo(x0, top); lineTo(fxL, top); lineTo(x0, bot); close() },
                    wedge,
                )
                drawLine(rampColor, Offset(x0, bot), Offset(fxL, top), strokeWidth = 2.5f)
            }
            if (fadeOut > 0) {
                drawPath(
                    Path().apply { moveTo(x1, top); lineTo(fxR, top); lineTo(x1, bot); close() },
                    wedge,
                )
                drawLine(rampColor, Offset(fxR, top), Offset(x1, bot), strokeWidth = 2.5f)
            }

            drawRect(
                if (isSel) Colors.accent else Colors.line,
                topLeft = Offset(x0, top),
                size = Size(x1 - x0, bot - top),
                style = Stroke(if (isSel) 2.5f else 1.5f),
            )
            if (isSel) {
                // Grip pills sit on the lower part of the clip, where the trim
                // hit zone is.
                val hw = 7.dp.toPx()
                val hh = size.height * 0.2f
                val cy = size.height * 0.68f
                val gripInset = 2.5.dp.toPx()
                for (x in floatArrayOf(x0, x1)) {
                    drawRoundRect(
                        Colors.accent,
                        topLeft = Offset(x - hw, cy - hh),
                        size = Size(2 * hw, 2 * hh),
                        cornerRadius = CornerRadius(hw),
                    )
                    drawLine(Colors.bg, Offset(x - gripInset, cy - hh * 0.45f), Offset(x - gripInset, cy + hh * 0.45f), strokeWidth = 1.5.dp.toPx())
                    drawLine(Colors.bg, Offset(x + gripInset, cy - hh * 0.45f), Offset(x + gripInset, cy + hh * 0.45f), strokeWidth = 1.5.dp.toPx())
                }
                // A ring at the top end of each ramp; drag it sideways to
                // change the fade. Patterns have no fades.
                val r = 8.dp.toPx()
                val cyF = top + r + 3.dp.toPx()
                for (x in if (clip.isPattern) floatArrayOf() else floatArrayOf(fxL, fxR)) {
                    drawCircle(Colors.bg, r + 2.dp.toPx(), Offset(x, cyF))
                    drawCircle(Colors.green, r, Offset(x, cyF))
                    drawCircle(Colors.bg, r * 0.38f, Offset(x, cyF))
                }
            }
        }

        // Live recording region on armed tracks. recFrames is read only inside
        // this branch, so an idle lane doesn't recompose with it.
        val status = controller.status
        if (status.recording && track.armed && status.recStartFrame >= 0) {
            val x0 = tl.pxOf(status.recStartFrame)
            val x1 = tl.pxOf(status.recStartFrame + controller.recFrames)
            if (x1 >= 0f && x0 <= size.width) {
                drawRect(
                    Colors.red.copy(alpha = 0.18f),
                    topLeft = Offset(x0, 3f),
                    size = Size(x1 - x0, size.height - 6f),
                )
                drawRect(
                    Colors.red,
                    topLeft = Offset(x0, 3f),
                    size = Size(x1 - x0, size.height - 6f),
                    style = Stroke(1.5f),
                )
            }
        }

        val px = tl.pxOf(controller.playheadFrame)
        if (px in 0f..size.width) {
            drawLine(Colors.accent, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2f)
        }
    }
}

// ------------------------------------------------------------ pattern clips --

/**
 * A pattern clip: its name, a faint line where each repeat starts, and a mark
 * per hit, one row per sample of the track's kit, the way the step grid has
 * them.
 */
private fun DrawScope.drawPatternClip(
    song: Song,
    track: Track,
    clip: Clip,
    tl: TimelineState,
    top: Float,
    bot: Float,
    labelPaint: android.graphics.Paint,
) {
    val p = song.pattern(clip.pattern) ?: return
    val len = song.framesOf(p.lengthTicks)
    if (len <= 0) return
    val x0 = tl.pxOf(clip.start)
    val x1 = tl.pxOf(clip.end)
    val labelH = labelPaint.textSize + 6f
    drawContext.canvas.nativeCanvas.apply {
        save()
        clipRect(x0, top, x1, bot)
        drawText(p.name, x0 + 6f, top + labelPaint.textSize + 2f, labelPaint)
        restore()
    }

    // Repeat boundaries.
    var rep = len - Math.floorMod(clip.srcStart, len)
    if (rep == len) rep = 0
    while (rep < clip.length) {
        val x = tl.pxOf(clip.start + rep)
        if (rep > 0 && x in 0f..size.width) drawLine(Colors.green.copy(alpha = 0.5f), Offset(x, top), Offset(x, bot))
        rep += len
    }

    val rows = track.samples.size
    if (rows == 0) return
    val rowH = (bot - top - labelH - 4f) / rows
    val stepPx = song.framesOf(p.ticksPerStep) / tl.framesPerPx
    val markW = (stepPx * 0.6f).coerceIn(2f, 10f)
    val visStart = tl.frameAt(0f)
    val visEnd = tl.frameAt(size.width)
    for (h in p.hits) {
        if (h.tick >= p.lengthTicks) continue
        val row = track.samples.indexOfFirst { it.id == h.sample }
        if (row < 0) continue
        val y = top + labelH + row * rowH
        var pos = Math.floorMod(song.framesOf(h.tick) - clip.srcStart, len)
        while (pos < clip.length) {
            val f = clip.start + pos
            if (f > visEnd) break
            if (f >= visStart - len) {
                val x = tl.pxOf(f)
                drawRect(Colors.accent.copy(alpha = 0.85f), Offset(x, y + 1f), Size(markW, (rowH - 2f).coerceAtLeast(1f)))
            }
            pos += len
        }
    }
}

// ----------------------------------------------------------------- waveform --

/** Widest clip, in pixel columns, that the line cache will hold. */
private const val MAX_WAVE_COLS = 4096

private data class WaveKey(val take: String, val srcStart: Long, val length: Long)

/**
 * Line arrays in clip-local pixel space, so panning translates a cached array
 * instead of re-reducing every bucket. Zoom and lane height are baked into the
 * geometry, so a change in either drops the whole map.
 */
private class WaveCache {
    private val lines = HashMap<WaveKey, FloatArray>()
    private var framesPerPx = -1f
    private var laneH = -1f

    fun get(key: WaveKey, framesPerPx: Float, laneH: Float, build: () -> FloatArray): FloatArray {
        // The size guard catches trim drags, whose key moves every frame.
        if (framesPerPx != this.framesPerPx || laneH != this.laneH || lines.size > 64) {
            lines.clear()
            this.framesPerPx = framesPerPx
            this.laneH = laneH
        }
        return lines.getOrPut(key, build)
    }
}

// One native drawLines call per clip, with bucket sampling capped so zoomed-out
// long takes stay cheap. Clips narrower than the cache's reach are built once in
// their own coordinates and translated into place; wider ones take the direct
// path.
private fun DrawScope.drawWaveform(
    cache: WaveCache,
    peaks: PeakData?,
    take: String,
    srcStart: Long,
    length: Long,
    x0: Float,
    x1: Float,
    framesPerPx: Float,
    paint: android.graphics.Paint,
    lines: FloatArray,
) {
    peaks ?: return
    if (peaks.data.size < 2 || x1 - x0 < 1f) return
    val cols = (x1 - x0).toInt()
    if (cols in 1..MAX_WAVE_COLS) {
        val arr = cache.get(WaveKey(take, srcStart, length), framesPerPx, size.height) {
            buildWaveLines(peaks, srcStart, length, x1 - x0, size.height)
        }
        withTransform({ translate(left = kotlin.math.floor(x0)) }) {
            drawContext.canvas.nativeCanvas.drawLines(arr, 0, arr.size, paint)
        }
        return
    }
    val midY = size.height / 2f
    val amp = (size.height / 2f) - 6f
    val perPx = length.toFloat() / (x1 - x0)
    val left = maxOf(x0, 0f).toInt()
    // One pixel of slack keeps room for the centerline segment below.
    val right = minOf(x1, size.width).toInt().coerceAtMost(left + lines.size / 4 - 1)
    var n = 0
    for (x in left until right) {
        val f0 = srcStart + ((x - x0) * perPx).toLong()
        val f1 = (srcStart + ((x + 1 - x0) * perPx).toLong()).coerceAtLeast(f0 + 1)
        n = appendColumn(peaks, lines, n, x.toFloat(), f0, f1, midY, amp)
    }
    if (right > left) {
        // Centerline across the clip: true silence reads as a flat line, not
        // as a hole in the waveform.
        lines[n++] = left.toFloat()
        lines[n++] = midY
        lines[n++] = right.toFloat()
        lines[n++] = midY
    }
    drawContext.canvas.nativeCanvas.drawLines(lines, 0, n, paint)
}

internal fun buildWaveLines(
    peaks: PeakData,
    srcStart: Long,
    length: Long,
    widthPx: Float,
    laneH: Float,
): FloatArray {
    val cols = widthPx.toInt()
    val midY = laneH / 2f
    val amp = (laneH / 2f) - 6f
    val perPx = length.toFloat() / widthPx
    val out = FloatArray(cols * 4 + 4)
    var n = 0
    for (x in 0 until cols) {
        val f0 = srcStart + (x * perPx).toLong()
        val f1 = (srcStart + ((x + 1) * perPx).toLong()).coerceAtLeast(f0 + 1)
        n = appendColumn(peaks, out, n, x.toFloat(), f0, f1, midY, amp)
    }
    out[n++] = 0f
    out[n++] = midY
    out[n++] = cols.toFloat()
    out[n] = midY
    return out
}

// One pixel column: the loudest bucket pair the column covers. Peak buckets
// are companded (sqrt domain, see Peaks.cpp); drawing them linearly yields
// the perceptual curve.
internal fun appendColumn(
    peaks: PeakData,
    out: FloatArray,
    at: Int,
    x: Float,
    f0: Long,
    f1: Long,
    midY: Float,
    amp: Float,
): Int {
    val buckets = peaks.data.size / 2
    val b0 = (f0 / peaks.bucketFrames).toInt().coerceIn(0, buckets - 1)
    val b1 = ((f1 - 1) / peaks.bucketFrames).toInt().coerceIn(b0, buckets - 1)
    val step = ((b1 - b0) / 24 + 1)
    var lo = 0
    var hi = 0
    var b = b0
    while (b <= b1) {
        val mn = peaks.data[2 * b].toInt()
        val mx = peaks.data[2 * b + 1].toInt()
        if (mn < lo) lo = mn
        if (mx > hi) hi = mx
        b += step
    }
    var n = at
    out[n++] = x
    out[n++] = midY - (hi / 127f) * amp
    out[n++] = x
    out[n++] = midY - (lo / 127f) * amp
    return n
}
