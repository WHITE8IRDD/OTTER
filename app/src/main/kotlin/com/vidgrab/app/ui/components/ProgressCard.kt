package com.vidgrab.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.vidgrab.app.ui.theme.OtterDimens

@Composable
fun ProgressCard(
    title: String,
    progress: Float,
    speedText: String,
    etaText: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 200f),
        label = "download_progress"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "sparkle")
    val sparkleAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sparkle_alpha"
    )

    BrutalCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Downloading\u2026",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(16.dp))

            val trackColor = MaterialTheme.colorScheme.primaryContainer
            val primaryColor = MaterialTheme.colorScheme.primary
            val secondaryColor = MaterialTheme.colorScheme.secondary
            val glowColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
            val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

            Box(modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val barHeight = size.height
                    val barWidth = size.width
                    val cornerPx = barHeight / 2f

                    drawRoundRect(
                        color = trackColor,
                        cornerRadius = CornerRadius(cornerPx)
                    )

                    val fillWidth = barWidth * animatedProgress.coerceIn(0f, 1f)
                    if (fillWidth > 0f) {
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(primaryColor, secondaryColor)
                            ),
                            size = Size(fillWidth, barHeight),
                            cornerRadius = CornerRadius(cornerPx)
                        )

                        val glow = glowColor.copy(alpha = sparkleAlpha * 0.5f)
                        drawRoundRect(
                            color = glow,
                            size = Size((fillWidth * 0.15f).coerceAtLeast(8.dp.toPx()), barHeight),
                            topLeft = Offset((fillWidth - fillWidth * 0.15f).coerceAtLeast(0f), 0f),
                            cornerRadius = CornerRadius(cornerPx)
                        )
                    }

                    drawRoundRect(
                        color = outlineColor,
                        topLeft = Offset(0f, 0f),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(cornerPx),
                        style = Stroke(width = OtterDimens.InkyBorder.toPx())
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (speedText.isNotEmpty()) {
                    Text(
                        text = " \u2022 $speedText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (etaText.isNotEmpty()) {
                    Text(
                        text = etaText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(OtterDimens.ButtonCorner),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
            ) {
                Text(text = "CANCEL", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
