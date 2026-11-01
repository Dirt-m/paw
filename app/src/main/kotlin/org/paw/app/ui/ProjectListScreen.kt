package org.paw.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.paw.app.model.ProjectStore

// The song list. Tap a song to open it; everything else about a song lives
// behind its own menu. The one primary action on the screen is New song,
// which makes the song and opens it straight away: naming can wait.

/** What a song row shows: its facts, its actions, a rename field, or the delete choice. */
enum class SongRowMode { Idle, Actions, Renaming, Deleting }

@Composable
fun ProjectListScreen(
    entries: List<ProjectStore.Entry>,
    summaries: Map<java.io.File, ProjectStore.Summary> = emptyMap(),
    onOpen: (ProjectStore.Entry) -> Unit,
    onCreate: () -> Unit,
    onRename: (ProjectStore.Entry, String) -> Boolean,
    onDelete: (ProjectStore.Entry, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    loading: Boolean = false,
    initialRow: Pair<String, SongRowMode>? = null,
) {
    val empty = !loading && entries.isEmpty()
    BoxWithConstraints(Modifier.fillMaxSize().background(Colors.bg).safeDrawingPadding()) {
        val compact = maxWidth < COMPACT_WIDTH
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .padding(horizontal = if (compact) 16.dp else 24.dp)
                .padding(top = if (compact) 16.dp else 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PAW",
                    style = TitleStyle.copy(color = Colors.accent, fontSize = 26.sp),
                    modifier = Modifier.weight(1f),
                )
                AppButton("Settings", accent = Colors.dim, height = 44.dp, onClick = onOpenSettings)
                if (!empty) {
                    HSpace(8.dp)
                    AppButton("+  New song", accent = Colors.accent, filled = true, height = 44.dp,
                        enabled = !loading, onClick = onCreate)
                }
            }
            VSpace(16.dp)

            when {
                loading -> Text("Moving songs to Documents/PAW…", style = DimStyle)
                empty -> FirstRun(onCreate, Modifier.weight(1f).fillMaxWidth())
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(entries, key = { it.dir.absolutePath }) { entry ->
                        val start = initialRow?.takeIf { it.first == entry.name }?.second ?: SongRowMode.Idle
                        SongRow(entry, summaries[entry.dir], compact, start, onOpen, onRename, onDelete)
                    }
                }
            }
        }
    }
}

/** No songs yet: an empty arrangement and the one thing to do about it. */
@Composable
private fun FirstRun(onCreate: () -> Unit, modifier: Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SongThumbnail(
            ProjectStore.Summary(
                clips = List(4) { emptyList() }, patternTracks = emptySet(),
                lengthFrames = 0, sampleRate = 48_000, tempoBpm = 120f,
            ),
            Modifier.size(width = 168.dp, height = 96.dp),
        )
        VSpace(20.dp)
        Text("No songs yet", style = TitleStyle.copy(fontSize = 18.sp))
        VSpace(20.dp)
        AppButton(
            "+  New song", accent = Colors.accent, filled = true, height = Dimens.touchBig,
            fontSize = 15.sp, padding = 28.dp, onClick = onCreate,
        )
        // Keeps the group a little above centre, where the eye lands.
        VSpace(64.dp)
    }
}

