package org.paw.app.ui

import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.paw.app.PeakData
import org.paw.app.SongController
import org.paw.app.TakeEntry
import org.paw.app.model.Clip
import org.paw.app.model.TRACK_AUDIO
import org.paw.app.model.Track
import org.paw.app.model.barBeatAt

// Every recording in the song's folder, grouped per track, newest first, with
// imports in their own group. Audition one, put it on a track at the playhead
// or where it was first placed, jump to where it already plays, or delete one
// nothing uses. The target track is named on the button, so adding never
// lands somewhere unexpected.

/** One per-track group of recordings, in song track order. [key] is unique
 *  even when two tracks share a name. */
private data class TakeGroup(val key: String, val title: String, val entries: List<TakeEntry>)

/** Files brought in from elsewhere, "import_<original name>.wav". */
private const val IMPORT_PREFIX = "import_"

/** trackId parsed from the standard take file name, "…_t<id>.wav". */
private val TRACK_SUFFIX = Regex("_t(\\d+)\\.wav$")

/** Creation moment: the timestamp baked into the name, else file mtime. */
private val NAME_STAMP = Regex("(\\d{8})-(\\d{6})")

private fun createdAt(entry: TakeEntry): Long {
    val m = NAME_STAMP.find(entry.name) ?: return entry.file.lastModified()
    return try {
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(m.value)?.time ?: entry.file.lastModified()
    } catch (e: Exception) {
        entry.file.lastModified()
    }
}

