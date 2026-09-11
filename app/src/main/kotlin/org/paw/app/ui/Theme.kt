package org.paw.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The visual language is deliberately plain. Dark, flat, one amber accent.
// Controls are rounded so they read as pressable; the workspace (lanes, panels,
// lists) stays square so it reads as surface. Every tappable thing is at least
// 40 dp tall.

/** Below this the layouts stack instead of sitting side by side: portrait on
 *  a phone, or any narrow window. */
val COMPACT_WIDTH = 640.dp

/** Landscape on a phone is short, not narrow: rows collapse to one. */
val SHORT_HEIGHT = 520.dp

object Colors {
    val bg = Color(0xFF121315)
    val panel = Color(0xFF1A1C1F)
    val raised = Color(0xFF24272B)
    val line = Color(0xFF30343A)
    val text = Color(0xFFECEAE4)
    val dim = Color(0xFF8F949B)
    val amber = Color(0xFFE8A33D)
    val red = Color(0xFFE5484D)
    val green = Color(0xFF5DAF62)
    val orange = Color(0xFFE0722B)
    val clip = Color(0xFF232830)
    val wave = Color(0xFFB9C0C7)
}

object Dimens {
    val touch: Dp = 40.dp
    val touchBig: Dp = 52.dp
    val corner: Dp = 6.dp
    val gap: Dp = 8.dp
    val pad: Dp = 12.dp
}

// Sans for words, mono for numbers that change under the eye (clock, dB,
// times): a moving number in a proportional face jitters sideways.

val TitleStyle = TextStyle(
    fontSize = 20.sp,
    fontWeight = FontWeight.Bold,
    color = Colors.text,
)

val NameStyle = TextStyle(
    fontSize = 15.sp,
    fontWeight = FontWeight.SemiBold,
    color = Colors.text,
)

val BodyStyle = TextStyle(
    fontSize = 14.sp,
    color = Colors.text,
    lineHeight = 20.sp,
)

val DimStyle = BodyStyle.copy(color = Colors.dim, fontSize = 13.sp)

/** Section headings inside panels: small caps, tracked out. */
val LabelStyle = TextStyle(
    fontSize = 11.sp,
    letterSpacing = 1.5.sp,
    fontWeight = FontWeight.SemiBold,
    color = Colors.dim,
)

val ButtonStyle = TextStyle(
    fontSize = 13.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.8.sp,
)

val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    color = Colors.text,
)

val MonoDimStyle = MonoStyle.copy(color = Colors.dim, fontSize = 12.sp)

val ClockStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 28.sp,
    fontWeight = FontWeight.Bold,
    color = Colors.text,
)
