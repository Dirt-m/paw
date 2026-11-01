package org.paw.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.paw.app.AppPrefs
import org.paw.app.PeakData
import org.paw.app.SongController
import org.paw.app.TRANSPORT_RECORDING
import org.paw.app.TRANSPORT_STOPPED
import org.paw.app.model.Clip
import org.paw.app.model.INPUT_CH2
import org.paw.app.model.INPUT_NONE
import org.paw.app.model.INPUT_STEREO
import org.paw.app.model.MASTER_TRACK_ID
import org.paw.app.model.MIN_CLIP_FRAMES
import org.paw.app.model.TRACK_BEAT
import org.paw.app.model.InputDevice
import org.paw.app.model.Track

// The transport under the thumb: clock, tempo, click, loop, and the three big buttons.

// ---------------------------------------------------------------- transport --

@Composable
internal fun TransportBar(
    controller: SongController,
    ui: SongUi,
    tl: TimelineState,
    compact: Boolean,
    short: Boolean,
) {
    val status = controller.status
    val song = controller.song
    val rolling = status.transport != TRANSPORT_STOPPED

    val toStart: @Composable (Modifier) -> Unit = { m ->
        AppButton("|◀", onClick = {
            // Already at the start: snap the view there instead.
            if (controller.playheadFrame == 0L) tl.originFrame = 0 else controller.seek(0)
        }, modifier = m, height = Dimens.touchBig, fontSize = 16.sp)
    }
    val play: @Composable (Modifier) -> Unit = { m ->
        AppButton(
            if (rolling) "■   Stop" else "▶   Play",
            onClick = { controller.playPause() },
            filled = true,
            accent = Colors.text,
            modifier = m,
            height = Dimens.touchBig,
            fontSize = 15.sp,
        )
    }
    val rec: @Composable (Modifier) -> Unit = { m ->
        AppButton(
            if (status.recording) "●   Recording" else "●   Rec",
            onClick = { controller.toggleRecord() },
            filled = status.recording,
            accent = Colors.red,
            modifier = m,
            height = Dimens.touchBig,
            fontSize = 15.sp,
        )
    }
    val modes: @Composable RowScope.() -> Unit = {
        // The tempo is read before every take, so it sits beside the click
        // it drives; a tap opens the metronome to change it.
        TempoButton(song.tempoBpm, song.beatsPerBar) { ui.open(SongPanel.Tempo) }
        Toggle("Click", controller.clickEnabled, Colors.accent, onClick = {
            controller.setClick(!controller.clickEnabled)
        })
        Toggle("Loop", song.loopEnabled, Colors.green, onClick = {
            toggleLoop(controller)
        })
    }

    Column(Modifier.fillMaxWidth().background(Colors.panel)) {
        // Master level along the top edge: always visible, never in the way.
        LiveMeter(controller, MASTER_TRACK_ID, Modifier.fillMaxWidth(), thickness = 4.dp)
        if (short) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                toStart(Modifier.width(56.dp))
                play(Modifier.width(132.dp))
                rec(Modifier.width(150.dp))
                Spacer(Modifier.weight(1f))
                TransportClock(controller)
                Spacer(Modifier.weight(1f))
                modes()
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TransportClock(controller)
                Spacer(Modifier.weight(1f))
                modes()
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                toStart(Modifier.width(56.dp))
                play(Modifier.weight(1f))
                rec(Modifier.weight(1f))
            }
        }
    }
}

/** Tempo over meter, in two small lines, framed like the toggles beside it. */
@Composable
private fun TempoButton(bpm: Float, beatsPerBar: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.corner)
    Column(
        Modifier
            .heightIn(min = Dimens.touch)
            .clip(shape)
            .border(1.dp, Colors.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "%.0f".format(bpm),
            style = MonoStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Text("BPM · $beatsPerBar/4", style = LabelStyle.copy(fontSize = 9.sp, letterSpacing = 0.5.sp), maxLines = 1)
    }
}

/**
 * LOOP with no region marked yet makes one instead of doing nothing: around the
 * selected clip if there is one, else around the whole song.
 */
private fun toggleLoop(controller: SongController) {
    val song = controller.song
    if (song.loopEnd > song.loopStart) {
        controller.setLoop(song.loopStart, song.loopEnd, !song.loopEnabled)
        return
    }
    val sel = controller.selectedClip?.let { (t, c) -> song.track(t)?.clips?.find { it.id == c } }
    val (lo, hi) = when {
        sel != null -> sel.start to sel.end
        song.endFrame > 0 -> 0L to song.endFrame
        else -> {
            controller.message = "Nothing to loop yet"
            return
        }
    }
    controller.setLoop(lo, hi, true)
    if (hi - lo < song.sampleRate) controller.message = "Loop needs at least one second"
}

// The only thing on the bar that moves with the transport, so it reads the
// playhead on its own: a tick repaints the clock, not the whole bar.
@Composable
private fun TransportClock(controller: SongController) {
    val song = controller.song
    val status = controller.status
    // During count-in the clock shows the beats left, where the musician is
    // already looking.
    if (status.countdown > 0 && status.transport == TRANSPORT_RECORDING) {
        val fpb = controller.framesPerBeat
        val beatsLeft = ((status.countdown + fpb - 1) / fpb).toInt()
        Text("· $beatsLeft ·", style = ClockStyle.copy(color = Colors.red))
    } else {
        Text(
            formatTime(controller.playheadFrame, song.sampleRate),
            style = ClockStyle.copy(color = if (status.recording) Colors.red else Colors.text),
        )
    }
}
