package org.paw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.paw.app.SongController
import org.paw.app.model.TRACK_BEAT

// Turns the selected audio clip into a sample on a beat track: which kit it
// joins (the one Beats mode last showed, to begin with), under what name. Beats mode opens on that kit afterwards, so the new
// sound is in view.

/** The longest sample the controller keeps; it cuts the rest. Must match
 *  SongController's own limit, which is private to it. */
private const val MAX_SAMPLE_SECONDS = 10L

@Composable
internal fun SampleToKitPanel(controller: SongController, ui: SongUi, p: SongPanel.SampleToKit, modifier: Modifier) {
    val song = controller.song
    val clip = song.track(p.trackId)?.clips?.find { it.id == p.clipId }?.takeIf { !it.isPattern }
    if (clip == null) {
        LaunchedEffect(p) { ui.close() }
        return
    }
    val beatTracks = song.tracks.filter { it.kind == TRACK_BEAT }
    // null = a new beat track.
    var target by remember(p) {
        mutableStateOf(beatTracks.find { it.id == ui.beatsTrack }?.id ?: beatTracks.firstOrNull()?.id)
    }
    var name by remember(p) { mutableStateOf("Sample ${song.nextSampleId}") }
    val maxFrames = MAX_SAMPLE_SECONDS * song.sampleRate
    val tooLong = clip.length > maxFrames
    val kept = minOf(clip.length, maxFrames)

    Column(modifier) {
        PanelHeader("Sample to kit", onClose = ui::close)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))
        PanelBody(Modifier.weight(1f)) {
            TakeWave(controller, clip.take, clip.srcStart, kept, Modifier.fillMaxWidth().height(56.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.track(p.trackId)?.name ?: "",
                    style = DimStyle,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    "%.2f s".format(kept.toFloat() / song.sampleRate),
                    style = MonoStyle,
                )
            }
            if (tooLong) {
                Text(
                    "Clip is %.0f s, the sample keeps the first %.0f".format(
                        clip.length.toFloat() / song.sampleRate,
                        MAX_SAMPLE_SECONDS.toFloat(),
                    ),
                    style = DimStyle.copy(color = Colors.warn),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            PanelDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Name", style = BodyStyle, modifier = Modifier.width(72.dp))
                NameField(name, { name = it }, Modifier.weight(1f), maxLength = 24)
            }
            PanelDivider()
            SectionLabel("Add to", Modifier.padding(top = 4.dp, bottom = 4.dp))
            for (t in beatTracks) {
                val n = t.samples.size
                RadioRow(t.name, target == t.id, detail = if (n == 1) "1 sound" else "$n sounds", accent = Colors.accent) {
                    target = t.id
                }
            }
            RadioRow("New beat track", target == null, accent = Colors.accent) { target = null }
            VSpace(16.dp)
            AppButton(
                "Make sample",
                accent = Colors.accent,
                filled = true,
                enabled = name.isNotBlank(),
                height = 48.dp,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val id = controller.sampleFromClip(p.trackId, p.clipId, target, name)
                    if (id != null) {
                        val kit = controller.song.tracks.find { t -> t.samples.any { it.id == id } }?.id
                        ui.openBeats(kit)
                    }
                },
            )
        }
    }
}
