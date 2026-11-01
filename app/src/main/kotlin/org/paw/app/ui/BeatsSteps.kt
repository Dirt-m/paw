package org.paw.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.paw.app.SongController
import org.paw.app.model.Pattern
import org.paw.app.model.Sample

// The step grid of Beats mode, and the same rows turned into the kit: each
// sound's name, level, audition and removal on the row of its steps.

private val LabelShape = RoundedCornerShape(Dimens.corner)
private val HEAD_H = 20.dp
private const val FIT_STEPS = 16
private val CELL_MAX = 64.dp

/**
 * The grid of [pattern] over [samples], or their kit rows when [kit]. Rows
 * grow from [rowMin] to [rowMax] to fill the height and scroll past it. With
 * no pattern the grid is an empty bar of 4/4 and the first tap on it makes the
 * pattern, through [onFirstStep].
 */
@Composable
internal fun BeatsSteps(
    controller: SongController,
    trackId: Int,
    samples: List<Sample>,
    pattern: Pattern?,
    kit: Boolean,
    rowMin: Dp,
    rowMax: Dp,
    onFirstStep: (sampleId: Int, step: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val n = samples.size.coerceAtLeast(1)
        if (kit) {
            val rowH = ((maxHeight - Dimens.gap) / n).coerceIn(rowMin, 56.dp)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
                for (s in samples) {
                    androidx.compose.runtime.key(s.id) { KitRow(controller, trackId, s, rowH) }
                }
            }
            return@BoxWithConstraints
        }

        val p = pattern ?: Pattern(id = -1, name = "")
        val rowH = ((maxHeight - HEAD_H - Dimens.gap) / n).coerceIn(rowMin, rowMax)
        val gridW = maxWidth - BeatsLabelW - Dimens.gap * 2
        val cellW = (gridW / p.steps.coerceIn(1, FIT_STEPS)).coerceAtMost(CELL_MAX)
        val hScroll = rememberScrollState()
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(HEAD_H)) {
                Box(Modifier.width(BeatsLabelW + Dimens.gap * 3 / 2))
                Box(Modifier.weight(1f).horizontalScroll(hScroll)) { BeatNumbers(p, cellW) }
            }
            Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(start = Dimens.gap, end = Dimens.gap / 2)) {
                    for (s in samples) {
                        AuditionLabel(s.name, rowH) { controller.triggerSample(trackId, s.id) }
                    }
                }
                Box(Modifier.weight(1f).horizontalScroll(hScroll).padding(end = Dimens.gap)) {
                    StepCells(controller, samples, p, real = pattern != null, cellW, rowH, onFirstStep)
                }
            }
        }
    }
}

