package org.paw.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.paw.app.SongController
import org.paw.app.model.Pattern
import org.paw.app.model.Sample
import org.paw.app.model.groupingsFor

// The sampler: a beat track's samples down the side, played by touching them,
// and the step grid of one pattern beside them. A tile fires on touch down and
// each takes its own finger. Play loops the pattern on its own; Place puts it
// on the timeline.

private val TileShape = RoundedCornerShape(Dimens.corner)
private val RowH = 52.dp
private val CellW = 34.dp
private val TileW = 92.dp

@Composable
fun SamplerScreen(
    controller: SongController,
    trackId: Int,
    initialPattern: Int?,
    onBack: () -> Unit,
) {
    val song = controller.song
    val track = song.track(trackId) ?: return
    var patternId by rememberSaveable {
        mutableStateOf(
            initialPattern
                ?: track.clips.firstNotNullOfOrNull { it.pattern }
                ?: song.patterns.firstOrNull()?.id,
        )
    }
    val pattern = song.pattern(patternId)
    val previewing = controller.previewing != null
    DisposableEffect(Unit) { onDispose { controller.stopPreview() } }

    Column(Modifier.fillMaxSize().background(Colors.bg).safeDrawingPadding()) {
        ScreenTopBar(title = track.name, onBack = onBack, backLabel = "Song") {
            if (pattern != null) {
                AppButton(
                    if (previewing) "■  Stop" else "▶  Play",
                    onClick = {
                        if (previewing) controller.stopPreview()
                        else controller.startPreview(trackId, pattern.id)
                    },
                    accent = if (previewing) Colors.amber else Colors.green,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))

        val strip: @Composable () -> Unit = { PatternStrip(
            patterns = song.patterns,
            selected = patternId,
            onSelect = {
                patternId = it
                if (previewing) controller.startPreview(trackId, it)
            },
            onNew = {
                val id = controller.newPattern(trackId)
                patternId = id
                if (previewing) controller.startPreview(trackId, id)
            },
            onCopy = pattern?.let { p -> { patternId = controller.newPattern(trackId, from = p) } },
        ) }
        val meter: @Composable () -> Unit = { if (pattern != null) MeterControls(controller, pattern) }
        val grid: @Composable (Modifier) -> Unit = { m -> Box(m.fillMaxWidth()) {
            if (track.samples.isEmpty()) {
                Text(
                    "No samples yet. Select a clip on the timeline and press Sample.",
                    style = DimStyle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                StepGrid(controller, trackId, track.samples, pattern)
            }
        } }

        // Landscape is short: the controls move to a column beside the grid
        // so the rows keep their height.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxHeight < SHORT_HEIGHT) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(340.dp).fillMaxSize().background(Colors.panel)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        strip()
                        meter()
                    }
                    Box(Modifier.width(1.dp).fillMaxSize().background(Colors.line))
                    grid(Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    strip()
                    meter()
                    grid(Modifier.weight(1f))
                }
            }
        }

        if (pattern != null) {
            Row(
                Modifier.fillMaxWidth().background(Colors.panel).padding(Dimens.gap),
                horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
            ) {
                AppButton(
                    "Place at playhead",
                    onClick = { controller.placePattern(trackId, pattern.id) },
                    modifier = Modifier.weight(1f),
                    accent = Colors.amber,
                )
            }
        }
    }
}

@Composable
private fun PatternStrip(
    patterns: List<Pattern>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    onNew: () -> Unit,
    onCopy: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Colors.panel)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Dimens.gap, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (p in patterns) {
            Toggle(p.name, p.id == selected, Colors.amber, onClick = { onSelect(p.id) })
        }
        AppButton("+ New", onClick = onNew, accent = Colors.green)
        if (onCopy != null) AppButton("Copy", onClick = onCopy, accent = Colors.dim)
    }
}

