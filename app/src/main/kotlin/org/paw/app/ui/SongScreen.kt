package org.paw.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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

// The song workspace. One mode fills the middle (Tracks, Mix or Beats), and at
// most one panel opens over it: over the whole workspace in portrait, as a
// column on the right when the window is wide. The transport stays at the
// bottom through all of it, so nothing a musician opens stops the song or
// hides the playhead. The mode bar sits above the transport in thumb reach;
// while a clip is selected the clip's actions take its place.


/** Fit never zooms in closer than this, so a short song still has room. */
internal const val FIT_MIN_SECONDS = 20L

/** Width of a panel beside the workspace when the window is wide. */
private val PANEL_W = 340.dp

@Composable
fun SongScreen(
    controller: SongController,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onRename: (String) -> Boolean = { false },
    ui: SongUi = remember(controller) { SongUi() },
    tl: TimelineState = remember(controller) { TimelineState() },
) {
    val context = LocalContext.current
    val prefs = remember(context) { AppPrefs.of(context) }
    val savedZoom = remember(controller) { prefs.zoomFor(controller.projectDir.name) }
    remember(tl) { savedZoom?.let { tl.framesPerPx = it } }
    // A song opened for the first time shows all of itself, not the first
    // few seconds; an empty one shows enough room for a take.
    LaunchedEffect(tl) {
        if (savedZoom != null) return@LaunchedEffect
        snapshotFlow { tl.laneWidthPx }.first { it > 0f }
        tl.fitTo(maxOf(controller.song.endFrame, FIT_MIN_SECONDS * controller.song.sampleRate))
    }
    // Zoom survives leaving the song; the write sits out the pinch.
    LaunchedEffect(tl) {
        snapshotFlow { tl.framesPerPx }.collectLatest { fpp ->
            delay(400)
            prefs.setZoomFor(controller.projectDir.name, fpp)
        }
    }
    val song = controller.song

    // Waveform peaks, loaded off the UI thread and keyed by take path.
    val peaks = remember(controller) { mutableStateMapOf<String, PeakData>() }
    val takeSet = remember(song.tracks) {
        song.tracks.flatMapTo(mutableSetOf()) { t -> t.clips.map { it.take } }
    }
    LaunchedEffect(takeSet) {
        for (take in takeSet) {
            if (take !in peaks) {
                controller.peaksFor(take)?.let { peaks[take] = it }
            }
        }
    }

    // Watched as a flow: reading the playhead in composition here would
    // recompose the whole screen per tick.
    LaunchedEffect(controller, tl) {
        snapshotFlow { controller.playheadFrame }.collect { ph ->
            if (!tl.follow || controller.status.transport == TRANSPORT_STOPPED) return@collect
            val w = tl.laneWidthPx
            if (w <= 0f) return@collect
            val px = tl.pxOf(ph)
            if (controller.followSmooth) {
                if (px < 0f || px > w * 0.5f) {
                    tl.originFrame = (ph - (w * 0.5f * tl.framesPerPx).toLong()).coerceAtLeast(0L)
                }
            } else if (px < 0f || px > w * 0.88f) {
                tl.originFrame = (ph - (w * 0.12f * tl.framesPerPx).toLong()).coerceAtLeast(0L)
            }
        }
    }

    // Transient messages clear themselves.
    LaunchedEffect(controller.message) {
        if (controller.message != null) {
            delay(4000)
            controller.message = null
        }
    }

    // Back unwinds the screen one layer at a time before it leaves the song:
    // the panel, then the clip selection, then back to Tracks.
    val clipSelected = controller.selectedClip != null && ui.mode == SongMode.Tracks
    BackHandler(enabled = ui.panel != null || clipSelected || ui.mode != SongMode.Tracks) {
        when {
            ui.panel != null -> ui.close()
            clipSelected -> controller.selectedClip = null
            else -> ui.mode = SongMode.Tracks
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Colors.bg)) {
        val compact = maxWidth < COMPACT_WIDTH
        val short = maxHeight < SHORT_HEIGHT
        val panel = ui.panel

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            SongTopBar(controller, ui, tl, onBack, onOpenSettings, onRename, compact = compact, short = short)

            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    // A narrow window gives the panel the whole workspace; the
                    // mode under it keeps its state for when the panel closes.
                    // The mode stays composed under a panel, so its scroll
                    // position, its choices and a looping pattern survive a
                    // visit to the inspector.
                    when (ui.mode) {
                        SongMode.Tracks -> {
                            TracksView(controller, ui, tl, peaks, compact, short)
                            PlayheadChip(controller, tl, compact)
                        }
                        SongMode.Mix -> MixerView(controller, ui, short)
                        SongMode.Beats -> BeatsMode(controller, ui, compact, short)
                    }
                    // A narrow window gives the panel the whole workspace;
                    // touches stop at it instead of reaching the mode below.
                    if (panel != null && compact) {
                        PanelHost(controller, ui, tl, compact, Modifier.fillMaxSize().blockTouches())
                    }
                    MessageBanner(controller, Modifier.align(Alignment.BottomCenter).padding(12.dp))
                }
                if (panel != null && !compact) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Colors.line))
                    PanelHost(controller, ui, tl, compact, Modifier.width(PANEL_W).fillMaxHeight())
                }
            }

            // In a short window the modes and clip actions live in the top
            // bar, so the lanes keep their height.
            if (!short) {
                // Clip actions belong to the timeline: a panel over it gets the modes.
                if (clipSelected && panel == null) ClipBar(controller, ui, tl, compact, Modifier.fillMaxWidth())
                else ModeBar(ui, Modifier.fillMaxWidth())
            }
            TransportBar(controller, ui, tl, compact = compact, short = short)
        }
    }
}