/** A sound's name at the head of its row; touching it plays the sound. */
@Composable
private fun AuditionLabel(name: String, rowH: Dp, onHit: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    val hit by rememberUpdatedState(onHit)
    Box(
        Modifier
            .padding(vertical = 3.dp)
            .width(BeatsLabelW)
            .height(rowH - 6.dp)
            .clip(LabelShape)
            .background(if (down) Colors.accent else Colors.raised)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    hit()
                    down = true
                    waitForUpOrCancellation()
                    down = false
                }
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            name,
            style = NameStyle.copy(color = if (down) Colors.bg else Colors.text, fontSize = 13.sp, lineHeight = 15.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Where each group of beats starts within a bar: 0, 2, 4 for 2+2+3. */
private fun groupStarts(p: Pattern): Set<Int> = p.grouping.runningFold(0) { a, g -> a + g }.dropLast(1).toSet()

@Composable
private fun BeatNumbers(p: Pattern, cellW: Dp) {
    val groups = groupStarts(p)
    Row(Modifier.height(HEAD_H), verticalAlignment = Alignment.CenterVertically) {
        for (bar in 0 until p.bars) for (beat in 0 until p.beats) {
            // A bar starts with its own number; inside a bar the beats count.
            val label = if (beat == 0 && p.bars > 1) "${bar + 1}." else "${beat + 1}"
            Text(
                label,
                style = MonoDimStyle.copy(
                    fontSize = 11.sp,
                    color = when {
                        beat == 0 -> Colors.text
                        beat in groups -> Colors.dim
                        else -> Colors.dim.copy(alpha = 0.55f)
                    },
                ),
                modifier = Modifier.width(cellW * p.stepsPerBeat).padding(start = 4.dp),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun StepCells(
    controller: SongController,
    samples: List<Sample>,
    p: Pattern,
    real: Boolean,
    cellW: Dp,
    rowH: Dp,
    onFirstStep: (Int, Int) -> Unit,
) {
    val steps = p.steps
    val on = remember(p.hits, p.ticksPerStep, samples) {
        samples.map { s -> p.hits.filter { it.sample == s.id }.map { it.tick / p.ticksPerStep }.toSet() }
    }
    val groups = groupStarts(p)
    val bounds = p.grouping.runningFold(0) { a, g -> a + g }
    val song = controller.song
    val first by rememberUpdatedState(onFirstStep)

    Canvas(
        Modifier
            .width(cellW * steps)
            .height(rowH * samples.size)
            .pointerInput(p.id, real, samples, on, cellW, rowH) {
                detectTapGestures { pos ->
                    val col = (pos.x / cellW.toPx()).toInt()
                    val row = (pos.y / rowH.toPx()).toInt()
                    val s = samples.getOrNull(row) ?: return@detectTapGestures
                    if (col !in 0 until steps) return@detectTapGestures
                    if (real) controller.setStep(p.id, s.id, col, col !in on[row]) else first(s.id, col)
                }
            },
    ) {
        // Read here, in drawing, so the moving step redraws the grid
        // without recomposing it.
        val playStep = if (real && controller.previewing == p.id && controller.previewFrame >= 0) {
            val tick = (controller.previewFrame / song.framesPerTick).toLong()
            ((tick % p.lengthTicks) / p.ticksPerStep).toInt()
        } else -1
        val cw = cellW.toPx()
        val rh = rowH.toPx()
        val inset = 3.dp.toPx()
        val radius = CornerRadius(4.dp.toPx())
        for (col in 0 until steps) {
            val beatInBar = (col / p.stepsPerBeat) % p.beats
            val beatStart = col % p.stepsPerBeat == 0
            val groupIdx = bounds.indexOfLast { it <= beatInBar }
            // Alternate shading per group, so 2+2+3 reads at a glance.
            if (groupIdx % 2 == 1) {
                drawRect(Colors.panel, Offset(col * cw, 0f), Size(cw, size.height))
            }
            if (col == playStep) {
                drawRect(Colors.accent.copy(alpha = 0.16f), Offset(col * cw, 0f), Size(cw, size.height))
            }
            if (beatStart && col > 0) {
                val barStart = beatInBar == 0
                val color = when {
                    barStart -> Colors.dim
                    beatInBar in groups -> Colors.line
                    else -> Colors.line.copy(alpha = 0.5f)
                }
                drawLine(color, Offset(col * cw, 0f), Offset(col * cw, size.height), strokeWidth = if (barStart) 2.dp.toPx() else 1f)
            }
            for (row in samples.indices) {
                val hit = col in on[row]
                val color = when {
                    hit && col == playStep -> Colors.text
                    hit -> Colors.accent
                    beatStart -> Colors.raised
                    else -> Colors.raised.copy(alpha = 0.7f)
                }
                drawRoundRect(
                    color,
                    topLeft = Offset(col * cw + inset, row * rh + inset),
                    size = Size(cw - 2 * inset, rh - 2 * inset),
                    cornerRadius = radius,
                )
            }
        }
    }
}

/**
 * One sound of the kit on its grid row: name (tap to rename), level, play
 * and remove. The level slider is live: one undo step per drag, and the
 * sound plays when the finger lifts so the new level is heard.
 */
@Composable
private fun KitRow(controller: SongController, trackId: Int, s: Sample, rowH: Dp) {
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(rowH).padding(horizontal = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val name = renaming
        when {
            confirming -> ConfirmRow(
                "Remove ${s.name}?", "Remove",
                onConfirm = { confirming = false; controller.removeSample(trackId, s.id) },
                onCancel = { confirming = false },
            )
            name != null -> {
                NameField(name, { renaming = it }, Modifier.weight(1f), maxLength = 24)
                AppButton("Save", accent = Colors.accent, filled = true, enabled = name.isNotBlank(), onClick = {
                    controller.renameSample(trackId, s.id, name)
                    renaming = null
                })
                IconButton(CloseGlyph, onClick = { renaming = null })
            }
            else -> {
                // Drawn as a field, not a pad: here the name is for editing.
                Box(
                    Modifier
                        .width(BeatsLabelW + 8.dp)
                        .height(Dimens.touch)
                        .clip(LabelShape)
                        .border(1.dp, Colors.line, LabelShape)
                        .clickable { renaming = s.name }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(s.name, style = NameStyle.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MiniSlider(
                    value = gainToPos(s.gain),
                    onChange = { controller.setSampleGain(trackId, s.id, posToGain(it), undoable = false) },
                    onStart = { controller.beginGesture() },
                    onCommit = { controller.triggerSample(trackId, s.id) },
                    onReset = { controller.setSampleGain(trackId, s.id, 1f) },
                    modifier = Modifier.weight(1f),
                )
                ValueReadout(
                    formatDb(s.gain),
                    style = MonoStyle.copy(fontSize = 12.sp, color = if (s.gain == 1f) Colors.dim else Colors.text),
                    modifier = Modifier.width(62.dp),
                    onReset = { controller.setSampleGain(trackId, s.id, 1f) },
                )
                IconButton(PlayGlyph, onClick = { controller.triggerSample(trackId, s.id) }, tint = Colors.green, glyphSize = 16.dp)
                IconButton(CloseGlyph, onClick = { confirming = true }, tint = Colors.dim, glyphSize = 16.dp)
            }
        }
    }
}