/** Time signature, grouping, length and grid resolution of one pattern. */
@Composable
private fun MeterControls(controller: SongController, p: Pattern) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Colors.panel)
            .padding(horizontal = Dimens.gap)
            .padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stepper(
                value = "${p.beats}/${p.unit}",
                onDown = { controller.setPatternMeter(p.id, p.beats - 1, p.unit) },
                onUp = { controller.setPatternMeter(p.id, p.beats + 1, p.unit) },
            )
            HSpace(Dimens.gap)
            Segmented(
                listOf("4", "8", "16"),
                selected = listOf(4, 8, 16).indexOf(p.unit),
                onSelect = { controller.setPatternMeter(p.id, p.beats, listOf(4, 8, 16)[it]) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stepper(
                value = if (p.bars == 1) "1 bar" else "${p.bars} bars",
                onDown = { controller.editPattern(p.id) { it.copy(bars = (it.bars - 1).coerceAtLeast(1)) } },
                onUp = { controller.editPattern(p.id) { it.copy(bars = (it.bars + 1).coerceAtMost(16)) } },
            )
            HSpace(Dimens.gap)
            // Steps per beat: straight 8ths, triplets, 16ths.
            val steps = listOf(1, 2, 3, 4)
            Segmented(
                listOf("1", "2", "3", "4"),
                selected = steps.indexOf(p.stepsPerBeat),
                onSelect = { i -> controller.editPattern(p.id) { it.copy(stepsPerBeat = steps[i]) } },
                modifier = Modifier.weight(1f),
            )
        }
        val groupings = groupingsFor(p.beats)
        if (groupings.size > 1) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (g in groupings) {
                    Toggle(
                        g.joinToString("+"), g == p.grouping, Colors.green,
                        onClick = { controller.editPattern(p.id) { it.copy(grouping = g) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun StepGrid(
    controller: SongController,
    trackId: Int,
    samples: List<Sample>,
    pattern: Pattern?,
) {
    val hScroll = rememberScrollState()
    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.width(TileW + Dimens.gap).padding(start = Dimens.gap)) {
            if (pattern != null) Box(Modifier.height(20.dp))
            for (s in samples) {
                SampleTile(s.name, onHit = { controller.triggerSample(trackId, s.id) })
            }
        }
        if (pattern != null) {
            Column(Modifier.weight(1f).horizontalScroll(hScroll).padding(end = Dimens.gap)) {
                BeatNumbers(pattern)
                StepCells(controller, samples, pattern)
            }
        }
    }
}

@Composable
private fun SampleTile(name: String, onHit: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(vertical = 3.dp)
            .width(TileW)
            .height(RowH - 6.dp)
            .clip(TileShape)
            .background(if (down) Colors.amber else Colors.raised)
            .border(1.dp, if (down) Colors.amber else Colors.line, TileShape)
            .pointerInput(onHit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onHit()
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
            style = NameStyle.copy(color = if (down) Colors.bg else Colors.text, fontSize = 13.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Group starts within each bar: 0, 2, 4 for 2+2+3. */
private fun groupStarts(p: Pattern): Set<Int> {
    val out = mutableSetOf<Int>()
    var b = 0
    for (g in p.grouping) {
        out += b
        b += g
    }
    return out
}

@Composable
private fun BeatNumbers(p: Pattern) {
    Row(Modifier.height(20.dp)) {
        val groups = groupStarts(p)
        for (bar in 0 until p.bars) for (beat in 0 until p.beats) {
            Text(
                if (beat == 0) "${bar + 1}" else "${beat + 1}",
                style = MonoDimStyle.copy(
                    fontSize = 10.sp,
                    color = if (beat == 0) Colors.text else if (beat in groups) Colors.dim else Colors.line,
                ),
                modifier = Modifier.width(CellW * p.stepsPerBeat).padding(start = 3.dp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StepCells(controller: SongController, samples: List<Sample>, p: Pattern) {
    val steps = p.steps
    val playing = controller.previewing == p.id
    val previewFrame = if (playing) controller.previewFrame else -1L
    val song = controller.song
    val playStep = if (previewFrame >= 0) {
        val tick = (previewFrame / song.framesPerTick).toInt()
        tick / p.ticksPerStep
    } else -1
    val on = remember(p.hits, samples) {
        samples.map { s -> p.hits.filter { it.sample == s.id }.map { it.tick / p.ticksPerStep }.toSet() }
    }
    val groups = groupStarts(p)

    Canvas(
        Modifier
            .width(CellW * steps)
            .height(RowH * samples.size)
            .pointerInput(p.id, samples, p.hits, p.ticksPerStep) {
                detectTapGestures { pos ->
                    val col = (pos.x / CellW.toPx()).toInt()
                    val row = (pos.y / RowH.toPx()).toInt()
                    val s = samples.getOrNull(row) ?: return@detectTapGestures
                    if (col !in 0 until steps) return@detectTapGestures
                    controller.setStep(p.id, s.id, col, col !in on[row])
                }
            },
    ) {
        val cw = CellW.toPx()
        val rh = RowH.toPx()
        val inset = 3.dp.toPx()
        for (col in 0 until steps) {
            val beatInBar = (col / p.stepsPerBeat) % p.beats
            val beatStart = col % p.stepsPerBeat == 0
            val groupIdx = p.grouping.runningFold(0) { a, g -> a + g }.indexOfLast { it <= beatInBar }
            // Alternate shading per group, so 2+2+3 reads at a glance.
            if (groupIdx % 2 == 1) {
                drawRect(Colors.panel, Offset(col * cw, 0f), Size(cw, size.height))
            }
            if (col == playStep) {
                drawRect(Colors.text.copy(alpha = 0.10f), Offset(col * cw, 0f), Size(cw, size.height))
            }
            if (beatStart) {
                val barStart = beatInBar == 0
                val color = when {
                    barStart -> Colors.dim
                    beatInBar in groups -> Colors.line
                    else -> Colors.line.copy(alpha = 0.45f)
                }
                drawLine(color, Offset(col * cw, 0f), Offset(col * cw, size.height), strokeWidth = if (barStart) 2f else 1f)
            }
            for (row in samples.indices) {
                val hit = col in on[row]
                drawRoundRect(
                    if (hit) Colors.amber else Colors.raised,
                    topLeft = Offset(col * cw + inset, row * rh + inset),
                    size = Size(cw - 2 * inset, rh - 2 * inset),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
        }
    }
}
