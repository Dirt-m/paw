package org.paw.app.ui

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.paw.app.SongController
import org.paw.app.TakeEntry

// The recordings browser: every recording WAV in the song's folder, grouped per
// track, newest first. Audition, add to a track at the playhead, or delete from
// disk. Delete confirms first and refuses while the recording is on the
// timeline. Listed by duration and date, not file name: "0:12, 5 Aug 14:18".

/** One per-track group of recordings, in song track order. */
private data class RecGroup(val title: String, val entries: List<TakeEntry>)

/** trackId parsed from the standard take file name, "…_t<id>.wav". */
private val TRACK_SUFFIX = Regex("_t(\\d+)\\.wav$")

/** Creation moment: the timestamp baked into the name, else file mtime. */
private val NAME_STAMP = Regex("(\\d{8})-(\\d{6})")
private fun createdAt(entry: TakeEntry): Long {
    val m = NAME_STAMP.find(entry.name) ?: return entry.file.lastModified()
    return try {
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(m.value)?.time
            ?: entry.file.lastModified()
    } catch (e: Exception) {
        entry.file.lastModified()
    }
}

private fun grouped(controller: SongController, list: List<TakeEntry>): List<RecGroup> {
    val byTrack = list.groupBy { TRACK_SUFFIX.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
    val groups = ArrayList<RecGroup>()
    val claimed = HashSet<Int>()
    for (track in controller.song.tracks) {
        val entries = byTrack[track.id] ?: continue
        claimed += track.id
        groups += RecGroup(track.name, entries)
    }
    // Recordings whose track is gone keep their group, named after the id.
    for ((id, entries) in byTrack) {
        if (id == null || id in claimed) continue
        groups += RecGroup("Track $id (removed)", entries)
    }
    byTrack[null]?.let { groups += RecGroup("Other files", it) }
    return groups.map { g -> g.copy(entries = g.entries.sortedByDescending { createdAt(it) }) }
}

@Composable
fun RecordingsScreen(
    controller: SongController,
    onBack: () -> Unit,
) {
    // Disk work stays off the UI thread; reloadKey re-lists after a delete.
    var recordings by remember { mutableStateOf<List<TakeEntry>?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(controller.song.tracks, reloadKey) {
        recordings = withContext(Dispatchers.IO) { controller.allTakes() }
    }

    // One audition player at a time; released when the screen goes away.
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

    Column(Modifier.fillMaxSize().background(Colors.bg).safeDrawingPadding()) {
        ScreenTopBar(title = "Recordings", onBack = onBack, backLabel = "Song") {
            Text(controller.song.name, style = DimStyle, modifier = Modifier.padding(end = 10.dp), maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))

        val list = recordings
        val groups = remember(list, controller.song.tracks) { list?.let { grouped(controller, it) } }
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            when {
                groups == null -> Text("…", style = DimStyle)
                groups.isEmpty() -> Text("Nothing recorded in this song yet.", style = DimStyle)
                else -> LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                    groups.forEach { group ->
                        item(key = "header-${group.title}") {
                            SectionLabel(group.title, Modifier.padding(top = 14.dp, bottom = 6.dp))
                        }
                        items(group.entries.size, key = { group.entries[it].rel }) { i ->
                            RecordingRow(
                                entry = group.entries[i],
                                controller = controller,
                                auditioning = auditioning,
                                onAudition = { toggleAudition(it) },
                                onDeleted = { reloadKey++ },
                                onPlaced = onBack,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    entry: TakeEntry,
    controller: SongController,
    auditioning: String?,
    onAudition: (TakeEntry) -> Unit,
    onDeleted: () -> Unit,
    onPlaced: () -> Unit,
) {
    var confirmingDelete by remember(entry.rel) { mutableStateOf(false) }
    var placing by remember(entry.rel) { mutableStateOf(false) }
    val sr = controller.song.sampleRate
    val playing = auditioning == entry.rel

    Column(Modifier.fillMaxWidth().panel().padding(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppButton(
                if (playing) "■" else "▶",
                onClick = { onAudition(entry) },
                accent = if (playing) Colors.amber else Colors.text,
                filled = playing,
                height = 44.dp,
                fontSize = 16.sp,
                modifier = Modifier.width(52.dp),
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatShortTime(entry.frames, sr),
                        style = MonoStyle.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    )
                    HSpace(10.dp)
                    Text(
                        SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(createdAt(entry))),
                        style = DimStyle,
                        maxLines = 1,
                    )
                }
                Text(
                    if (entry.usedBy > 0) {
                        if (entry.usedBy == 1) "On the timeline" else "On the timeline ×${entry.usedBy}"
                    } else "Not on the timeline",
                    style = DimStyle.copy(color = if (entry.usedBy > 0) Colors.green else Colors.dim, fontSize = 12.sp),
                    maxLines = 1,
                )
            }
            AppButton(
                if (placing) "Cancel" else "Add",
                accent = if (placing) Colors.dim else Colors.amber,
                onClick = {
                    confirmingDelete = false
                    placing = !placing
                },
            )
            if (entry.usedBy == 0 && !placing) {
                AppButton("✕", accent = Colors.red, onClick = {
                    placing = false
                    confirmingDelete = true
                })
            }
        }
        if (placing) {
            // Pick the destination track; the clip lands at the playhead.
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(
                    "Add at ${formatTime(controller.playheadFrame, sr)} to:",
                    style = DimStyle,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    controller.song.tracks.forEach { t ->
                        AppButton(t.name, modifier = Modifier.fillMaxWidth(), onClick = {
                            controller.addTakeToTrack(t.id, entry.rel, controller.playheadFrame)
                            onPlaced()
                        })
                    }
                }
                if (controller.song.tracks.isEmpty()) {
                    Text("No tracks in this song", style = DimStyle)
                }
            }
        }
        if (confirmingDelete) {
            ConfirmRow(
                prompt = "Delete this recording from disk?",
                confirmLabel = "Delete",
                onConfirm = {
                    if (controller.deleteRecording(entry)) onDeleted()
                    confirmingDelete = false
                },
                onCancel = { confirmingDelete = false },
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
    VSpace(6.dp)
}
