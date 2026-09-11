package org.paw.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import org.paw.app.PeakData
import org.paw.app.SongController
import org.paw.app.TRANSPORT_STOPPED
import org.paw.app.model.Clip
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
 * Time ruler over the lanes. Tap seeks, drag pans, pinch zooms. Drag a loop
 * handle to resize the region, or hold anywhere and drag to paint a new one.
 */
@Composable
fun Ruler(
    tl: TimelineState,
    controller: SongController,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val song = controller.song
    val sr = song.sampleRate
    val labels = remember { HashMap<Long, TextLayoutResult>() }
    val density = LocalDensity.current
    val edgeGrabPx = with(density) { 22.dp.toPx() }
    // Loop-drag preview; committed on release.
    var loopDrag by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    Canvas(
        modifier
            .clipToBounds()
            .background(Colors.panel)
            .pointerInput(tl) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val slop = viewConfiguration.touchSlop
                    val longPressMs = viewConfiguration.longPressTimeoutMillis
                    var acc = Offset.Zero
                    var mode: RulerMode = RulerMode.Undecided
                    val s = controller.song
                    val hasRegion = s.loopEnd > s.loopStart
                    // A finger landing on a loop edge grabs it straight away.
                    if (hasRegion) {
                        val xl = tl.pxOf(s.loopStart)
                        val xr = tl.pxOf(s.loopEnd)
                        val dl = kotlin.math.abs(down.position.x - xl)
                        val dr = kotlin.math.abs(down.position.x - xr)
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
                            val f = tl.frameAt(down.position.x).coerceAtLeast(0)
                            mode = RulerMode.Mark(f)
                            loopDrag = f to f
                            continue
                        }
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            when (val m = mode) {
                                is RulerMode.Undecided -> controller.seek(tl.frameAt(down.position.x))
                                is RulerMode.Mark -> loopDrag?.let { (a, b) ->
                                    val lo = minOf(a, b)
                                    val hi = maxOf(a, b)
                                    // Too short to loop = clear the region.
                                    controller.setLoop(lo, hi, hi - lo >= sr)
                                }
                                is RulerMode.Edge -> loopDrag?.let { (a, b) ->
                                    val lo = minOf(a, b)
                                    val hi = maxOf(a, b)
                                    controller.setLoop(lo, hi, controller.song.loopEnabled)
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
                                loopDrag = m.anchor to tl.frameAt(x).coerceAtLeast(0)
                            }
                            is RulerMode.Edge -> {
                                change.consume()
                                val f = tl.frameAt(x).coerceAtLeast(0)
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

        val stepFrames = gridStepFrames(tl.framesPerPx, sr)
        var f = (tl.originFrame / stepFrames) * stepFrames
        while (true) {
            val x = tl.pxOf(f)
            if (x > size.width) break
            if (x >= 0f) {
                drawLine(Colors.dim, Offset(x, size.height - 6.dp.toPx()), Offset(x, size.height))
                if (labels.size > 256) labels.clear()
                val label = labels.getOrPut(f) {
                    measurer.measure(AnnotatedString(formatRulerTime(f, sr)), RulerLabelStyle)
                }
                if (x + 4f + label.size.width <= size.width) {
                    drawText(label, topLeft = Offset(x + 5f, 2f))
                }
            }
            f += stepFrames
        }
        drawLine(Colors.line, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f))
        val px = tl.pxOf(controller.playheadFrame)
        if (px in 0f..size.width) {
            drawLine(Colors.amber, Offset(px, 0f), Offset(px, size.height), strokeWidth = 3f)
            val w = 5.dp.toPx()
            drawRect(Colors.amber, Offset(px - w, 0f), Size(2 * w, 4.dp.toPx()))
        }
    }
}

private val RulerLabelStyle = MonoDimStyle.copy(fontSize = 10.sp)
private val GridSteps = floatArrayOf(0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f)

fun gridStepFrames(framesPerPx: Float, sampleRate: Int): Long {
    val targetPx = 90f
    val targetSec = framesPerPx * targetPx / sampleRate
    val sec = GridSteps.firstOrNull { it >= targetSec } ?: 600f
    return (sec * sampleRate).toLong()
}

fun DrawScope.drawGrid(tl: TimelineState, sampleRate: Int) {
    val step = gridStepFrames(tl.framesPerPx, sampleRate)
    var f = (tl.originFrame / step) * step
    while (true) {
        val x = tl.pxOf(f)
        if (x > size.width) break
        if (x >= 0f) drawLine(Colors.line.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height))
        f += step
    }
    drawLine(Colors.line, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f))
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
    val fadeZone = ofsY < laneH * 0.38f
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

