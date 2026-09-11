package org.paw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.paw.app.model.ProjectStore

// The song list. Tap a song to open it; everything else about a song lives
// behind its own menu. The one primary action on the screen is New song.

@Composable
fun ProjectListScreen(
    entries: List<ProjectStore.Entry>,
    onOpen: (ProjectStore.Entry) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (ProjectStore.Entry, String) -> Boolean,
    onDelete: (ProjectStore.Entry, Boolean) -> Unit,
    initialNaming: Boolean = false,
) {
    var naming by remember { mutableStateOf(initialNaming) }
    var name by remember { mutableStateOf("") }

    BoxWithConstraints(Modifier.fillMaxSize().background(Colors.bg).safeDrawingPadding()) {
        val compact = maxWidth < COMPACT_WIDTH
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .padding(if (compact) 16.dp else 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PAW!",
                    style = TitleStyle.copy(color = Colors.amber, fontSize = 26.sp),
                    modifier = Modifier.weight(1f),
                )
                if (!naming) {
                    AppButton("+  New song", accent = Colors.amber, filled = true, height = 44.dp,
                        onClick = { naming = true })
                }
            }
            VSpace(16.dp)

            if (naming) {
                Column(Modifier.fillMaxWidth().panel().padding(12.dp)) {
                    Text("Name the new song", style = NameStyle)
                    VSpace(8.dp)
                    NameField(name, { name = it }, Modifier.fillMaxWidth())
                    VSpace(8.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton("Create", accent = Colors.amber, filled = true, enabled = name.isNotBlank(), onClick = {
                            onCreate(name.trim())
                            naming = false
                            name = ""
                        })
                        AppButton("Cancel", accent = Colors.dim, onClick = { naming = false; name = "" })
                    }
                }
                VSpace(16.dp)
            }

            if (entries.isEmpty() && !naming) {
                Text("No songs yet.", style = DimStyle)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries, key = { it.dir.absolutePath }) { entry ->
                        SongRow(entry, compact, onOpen, onRename, onDelete)
                    }
                }
            }
        }
    }
}

// One song row. Idle: open on tap, menu at the end. Renaming swaps the name
// for a text field; deleting swaps the row for the choice of what happens to
// the recordings.
@Composable
private fun SongRow(
    entry: ProjectStore.Entry,
    compact: Boolean,
    onOpen: (ProjectStore.Entry) -> Unit,
    onRename: (ProjectStore.Entry, String) -> Boolean,
    onDelete: (ProjectStore.Entry, Boolean) -> Unit,
) {
    var renaming by remember(entry.dir) { mutableStateOf<String?>(null) }
    var nameTaken by remember(entry.dir) { mutableStateOf(false) }
    var confirmingDelete by remember(entry.dir) { mutableStateOf(false) }
    var menuOpen by remember(entry.dir) { mutableStateOf(false) }
    val editing = renaming

    Column(
        Modifier
            .fillMaxWidth()
            .panel()
            .let {
                if (editing == null && !confirmingDelete) it.clickable { onOpen(entry) } else it
            }
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
    ) {
        when {
            confirmingDelete -> {
                Text(
                    "Delete “${entry.name}”?",
                    style = BodyStyle.copy(color = Colors.red),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                VSpace(8.dp)
                val buttons: @Composable () -> Unit = {
                    AppButton("Delete song and recordings", accent = Colors.red, filled = true, fontSize = 12.sp,
                        onClick = { onDelete(entry, false) })
                    AppButton("Delete song, keep recordings", accent = Colors.amber, fontSize = 12.sp,
                        onClick = { onDelete(entry, true) })
                    AppButton("Cancel", accent = Colors.dim, fontSize = 12.sp, onClick = { confirmingDelete = false })
                }
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { buttons() }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
                }
            }
            editing != null -> {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NameField(editing, {
                        renaming = it
                        nameTaken = false
                    }, Modifier.weight(1f))
                    AppButton("Save", accent = Colors.amber, filled = true, enabled = editing.isNotBlank(), onClick = {
                        if (onRename(entry, editing)) renaming = null else nameTaken = true
                    })
                    AppButton("Cancel", accent = Colors.dim, onClick = { renaming = null })
                }
                if (nameTaken) {
                    Text("A song with that name already exists", style = DimStyle.copy(color = Colors.red),
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
            else -> {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            style = NameStyle.copy(fontSize = 16.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(entry.lastModified)),
                            style = DimStyle.copy(fontSize = 12.sp),
                            maxLines = 1,
                        )
                    }
                    Box {
                        AppButton("⋮", accent = Colors.dim, fontSize = 20.sp, onClick = { menuOpen = true })
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename", style = BodyStyle) },
                                onClick = { menuOpen = false; renaming = entry.name },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete…", style = BodyStyle.copy(color = Colors.red)) },
                                onClick = { menuOpen = false; confirmingDelete = true },
                            )
                        }
                    }
                }
            }
        }
    }
}
