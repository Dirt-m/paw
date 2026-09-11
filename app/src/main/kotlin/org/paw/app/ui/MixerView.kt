package org.paw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.paw.app.SongController
import org.paw.app.model.MASTER_TRACK_ID
import org.paw.app.model.Track

// MIX view: one channel strip per track, master pinned on the right. Strip
// widths are computed so a whole number of strips fills the space beside the
// master (no half strip peeking out). Tall windows stack a strip's controls
// under the fader; short ones (phone landscape) put them beside it so the fader
// keeps its travel.

private val STRIP_GAP = 6.dp
private val STRIP_MIN_TALL = 92.dp
private val STRIP_MAX_TALL = 128.dp
private val STRIP_MIN_SHORT = 150.dp
private val STRIP_MAX_SHORT = 190.dp
private val MASTER_W_TALL = 88.dp
private val MASTER_W_SHORT = 132.dp

@Composable
fun MixerView(
    controller: SongController,
    onOpenTrack: (Int) -> Unit,
    short: Boolean,
) {
    val song = controller.song
    val scroll = rememberScrollState()
    val masterW = if (short) MASTER_W_SHORT else MASTER_W_TALL
    BoxWithConstraints(Modifier.fillMaxSize().background(Colors.bg)) {
        // Fit as many strips as the minimum width allows, then widen them to
        // fill the row exactly: leading pad, the gaps between, and a trailing
        // gap so the next strip starts precisely at the master's edge.
        val avail = maxWidth - masterW - STRIP_GAP
        val minW = if (short) STRIP_MIN_SHORT else STRIP_MIN_TALL
        val maxW = if (short) STRIP_MAX_SHORT else STRIP_MAX_TALL
        val n = ((avail + STRIP_GAP) / (minW + STRIP_GAP)).toInt().coerceAtLeast(1)
        val stripW = ((avail - STRIP_GAP * n) / n).coerceIn(minW, maxW)

        Row(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(scroll)
                    .padding(start = STRIP_GAP, top = STRIP_GAP, bottom = STRIP_GAP),
                horizontalArrangement = Arrangement.spacedBy(STRIP_GAP),
            ) {
                song.tracks.forEach { track ->
                    key(track.id) { ChannelStrip(track, controller, onOpenTrack, short, stripW) }
                }
                Box(
                    Modifier
                        .width(stripW)
                        .fillMaxHeight()
                        .border(1.dp, Colors.line)
                        .clickable { controller.addTrack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "+\nAdd\ntrack",
                        style = ButtonStyle.copy(color = Colors.amber, lineHeight = 20.sp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            MasterStrip(controller, onOpenTrack, short, masterW)
        }
    }
}

@Composable
private fun StripName(name: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.touch)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = NameStyle.copy(color = color, fontSize = 13.sp),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("›", style = TitleStyle.copy(color = Colors.dim, fontSize = 18.sp))
    }
}

/** Fader beside meter, dB under them; the part every strip shares. */
@Composable
private fun FaderBlock(
    controller: SongController,
    trackId: Int,
    gain: Float,
    modifier: Modifier = Modifier,
) {
    var liveGain by remember { mutableStateOf<Float?>(null) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GainSlider(
                controller, trackId, gain,
                vertical = true,
                modifier = Modifier.fillMaxHeight(),
                onLive = { liveGain = it },
            )
            LiveMeter(controller, trackId, Modifier.fillMaxHeight(), vertical = true, thickness = 10.dp)
        }
        Text(
            formatDb(liveGain ?: gain),
            style = MonoStyle.copy(fontSize = 11.sp),
            modifier = Modifier.padding(top = 2.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun ArmMuteSolo(track: Track, controller: SongController, stacked: Boolean) {
    val arm: @Composable (Modifier) -> Unit = { m ->
        Toggle("●", track.armed, Colors.red, fontSize = 14.sp, size = 36.dp, modifier = m, onClick = {
            controller.updateTrack(track.id) { it.copy(armed = !it.armed) }
        })
    }
    val mute: @Composable (Modifier) -> Unit = { m ->
        Toggle("M", track.mute, Colors.amber, size = 36.dp, modifier = m, onClick = {
            controller.updateTrack(track.id) { it.copy(mute = !it.mute) }
        })
    }
    val solo: @Composable (Modifier) -> Unit = { m ->
        Toggle("S", track.solo, Colors.green, size = 36.dp, modifier = m, onClick = {
            controller.updateTrack(track.id) { it.copy(solo = !it.solo) }
        })
    }
    if (stacked) {
        arm(Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            mute(Modifier.weight(1f))
            solo(Modifier.weight(1f))
        }
    } else {
        arm(Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            mute(Modifier.weight(1f))
            solo(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PanRow(track: Track, controller: SongController) {
    var livePan by remember { mutableStateOf<Float?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PanSlider(
            controller, track.id, track.pan,
            modifier = Modifier.weight(1f),
            onLive = { livePan = it },
        )
        Text(
            formatPan(livePan ?: track.pan),
            style = MonoDimStyle.copy(fontSize = 10.sp),
            modifier = Modifier.width(26.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun StripFrame(
    width: Dp,
    accent: androidx.compose.ui.graphics.Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(Colors.panel)
            .border(1.dp, accent)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun ChannelStrip(
    track: Track,
    controller: SongController,
    onOpenTrack: (Int) -> Unit,
    short: Boolean,
    width: Dp,
) {
    StripFrame(width, Colors.line) {
        StripName(track.name, Colors.text) { onOpenTrack(track.id) }
        val badge = @Composable {
            Text(
                if (track.armed) inputBadge(track) else " ",
                style = LabelStyle.copy(color = Colors.red, fontSize = 10.sp, letterSpacing = 0.5.sp),
                maxLines = 1,
            )
        }
        if (short) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FaderBlock(controller, track.id, track.gain, Modifier.fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    badge()
                    Spacer(Modifier.height(4.dp))
                    ArmMuteSolo(track, controller, stacked = false)
                    Spacer(Modifier.weight(1f))
                    PanRow(track, controller)
                }
            }
        } else {
            badge()
            Spacer(Modifier.height(4.dp))
            FaderBlock(controller, track.id, track.gain, Modifier.weight(1f))
            PanRow(track, controller)
            Spacer(Modifier.height(4.dp))
            ArmMuteSolo(track, controller, stacked = true)
        }
    }
}

@Composable
private fun MasterStrip(
    controller: SongController,
    onOpenTrack: (Int) -> Unit,
    short: Boolean,
    width: Dp,
) {
    val master = controller.song.master
    val mute = @Composable {
        Toggle("M", master.mute, Colors.amber, size = 36.dp, modifier = Modifier.fillMaxWidth(), onClick = {
            controller.updateTrack(MASTER_TRACK_ID) { it.copy(mute = !it.mute) }
        })
    }
    Box(Modifier.padding(STRIP_GAP)) {
        StripFrame(width - STRIP_GAP * 2, Colors.amber.copy(alpha = 0.5f)) {
            StripName("Master", Colors.amber) { onOpenTrack(MASTER_TRACK_ID) }
            if (short) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FaderBlock(controller, MASTER_TRACK_ID, master.gain, Modifier.fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text(" ", style = LabelStyle.copy(fontSize = 10.sp))
                        Spacer(Modifier.height(4.dp))
                        mute()
                    }
                }
            } else {
                Text(" ", style = LabelStyle.copy(fontSize = 10.sp))
                Spacer(Modifier.height(4.dp))
                FaderBlock(controller, MASTER_TRACK_ID, master.gain, Modifier.weight(1f))
                // Same height as a track strip's pan row and arm button, so
                // the master fader lines up with the others.
                Spacer(Modifier.height(36.dp + 4.dp + 36.dp + 4.dp))
                mute()
            }
        }
    }
}
