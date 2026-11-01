package org.paw.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.paw.app.SongController
import org.paw.app.model.MIN_CLIP_FRAMES
import org.paw.app.model.Pattern
import org.paw.app.model.TRACK_BEAT
import org.paw.app.model.barStartFrame
import org.paw.app.model.framesPerBar

// Beats mode: one beat track's patterns, its step grid, its kit and its pads,
// with the song's transport still underneath. Portrait has room for either
// the grid or the pads at a size a finger can use, so it switches between
// them; landscape is wide enough to put them side by side. Pads and grid
// rows share one order, the kit's.

private val BeatsShape = RoundedCornerShape(Dimens.corner)
private val ROW_H = 52.dp

// Portrait step rows grow to fill the height up to STEP_ROW_MAX. With four
// sounds or fewer, pads at least PADS_MIN tall sit under the grid while the
// rows can stay STEP_ROW_MIN_WITH_PADS or taller.
private val STEP_ROW_MAX = 112.dp
private val STEP_ROW_MIN_WITH_PADS = 56.dp
private val PADS_MIN = 160.dp

@Composable
internal fun BeatsMode(controller: SongController, ui: SongUi, compact: Boolean, short: Boolean) {
    val song = controller.song
    val beatTracks = song.tracks.filter { it.kind == TRACK_BEAT }
    // A preview is this mode's sound; leaving the mode ends it.
    DisposableEffect(Unit) { onDispose { controller.stopPreview() } }

    if (beatTracks.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Colors.bg), contentAlignment = Alignment.Center) {
            AppButton(
                "+ Beat track",
                onClick = { ui.beatsTrack = controller.addBeatTrack(); ui.beatsPattern = null },
                accent = Colors.accent,
                filled = true,
                height = Dimens.touchBig,
                fontSize = 15.sp,
                padding = 28.dp,
            )
        }
        return
    }

    val track = beatTracks.firstOrNull { it.id == ui.beatsTrack }
        ?: beatTracks.first { it.id == controller.firstBeatTrack() }
    val patterns = controller.patternsFor(track.id)
    val pattern = patterns.firstOrNull { it.id == ui.beatsPattern } ?: patterns.firstOrNull()
    val samples = track.samples

    var pads by rememberSaveable { mutableStateOf(false) }
    var kit by rememberSaveable { mutableStateOf(false) }
    var listOpen by rememberSaveable { mutableStateOf(false) }
    // With no pattern left (the last one deleted or undone) there is no list.
    LaunchedEffect(pattern == null) { if (pattern == null) listOpen = false }

    val selectTrack: (Int) -> Unit = { id ->
        if (id != track.id) {
            controller.stopPreview()
            ui.beatsTrack = id
            ui.beatsPattern = null
            listOpen = false
        }
    }
    val addTrack = {
        controller.stopPreview()
        ui.beatsTrack = controller.addBeatTrack()
        ui.beatsPattern = null
        listOpen = false
    }
    // Picking a pattern while one previews keeps the music going on the new one.
    val selectPattern: (Int?) -> Unit = { id ->
        ui.beatsPattern = id
        if (controller.previewing != null) {
            if (id != null) controller.startPreview(track.id, id) else controller.stopPreview()
        }
    }

    val trackChips: @Composable (Modifier) -> Unit = { m ->
        TrackChips(beatTracks.map { it.id to it.name }, track.id, selectTrack, addTrack, m)
    }
    val patternRow: @Composable () -> Unit = {
        PatternControls(controller, ui, track.id, pattern, listOpen, onToggleList = { listOpen = !listOpen }, selectPattern)
    }
    val patternList: @Composable () -> Unit = {
        if (listOpen && pattern != null) {
            BeatsPatternList(
                controller, track.id, patterns, pattern,
                onSelect = { selectPattern(it); listOpen = false },
                maxHeight = if (short) 168.dp else 288.dp,
            )
        }
    }
    val emptyKit: @Composable (Modifier) -> Unit = { m ->
        Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("No sounds in ${track.name}", style = NameStyle.copy(fontSize = 17.sp), textAlign = TextAlign.Center)
            VSpace(8.dp)
            Text(
                "Select a clip on the timeline, then More → Sample to kit",
                style = DimStyle,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 340.dp),
            )
        }
    }
    val newPatternFromStep: (Int, Int) -> Unit = { sampleId, step ->
        // The empty grid stands for the pattern a first tap creates.
        val id = controller.newPattern(track.id)
        controller.setStep(id, sampleId, step, true, undoable = false)
        ui.beatsPattern = id
    }

    Column(Modifier.fillMaxSize().background(Colors.bg)) {
        if (short) {
            // Landscape: one row of everything, then pads beside the grid.
            Row(
                Modifier.fillMaxWidth().height(ROW_H).background(Colors.panel)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.gap),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trackChips(Modifier.widthIn(max = 240.dp))
                if (samples.isNotEmpty()) {
                    RowDivider()
                    patternRow()
                    RowDivider()
                    Toggle("Kit", kit, Colors.accent, onClick = { kit = !kit }, modifier = Modifier.width(56.dp))
                    if (pattern != null) PlaceButton(controller, track.id, pattern)
                }
            }
            Divider()
            patternList()
            if (samples.isEmpty()) {
                emptyKit(Modifier.weight(1f).fillMaxWidth())
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val padsW = (maxWidth * 0.3f).coerceIn(176.dp, 260.dp)
                    Row(Modifier.fillMaxSize()) {
                        BeatsPads(
                            controller, track.id, samples, pattern, columns = 2,
                            modifier = Modifier.width(padsW).fillMaxHeight(),
                        )
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Colors.line))
                        BeatsSteps(
                            controller, track.id, samples, pattern, kit,
                            rowMin = 40.dp, rowMax = 56.dp,
                            onFirstStep = newPatternFromStep,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
            return@Column
        }

        // Portrait.
        Row(
            Modifier.fillMaxWidth().height(ROW_H).background(Colors.panel).padding(horizontal = Dimens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            trackChips(Modifier.weight(1f))
            if (samples.isNotEmpty()) {
                HSpace(Dimens.gap)
                Segmented(
                    listOf("Steps", "Pads"),
                    selected = if (pads) 1 else 0,
                    onSelect = { pads = it == 1 },
                    modifier = Modifier.width(140.dp),
                )
            }
        }
        if (samples.isEmpty()) {
            Divider()
            emptyKit(Modifier.weight(1f).fillMaxWidth().padding(24.dp))
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().height(ROW_H).background(Colors.panel).padding(horizontal = Dimens.gap),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { patternRow() }
        Divider()
        patternList()
        if (pads) {
            BeatsPads(
                controller, track.id, samples, pattern,
                columns = if (samples.size >= 7) 3 else 2,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // A small kit leaves room under its rows. Pads go there, so
                // that space plays instead of sitting empty; the rows give
                // up height to them down to a size a finger still hits.
                val n = samples.size
                val rowH = ((maxHeight - 1.dp - PADS_MIN - BeatsHeadH - Dimens.gap) / n).coerceAtMost(STEP_ROW_MAX)
                val padsBelow = n <= 4 && rowH >= STEP_ROW_MIN_WITH_PADS
                Column(Modifier.fillMaxSize()) {
                    BeatsSteps(
                        controller, track.id, samples, pattern, kit,
                        rowMin = 48.dp, rowMax = STEP_ROW_MAX,
                        onFirstStep = newPatternFromStep,
                        modifier = if (padsBelow) Modifier.fillMaxWidth().height(BeatsHeadH + Dimens.gap + rowH * n)
                        else Modifier.weight(1f).fillMaxWidth(),
                    )
                    if (padsBelow) {
                        Divider()
                        BeatsPads(
                            controller, track.id, samples, pattern, columns = n,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            }
        }
        Divider()
        Row(
            Modifier.fillMaxWidth().background(Colors.panel).padding(Dimens.gap),
            horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!pads) Toggle("Kit", kit, Colors.accent, onClick = { kit = !kit }, modifier = Modifier.width(64.dp))
            if (pattern != null) PlaceButton(controller, track.id, pattern, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))

@Composable
private fun RowDivider() = Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(28.dp).background(Colors.line))

/** The song's beat tracks, one chip each, and the way to add another. */
@Composable
private fun TrackChips(
    tracks: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((id, name) in tracks) {
            Toggle(name, id == selected, Colors.accent, onClick = { onSelect(id) })
        }
        AppButton("+ Beat track", onClick = onAdd, accent = Colors.dim, padding = 12.dp)
    }
}

/** Pattern picker, meter chip and the pattern's own play button. */
@Composable
private fun PatternControls(
    controller: SongController,
    ui: SongUi,
    trackId: Int,
    pattern: Pattern?,
    listOpen: Boolean,
    onToggleList: () -> Unit,
    onSelect: (Int?) -> Unit,
) {
    if (pattern == null) {
        AppButton(
            "+ Pattern",
            onClick = { onSelect(controller.newPattern(trackId)) },
            accent = Colors.accent,
        )
        return
    }
    BeatsChip(
        pattern.name, open = listOpen, onClick = onToggleList, maxText = 104.dp,
    )
    BeatsChip(
        meterSummary(pattern), open = false, mono = true,
        onClick = { ui.open(SongPanel.Meter(pattern.id)) },
    )
    val playing = controller.previewing == pattern.id
    PreviewButton(playing) {
        if (playing) controller.stopPreview() else controller.startPreview(trackId, pattern.id)
    }
}

/** "7/8 · 2+2+3 · 1 bar": the shape of a pattern in one line. */
internal fun meterSummary(p: Pattern): String = buildString {
    append("${p.beats}/${p.unit}")
    if (p.grouping.size > 1) append(" · ").append(p.grouping.joinToString("+"))
    append(" · ").append(if (p.bars == 1) "1 bar" else "${p.bars} bars")
}

/** A chip that opens something: a list below it, or a panel. */
@Composable
private fun BeatsChip(
    label: String,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    maxText: Dp = Dp.Unspecified,
) {
    Row(
        modifier
            .heightIn(min = Dimens.touch)
            .clip(BeatsShape)
            .background(if (open) Colors.accent.copy(alpha = 0.18f) else Colors.raised)
            .border(1.dp, if (open) Colors.accent else Colors.line, BeatsShape)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = if (mono) MonoStyle.copy(fontSize = 13.sp) else NameStyle.copy(fontSize = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = maxText),
        )
        HSpace(6.dp)
        Text(
            if (open) "▴" else "▾",
            style = BodyStyle.copy(color = if (open) Colors.accent else Colors.dim, fontSize = 12.sp),
        )
    }
}

/** Plays the pattern alone, looped; green, so it never reads as the song's Play. */
@Composable
private fun PreviewButton(playing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 48.dp, height = Dimens.touch)
            .clip(BeatsShape)
            .background(if (playing) Colors.green.copy(alpha = 0.24f) else Color.Transparent)
            .border(1.dp, Colors.green, BeatsShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) { (if (playing) StopGlyph else PlayGlyph)(Colors.green) }
    }
}

