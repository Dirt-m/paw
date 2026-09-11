package org.paw.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.paw.app.SongController

// Shared primitives. Anything a finger lands on is at least [Dimens.touch] tall.

private val ButtonShape = RoundedCornerShape(Dimens.corner)

/**
 * The app's button. Outlined by default; [filled] makes it the primary
 * action of its row (tinted background). [accent] colors both the border and
 * the label: red for destructive, amber for primary, text for neutral.
 */
@Composable
fun AppButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = Colors.text,
    filled: Boolean = false,
    height: Dp = Dimens.touch,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
) {
    val border = if (!enabled) Colors.line else if (filled) accent else accent.copy(alpha = 0.55f)
    val tint = if (enabled) accent else Colors.dim.copy(alpha = 0.6f)
    Box(
        modifier
            .heightIn(min = height)
            .clip(ButtonShape)
            .background(if (filled && enabled) accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, border, ButtonShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = ButtonStyle.copy(color = tint, fontSize = fontSize),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * A state button: lit when [on] (tinted fill, colored border and label),
 * quiet otherwise. Arm, mute, solo, click, loop, monitor, bypass.
 */
@Composable
fun Toggle(
    label: String,
    on: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Dimens.touch,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
) {
    Box(
        modifier
            .defaultMinSize(minWidth = size, minHeight = size)
            .clip(ButtonShape)
            .background(if (on) color.copy(alpha = 0.24f) else Colors.raised)
            .border(1.dp, if (on) color else Colors.line, ButtonShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = ButtonStyle.copy(
                color = if (on) color else Colors.dim,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** One-of-N choice drawn as connected segments; the chosen one is lit. */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Colors.amber,
) {
    Row(
        modifier
            .heightIn(min = Dimens.touch)
            .clip(ButtonShape)
            .border(1.dp, Colors.line, ButtonShape),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = Dimens.touch)
                    .background(if (on) accent.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = ButtonStyle.copy(color = if (on) accent else Colors.dim),
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                )
            }
            if (i < options.lastIndex) {
                Box(Modifier.width(1.dp).heightIn(min = Dimens.touch).background(Colors.line))
            }
        }
    }
}

/** A full-width pick-one row: radio dot, label, optional detail on the right. */
@Composable
fun RadioRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    detail: String? = null,
    accent: Color = Colors.green,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(ButtonShape)
            .background(if (selected) accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .border(2.dp, if (selected) accent else Colors.dim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(9.dp).background(accent, CircleShape))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = BodyStyle.copy(color = if (selected) Colors.text else Colors.dim),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            Text(detail, style = MonoDimStyle, maxLines = 1)
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = LabelStyle, modifier = modifier)
}

/** A screen header: back control, title, and whatever actions the screen has. */
@Composable
fun ScreenTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String = "Back",
    titleColor: Color = Colors.text,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Colors.panel)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(backLabel, onBack)
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            style = TitleStyle.copy(color = titleColor),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
fun BackButton(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = Dimens.touch)
            .clip(ButtonShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹", style = TitleStyle.copy(color = Colors.dim, fontSize = 24.sp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = BodyStyle.copy(color = Colors.dim))
    }
}

/** Panel surface: the flat card every settings list sits on. */
fun Modifier.panel(): Modifier = this
    .background(Colors.panel)
    .border(1.dp, Colors.line)

/** Inline confirmation that replaces the control that asked for it. */
@Composable
fun ConfirmRow(
    prompt: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Colors.red,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            prompt,
            style = BodyStyle.copy(color = accent),
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        AppButton(confirmLabel, accent = accent, filled = true, onClick = onConfirm)
        AppButton("Cancel", accent = Colors.dim, onClick = onCancel)
    }
}

