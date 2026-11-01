package org.paw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import org.paw.app.SongController
import org.paw.app.model.PluginInfo
import org.paw.app.model.PluginPort

// A track's effect chain, top to bottom in signal order. Each effect is a
// card of its own controls; removing one is an undo step, so it does not ask.
// Adding opens the catalog in place of the cards.

@Composable
internal fun InspectorFxTab(controller: SongController, trackId: Int, modifier: Modifier) {
    val track = controller.song.track(trackId) ?: return
    var picking by remember(trackId) { mutableStateOf(false) }
    PanelBody(modifier) {
        if (picking) {
            EffectPicker(controller, onPick = { plugin ->
                controller.addEffect(trackId, plugin)
                picking = false
            }, onCancel = { picking = false })
            return@PanelBody
        }
        track.effects.forEachIndexed { index, _ ->
            EffectCard(controller, trackId, index)
            VSpace(8.dp)
        }
        AppButton(
            "+ Add effect",
            onClick = { picking = true },
            accent = Colors.accent,
            filled = track.effects.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
            height = 44.dp,
        )
    }
}

@Composable
private fun EffectPicker(controller: SongController, onPick: (PluginInfo) -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Add effect", style = NameStyle, modifier = Modifier.weight(1f))
        AppButton("Cancel", accent = Colors.dim, onClick = onCancel)
    }
    val catalog = controller.catalog
    if (catalog.isEmpty()) {
        Text("Scanning effects…", style = DimStyle, modifier = Modifier.padding(vertical = 12.dp))
        return
    }
    val (builtins, external) = catalog.partition { it.isBuiltin }
    Column(Modifier.fillMaxWidth().panel()) {
        builtins.forEachIndexed { i, it ->
            if (i > 0) RowLine()
            PickRow(it, onPick)
        }
    }
    if (external.isNotEmpty()) {
        SectionLabel("Effects folder", Modifier.padding(top = 16.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().panel()) {
            external.forEachIndexed { i, it ->
                if (i > 0) RowLine()
                PickRow(it, onPick)
            }
        }
    }
}

@Composable
private fun RowLine() = androidx.compose.foundation.layout.Box(
    Modifier.fillMaxWidth().height(1.dp).background(Colors.line),
)

@Composable
private fun PickRow(plugin: PluginInfo, onPick: (PluginInfo) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable { onPick(plugin) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            effectName(plugin.name),
            style = BodyStyle,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${plugin.ports.size} controls · ${if (plugin.stereo) "stereo" else "mono"}",
            style = MonoDimStyle,
        )
        HSpace(12.dp)
        Text("+", style = TitleStyle.copy(color = Colors.accent))
    }
}

private fun effectName(name: String) = name.removePrefix("PAW ").removePrefix("paw ")

@Composable
private fun EffectCard(controller: SongController, trackId: Int, index: Int) {
    val track = controller.song.track(trackId) ?: return
    val effect = track.effects.getOrNull(index) ?: return
    val plugin = controller.plugin(effect.pluginId)
    val bypassed = effect.bypass
    val many = track.effects.size > 1

    Column(Modifier.fillMaxWidth().panel().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${index + 1}",
                style = MonoDimStyle,
                modifier = Modifier.width(18.dp),
            )
            Text(
                plugin?.let { effectName(it.name) } ?: (effect.pluginId.substringAfterLast(':') + " (missing)"),
                style = NameStyle.copy(color = if (plugin == null || bypassed) Colors.dim else Colors.text),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (plugin != null) {
                Toggle(if (bypassed) "Off" else "On", !bypassed, Colors.green, onClick = {
                    controller.setEffectState(trackId, index, !bypassed, effect.mix)
                }, modifier = Modifier.width(56.dp))
            }
        }
        if (plugin == null) {
            Text("Plugin file missing, kept but not running", style = DimStyle, modifier = Modifier.padding(vertical = 6.dp))
        } else {
            plugin.ports.forEachIndexed { portIndex, port ->
                PortRow(controller, trackId, index, portIndex, port, effect.values.getOrNull(portIndex) ?: port.def)
            }
            MixRow(controller, trackId, index, bypassed, effect.mix)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (many) {
                // Chain order: the slot moves, the chain rebuilds (brief state reset).
                AppButton("▲", enabled = index > 0, accent = Colors.dim, fontSize = 11.sp,
                    modifier = Modifier.width(44.dp), padding = 0.dp,
                    onClick = { controller.moveEffect(trackId, index, -1) })
                AppButton("▼", enabled = index < track.effects.lastIndex, accent = Colors.dim, fontSize = 11.sp,
                    modifier = Modifier.width(44.dp), padding = 0.dp,
                    onClick = { controller.moveEffect(trackId, index, +1) })
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            AppButton("Remove", accent = Colors.dim, onClick = { controller.removeEffect(trackId, index) })
        }
    }
}