@Composable
fun ClipLane(
    track: Track,
    controller: SongController,
    tl: TimelineState,
    peaks: Map<String, PeakData>,
    scroll: ScrollState,
    laneTop: Float,
    viewportPx: Int,
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
                        when (val m = dragMode) {
                            is DragMode.Move ->
                                controller.moveClip(track.id, m.clip.id, m.startFrame + dragDeltaFrames)
                            is DragMode.TrimL ->
                                controller.trimClipLeft(track.id, m.clip.id, m.edge + dragDeltaFrames)
                            is DragMode.TrimR ->
                                controller.trimClipRight(track.id, m.clip.id, m.edge + dragDeltaFrames)
                            is DragMode.FadeL ->
                                controller.setClipFades(
                                    track.id, m.clip.id,
                                    m.startFade + dragDeltaFrames, m.clip.fadeOut,
                                )
                            is DragMode.FadeR ->
                                controller.setClipFades(
                                    track.id, m.clip.id,
                                    m.clip.fadeIn, m.startFade - dragDeltaFrames,
                                )
                            else -> {}
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
                                controller.selectedClip =
                                    if (hit != null) track.id to hit.id else null
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
        drawGrid(tl, controller.song.sampleRate)

        for (clip in track.clips) {
            // The in-flight drag previews through the same clamp functions the
            // commit uses, so what is drawn is what will land.
            val m = dragMode
            val preview = when {
                m is DragMode.Move && m.clip.id == clip.id ->
                    clip.movedTo(m.startFrame + dragDeltaFrames)
                m is DragMode.TrimL && m.clip.id == clip.id ->
                    clip.trimmedLeft(m.edge + dragDeltaFrames)
                m is DragMode.TrimR && m.clip.id == clip.id -> clip.trimmedRight(
                    m.edge + dragDeltaFrames, controller.takeFramesCached(clip.take),
                )
                m is DragMode.FadeL && m.clip.id == clip.id ->
                    clip.withFades(m.startFade + dragDeltaFrames, clip.fadeOut)
                m is DragMode.FadeR && m.clip.id == clip.id ->
                    clip.withFades(clip.fadeIn, m.startFade - dragDeltaFrames)
                else -> clip
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
                if (isSel) Colors.amber.copy(alpha = 0.12f) else Colors.clip,
                topLeft = Offset(x0, top),
                size = Size(x1 - x0, bot - top),
            )
            drawWaveform(
                waveCache, peaks[clip.take], clip.take, srcStart, length,
                x0, x1, tl.framesPerPx, wavePaint, waveLines,
            )

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
                if (isSel) Colors.amber else Colors.line,
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
                        Colors.amber,
                        topLeft = Offset(x - hw, cy - hh),
                        size = Size(2 * hw, 2 * hh),
                        cornerRadius = CornerRadius(hw),
                    )
                    drawLine(Colors.bg, Offset(x - gripInset, cy - hh * 0.45f), Offset(x - gripInset, cy + hh * 0.45f), strokeWidth = 1.5.dp.toPx())
                    drawLine(Colors.bg, Offset(x + gripInset, cy - hh * 0.45f), Offset(x + gripInset, cy + hh * 0.45f), strokeWidth = 1.5.dp.toPx())
                }
                // A ring at the top end of each ramp; drag it sideways to
                // change the fade.
                val r = 8.dp.toPx()
                val cyF = top + r + 3.dp.toPx()
                for (x in floatArrayOf(fxL, fxR)) {
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
            drawLine(Colors.amber, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2f)
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
