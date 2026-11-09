package org.paw.app.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// A list that runs past its edge says so: the content fades into the
// background on each side where there is more to scroll to. Nothing shows
// when everything fits. Lists of big items also size them so the last one
// is cut part way, which the fade then makes obvious.

/**
 * Fades the edges of a scrolling area that has more content beyond them.
 * [color] is whatever sits behind the list. Apply it outside the scroll
 * modifier (before horizontalScroll or verticalScroll in the chain, or on a
 * lazy list) so it draws over the viewport rather than the content.
 */
internal fun Modifier.scrollCues(
    state: ScrollableState,
    vertical: Boolean,
    color: Color = Colors.bg,
    length: Dp = 28.dp,
): Modifier = drawWithContent {
    drawContent()
    val l = length.toPx().coerceAtMost((if (vertical) size.height else size.width) / 3f)
    val clear = color.copy(alpha = 0f)
    if (state.canScrollBackward) {
        if (vertical) {
            drawRect(Brush.verticalGradient(listOf(color, clear), 0f, l), size = Size(size.width, l))
        } else {
            drawRect(Brush.horizontalGradient(listOf(color, clear), 0f, l), size = Size(l, size.height))
        }
    }
    if (state.canScrollForward) {
        if (vertical) {
            val top = size.height - l
            drawRect(Brush.verticalGradient(listOf(clear, color), top, size.height), Offset(0f, top), Size(size.width, l))
        } else {
            val left = size.width - l
            drawRect(Brush.horizontalGradient(listOf(clear, color), left, size.width), Offset(left, 0f), Size(l, size.height))
        }
    }
}
