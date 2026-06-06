package com.vidgrab.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vidgrab.app.ui.theme.OtterDimens

@Composable
fun SpeechBubble(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    pointerAtBottom: Boolean = true,
    cornerRadius: Dp = 16.dp
) {
    val bgColor = backgroundColor
    val brdColor = borderColor
    val txtColor = textColor
    val style = MaterialTheme.typography.bodyMedium

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val bubbleWidth = size.width
            val bubbleHeight = size.height
            val pointerHeight = 12.dp.toPx()
            val pointerWidth = 16.dp.toPx()
            val cornerPx = cornerRadius.toPx()

            val rectHeight = bubbleHeight - pointerHeight
            val rectTop = if (pointerAtBottom) 0f else pointerHeight

            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(0f, rectTop, bubbleWidth, rectTop + rectHeight),
                        cornerRadius = CornerRadius(cornerPx)
                    )
                )
                if (pointerAtBottom) {
                    val cx = bubbleWidth / 2f
                    val cy = bubbleHeight
                    moveTo(cx - pointerWidth / 2f, rectHeight)
                    lineTo(cx, cy)
                    lineTo(cx + pointerWidth / 2f, rectHeight)
                    close()
                } else {
                    val cx = bubbleWidth / 2f
                    val cy = 0f
                    moveTo(cx - pointerWidth / 2f, pointerHeight)
                    lineTo(cx, cy)
                    lineTo(cx + pointerWidth / 2f, pointerHeight)
                    close()
                }
            }

            drawPath(path, bgColor)
            drawPath(path, brdColor, style = Stroke(width = OtterDimens.InkyBorder.toPx()))
        }

        Text(
            text = text,
            style = style,
            color = txtColor,
            modifier = Modifier
                .matchParentSize()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = if (pointerAtBottom) 16.dp else (12.dp + 16.dp),
                    bottom = if (pointerAtBottom) (12.dp + 16.dp) else 16.dp
                ),
            softWrap = true
        )
    }
}
