package org.paw.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.paw.app.SongController

// Tempo and meter, count-in and click. The transport stays live under the
// panel, so the click can be heard while the tempo moves.

private const val TEMPO_MIN = 30f
private const val TEMPO_MAX = 300f

/** A pause this long starts a new tap count. */
private const val TAP_RESET_MS = 2000L

@Composable
internal fun TempoPanel(controller: SongController, ui: SongUi, compact: Boolean, modifier: Modifier) {
    val song = controller.song
    // Slider drags and tap counts only preview; the song takes the tempo once.
    var live by remember { mutableStateOf<Float?>(null) }
    val bpm = live ?: song.tempoBpm
    fun set(v: Float) {
        live = null
        controller.setTempo(v.roundToInt().toFloat().coerceIn(TEMPO_MIN, TEMPO_MAX))
    }

    Column(modifier) {
        PanelHeader("Tempo", onClose = ui::close)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))
        PanelBody(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().padding(top = if (compact) 12.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RepeatButton("−", { set(bpm - 1f) }, { set(bpm - 5f) }, if (compact) 64.dp else 52.dp)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "%.0f".format(bpm),
                        style = ClockStyle.copy(fontSize = if (compact) 56.sp else 40.sp, textAlign = TextAlign.Center),
                        maxLines = 1,
                    )
                    Text(
                        "BPM · ${song.beatsPerBar}/${song.beatUnit}",
                        style = LabelStyle.copy(fontSize = 12.sp),
                    )
                }
                RepeatButton("+", { set(bpm + 1f) }, { set(bpm + 5f) }, if (compact) 64.dp else 52.dp)
            }
            VSpace(if (compact) 12.dp else 4.dp)
            MiniSlider(
                value = (bpm - TEMPO_MIN) / (TEMPO_MAX - TEMPO_MIN),
                onChange = { pos ->
                    val v = TEMPO_MIN + pos * (TEMPO_MAX - TEMPO_MIN)
                    live = v
                    controller.previewTempo(v)
                },
                onCommit = { live?.let { set(it) } },
                onReset = { set(120f) },
                modifier = Modifier.fillMaxWidth(),
            )
            VSpace(if (compact) 12.dp else 6.dp)
            TapButton(controller, onLive = { live = it }, onDone = { set(it) })
            VSpace(if (compact) 16.dp else 8.dp)
            PanelDivider()
            SettingRow("Beats per bar", labelWidth = 120.dp) {
                Box(Modifier.weight(1f))
                Stepper(
                    "${song.beatsPerBar}",
                    onDown = { controller.setMeter((song.beatsPerBar - 1).coerceAtLeast(1), song.beatUnit) },
                    onUp = { controller.setMeter((song.beatsPerBar + 1).coerceAtMost(12), song.beatUnit) },
                )
            }
            SettingRow("Beat unit", labelWidth = 120.dp) {
                Segmented(
                    listOf("4", "8"),
                    selected = if (song.beatUnit == 8) 1 else 0,
                    onSelect = { controller.setMeter(song.beatsPerBar, if (it == 1) 8 else 4) },
                    modifier = Modifier.weight(1f),
                )
            }
            PanelDivider()
            SettingRow("Count-in", labelWidth = 120.dp) {
                Segmented(
                    listOf("Off", "1 bar", "2 bars"),
                    selected = controller.countInBars.coerceIn(0, 2),
                    onSelect = { controller.setCountIn(it) },
                    modifier = Modifier.weight(1f),
                )
            }
            SettingRow("Click", labelWidth = 120.dp) {
                Segmented(
                    listOf("Off", "On"),
                    selected = if (controller.clickEnabled) 1 else 0,
                    onSelect = { controller.setClick(it == 1) },
                    accent = Colors.green,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** − or +: a tap steps by one, a long press by five. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepeatButton(label: String, onClick: () -> Unit, onLongClick: () -> Unit, size: Dp) {
    val shape = RoundedCornerShape(Dimens.corner)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(Colors.raised)
            .border(1.dp, Colors.line, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TitleStyle.copy(fontSize = 26.sp, fontWeight = FontWeight.Normal))
    }
}

/**
 * Tap along with the beat: the tempo is the average of the last few taps,
 * counted in the song's beat (an eighth in /8) and set as quarters. A pause
 * of two seconds ends the count and keeps what it found.
 */
@Composable
private fun TapButton(controller: SongController, onLive: (Float?) -> Unit, onDone: (Float) -> Unit) {
    val taps = remember { mutableStateListOf<Long>() }
    var found by remember { mutableStateOf<Float?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        delay(TAP_RESET_MS)
        found?.let { done(it) }
        found = null
        taps.clear()
    }
    // Leaving the panel mid-count keeps the count.
    DisposableEffect(Unit) {
        onDispose { found?.let { done(it) } }
    }
    val shape = RoundedCornerShape(Dimens.corner)
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(if (taps.isEmpty()) Colors.raised else Colors.accent.copy(alpha = 0.18f))
            .border(1.dp, if (taps.isEmpty()) Colors.line else Colors.accent, shape)
            .pointerTapDown {
                val now = System.currentTimeMillis()
                if (taps.isNotEmpty() && now - taps.last() > TAP_RESET_MS) taps.clear()
                taps += now
                while (taps.size > 5) taps.removeAt(0)
                if (taps.size >= 2) {
                    val interval = (taps.last() - taps.first()).toFloat() / (taps.size - 1)
                    val songBeats = 60000f / interval
                    val quarters = (songBeats * 4f / controller.song.beatUnit).coerceIn(TEMPO_MIN, TEMPO_MAX)
                    found = quarters
                    onLive(quarters)
                    controller.previewTempo(quarters)
                }
                tick++
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (taps.isEmpty()) "Tap tempo" else "Tap ${taps.size}",
            style = ButtonStyle.copy(color = if (taps.isEmpty()) Colors.text else Colors.accent, fontSize = 15.sp),
        )
    }
}

/** Taps count on touch down, like a pad: a tap tempo that waits for the
 *  release lags the finger. */
private fun Modifier.pointerTapDown(onDown: () -> Unit): Modifier =
    this.then(
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                onDown()
            }
        },
    )
