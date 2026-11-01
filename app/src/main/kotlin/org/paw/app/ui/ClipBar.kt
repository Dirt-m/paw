package org.paw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.paw.app.SongController
import org.paw.app.model.Clip
import org.paw.app.model.MIN_CLIP_FRAMES
import org.paw.app.model.Track
import org.paw.app.model.barBeatAt
import org.paw.app.model.snapFrame

// Actions for the selected clip. The bar takes the mode bar's place while a
// clip is selected (the top bar's middle in landscape), so it is one row the
// width of the screen; More, the pattern list and the takes under a clip swap
// into the same place instead of opening anything over the song.

private enum class ClipPage { Main, More, Patterns, Takes }

@Composable
internal fun ClipBar(
    controller: SongController,
    ui: SongUi,
    tl: TimelineState,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val sel = controller.selectedClip ?: return
    val (trackId, clipId) = sel
    val song = controller.song
    val track = song.track(trackId) ?: return
    val clip = track.clips.find { it.id == clipId } ?: return
    var page by remember(sel) { mutableStateOf(ClipPage.Main) }
    val rowH = if (compact) 48.dp else 44.dp

    Column(
        modifier
            .background(Colors.panel)
            .background(Colors.accent.copy(alpha = 0.07f))
            .drawBehind { drawLine(Colors.accent.copy(alpha = 0.6f), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
    ) {
        when (page) {
            ClipPage.Main -> BarRow(rowH) {
                IconButton(CloseGlyph, onClick = { controller.selectedClip = null }, tint = Colors.dim)
                if (clip.isPattern) {
                    PatternMain(controller, ui, track, clip) { page = it }
                } else {
                    SplitButton(controller, ui, track.id, clip, Modifier.weight(1f))
                    AppButton("Duplicate", onClick = { controller.duplicateClip(trackId, clipId) },
                        modifier = Modifier.weight(1.4f), fontSize = 12.sp, padding = 6.dp)
                    AppButton("Delete", accent = Colors.red, onClick = { controller.deleteClip(trackId, clipId) },
                        modifier = Modifier.weight(1.1f), fontSize = 12.sp, padding = 6.dp)
                }
                MoreButton { page = ClipPage.More }
            }
            ClipPage.More -> MorePage(controller, ui, track, clip, rowH) { page = it }
            ClipPage.Patterns -> PatternsPage(controller, track, clip, rowH) { page = it }
            ClipPage.Takes -> TakesPage(controller, ui, track, clip, compact, rowH) { page = it }
        }
    }
}

@Composable
private fun BarRow(height: Dp, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(height).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun MoreButton(onClick: () -> Unit) {
    IconButton(MoreGlyph, onClick = onClick, modifier = Modifier.semantics { contentDescription = "More" })
}

@Composable
private fun PageBack(onClick: () -> Unit) {
    IconButton(ChevronLeftGlyph, onClick = onClick, tint = Colors.dim, modifier = Modifier.semantics { contentDescription = "Back" })
}

// Splits at the playhead, on the snap grid. The one control that depends on
// where the playhead is; reading it here keeps the rest of the bar off the
// transport's clock.
@Composable
private fun SplitButton(controller: SongController, ui: SongUi, trackId: Int, clip: Clip, modifier: Modifier) {
    val at = controller.song.snapFrame(controller.playheadFrame, ui.snap)
    AppButton(
        "Split",
        enabled = at > clip.start + MIN_CLIP_FRAMES && at < clip.end - MIN_CLIP_FRAMES,
        onClick = { controller.splitClip(trackId, clip.id, at) },
        modifier = modifier,
        fontSize = 12.sp,
        padding = 6.dp,
    )
}

/** Pattern ▾, its repeats with − and +, and Edit. */
@Composable
private fun RowScope.PatternMain(
    controller: SongController,
    ui: SongUi,
    track: Track,
    clip: Clip,
    go: (ClipPage) -> Unit,
) {
    val pattern = controller.song.pattern(clip.pattern)
    AppButton(
        "${pattern?.name ?: "Pattern"} ▾",
        onClick = { go(ClipPage.Patterns) },
        accent = Colors.accent,
        modifier = Modifier.weight(1f),
        fontSize = 12.sp,
        padding = 6.dp,
    )
    val n = controller.patternRepeats(clip)
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppButton("−", enabled = n > 1, onClick = { controller.setPatternRepeats(track.id, clip.id, n - 1) },
            modifier = Modifier.width(Dimens.touch), fontSize = 16.sp, padding = 0.dp)
        Text(
            "×$n",
            style = MonoStyle.copy(fontSize = 13.sp),
            maxLines = 1,
            modifier = Modifier.widthIn(min = 30.dp).padding(horizontal = 3.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        AppButton("+", onClick = { controller.setPatternRepeats(track.id, clip.id, n + 1) },
            modifier = Modifier.width(Dimens.touch), fontSize = 16.sp, padding = 0.dp)
    }
    AppButton("Edit", onClick = { ui.openBeats(track.id, clip.pattern) }, fontSize = 12.sp, padding = 10.dp)
}

/**
 * The rarer actions, in the same one-row slot as the main page: the row
 * scrolls sideways rather than growing, so the lanes above never move.
 */
@Composable
private fun MorePage(
    controller: SongController,
    ui: SongUi,
    track: Track,
    clip: Clip,
    rowH: Dp,
    go: (ClipPage) -> Unit,
) {
    val song = controller.song
    val idx = song.tracks.indexOfFirst { it.id == track.id }
    // A clip only moves to a neighbour that holds its kind.
    fun canMoveTo(i: Int) = song.tracks.getOrNull(i)?.kind == track.kind
    val under = controller.clipsUnder(track.id, clip.id)
    Row(
        Modifier.fillMaxWidth().height(rowH).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageBack { go(ClipPage.Main) }
        if (clip.isPattern) {
            SplitButton(controller, ui, track.id, clip, Modifier)
            AppButton("Duplicate", onClick = { controller.duplicateClip(track.id, clip.id) }, fontSize = 12.sp, padding = 10.dp)
            AppButton("Delete", accent = Colors.red, onClick = { controller.deleteClip(track.id, clip.id) },
                fontSize = 12.sp, padding = 10.dp)
        } else {
            AppButton("Sample to kit", accent = Colors.accent, fontSize = 12.sp, padding = 10.dp,
                onClick = { ui.open(SongPanel.SampleToKit(track.id, clip.id)) })
        }
        AppButton("Loop clip", fontSize = 12.sp, padding = 10.dp, onClick = { controller.setLoopRegion(clip.start, clip.end) })
        AppButton("Move ▲", enabled = canMoveTo(idx - 1), fontSize = 12.sp, padding = 10.dp,
            onClick = { controller.moveClipToTrack(track.id, clip.id, -1) })
        AppButton("Move ▼", enabled = canMoveTo(idx + 1), fontSize = 12.sp, padding = 10.dp,
            onClick = { controller.moveClipToTrack(track.id, clip.id, 1) })
        if (under.isNotEmpty()) {
            AppButton("Takes ${under.size}", fontSize = 12.sp, padding = 10.dp, onClick = { go(ClipPage.Takes) })
        }
    }
}

/** The track's patterns to swap in, and a fresh copy for this clip alone. */
@Composable
private fun PatternsPage(controller: SongController, track: Track, clip: Clip, rowH: Dp, go: (ClipPage) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(rowH).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageBack { go(ClipPage.Main) }
        for (p in controller.patternsFor(track.id)) {
            val current = p.id == clip.pattern
            AppButton(
                p.name,
                filled = current,
                accent = if (current) Colors.accent else Colors.text,
                fontSize = 12.sp,
                padding = 12.dp,
                onClick = {
                    if (!current) controller.setClipPattern(track.id, clip.id, p.id)
                    go(ClipPage.Main)
                },
            )
        }
        AppButton("+ New variation", accent = Colors.accent, fontSize = 12.sp, padding = 12.dp, onClick = {
            controller.makeVariation(track.id, clip.id)
            go(ClipPage.Main)
        })
    }
}

/**
 * The older takes this clip covers, newest first. Bringing one to the top
 * selects it, so the clip it replaced is in its own list to bring back.
 */
@Composable
private fun TakesPage(
    controller: SongController,
    ui: SongUi,
    track: Track,
    clip: Clip,
    compact: Boolean,
    rowH: Dp,
    go: (ClipPage) -> Unit,
) {
    val under = controller.clipsUnder(track.id, clip.id)
    val song = controller.song
    // Where each take sits, in whatever the clock reads.
    fun at(frame: Long): String =
        if (ui.clockAsTime) formatShortTime(frame, song.sampleRate)
        else song.barBeatAt(frame).let { "${it.bar}.${it.beat}" }
    val bring: (Clip) -> Unit = { c ->
        controller.bringClipToTop(track.id, c.id)
        controller.selectedClip = track.id to c.id
    }
    if (compact) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            BarRow(rowH) {
                PageBack { go(ClipPage.Main) }
                Text("UNDER THIS CLIP", style = LabelStyle, maxLines = 1)
            }
            for (c in under.take(4)) {
                Row(
                    Modifier.fillMaxWidth().height(rowH).padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(takeLabel(c.take), style = NameStyle.copy(fontSize = 13.sp), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(
                            "${at(c.start)} to ${at(c.end)}",
                            style = MonoDimStyle.copy(fontSize = 11.sp),
                            maxLines = 1,
                        )
                    }
                    AppButton("Bring to top", accent = Colors.accent, fontSize = 12.sp, padding = 12.dp, onClick = { bring(c) })
                }
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().height(rowH).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PageBack { go(ClipPage.Main) }
            for (c in under) {
                Text(takeLabel(c.take), style = NameStyle.copy(fontSize = 12.sp), maxLines = 1)
                Text(at(c.start), style = MonoDimStyle.copy(fontSize = 11.sp), maxLines = 1)
                AppButton("Bring to top", accent = Colors.accent, fontSize = 12.sp, padding = 10.dp, onClick = { bring(c) })
            }
        }
    }
}

private val TakeName = Regex("""take_\d{8}-(\d{2})(\d{2})(\d{2})_t(\d+)""")

/** "audio/take_20260805-141210_t1.wav" reads as "Take 14:12:10 · 1". */
internal fun takeLabel(take: String): String {
    val file = take.substringAfterLast('/').substringBeforeLast('.')
    val m = TakeName.matchEntire(file) ?: return file
    val (h, min, s, t) = m.destructured
    return "Take $h:$min:$s · $t"
}