/** Single-line text entry with the accent underline; the app's only text field. */
@Composable
fun NameField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxLength: Int = 40,
) {
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.take(maxLength)) },
        textStyle = NameStyle,
        cursorBrush = SolidColor(Colors.amber),
        singleLine = true,
        modifier = modifier
            .heightIn(min = Dimens.touch)
            .clip(ButtonShape)
            .background(Colors.raised)
            .border(1.dp, Colors.amber, ButtonShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * A setting row: label in a fixed column on the left, control on the right.
 * Stacks the control under the label when the row is too narrow to share.
 */
@Composable
fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 110.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = BodyStyle,
            modifier = Modifier.width(labelWidth),
            maxLines = 2,
        )
        content()
    }
}

/** The −/+ stepper for integers. */
@Composable
fun Stepper(
    value: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
    onTapValue: (() -> Unit)? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AppButton("−", onClick = onDown, fontSize = 18.sp)
        Text(
            value,
            style = MonoStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(84.dp)
                .then(
                    if (onTapValue != null) Modifier.clip(ButtonShape).clickable(onClick = onTapValue)
                    else Modifier,
                )
                .padding(vertical = 8.dp),
        )
        AppButton("+", onClick = onUp, fontSize = 18.sp)
    }
}

// -------------------------------------------------------------------- slider --

/**
 * The fader. [value] is normalized 0..1. onStart fires once when a gesture
 * begins (undo snapshots hang off it), onCommit once when it ends. A long
 * press fires onReset; double tap would add a detection delay to plain taps.
 *
 * [vertical] makes a channel-strip fader: 1.0 at the top, only vertical
 * drags claimed.
 */
@Composable
fun MiniSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Colors.amber,
    onCommit: (() -> Unit)? = null,
    onStart: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    vertical: Boolean = false,
    centerTick: Boolean = false,
) {
    Canvas(
        modifier
            .then(if (vertical) Modifier.width(36.dp) else Modifier.height(36.dp))
            .pointerInput(vertical) {
                detectTapGestures(
                    onLongPress = if (onReset != null) { _ -> onReset() } else null,
                ) { ofs ->
                    onStart?.invoke()
                    onChange(fraction(ofs, vertical))
                    onCommit?.invoke()
                }
            }
            .pointerInput(vertical) {
                // A cross-axis drag that starts on a slider belongs to the
                // container under it. A cancelled drag still ends the gesture.
                if (vertical) {
                    detectVerticalDragGestures(
                        onDragStart = { onStart?.invoke() },
                        onDragEnd = { onCommit?.invoke() },
                        onDragCancel = { onCommit?.invoke() },
                    ) { change, _ ->
                        change.consume()
                        onChange(fraction(change.position, true))
                    }
                } else {
                    detectHorizontalDragGestures(
                        onDragStart = { onStart?.invoke() },
                        onDragEnd = { onCommit?.invoke() },
                        onDragCancel = { onCommit?.invoke() },
                    ) { change, _ ->
                        change.consume()
                        onChange(fraction(change.position, false))
                    }
                }
            },
    ) {
        val v = value.coerceIn(0f, 1f)
        val trackW = 4.dp.toPx()
        val thumbLong = 22.dp.toPx()
        val thumbShort = 6.dp.toPx()
        if (vertical) {
            val midX = size.width / 2f
            val y = (1f - v) * size.height
            drawLine(Colors.line, Offset(midX, 0f), Offset(midX, size.height), strokeWidth = trackW)
            drawLine(accent.copy(alpha = 0.55f), Offset(midX, y), Offset(midX, size.height), strokeWidth = trackW)
            drawRoundRect(
                accent,
                topLeft = Offset(midX - thumbLong / 2f, y - thumbShort / 2f),
                size = Size(thumbLong, thumbShort),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbShort / 2f),
            )
        } else {
            val midY = size.height / 2f
            val x = v * size.width
            drawLine(Colors.line, Offset(0f, midY), Offset(size.width, midY), strokeWidth = trackW)
            if (centerTick) {
                val cx = size.width / 2f
                drawLine(Colors.dim, Offset(cx, midY - 8.dp.toPx()), Offset(cx, midY + 8.dp.toPx()), strokeWidth = 2f)
            }
            drawLine(accent.copy(alpha = 0.55f), Offset(0f, midY), Offset(x, midY), strokeWidth = trackW)
            drawRoundRect(
                accent,
                topLeft = Offset(x - thumbShort / 2f, midY - thumbLong / 2f),
                size = Size(thumbShort, thumbLong),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbShort / 2f),
            )
        }
    }
}