/** Swallows whatever the content above it leaves, so nothing falls through. */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                .changes.forEach { it.consume() }
        }
    }
}

/** Tracks · Mix · Beats, the three places a song is worked on. */
@Composable
internal fun ModeBar(ui: SongUi, modifier: Modifier = Modifier, height: Dp = 48.dp) {
    Row(modifier.height(height).background(Colors.panel)) {
        for (m in SongMode.entries) {
            val on = ui.mode == m
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { ui.panel = null; ui.mode = m },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    m.name,
                    style = ButtonStyle.copy(color = if (on) Colors.accent else Colors.dim, fontSize = 14.sp),
                )
                // The lit mode carries a bar along its top edge, where the eye
                // comes from the workspace.
                if (on) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .width(40.dp)
                            .height(3.dp)
                            .background(Colors.accent, RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ top bar --

/**
 * Back, the song's name (tap to rename), what the engine hears, undo, redo,
 * share and the menu of rare things. In a short window the mode tabs, or the
 * selected clip's actions, sit in the middle of this row.
 */
@Composable
private fun SongTopBar(
    controller: SongController,
    ui: SongUi,
    tl: TimelineState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onRename: (String) -> Boolean,
    compact: Boolean,
    short: Boolean,
) {
    val song = controller.song
    val scope = rememberCoroutineScope()
    val hasAudio = remember(song) { song.endFrame > 0 }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().background(Colors.panel)) {
        Row(
            Modifier.fillMaxWidth().height(if (short) 48.dp else 52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(ChevronLeftGlyph, onClick = onBack)
            val editing = renaming
            if (editing != null) {
                NameField(editing, { renaming = it }, Modifier.weight(1f))
                HSpace(6.dp)
                AppButton("Save", accent = Colors.accent, filled = true, enabled = editing.isNotBlank(), onClick = {
                    // A song is a folder: the name can be taken.
                    if (onRename(editing.trim())) renaming = null
                    else controller.message = "A song with that name already exists"
                })
                HSpace(4.dp)
                IconButton(CloseGlyph, onClick = { renaming = null })
                return@Row
            }
            Text(
                song.name,
                style = TitleStyle.copy(fontSize = 18.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .then(if (short) Modifier.widthIn(max = 180.dp) else Modifier.weight(1f))
                    .clip(RoundedCornerShape(Dimens.corner))
                    .clickable { renaming = song.name }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
            if (short) {
                HSpace(8.dp)
                Box(Modifier.weight(1f)) {
                    if (controller.selectedClip != null && ui.mode == SongMode.Tracks) {
                        ClipBar(controller, ui, tl, compact, Modifier.fillMaxWidth())
                    } else {
                        ModeBar(ui, Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.corner)), height = 44.dp)
                    }
                }
                HSpace(8.dp)
            }
            IoChip(controller) { ui.open(SongPanel.Io) }
            IconButton(UndoGlyph, enabled = controller.canUndo, onClick = { controller.undo() })
            IconButton(RedoGlyph, enabled = controller.canRedo, onClick = { controller.redo() })
            Box(contentAlignment = Alignment.Center) {
                IconButton(
                    ShareGlyph,
                    enabled = hasAudio && !controller.exporting,
                    onClick = { scope.launch { controller.exportMixdown() } },
                )
                // Export runs for a while on a long song; the button itself says so.
                if (controller.exporting) {
                    Box(Modifier.size(28.dp).border(2.dp, Colors.accent, CircleShape))
                }
            }
            Box {
                IconButton(MoreGlyph, onClick = { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename song", style = BodyStyle) },
                        onClick = { menuOpen = false; renaming = song.name },
                    )
                    DropdownMenuItem(
                        text = { Text("Import audio", style = BodyStyle) },
                        onClick = { menuOpen = false; ui.open(SongPanel.Takes) },
                    )
                    DropdownMenuItem(
                        text = { Text("Settings", style = BodyStyle) },
                        onClick = { menuOpen = false; onOpenSettings() },
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))
    }
}

/** What the engine is listening to right now: DUO / MIC, or the trouble. */
@Composable
private fun IoChip(controller: SongController, onClick: () -> Unit) {
    val st = controller.status
    val (label, color) = when {
        st.deviceLost -> "LOST" to Colors.red
        !st.inputOpen -> "NO INPUT" to Colors.dim
        controller.currentInput == InputDevice.Mic -> "MIC" to Colors.green
        else -> shortDeviceName((controller.currentInput as? InputDevice.Usb)?.name ?: "USB") to Colors.green
    }
    Row(
        Modifier
            .heightIn(min = Dimens.touch)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color)
        HSpace(6.dp)
        Text(label, style = LabelStyle.copy(color = color, letterSpacing = 1.sp), maxLines = 1)
    }
}

/** "M-Track Duo" -> "DUO": last word if it's short, else the first five letters. */
fun shortDeviceName(name: String): String {
    val short = name.split(" ").lastOrNull()?.takeIf { it.length in 2..5 }
        ?: name.take(5).trimEnd()
    return short.uppercase()
}
