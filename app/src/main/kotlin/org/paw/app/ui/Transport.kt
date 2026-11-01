package org.paw.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.paw.app.SongController
import org.paw.app.TRANSPORT_RECORDING
import org.paw.app.TRANSPORT_STOPPED
import org.paw.app.model.MASTER_TRACK_ID
import org.paw.app.model.barBeatAt

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
        }, modifier = m, height = Dimens.touchBig, fontSize = 16.sp, padding = 0.dp)
    }
    val play: @Composable (Modifier) -> Unit = { m ->
        TransportButton(
            if (rolling) StopGlyph else PlayGlyph,
            if (rolling) "Stop" else "Play",
            Colors.text,
            filled = true,
            onClick = { controller.playPause() },
            modifier = m,
        )
    }
    val rec: @Composable (Modifier) -> Unit = { m ->
        TransportButton(
            ArmGlyph,
            if (status.recording) "Recording" else "Rec",
            Colors.red,
            filled = status.recording,
            onClick = { controller.toggleRecord() },
            modifier = m,
        )
    }
    val modes: @Composable RowScope.() -> Unit = {
        // The tempo is read before every take, so it sits beside the click
        // it drives; a tap opens the tempo panel to change it.
        TempoButton(song.tempoBpm, song.beatsPerBar, song.beatUnit) { ui.open(SongPanel.Tempo) }
        Toggle("Click", controller.clickEnabled, Colors.accent, onClick = {
            controller.setClick(!controller.clickEnabled)
        })
        Toggle("Loop", song.loopEnabled, Colors.green, onClick = { toggleLoop(controller) })
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
                toStart(Modifier.width(52.dp))
                play(Modifier.width(124.dp))
                rec(Modifier.width(150.dp))
                Spacer(Modifier.weight(1f))
                TransportClock(controller, ui)
                Spacer(Modifier.weight(1f))
                modes()
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TransportClock(controller, ui)
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

/** A big transport button: drawn glyph and word, [filled] when it is what
 *  is happening. */
@Composable
private fun TransportButton(
    glyph: Glyph,
    label: String,
    color: Color,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(Dimens.corner)
    Row(
        modifier
            .height(Dimens.touchBig)
            .clip(shape)
            .background(if (filled) color.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (filled) color else color.copy(alpha = 0.55f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(18.dp)) { glyph(color) }
        Spacer(Modifier.width(8.dp))
        Text(label, style = ButtonStyle.copy(color = color, fontSize = 15.sp), maxLines = 1, softWrap = false)
    }
}

/** Tempo over meter, in two small lines, framed like the toggles beside it. */
@Composable
private fun TempoButton(bpm: Float, beatsPerBar: Int, beatUnit: Int, onClick: () -> Unit) {
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
        Text("BPM · $beatsPerBar/$beatUnit", style = LabelStyle.copy(fontSize = 9.sp, letterSpacing = 0.5.sp), maxLines = 1)
    }
}

/**
 * Loop with no region marked yet makes one instead of doing nothing: around
 * the selected clip if there is one, else around the whole song.
 */
private fun toggleLoop(controller: SongController) {
    val song = controller.song
    if (song.loopEnd > song.loopStart) {
        controller.setLoopEnabled(!song.loopEnabled)
        return
    }
    val sel = controller.selectedClip?.let { (t, c) -> song.track(t)?.clips?.find { it.id == c } }
    when {
        sel != null -> controller.setLoopRegion(sel.start, sel.end)
        song.endFrame > 0 -> controller.setLoopRegion(0L, song.endFrame)
        else -> controller.message = "Nothing to loop yet"
    }
}

// The only thing on the bar that moves with the transport, so it reads the
// playhead on its own: a tick repaints the clock, not the whole bar. Bars and
// beats by default; a tap switches it, and the ruler with it, to time.
@Composable
private fun TransportClock(controller: SongController, ui: SongUi) {
    val song = controller.song
    val status = controller.status
    val color = if (status.recording) Colors.red else Colors.text
    Row(
        Modifier
            .heightIn(min = Dimens.touch)
            .widthIn(min = 120.dp)
            .clip(RoundedCornerShape(Dimens.corner))
            .clickable { ui.clockAsTime = !ui.clockAsTime }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // During count-in the clock shows the beats left, where the musician
        // is already looking.
        if (status.countdown > 0 && status.transport == TRANSPORT_RECORDING) {
            val fpb = controller.framesPerBeat
            val beatsLeft = ((status.countdown + fpb - 1) / fpb).toInt()
            Text("· $beatsLeft ·", style = ClockStyle.copy(color = Colors.red))
        } else if (ui.clockAsTime) {
            Text(formatTime(controller.playheadFrame, song.sampleRate), style = ClockStyle.copy(color = color))
        } else {
            val bb = song.barBeatAt(controller.playheadFrame)
            Text("${bb.bar}.${bb.beat}", style = ClockStyle.copy(color = color))
            Text(
                ".%02d".format(bb.sub),
                style = ClockStyle.copy(color = color.copy(alpha = 0.6f), fontSize = 17.sp),
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}