private fun androidx.compose.ui.input.pointer.PointerInputScope.fraction(
    pos: Offset,
    vertical: Boolean,
): Float =
    if (vertical) (1f - pos.y / size.height).coerceIn(0f, 1f)
    else (pos.x / size.width).coerceIn(0f, 1f)

// --------------------------------------------------------------------- meter --

// Meter scale: dB from floor to ceiling so the useful range fills the bar
// (drawn linearly, everything below -12 dBFS sits in the leftmost quarter).
private const val METER_DB_FLOOR = -48f
private const val METER_DB_CEIL = 6f
private const val METER_DB_WARN = -12f

/** Decay per tick and the tick itself: the needle falls to a tenth of a
 *  full-scale peak in about a third of a second. */
private const val METER_DECAY = 0.82f
private const val METER_TICK_MS = 33L

private fun meterFrac(db: Float) =
    ((db - METER_DB_FLOOR) / (METER_DB_CEIL - METER_DB_FLOOR)).coerceIn(0f, 1f)

private fun levelFrac(v: Float) = if (v <= 0f) 0f else meterFrac(20f * log10(v))

/** One bar's animation state. The level and the hold time are written from
 *  composition and read by the bar's own timer, so a level that stops
 *  arriving still decays. */
@Stable
private class MeterState {
    var input: Float by mutableFloatStateOf(0f)
    var hold: Duration? = null
    var shown: Float by mutableFloatStateOf(0f)
    var held: Float by mutableFloatStateOf(0f)
    var heldForMs: Long = 0L

    val idle: Boolean
        get() = input <= 0f && shown <= 0f && (held <= 0f || hold == null || hold == Duration.INFINITE)
}

/**
 * Peak meter, dB scale. Green below -12 dBFS, orange from there to the red
 * 0 dBFS line, red past it (the zone beyond the line stays tinted so it
 * reads as overload). A tick holds the loudest recent peak for [hold] (null =
 * off, Duration.INFINITE = until resetKey changes), and turns red once it clipped.
 */
