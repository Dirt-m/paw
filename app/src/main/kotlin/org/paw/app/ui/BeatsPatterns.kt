package org.paw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.paw.app.SongController
import org.paw.app.model.Pattern

// The pattern picker of Beats mode, opened in place under the pattern row:
// the track's patterns, then what can be done to the one in use.

@Composable
internal fun BeatsPatternList(
    controller: SongController,
    trackId: Int,
    patterns: List<Pattern>,
    current: Pattern,
    onSelect: (Int?) -> Unit,
    maxHeight: Dp,
) {
    var renaming by remember(current.id) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().background(Colors.panel)) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
            for (p in patterns) {
                val on = p.id == current.id
                val uses = controller.song.tracks.sumOf { t -> t.clips.count { it.pattern == p.id } }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(if (on) Colors.accent.copy(alpha = 0.10f) else Colors.panel)
                        .clickable { onSelect(p.id) }
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The one in use carries the accent edge, like the lit mode.
                    Box(Modifier.width(3.dp).fillMaxHeight().background(if (on) Colors.accent else Colors.panel))
                    Text(
                        p.name,
                        style = NameStyle.copy(color = if (on) Colors.text else Colors.dim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 13.dp),
                    )
                    if (uses > 0) {
                        Text("×$uses", style = MonoDimStyle.copy(color = Colors.green))
                        HSpace(12.dp)
                    }
                    Text(meterSummary(p), style = MonoDimStyle)
                }
            }
        }
        Line()
        val name = renaming
        Row(
            Modifier.fillMaxWidth().padding(Dimens.gap),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (name != null) {
                NameField(name, { renaming = it }, Modifier.weight(1f), maxLength = 24)
                AppButton("Save", accent = Colors.accent, filled = true, enabled = name.isNotBlank(), onClick = {
                    controller.renamePattern(current.id, name)
                    renaming = null
                })
                IconButton(CloseGlyph, onClick = { renaming = null })
            } else {
                // A new pattern keeps this one's meter and starts empty.
                AppButton("New", onClick = {
                    onSelect(controller.newPattern(trackId, from = current.copy(hits = emptyList())))
                }, modifier = Modifier.weight(1f), padding = 8.dp)
                AppButton("Copy", onClick = {
                    onSelect(controller.newPattern(trackId, from = current))
                }, modifier = Modifier.weight(1f), padding = 8.dp)
                AppButton("Rename", onClick = { renaming = current.name }, modifier = Modifier.weight(1f), padding = 8.dp)
                // A pattern the timeline plays can't go; the button says why.
                val inUse = controller.song.tracks.any { t -> t.clips.any { it.pattern == current.id } }
                AppButton(if (inUse) "In use" else "Delete", accent = Colors.red, enabled = !inUse, onClick = {
                    if (controller.deletePattern(current.id)) onSelect(null)
                }, modifier = Modifier.weight(1f), padding = 8.dp)
            }
        }
        Line()
    }
}

@Composable
private fun Line() = Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))
