package org.paw.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.paw.app.SongController
import org.paw.app.model.Pattern
import org.paw.app.model.Sample

// The kit as pads to play by hand. A pad fires the moment a finger lands and
// each pad takes its own finger, so two sounds hit together play together.
// While the pattern previews, each pad lights faintly as the grid plays it.

private val PadShape = RoundedCornerShape(10.dp)

@Composable
internal fun BeatsPads(
    controller: SongController,
    trackId: Int,
    samples: List<Sample>,
    pattern: Pattern?,
    columns: Int,
    modifier: Modifier = Modifier,
) {
    val rows = samples.chunked(columns)
    val step = playingStep(controller, pattern)
    val hitsNow = remember(pattern, step) {
        if (pattern == null || step < 0) emptySet()
        else pattern.hits.filter { it.tick / pattern.ticksPerStep == step }.mapTo(HashSet()) { it.sample }
    }
    Column(
        modifier.padding(Dimens.gap),
        verticalArrangement = Arrangement.spacedBy(Dimens.gap),
    ) {
        for (row in rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                for (s in row) {
                    Pad(s.name, if (s.id in hitsNow) step else -1, Modifier.weight(1f).fillMaxHeight()) {
                        controller.triggerSample(trackId, s.id)
                    }
                }
                // A short last row keeps its pads the size of the others.
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** The grid step the preview is on, or -1. Recomposes only while previewing. */
@Composable
private fun playingStep(controller: SongController, p: Pattern?): Int {
    if (p == null || controller.previewing != p.id) return -1
    val frame = controller.previewFrame
    if (frame < 0) return -1
    val tick = (frame / controller.song.framesPerTick).toLong()
    return ((tick % p.lengthTicks) / p.ticksPerStep).toInt()
}

@Composable
private fun Pad(name: String, sequencedAt: Int, modifier: Modifier, onHit: () -> Unit) {
    val flash = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }
    var down by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hit by rememberUpdatedState(onHit)
    LaunchedEffect(sequencedAt) {
        if (sequencedAt >= 0) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(260))
        }
    }
    BoxWithConstraints(
        modifier
            .clip(PadShape)
            .drawBehind {
                // Struck: full accent that fades out; played by the grid: a
                // faint wash of it.
                val base = lerp(Colors.raised, Colors.accent.copy(alpha = 0.35f), pulse.value * 0.6f)
                drawRect(lerp(base, Colors.accent, flash.value))
            }
            .border(1.dp, if (down) Colors.accent else Colors.line, PadShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    hit()
                    down = true
                    scope.launch { flash.snapTo(1f) }
                    waitForUpOrCancellation()
                    down = false
                    scope.launch { flash.animateTo(0f, tween(220)) }
                }
            }
            .padding(horizontal = 12.dp),
    ) {
        // A tall pad wears its name at the top, a short one in the middle.
        val tall = maxHeight >= 72.dp
        Text(
            name,
            style = NameStyle.copy(color = if (flash.value > 0.5f) Colors.bg else Colors.text, fontSize = 16.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = if (tall) Modifier.align(Alignment.TopStart).padding(top = 12.dp)
            else Modifier.align(Alignment.CenterStart),
        )
    }
}