@Composable
fun MeterBar(
    peak: Float,
    modifier: Modifier = Modifier,
    hold: Duration? = null,
    resetKey: Any? = null,
    vertical: Boolean = false,
    thickness: Dp = 8.dp,
) {
    val state = remember { MeterState() }
    state.input = peak
    state.hold = hold
    LaunchedEffect(resetKey) { state.held = 0f }
    LaunchedEffect(state) {
        while (true) {
            if (state.idle) snapshotFlow { state.input }.first { it > 0f }
            val v = state.input
            val decayed = maxOf(v, state.shown * METER_DECAY)
            if (decayed != state.shown) state.shown = decayed
            if (v > state.held) {
                state.held = v
                state.heldForMs = 0L
            } else {
                state.heldForMs += METER_TICK_MS
            }
            val holdMs = state.hold?.takeIf { it != Duration.INFINITE }?.inWholeMilliseconds
            if (holdMs != null && state.heldForMs >= holdMs) state.held = 0f
            delay(METER_TICK_MS)
        }
    }
    val shown = state.shown
    val held = state.held
    Canvas(modifier.then(if (vertical) Modifier.width(thickness) else Modifier.height(thickness))) {
        if (vertical) {
            fun yOf(frac: Float) = size.height - frac * size.height
            val clipY = yOf(meterFrac(0f))
            val warnY = yOf(meterFrac(METER_DB_WARN))
            drawRect(
                Colors.line.copy(alpha = 0.5f),
                topLeft = Offset(0f, clipY),
                size = Size(size.width, size.height - clipY),
            )
            drawRect(Colors.red.copy(alpha = 0.16f), size = Size(size.width, clipY))
            val y = yOf(levelFrac(shown))
            drawRect(
                Colors.green,
                topLeft = Offset(0f, maxOf(y, warnY)),
                size = Size(size.width, size.height - maxOf(y, warnY)),
            )
            if (y < warnY) {
                drawRect(
                    Colors.orange,
                    topLeft = Offset(0f, maxOf(y, clipY)),
                    size = Size(size.width, warnY - maxOf(y, clipY)),
                )
            }
            if (y < clipY) {
                drawRect(Colors.red, topLeft = Offset(0f, y), size = Size(size.width, clipY - y))
            }
            drawLine(Colors.red, Offset(0f, clipY), Offset(size.width, clipY), strokeWidth = 2f)
            if (hold != null && held > 0f) {
                val hy = yOf(levelFrac(held))
                drawLine(
                    if (held >= 1f) Colors.red else Colors.text,
                    start = Offset(0f, hy),
                    end = Offset(size.width, hy),
                    strokeWidth = 3f,
                )
            }
        } else {
            val clipX = meterFrac(0f) * size.width
            val warnX = meterFrac(METER_DB_WARN) * size.width
            drawRect(Colors.line.copy(alpha = 0.5f), size = size.copy(width = clipX))
            drawRect(
                Colors.red.copy(alpha = 0.16f),
                topLeft = Offset(clipX, 0f),
                size = Size(size.width - clipX, size.height),
            )
            val x = levelFrac(shown) * size.width
            drawRect(Colors.green, size = size.copy(width = minOf(x, warnX)))
            if (x > warnX) {
                drawRect(
                    Colors.orange,
                    topLeft = Offset(warnX, 0f),
                    size = Size(minOf(x, clipX) - warnX, size.height),
                )
            }
            if (x > clipX) {
                drawRect(Colors.red, topLeft = Offset(clipX, 0f), size = Size(x - clipX, size.height))
            }
            drawLine(Colors.red, start = Offset(clipX, 0f), end = Offset(clipX, size.height), strokeWidth = 2f)
            if (hold != null && held > 0f) {
                val hx = levelFrac(held) * size.width
                drawLine(
                    if (held >= 1f) Colors.red else Colors.text,
                    start = Offset(hx, 0f),
                    end = Offset(hx, size.height),
                    strokeWidth = 3f,
                )
            }
        }
    }
}

/**
 * Phone-mic boost, on both the track and the settings screen. The drag pushes
 * only the engine gain; the preference write happens once, on release.
 */
@Composable
fun MicBoostSlider(controller: SongController, modifier: Modifier = Modifier) {
    var live by remember { mutableStateOf<Float?>(null) }
    val db = live ?: controller.micBoostDb
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MiniSlider(
            value = db / 32f,
            onChange = { pos ->
                val v = (pos * 32f).coerceIn(0f, 32f)
                live = v
                controller.previewMicBoost(v)
            },
            onCommit = {
                live?.let { controller.setMicBoost(it) }
                live = null
            },
            onReset = {
                live = null
                controller.setMicBoost(16f)
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text("+%.0f dB".format(db), style = MonoStyle, modifier = Modifier.width(56.dp))
    }
}

/**
 * Thin always-visible scrollbar for panels whose settings run past the fold.
 * Apply before verticalScroll(state) so it draws in viewport coordinates.
 */
fun Modifier.verticalScrollbar(state: ScrollState): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max > 0 && max != Int.MAX_VALUE) {
        val barW = 3.dp.toPx()
        val thumbH = (size.height * size.height / (size.height + max))
            .coerceAtLeast(24.dp.toPx())
        val y = state.value.toFloat() / max * (size.height - thumbH)
        drawRect(
            Colors.dim.copy(alpha = 0.45f),
            topLeft = Offset(size.width - barW, y),
            size = Size(barW, thumbH),
        )
    }
}

