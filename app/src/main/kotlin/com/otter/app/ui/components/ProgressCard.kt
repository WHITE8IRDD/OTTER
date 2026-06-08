package com.otter.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.otter.app.R
import com.otter.app.data.model.DownloadPhase
import com.otter.app.data.model.DownloadState
import com.otter.app.ui.theme.OtterDimens

@Composable
fun ProgressCard(
    title: String,
    state: DownloadState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.primaryContainer
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val glowColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

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

    OtterCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            when (state) {
                is DownloadState.Downloading -> {
                    val animatedProgress by animateFloatAsState(
                        targetValue = state.progress,
                        animationSpec = spring(dampingRatio = 0.6f, stiffness = 200f),
                        label = "download_progress"
                    )

                    Text(
                        text = when (state.phase) {
                            DownloadPhase.PREPARING -> stringResource(R.string.progress_preparing)
                            DownloadPhase.DOWNLOADING -> stringResource(R.string.progress_downloading)
                            DownloadPhase.MERGING -> stringResource(R.string.progress_merging)
                            DownloadPhase.POST_PROCESSING -> stringResource(R.string.progress_finalizing)
                            DownloadPhase.FINISHING -> stringResource(R.string.progress_finishing)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Box(modifier = Modifier.fillMaxWidth().height(14.dp)) {
                        Canvas(modifier = Modifier.matchParentSize()) {
                            val barHeight = size.height
                            val barWidth = size.width
                            val cornerPx = barHeight / 2f

                            drawRoundRect(color = trackColor, cornerRadius = CornerRadius(cornerPx))

                            val fillWidth = barWidth * animatedProgress.coerceIn(0f, 1f)
                            if (fillWidth > 0f) {
                                drawRoundRect(
                                    brush = Brush.horizontalGradient(listOf(primaryColor, secondaryColor)),
                                    size = Size(fillWidth, barHeight),
                                    cornerRadius = CornerRadius(cornerPx)
                                )
                                drawRoundRect(
                                    color = glowColor.copy(alpha = sparkleAlpha * 0.5f),
                                    size = Size((fillWidth * 0.15f).coerceAtLeast(8.dp.toPx()), barHeight),
                                    topLeft = Offset((fillWidth - fillWidth * 0.15f).coerceAtLeast(0f), 0f),
                                    cornerRadius = CornerRadius(cornerPx)
                                )
                            }
                            drawRoundRect(
                                color = outlineColor, topLeft = Offset(0f, 0f),
                                size = Size(barWidth, barHeight), cornerRadius = CornerRadius(cornerPx),
                                style = Stroke(width = OtterDimens.InkyBorder.toPx())
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (state.totalBytes > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(text = state.percentText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.weight(1f))
                            Text(text = state.sizeText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (state.speedBytesPerSec > 0) {
                            Text(text = state.speedText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (state.etaSeconds > 0) {
                            Text(text = stringResource(R.string.progress_eta, state.etaText), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onPause,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(OtterDimens.ButtonCorner),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                        ) {
                            Text(text = stringResource(R.string.pause), style = MaterialTheme.typography.labelLarge)
                        }
                        Button(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(OtterDimens.ButtonCorner),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                        ) {
                            Text(text = stringResource(R.string.stop), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                is DownloadState.Paused -> {
                    val pausedProgress by animateFloatAsState(
                        targetValue = state.progress,
                        animationSpec = spring(dampingRatio = 0.6f, stiffness = 200f),
                        label = "paused_progress"
                    )

                    Text(
                        text = stringResource(R.string.progress_paused),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Box(modifier = Modifier.fillMaxWidth().height(14.dp)) {
                        Canvas(modifier = Modifier.matchParentSize()) {
                            val barHeight = size.height
                            val barWidth = size.width
                            val cornerPx = barHeight / 2f

                            drawRoundRect(color = trackColor, cornerRadius = CornerRadius(cornerPx))

                            val fillWidth = barWidth * pausedProgress.coerceIn(0f, 1f)
                            if (fillWidth > 0f) {
                                drawRoundRect(
                                    color = Color(0xFFFFA000),
                                    size = Size(fillWidth, barHeight),
                                    cornerRadius = CornerRadius(cornerPx)
                                )
                            }
                            drawRoundRect(
                                color = outlineColor, topLeft = Offset(0f, 0f),
                                size = Size(barWidth, barHeight), cornerRadius = CornerRadius(cornerPx),
                                style = Stroke(width = OtterDimens.InkyBorder.toPx())
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (state.totalBytes > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(
                        text = stringResource(R.string.tap_resume),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onResume,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(OtterDimens.ButtonCorner),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                        ) {
                            Text(text = stringResource(R.string.resume), style = MaterialTheme.typography.labelLarge)
                        }
                        Button(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(OtterDimens.ButtonCorner),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                        ) {
                            Text(text = stringResource(R.string.stop), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                else -> {}
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }
}
