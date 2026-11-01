package org.paw.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.paw.app.SongController
import org.paw.app.model.Pattern
import org.paw.app.model.groupingsFor

// A pattern's time signature, how its beats group, how many bars it runs and
// how fine its grid is. Every change is one undo step.

private val UNITS = listOf(4, 8, 16)
private val STEPS = listOf(1, 2, 3, 4)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MeterPanel(controller: SongController, ui: SongUi, patternId: Int, modifier: Modifier = Modifier) {
    val p = controller.song.pattern(patternId)
    Column(modifier.fillMaxSize().background(Colors.bg)) {
        PanelHeader(p?.name ?: "Pattern", onClose = ui::close)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))
        if (p == null) return@Column

        fun set(
            beats: Int = p.beats,
            unit: Int = p.unit,
            bars: Int = p.bars,
            steps: Int = p.stepsPerBeat,
            grouping: List<Int> = p.grouping,
        ) = controller.setPatternLayout(p.id, beats, unit, bars, steps, grouping)

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${p.beats}/${p.unit}", style = ClockStyle.copy(fontSize = 36.sp))
                HSpace(14.dp)
                Text(
                    "${p.steps} steps",
                    style = MonoDimStyle.copy(fontSize = 13.sp),
                    modifier = Modifier.padding(bottom = 7.dp),
                )
            }
            VSpace(4.dp)
            GroupStrip(p, Modifier.fillMaxWidth().height(22.dp))
            VSpace(12.dp)

            SettingRow("Beats") {
                Stepper("${p.beats}", onDown = { set(beats = p.beats - 1) }, onUp = { set(beats = p.beats + 1) })
            }
            SettingRow("Unit") {
                Segmented(
                    UNITS.map { "$it" },
                    selected = UNITS.indexOf(p.unit),
                    onSelect = { set(unit = UNITS[it]) },
                    modifier = Modifier.weight(1f),
                )
            }
            val groupings = groupingsFor(p.beats)
            if (groupings.size > 1) {
                SettingRow("Grouping") {
                    FlowRow(
                        Modifier.weight(1f).padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (g in groupings) {
                            Toggle(g.joinToString("+"), g == p.grouping, Colors.accent, onClick = { set(grouping = g) })
                        }
                    }
                }
            }
            SettingRow("Bars") {
                Stepper("${p.bars}", onDown = { set(bars = p.bars - 1) }, onUp = { set(bars = p.bars + 1) })
            }
            SettingRow("Steps per beat") {
                Segmented(
                    STEPS.map { "$it" },
                    selected = STEPS.indexOf(p.stepsPerBeat),
                    onSelect = { set(steps = STEPS[it]) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The bar's beats as blocks, a wider gap between groups and each group's
 *  first beat lit: how the pattern is felt, drawn. */
@Composable
private fun GroupStrip(p: Pattern, modifier: Modifier) {
    Canvas(modifier) {
        val inner = 3.dp.toPx()
        val outer = 10.dp.toPx()
        val gaps = (p.beats - p.grouping.size) * inner + (p.grouping.size - 1) * outer
        val w = ((size.width - gaps) / p.beats).coerceAtMost(48.dp.toPx())
        val r = CornerRadius(3.dp.toPx())
        var x = 0f
        p.grouping.forEachIndexed { gi, g ->
            repeat(g) { i ->
                drawRoundRect(
                    if (i == 0) Colors.accent else Colors.accent.copy(alpha = 0.3f),
                    topLeft = Offset(x, 0f),
                    size = Size(w, size.height),
                    cornerRadius = r,
                )
                x += w + if (i < g - 1) inner else 0f
            }
            if (gi < p.grouping.lastIndex) x += outer
        }
    }
}