/** Puts one clip of the pattern on the track at the first free bar from
 *  the playhead on. Its own composable: the label follows the playhead. */
@Composable
private fun PlaceButton(controller: SongController, trackId: Int, pattern: Pattern, modifier: Modifier = Modifier) {
    val bar = controller.freeBarFor(trackId, pattern)
    AppButton(
        "Place at bar ${bar + 1}",
        onClick = {
            val before = controller.song.track(trackId)?.clips?.size ?: 0
            controller.placePatternAt(trackId, pattern.id, bar)
            if ((controller.song.track(trackId)?.clips?.size ?: 0) > before) {
                controller.message = "${pattern.name} placed at bar ${bar + 1}"
            }
        },
        modifier = modifier,
        accent = Colors.accent,
        filled = true,
    )
}

/**
 * The 0-based bar at or after the playhead where one clip of [pattern] fits
 * on the track without overlapping a clip already there. Placing twice in a
 * row then puts the second clip after the first instead of on top of it,
 * where every hit would play twice.
 */
private fun SongController.freeBarFor(trackId: Int, pattern: Pattern): Int {
    val s = song
    val clips = s.track(trackId)?.clips.orEmpty()
    val length = s.framesOf(pattern.lengthTicks).coerceAtLeast(MIN_CLIP_FRAMES)
    var bar = nextBarIndex()
    while (true) {
        val start = s.barStartFrame(bar)
        val blocking = clips.filter { it.start < start + length && it.end > start }
        if (blocking.isEmpty()) return bar
        // On to the first bar that starts at or after the last overlap ends.
        val end = blocking.maxOf { it.end }
        var next = Math.floor(end / s.framesPerBar).toInt()
        if (s.barStartFrame(next) < end) next++
        bar = maxOf(next, bar + 1)
    }
}

internal val BeatsLabelW: Dp = 64.dp
