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

// Actions for the selected clip.

// ------------------------------------------------------------ selection bar --

// The one control on the bar that depends on where the playhead is; reading
// it here keeps the rest of the bar off the transport's clock.
@Composable
private fun SplitButton(controller: SongController, trackId: Int, clip: Clip, modifier: Modifier) {
    val playhead = controller.playheadFrame
    AppButton(
        "Split",
        enabled = playhead > clip.start + MIN_CLIP_FRAMES && playhead < clip.end - MIN_CLIP_FRAMES,
        onClick = { controller.splitClip(trackId, clip.id, playhead) },
        modifier = modifier,
        fontSize = 12.sp,
    )
}

/** Actions for the selected clip. Only exists while something is selected. */
@Composable
internal fun ClipBar(
    controller: SongController,
    ui: SongUi,
    tl: TimelineState,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val onOpenSampler: (Int, Int?) -> Unit = { t, p -> ui.openBeats(t, p) }
    val sel = controller.selectedClip ?: return
    val song = controller.song
    val (trackId, clipId) = sel
    val clip = song.track(trackId)?.clips?.find { it.id == clipId } ?: return
    val trackIdx = song.tracks.indexOfFirst { it.id == trackId }

    Row(
        Modifier
            .fillMaxWidth()
            .background(Colors.accent.copy(alpha = 0.08f))
            .border(1.dp, Colors.accent.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!compact) {
            Text(
                "${if (clip.isPattern) "PATTERN" else "CLIP"}  ·  ${formatTime(clip.length, song.sampleRate)}",
                style = LabelStyle.copy(color = Colors.accent),
                modifier = Modifier.padding(horizontal = 6.dp),
                maxLines = 1,
            )
        }
        if (clip.isPattern) {
            AppButton("Edit", accent = Colors.accent, fontSize = 12.sp, modifier = Modifier.weight(1f),
                onClick = { onOpenSampler(trackId, clip.pattern) })
        } else {
            // Turns the clip's slice into a sample on a beat track.
            AppButton("Sample", accent = Colors.accent, fontSize = 12.sp, modifier = Modifier.weight(1.2f),
                onClick = { controller.sampleFromClip(trackId, clipId, controller.firstBeatTrack(), "") })
        }
        SplitButton(controller, trackId, clip, Modifier.weight(1.1f))
        AppButton(if (compact) "Dup" else "Duplicate", onClick = { controller.duplicateClip(trackId, clipId) },
            modifier = Modifier.weight(if (compact) 1f else 1.4f), fontSize = 12.sp)
        AppButton("▲", enabled = trackIdx > 0, fontSize = 12.sp,
            onClick = { controller.moveClipToTrack(trackId, clipId, -1) })
        AppButton("▼", enabled = trackIdx in 0 until song.tracks.size - 1, fontSize = 12.sp,
            onClick = { controller.moveClipToTrack(trackId, clipId, 1) })
        AppButton("Delete", accent = Colors.red, fontSize = 12.sp, modifier = Modifier.weight(1.1f),
            onClick = { controller.deleteClip(trackId, clipId) })
    }
}