// One song row. Idle: open on tap, ⋮ at the end. The ⋮ opens a row of
// actions under the facts; renaming swaps the name for a text field;
// deleting swaps the row for the choice of what happens to the recordings.
@Composable
private fun SongRow(
    entry: ProjectStore.Entry,
    summary: ProjectStore.Summary?,
    compact: Boolean,
    initialMode: SongRowMode,
    onOpen: (ProjectStore.Entry) -> Unit,
    onRename: (ProjectStore.Entry, String) -> Boolean,
    onDelete: (ProjectStore.Entry, Boolean) -> Unit,
) {
    var mode by remember(entry.dir) { mutableStateOf(initialMode) }
    var name by remember(entry.dir) { mutableStateOf(entry.name) }
    var nameTaken by remember(entry.dir) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .panel()
            .let { if (mode == SongRowMode.Idle) it.clickable { onOpen(entry) } else it }
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
    ) {
        when (mode) {
            SongRowMode.Deleting -> {
                Text(
                    "Delete “${entry.name}”?",
                    style = BodyStyle.copy(color = Colors.red),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                VSpace(8.dp)
                // Stacked in a narrow window, all one width so they read as one choice.
                val w = if (compact) Modifier.fillMaxWidth() else Modifier
                val buttons: @Composable () -> Unit = {
                    AppButton("Delete song and recordings", accent = Colors.red, filled = true, fontSize = 12.sp,
                        modifier = w, onClick = { onDelete(entry, false) })
                    AppButton("Delete song, keep recordings", accent = Colors.accent, fontSize = 12.sp,
                        modifier = w, onClick = { onDelete(entry, true) })
                    AppButton("Cancel", accent = Colors.dim, fontSize = 12.sp, modifier = w,
                        onClick = { mode = SongRowMode.Idle })
                }
                if (compact) {
                    Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { buttons() }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
                }
            }
            SongRowMode.Renaming -> {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NameField(name, {
                        name = it
                        nameTaken = false
                    }, Modifier.weight(1f))
                    AppButton("Save", accent = Colors.accent, filled = true, enabled = name.isNotBlank(), onClick = {
                        if (onRename(entry, name)) mode = SongRowMode.Idle else nameTaken = true
                    })
                    AppButton("Cancel", accent = Colors.dim, onClick = { mode = SongRowMode.Idle; name = entry.name })
                }
                if (nameTaken) {
                    Text("A song with that name already exists", style = DimStyle.copy(color = Colors.red),
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
            SongRowMode.Idle, SongRowMode.Actions -> {
                val open = mode == SongRowMode.Actions
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SongThumbnail(summary, Modifier.size(width = 76.dp, height = 44.dp))
                    HSpace(12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            style = NameStyle.copy(fontSize = 16.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        VSpace(2.dp)
                        Text(
                            listOfNotNull(summary?.let { songFacts(it) }, editedAt(entry.lastModified))
                                .joinToString(" · "),
                            style = MonoDimStyle.copy(fontSize = 11.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        MoreGlyph,
                        tint = if (open) Colors.accent else Colors.dim,
                        modifier = Modifier.padding(start = 4.dp),
                        onClick = { mode = if (open) SongRowMode.Idle else SongRowMode.Actions },
                    )
                }
                if (open) {
                    VSpace(8.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppButton("Rename", onClick = { name = entry.name; mode = SongRowMode.Renaming },
                            modifier = Modifier.weight(1f))
                        AppButton("Delete…", accent = Colors.red, onClick = { mode = SongRowMode.Deleting },
                            modifier = Modifier.weight(1f))
                        AppButton("Cancel", accent = Colors.dim, onClick = { mode = SongRowMode.Idle },
                            modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** "4 tracks, 1:23" for a song with audio, "empty" before the first take. */
private fun songFacts(s: ProjectStore.Summary): String {
    val n = s.clips.size
    val tracks = if (n == 1) "1 track" else "$n tracks"
    return if (s.lengthFrames > 0) "$tracks · ${formatShortTime(s.lengthFrames, s.sampleRate)}"
    else "$tracks · empty"
}

/** The date without its year when it is this year's. */
private fun editedAt(millis: Long): String {
    val cal = java.util.Calendar.getInstance()
    val year = cal.get(java.util.Calendar.YEAR)
    cal.timeInMillis = millis
    val pattern = if (cal.get(java.util.Calendar.YEAR) == year) "d MMM HH:mm" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.US).format(Date(millis))
}

/**
 * The song's arrangement in miniature: a row per track, its clips as bars
 * along the song's length. Beat tracks draw in the accent, as on the timeline.
 */
@Composable
private fun SongThumbnail(summary: ProjectStore.Summary?, modifier: Modifier) {
    Canvas(modifier.background(Colors.bg).border(1.dp, Colors.line)) {
        val pad = 5.dp.toPx()
        val s = summary
        if (s == null || s.clips.isEmpty() || s.lengthFrames <= 0L) {
            // Nothing recorded yet: the empty lanes, so the box still reads
            // as a song and not as a missing image.
            val lanes = (s?.clips?.size ?: 2).coerceIn(1, 6)
            val rowH = (size.height - 2 * pad) / lanes
            for (i in 1 until lanes) {
                val y = pad + i * rowH
                drawLine(Colors.line, Offset(pad, y), Offset(size.width - pad, y))
            }
            return@Canvas
        }
        val w = size.width - 2 * pad
        val rowH = ((size.height - 2 * pad) / s.clips.size).coerceAtMost(7.dp.toPx())
        val top = (size.height - rowH * s.clips.size) / 2f
        s.clips.forEachIndexed { i, clips ->
            val color = if (i in s.patternTracks) Colors.accent.copy(alpha = 0.8f) else Colors.wave.copy(alpha = 0.7f)
            for ((a, b) in clips) {
                val x0 = pad + a.toFloat() / s.lengthFrames * w
                val x1 = pad + b.toFloat() / s.lengthFrames * w
                drawRect(
                    color,
                    topLeft = Offset(x0, top + i * rowH + rowH * 0.15f),
                    size = Size((x1 - x0).coerceAtLeast(1.5f), rowH * 0.7f),
                )
            }
        }
    }
}