// ------------------------------------------------------------- value mapping --

/** Fader position 0..1 <-> linear gain, -60..+12 dB with 0 dB at ~0.83. */
fun posToGain(pos: Float): Float {
    if (pos <= 0f) return 0f
    val db = -60f + pos.coerceIn(0f, 1f) * 72f
    return 10f.pow(db / 20f)
}

fun gainToPos(gain: Float): Float {
    if (gain <= 0f) return 0f
    val db = 20f * log10(gain.coerceAtLeast(1e-4f))
    return ((db + 60f) / 72f).coerceIn(0f, 1f)
}

fun formatDb(gain: Float): String =
    if (gain <= 0f) "-inf" else "%+.1f dB".format(20f * log10(gain))

fun formatPan(pan: Float): String = when {
    pan < -0.02f -> "L%2.0f".format(-pan * 100)
    pan > 0.02f -> "R%2.0f".format(pan * 100)
    else -> "C"
}

fun formatTime(frames: Long, sampleRate: Int): String {
    val totalCs = frames * 100 / sampleRate
    val cs = totalCs % 100
    val s = totalCs / 100 % 60
    val m = totalCs / 6000
    return "%02d:%02d.%02d".format(m, s, cs)
}

/** Short form for lists: 0:12, 1:03. */
fun formatShortTime(frames: Long, sampleRate: Int): String {
    val totalS = frames / sampleRate
    return "%d:%02d".format(totalS / 60, totalS % 60)
}

fun formatRulerTime(frames: Long, sampleRate: Int): String = formatShortTime(frames, sampleRate)

/** A track's volume + pan block, shared by the track screen and the mixer:
 *  drags preview against the engine, the model lands once on release. */
@Composable
fun GainSlider(
    controller: SongController,
    trackId: Int,
    gain: Float,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    onLive: ((Float?) -> Unit)? = null,
) {
    var liveGain by remember { mutableStateOf<Float?>(null) }
    MiniSlider(
        value = liveGain ?: gainToPos(gain),
        vertical = vertical,
        onStart = { controller.beginGesture() },
        onChange = { pos ->
            liveGain = pos
            onLive?.invoke(posToGain(pos))
            controller.previewTrackGain(trackId, posToGain(pos))
        },
        onCommit = {
            liveGain?.let { pos ->
                controller.updateTrack(trackId, undoable = false) { it.copy(gain = posToGain(pos)) }
            }
            liveGain = null
            onLive?.invoke(null)
        },
        onReset = {
            liveGain = null
            onLive?.invoke(null)
            controller.updateTrack(trackId) { it.copy(gain = 1f) }
        },
        modifier = modifier,
    )
}

@Composable
fun PanSlider(
    controller: SongController,
    trackId: Int,
    pan: Float,
    modifier: Modifier = Modifier,
    onLive: ((Float?) -> Unit)? = null,
) {
    var livePan by remember { mutableStateOf<Float?>(null) }
    MiniSlider(
        value = livePan ?: (pan + 1f) / 2f,
        centerTick = true,
        onStart = { controller.beginGesture() },
        onChange = { pos ->
            livePan = pos
            onLive?.invoke(pos * 2f - 1f)
            controller.previewTrackPan(trackId, pos * 2f - 1f)
        },
        onCommit = {
            livePan?.let { pos ->
                controller.updateTrack(trackId, undoable = false) { it.copy(pan = pos * 2f - 1f) }
            }
            livePan = null
            onLive?.invoke(null)
        },
        onReset = {
            livePan = null
            onLive?.invoke(null)
            controller.updateTrack(trackId) { it.copy(pan = 0f) }
        },
        modifier = modifier,
    )
}

/** Small filled status dot. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, CircleShape))
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

/** Column helper that puts [gap] between children. */
@Composable
fun Stack(
    modifier: Modifier = Modifier,
    gap: Dp = Dimens.gap,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap), content = content)
}