/** A control's label, its slider and its value. 40 dp a row, so a six
 *  control effect is one card on a phone screen. */
@Composable
private fun ControlRow(
    label: String,
    value: Float,
    readout: String,
    onStart: () -> Unit,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit,
    accent: androidx.compose.ui.graphics.Color = Colors.accent,
) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = DimStyle,
            modifier = Modifier.width(88.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MiniSlider(
            value = value,
            accent = accent,
            onStart = onStart,
            onChange = onChange,
            onCommit = onCommit,
            onReset = onReset,
            modifier = Modifier.weight(1f),
        )
        HSpace(8.dp)
        ValueReadout(
            readout,
            Modifier.width(72.dp),
            style = MonoStyle.copy(textAlign = TextAlign.End),
            onReset = onReset,
        )
    }
}

@Composable
private fun MixRow(controller: SongController, trackId: Int, index: Int, bypassed: Boolean, mix: Float) {
    // Wet/dry under the plugin's own controls; 100% = fully wet.
    var live by remember { mutableStateOf<Float?>(null) }
    val shown = live ?: mix
    ControlRow(
        label = "Wet",
        value = shown,
        readout = "%.0f%%".format(shown * 100),
        accent = Colors.green,
        onStart = { controller.beginGesture() },
        onChange = { pos ->
            live = pos
            controller.previewEffectMix(trackId, index, bypassed, pos)
        },
        onCommit = {
            live?.let { controller.setEffectState(trackId, index, bypassed, it, undoable = false) }
            live = null
        },
        onReset = {
            live = null
            controller.setEffectState(trackId, index, bypassed, 1f)
        },
    )
}

@Composable
private fun PortRow(
    controller: SongController,
    trackId: Int,
    index: Int,
    portIndex: Int,
    port: PluginPort,
    value: Float,
) {
    var live by remember { mutableStateOf<Float?>(null) }
    val (label, unit) = splitPortName(port.name)
    ControlRow(
        label = label,
        value = live ?: portToPos(port, value),
        readout = formatPortValue(port, live?.let { posToPort(port, it) } ?: value, unit),
        onStart = { controller.beginGesture() },
        onChange = { p ->
            live = p
            controller.previewEffectValue(trackId, index, portIndex, posToPort(port, p))
        },
        onCommit = {
            live?.let { controller.setEffectValue(trackId, index, portIndex, posToPort(port, it)) }
            live = null
        },
        onReset = {
            live = null
            controller.beginGesture()
            controller.setEffectValue(trackId, index, portIndex, port.def)
        },
    )
}

/** "Threshold (dB)" -> "Threshold" and "dB": the unit rides with the value. */
private fun splitPortName(name: String): Pair<String, String> {
    val open = name.lastIndexOf(" (")
    if (open < 0 || !name.endsWith(")")) return name to ""
    return name.substring(0, open) to name.substring(open + 2, name.length - 1)
}

// Slider position <-> port value, honoring the LADSPA log/integer hints.
private fun portToPos(port: PluginPort, value: Float): Float {
    if (port.toggled) return if (value > 0.5f) 1f else 0f
    return if (port.logarithmic && port.min > 0f) {
        (ln(value.coerceIn(port.min, port.max) / port.min) / ln(port.max / port.min))
    } else {
        ((value - port.min) / (port.max - port.min)).coerceIn(0f, 1f)
    }
}

private fun posToPort(port: PluginPort, pos: Float): Float {
    if (port.toggled) return if (pos > 0.5f) 1f else 0f
    var v = if (port.logarithmic && port.min > 0f) {
        port.min * (port.max / port.min).pow(pos)
    } else {
        port.min + pos * (port.max - port.min)
    }
    if (port.integer) v = round(v)
    return v.coerceIn(port.min, port.max)
}

private fun formatPortValue(port: PluginPort, value: Float, unit: String): String {
    val number = when {
        port.toggled -> return if (value > 0.5f) "On" else "Off"
        port.integer -> "%.0f".format(value)
        abs(value) >= 100f -> "%.0f".format(value)
        else -> "%.1f".format(value)
    }
    return when {
        unit.isEmpty() -> number
        unit == "%" || unit.startsWith(":") -> number + unit
        else -> "$number $unit"
    }
}