private fun grouped(controller: SongController, list: List<TakeEntry>): List<TakeGroup> {
    val (imported, recorded) = list.partition { it.name.startsWith(IMPORT_PREFIX) }
    val byTrack = recorded.groupBy { TRACK_SUFFIX.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
    val groups = ArrayList<TakeGroup>()
    val claimed = HashSet<Int>()
    for (track in controller.song.tracks) {
        val entries = byTrack[track.id] ?: continue
        claimed += track.id
        groups += TakeGroup("t${track.id}", track.name, entries)
    }
    // Recordings whose track is gone keep their group, named after the id.
    for ((id, entries) in byTrack) {
        if (id == null || id in claimed) continue
        groups += TakeGroup("removed$id", "Track $id (removed)", entries)
    }
    if (imported.isNotEmpty()) groups += TakeGroup("import", "Imported", imported)
    byTrack[null]?.let { groups += TakeGroup("other", "Other files", it) }
    return groups.map { g -> g.copy(entries = g.entries.sortedByDescending { createdAt(it) }) }
}

@Composable
internal fun TakesPanel(controller: SongController, ui: SongUi, modifier: Modifier) {
    // Disk work stays off the UI thread; reloadKey re-lists after a delete.
    var takes by remember { mutableStateOf<List<TakeEntry>?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(controller.song.tracks, reloadKey, controller.importing) {
        takes = withContext(Dispatchers.IO) { controller.allTakes() }
    }

    // One audition player at a time; released when the panel goes away.
    var auditioning by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    fun toggleAudition(entry: TakeEntry) {
        if (auditioning == entry.rel) {
            player.reset()
            auditioning = null
            return
        }
        try {
            player.reset()
            player.setDataSource(entry.file.absolutePath)
            player.prepare()
            player.setOnCompletionListener { auditioning = null }
            player.start()
            auditioning = entry.rel
        } catch (e: Exception) {
            controller.message = "Can't play recording"
            auditioning = null
        }
    }

    // Android's file picker: a recorder plugged in as USB storage shows up
    // there as a drive.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        controller.importRecordings(uris)
    }

    // A take goes back to its own track by default, else to the selected
    // audio track, else the first one; each row can pick another.
    val song = controller.song
    val audioTracks = song.tracks.filter { it.kind == TRACK_AUDIO }
    val fallback = audioTracks.find { it.id == controller.selectedTrack } ?: audioTracks.firstOrNull()
    // A take being written has no clip yet and reads as unused: no deleting
    // anything until the recording stops.
    val recording = controller.status.recording || controller.status.recPending
    val at by remember(controller) {
        derivedStateOf { controller.song.barBeatAt(controller.playheadFrame).let { "${it.bar}.${it.beat}" } }
    }

    Column(modifier) {
        PanelHeader("Takes", onClose = ui::close) {
            AppButton(
                if (controller.importing) "Importing…" else "Import",
                enabled = !controller.importing,
                accent = Colors.accent,
                onClick = { picker.launch(arrayOf("audio/*")) },
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))

        val list = takes
        val groups = remember(list, song.tracks) { list?.let { grouped(controller, it) } }
        when {
            groups == null -> {}
            groups.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No recordings in this song yet", style = BodyStyle.copy(color = Colors.dim))
            }
            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = rememberLazyListState(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                groups.forEach { group ->
                    item(key = "header-${group.key}") {
                        SectionLabel(group.title, Modifier.padding(top = 14.dp, bottom = 6.dp))
                    }
                    items(group.entries.size, key = { group.entries[it].rel }) { i ->
                        val entry = group.entries[i]
                        val own = TRACK_SUFFIX.find(entry.name)?.groupValues?.get(1)?.toIntOrNull()
                        TakeRow(
                            entry = entry,
                            controller = controller,
                            playing = auditioning == entry.rel,
                            tracks = audioTracks,
                            defaultTarget = audioTracks.find { it.id == own } ?: fallback,
                            deletable = !recording,
                            at = at,
                            onAudition = { toggleAudition(it) },
                            onJump = { trackId, clip ->
                                controller.seek(clip.start)
                                controller.selectedTrack = trackId
                                controller.selectedClip = trackId to clip.id
                                ui.close()
                            },
                            onDeleted = { reloadKey++ },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TakeRow(
    entry: TakeEntry,
    controller: SongController,
    playing: Boolean,
    tracks: List<Track>,
    defaultTarget: Track?,
    deletable: Boolean,
    at: String,
    onAudition: (TakeEntry) -> Unit,
    onJump: (trackId: Int, clip: Clip) -> Unit,
    onDeleted: () -> Unit,
) {
    var confirmingDelete by remember(entry.rel) { mutableStateOf(false) }
    var choosing by remember(entry.rel) { mutableStateOf(false) }
    var chosen by remember(entry.rel) { mutableStateOf<Int?>(null) }
    val target = tracks.find { it.id == chosen } ?: defaultTarget
    val song = controller.song
    val sr = song.sampleRate
    val original = controller.originalStartOf(entry.rel)
    // Where the take first plays on the timeline, for the jump.
    val placed = song.tracks
        .flatMap { t -> t.clips.filter { it.take == entry.rel }.map { t.id to it } }
        .minByOrNull { it.second.start }

    Column(Modifier.fillMaxWidth().panel().padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Toggle(
                "",
                on = playing,
                color = Colors.accent,
                icon = if (playing) StopGlyph else PlayGlyph,
                onClick = { onAudition(entry) },
                size = 44.dp,
            )
            Column(
                Modifier
                    .width(148.dp)
                    .heightIn(min = 44.dp)
                    .then(if (placed != null) Modifier.clickable { onJump(placed.first, placed.second) } else Modifier),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatShortTime(entry.frames, sr),
                        style = MonoStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    )
                    HSpace(8.dp)
                    Text(
                        SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(createdAt(entry))),
                        style = DimStyle.copy(fontSize = 12.sp),
                        maxLines = 1,
                    )
                }
                if (placed == null) {
                    // usedBy also counts kit samples cut from the take.
                    Text(if (entry.usedBy > 0) "In a kit" else "Unused", style = DimStyle.copy(fontSize = 12.sp), maxLines = 1)
                } else {
                    val bb = song.barBeatAt(placed.second.start)
                    Text(
                        (if (entry.usedBy > 1) "×${entry.usedBy}, first at " else "On timeline at ") +
                            "${bb.bar}.${bb.beat} ›",
                        style = DimStyle.copy(fontSize = 12.sp, color = Colors.green),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TakeWave(controller, entry.rel, 0L, entry.frames, Modifier.weight(1f).height(36.dp))
            if (entry.usedBy == 0 && deletable) {
                IconButton(CloseGlyph, tint = Colors.red, onClick = { confirmingDelete = true })
            }
        }
        VSpace(8.dp)
        when {
            confirmingDelete && deletable -> ConfirmRow(
                question = "Delete from disk?",
                confirmLabel = "Delete",
                onConfirm = {
                    if (controller.deleteRecording(entry)) onDeleted()
                    confirmingDelete = false
                },
                onCancel = { confirmingDelete = false },
            )
            choosing -> FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (t in tracks) {
                    Toggle(shortName(t.name), t.id == target?.id, Colors.accent, onClick = {
                        chosen = t.id
                        choosing = false
                    })
                }
            }
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // The add and its track chooser read as one split button.
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    AppButton(
                        if (target == null) "No audio track" else "Add to ${shortName(target.name)} at $at",
                        enabled = target != null,
                        accent = Colors.accent,
                        padding = 8.dp,
                        onClick = { target?.let { controller.addTakeToTrack(it.id, entry.rel, controller.playheadFrame) } },
                        modifier = Modifier.weight(1f),
                    )
                    if (tracks.size > 1) {
                        AppButton(
                            "▾",
                            accent = Colors.accent,
                            padding = 0.dp,
                            fontSize = 16.sp,
                            onClick = { choosing = true },
                            modifier = Modifier.width(40.dp),
                        )
                    }
                }
                if (original != null) {
                    AppButton(
                        "At original time",
                        enabled = target != null,
                        accent = Colors.text,
                        padding = 10.dp,
                        onClick = { target?.let { controller.addTakeToTrack(it.id, entry.rel, original) } },
                    )
                }
            }
        }
    }
    VSpace(6.dp)
}

/** Track names on a button: long ones keep their start. */
private fun shortName(name: String) = if (name.length <= 12) name else name.take(11).trimEnd() + "…"

/** A take's (or a slice's) waveform, small: loaded lazily, drawn as columns. */
@Composable
internal fun TakeWave(
    controller: SongController,
    take: String,
    srcStart: Long,
    length: Long,
    modifier: Modifier,
) {
    val peaks by produceState<PeakData?>(null, take) { value = controller.peaksFor(take) }
    Canvas(modifier.background(Colors.clip)) {
        val p = peaks ?: return@Canvas
        val buckets = p.data.size / 2
        if (buckets < 1 || length <= 0) return@Canvas
        val midY = size.height / 2f
        val amp = size.height / 2f - 2f
        val cols = (size.width / 2f).toInt().coerceAtLeast(1)
        val perCol = length.toDouble() / cols
        for (c in 0 until cols) {
            val f0 = srcStart + (c * perCol).toLong()
            val f1 = (srcStart + ((c + 1) * perCol).toLong()).coerceAtLeast(f0 + 1)
            val b0 = (f0 / p.bucketFrames).toInt().coerceIn(0, buckets - 1)
            val b1 = ((f1 - 1) / p.bucketFrames).toInt().coerceIn(b0, buckets - 1)
            var lo = 0
            var hi = 0
            for (b in b0..b1) {
                lo = minOf(lo, p.data[2 * b].toInt())
                hi = maxOf(hi, p.data[2 * b + 1].toInt())
            }
            val x = c * 2f + 1f
            drawLine(
                Colors.wave,
                Offset(x, midY - hi / 127f * amp),
                Offset(x, midY - lo / 127f * amp + 1f),
                strokeWidth = 1.2f,
            )
        }
    }
}
