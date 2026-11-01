package org.paw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlin.math.log10
import kotlinx.coroutines.launch
import org.paw.app.SongController
import org.paw.app.model.MASTER_TRACK_ID
import org.paw.app.model.Track

// MIX mode: a channel strip per track, master pinned on the right. Every
// strip, master included, gets the same width, computed so a whole number of
// strips fills the row exactly; the strips scroll and snap a strip at a time.
// Tall windows put a row of track chips on top to jump along a long song and
// to add tracks. Short ones (phone landscape) spend all the height on the
// fader: mute and solo sit beside it, and adding lives at the end of the row.

private val GAP = 6.dp
private val STRIP_PAD = 4.dp
private val STRIP_MIN_TALL = 92.dp
private val STRIP_MIN_SHORT = 112.dp
private val INDEX_ROW_H = 52.dp
private val READOUT_H = 28.dp
private val ControlShape = RoundedCornerShape(Dimens.corner)

@Composable
fun MixerView(
    controller: SongController,
    ui: SongUi,
    short: Boolean,
) {
    val tracks = controller.song.tracks
    val strips = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val openTrack: (Int) -> Unit = {
        controller.selectedTrack = it
        ui.open(SongPanel.Inspector(it))
    }
    val openFx: (Int) -> Unit = {
        controller.selectedTrack = it
        ui.open(SongPanel.Inspector(it, InspectorTab.Fx))
    }
    // A new track lands at the end of the row; bring it on screen.
    val addAudio = {
        controller.addTrack()
        scope.launch { strips.animateScrollToItem(controller.song.tracks.lastIndex) }
        Unit
    }
    val addBeat = {
        controller.addBeatTrack()
        scope.launch { strips.animateScrollToItem(controller.song.tracks.lastIndex) }
        Unit
    }

    Column(Modifier.fillMaxSize().background(Colors.bg)) {
        if (!short) {
            StripIndex(tracks, strips, controller.selectedTrack, addAudio, addBeat) { i ->
                scope.launch { strips.animateScrollToItem(i) }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // n track strips and the master share the width, with a gap
            // before, between and after them all.
            val minW = if (short) STRIP_MIN_SHORT else STRIP_MIN_TALL
            val slots = ((maxWidth - GAP) / (minW + GAP)).toInt().coerceAtLeast(2)
            val stripW = (maxWidth - GAP * (slots + 1)) / slots
            Row(Modifier.fillMaxSize()) {
                LazyRow(
                    state = strips,
                    flingBehavior = rememberSnapFlingBehavior(strips),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(start = GAP, top = GAP, bottom = GAP, end = GAP),
                    horizontalArrangement = Arrangement.spacedBy(GAP),
                ) {
                    itemsIndexed(tracks, key = { _, t -> t.id }) { _, track ->
                        ChannelStrip(
                            track, controller, short, stripW,
                            selected = controller.selectedTrack == track.id,
                            onOpen = { openTrack(track.id) },
                            onFx = { openFx(track.id) },
                        )
                    }
                    item(key = "add") { AddStrip(stripW, addAudio, addBeat) }
                }
                Box(Modifier.padding(top = GAP, bottom = GAP, end = GAP)) {
                    MasterStrip(
                        controller, short, stripW,
                        selected = controller.selectedTrack == MASTER_TRACK_ID,
                        onOpen = { openTrack(MASTER_TRACK_ID) },
                        onFx = { openFx(MASTER_TRACK_ID) },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------- strip index --

/**
 * Every track as a chip; the ones whose strips are on screen are outlined.
 * A tap scrolls the strips there. The add chips close the row.
 */
@Composable
private fun StripIndex(
    tracks: List<Track>,
    strips: LazyListState,
    selected: Int?,
    onAddAudio: () -> Unit,
    onAddBeat: () -> Unit,
    onJump: (Int) -> Unit,
) {
    val chips = rememberLazyListState()
    // A strip counts as shown when most of it is inside the row.
    val shown by remember(strips) {
        derivedStateOf {
            val info = strips.layoutInfo
            info.visibleItemsInfo
                .filter { it.key is Int }
                .filter {
                    val left = maxOf(it.offset, info.viewportStartOffset)
                    val right = minOf(it.offset + it.size, info.viewportEndOffset)
                    right - left > it.size / 2
                }
                .map { it.index }
                .toSet()
        }
    }
    // Keep the outlined chips in view while the strips scroll.
    val first = shown.minOrNull()
    LaunchedEffect(first) {
        if (first == null) return@LaunchedEffect
        val seen = chips.layoutInfo.visibleItemsInfo
        val firstSeen = seen.firstOrNull()?.index ?: 0
        val lastSeen = seen.lastOrNull()?.index ?: 0
        val lastShown = shown.maxOrNull() ?: first
        if (first < firstSeen || lastShown >= lastSeen) chips.animateScrollToItem(first)
    }
    LazyRow(
        state = chips,
        modifier = Modifier.fillMaxWidth().height(INDEX_ROW_H).background(Colors.panel),
        contentPadding = PaddingValues(horizontal = GAP),
        horizontalArrangement = Arrangement.spacedBy(GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, track ->
            IndexChip(
                track.name,
                outlined = i in shown,
                selected = track.id == selected,
                onClick = { onJump(i) },
            )
        }
        item(key = "add-audio") { AddChip("+ Track", onAddAudio) }
        item(key = "add-beat") { AddChip("+ Beat", onAddBeat) }
    }
}

@Composable
private fun IndexChip(name: String, outlined: Boolean, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(Dimens.touch)
            .widthIn(min = 48.dp, max = 108.dp)
            .clip(ControlShape)
            .background(if (outlined) Colors.raised else Colors.bg)
            .border(1.dp, if (outlined) Colors.accent.copy(alpha = 0.7f) else Colors.line, ControlShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            style = ButtonStyle.copy(
                color = if (selected) Colors.accent else if (outlined) Colors.text else Colors.dim,
                fontSize = 13.sp,
                letterSpacing = 0.2.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Plain outline, so it does not read as one of the strips on screen. */
@Composable
private fun AddChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(Dimens.touch)
            .clip(ControlShape)
            .border(1.dp, Colors.line, ControlShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ButtonStyle.copy(color = Colors.accent), maxLines = 1, softWrap = false)
    }
}

// ------------------------------------------------------------------ strips --

@Composable
private fun StripFrame(
    width: Dp,
    selected: Boolean,
    master: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = when {
        selected -> Colors.accent
        master -> Colors.accent.copy(alpha = 0.4f)
        else -> Colors.line
    }
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(Colors.panel)
            .border(if (selected) 2.dp else 1.dp, border)
            .padding(STRIP_PAD),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** The name, in up to [lines] lines: opens the track's inspector. */
@Composable
private fun StripName(
    name: String,
    color: Color,
    lines: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(Dimens.touch)
            .clip(ControlShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            name,
            style = NameStyle.copy(color = color, fontSize = 13.sp, lineHeight = 15.sp),
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Opens the FX tab. Shows how many effects are on the track, or offers one. */
@Composable
private fun FxButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val on = count > 0
    Box(
        modifier
            .height(Dimens.touch)
            .clip(ControlShape)
            .background(if (on) Colors.accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (on) Colors.accent.copy(alpha = 0.7f) else Colors.line, ControlShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (on) "FX $count" else "+ FX",
            style = LabelStyle.copy(color = if (on) Colors.accent else Colors.dim, fontSize = 12.sp, letterSpacing = 1.sp),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Fader and meter side by side; the drag reports its live gain to [onLive]. */
@Composable
private fun Fader(
    controller: SongController,
    trackId: Int,
    gain: Float,
    onLive: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GainSlider(controller, trackId, gain, vertical = true, modifier = Modifier.fillMaxHeight(), onLive = onLive)
        LiveMeter(controller, trackId, Modifier.fillMaxHeight(), vertical = true, thickness = 8.dp)
    }
}

private fun dbNumber(gain: Float): String =
    if (gain <= 0f) "-inf" else "%+.1f".format(20f * log10(gain))

/**
 * The fader's value; a double tap puts it back to 0 dB. [stacked] sets the
 * unit under the number for a narrow column.
 */
@Composable
private fun DbReadout(
    controller: SongController,
    trackId: Int,
    gain: Float,
    stacked: Boolean,
    modifier: Modifier = Modifier,
) {
    val reset = { controller.updateTrack(trackId) { it.copy(gain = 1f) } }
    if (stacked) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            ValueReadout(dbNumber(gain), style = MonoStyle.copy(fontSize = 12.sp), onReset = reset)
            Text("dB", style = LabelStyle.copy(fontSize = 10.sp, letterSpacing = 1.sp))
        }
    } else {
        Box(modifier.height(READOUT_H), contentAlignment = Alignment.Center) {
            ValueReadout(formatDb(gain), style = MonoStyle.copy(fontSize = 12.sp), onReset = reset)
        }
    }
}

/** Pan slider with its value beside it; a double tap on either centres it. */
@Composable
private fun PanRow(track: Track, controller: SongController, modifier: Modifier = Modifier) {
    var livePan by remember { mutableStateOf<Float?>(null) }
    Row(modifier.height(Dimens.touch), verticalAlignment = Alignment.CenterVertically) {
        PanSlider(controller, track.id, track.pan, Modifier.weight(1f), onLive = { livePan = it })
        ValueReadout(
            formatPan(livePan ?: track.pan),
            style = MonoDimStyle.copy(fontSize = 11.sp, textAlign = TextAlign.End),
            modifier = Modifier.width(26.dp),
            onReset = { controller.updateTrack(track.id) { it.copy(pan = 0f) } },
        )
    }
}

@Composable
private fun MuteToggle(track: Track, controller: SongController, modifier: Modifier = Modifier) {
    Toggle("M", track.mute, Colors.accent, modifier = modifier, onClick = {
        controller.updateTrack(track.id) { it.copy(mute = !it.mute) }
    })
}

@Composable
private fun SoloToggle(track: Track, controller: SongController, modifier: Modifier = Modifier) {
    Toggle("S", track.solo, Colors.green, modifier = modifier, onClick = {
        controller.updateTrack(track.id) { it.copy(solo = !it.solo) }
    })
}

@Composable
private fun ChannelStrip(
    track: Track,
    controller: SongController,
    short: Boolean,
    width: Dp,
    selected: Boolean,
    onOpen: () -> Unit,
    onFx: () -> Unit,
) {
    var liveGain by remember { mutableStateOf<Float?>(null) }
    val gain = liveGain ?: track.gain
    val nameColor = if (selected) Colors.accent else Colors.text
    StripFrame(width, selected) {
        if (short) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StripName(track.name, nameColor, 2, onOpen, Modifier.weight(1f))
                FxButton(track.effects.size, onFx)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Fader(controller, track.id, track.gain, { liveGain = it }, Modifier.fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MuteToggle(track, controller, Modifier.fillMaxWidth())
                    SoloToggle(track, controller, Modifier.fillMaxWidth())
                    Spacer(Modifier.weight(1f))
                    DbReadout(controller, track.id, gain, stacked = true, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(4.dp))
            PanRow(track, controller, Modifier.fillMaxWidth())
        } else {
            StripName(track.name, nameColor, 2, onOpen, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            FxButton(track.effects.size, onFx, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Fader(controller, track.id, track.gain, { liveGain = it }, Modifier.weight(1f))
            DbReadout(controller, track.id, gain, stacked = false, modifier = Modifier.fillMaxWidth())
            PanRow(track, controller, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MuteToggle(track, controller, Modifier.weight(1f))
                SoloToggle(track, controller, Modifier.weight(1f))
            }
        }
    }
}

/**
 * The master: the same shape as a track strip so the faders line up, with
 * no pan and no solo.
 */
@Composable
private fun MasterStrip(
    controller: SongController,
    short: Boolean,
    width: Dp,
    selected: Boolean,
    onOpen: () -> Unit,
    onFx: () -> Unit,
) {
    val master = controller.song.master
    var liveGain by remember { mutableStateOf<Float?>(null) }
    val gain = liveGain ?: master.gain
    StripFrame(width, selected, master = true) {
        if (short) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StripName("Master", Colors.accent, 1, onOpen, Modifier.weight(1f))
                FxButton(master.effects.size, onFx)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Fader(controller, MASTER_TRACK_ID, master.gain, { liveGain = it }, Modifier.fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    MuteToggle(master, controller, Modifier.fillMaxWidth())
                    Spacer(Modifier.weight(1f))
                    DbReadout(controller, MASTER_TRACK_ID, gain, stacked = true, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(4.dp + Dimens.touch))
        } else {
            StripName("Master", Colors.accent, 2, onOpen, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            FxButton(master.effects.size, onFx, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Fader(controller, MASTER_TRACK_ID, master.gain, { liveGain = it }, Modifier.weight(1f))
            DbReadout(controller, MASTER_TRACK_ID, gain, stacked = false, modifier = Modifier.fillMaxWidth())
            // The track strips' pan row, so the faders end level.
            Spacer(Modifier.height(Dimens.touch + 4.dp))
            MuteToggle(master, controller, Modifier.fillMaxWidth())
        }
    }
}

/** The end of the row: a new audio or beat track. */
@Composable
private fun AddStrip(width: Dp, onAddAudio: () -> Unit, onAddBeat: () -> Unit) {
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .border(1.dp, Colors.line)
            .padding(STRIP_PAD),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppButton("+ Track", accent = Colors.accent, onClick = onAddAudio, padding = 6.dp, modifier = Modifier.fillMaxWidth())
        AppButton("+ Beat", accent = Colors.accent, onClick = onAddBeat, padding = 6.dp, modifier = Modifier.fillMaxWidth())
    }
}
