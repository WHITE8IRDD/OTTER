package com.otter.app.ui.screens.history

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.otter.app.R
import com.otter.app.data.db.PausedDownload
import com.otter.app.data.model.DownloadItem
import com.otter.app.data.model.DownloadStatus
import android.widget.Toast
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import com.otter.app.ui.components.BrutalButton
import com.otter.app.ui.components.OtterCard
import com.otter.app.ui.components.SpeechBubble
import com.otter.app.ui.theme.ThemeViewModel
import com.otter.app.util.copyToClipboard
import com.otter.app.util.toReadableDuration
import com.otter.app.util.toReadableFileSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    themeViewModel: ThemeViewModel,
    onNavigateToHome: () -> Unit = {},
    viewModel: HistoryViewModel = viewModel()
) {
    val context = LocalContext.current
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val pausedDownloads by viewModel.pausedDownloads.collectAsStateWithLifecycle()
    val isDarkMode by themeViewModel.isDarkMode.collectAsStateWithLifecycle()
    val tintLogo by themeViewModel.tintLogo.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }
    var detailsItem by remember { mutableStateOf<DownloadItem?>(null) }

    val logoTint = when {
        tintLogo -> MaterialTheme.colorScheme.primary
        isDarkMode -> Color.White
        else -> Color.Unspecified
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.history_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.history_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (downloads.isNotEmpty() || pausedDownloads.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.history_details_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
                if (downloads.isNotEmpty()) {
                    TextButton(onClick = { showClearDialog = true }) {
                        Text(
                            text = stringResource(R.string.btn_clear_all),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (downloads.isEmpty() && pausedDownloads.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    MascotHistoryEmptyState(logoTint = logoTint, onNavigateToHome = onNavigateToHome)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    if (pausedDownloads.isNotEmpty()) {
                        item(key = "paused_header") {
                            Text(
                                text = stringResource(R.string.paused_downloads),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        items(
                            items = pausedDownloads,
                            key = { "paused_${it.url}" },
                            contentType = { "paused" }
                        ) { paused ->
                            PausedDownloadCard(
                                item = paused,
                                onResume = { viewModel.resumePausedDownload(paused) },
                                onStop = { viewModel.stopPausedDownload(paused) },
                                onDelete = { viewModel.deletePausedDownload(paused) }
                            )
                        }
                        item(key = "paused_divider", contentType = "paused") {
                            Spacer(modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                    items(
                        items = downloads,
                        key = { it.id },
                        contentType = { "history" }
                    ) { item ->
                        val fileExists = remember(item) { viewModel.fileExists(item) }
                        HistoryItemCard(
                            item = item,
                            fileExists = fileExists,
                            onOpenDetails = { detailsItem = item },
                            onOpen = { viewModel.openFile(context, item) },
                            onLocate = { viewModel.locateFile(context, item) },
                            onShare = { viewModel.shareFile(context, item) },
                            onRetry = { viewModel.retryDownload(item) },
                            onResume = { viewModel.resumeStoppedDownload(item) },
                            onDelete = { viewModel.deleteItemWithFile(item) }
                        )
                    }
                }
            }
        }
    }

    detailsItem?.let { item ->
        HistoryDetailsDialog(item = item, onDismiss = { detailsItem = null })
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.clear_history_title)) },
            text = { Text(stringResource(R.string.clear_history_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllHistory()
                    showClearDialog = false
                }) {
                    Text(stringResource(R.string.btn_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun MascotHistoryEmptyState(logoTint: Color, onNavigateToHome: () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        AnimatedDreamerMascot(
            contentDescription = stringResource(R.string.history_empty_title),
            tint = logoTint
        )
        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .shadow(2.dp, shape = RoundedCornerShape(16.dp))
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.history_empty_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.history_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        TextButton(
            onClick = onNavigateToHome
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.history_empty_cta),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
fun HistoryItemCard(
    item: DownloadItem,
    fileExists: Boolean,
    onOpen: () -> Unit,
    onLocate: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit = {},
    onResume: () -> Unit = {},
    onDelete: () -> Unit,
    onOpenDetails: () -> Unit = {}
) {
    val dateFormat = SimpleDateFormat.getDateInstance(SimpleDateFormat.MEDIUM, Locale.getDefault())
    val itemStatus = runCatching { DownloadStatus.valueOf(item.status) }.getOrDefault(DownloadStatus.COMPLETED)

    OtterCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenDetails) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.size(64.dp)) {
                    if (item.thumbnailUrl != null) {
                        AsyncImage(
                            model = item.thumbnailUrl,
                            contentDescription = stringResource(R.string.content_desc_thumbnail),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp)
                        )
                    } else {
                        Icon(
                            imageVector = if (item.isAudioOnly) Icons.Default.AudioFile else Icons.Default.VideoFile,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp).align(Alignment.Center),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (item.hasSubtitles) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.badge_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.uploader,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (itemStatus == DownloadStatus.COMPLETED) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = item.ext.uppercase(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (item.duration > 0) {
                                Text(
                                    text = "\u2022 ${item.duration.toReadableDuration()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (item.fileSize > 0) {
                                Text(
                                    text = "\u2022 ${item.fileSize.toReadableFileSize()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        // Show status badge for stopped/failed
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (itemStatus == DownloadStatus.STOPPED) Icons.Default.Stop else Icons.Default.Error,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            val statusText = if (itemStatus == DownloadStatus.STOPPED) {
                                val pct = (item.progressAtStop * 100).toInt()
                                stringResource(R.string.stopped_at_percent, pct)
                            } else {
                                stringResource(R.string.failed)
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (item.downloadedBytesAtStop > 0 && item.totalBytesAtStop > 0) {
                            Text(
                                text = "${item.downloadedBytesAtStop.toReadableFileSize()} / ${item.totalBytesAtStop.toReadableFileSize()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (itemStatus == DownloadStatus.STOPPED && item.progressAtStop > 0f) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { item.progressAtStop },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                    Text(
                        text = dateFormat.format(Date(item.downloadedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when (itemStatus) {
                    DownloadStatus.COMPLETED -> {
                        if (fileExists) {
                            IconButton(onClick = onOpen) {
                                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.content_desc_play), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = onLocate) {
                                Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.content_desc_show_folder), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = onShare) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.content_desc_share), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    DownloadStatus.STOPPED, DownloadStatus.FAILED -> {
                        if (item.status == DownloadStatus.STOPPED.name && item.canResume) {
                            BrutalButton(
                                text = stringResource(R.string.resume),
                                onClick = onResume,
                                backgroundColor = MaterialTheme.colorScheme.primary,
                                textColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        BrutalButton(
                            text = stringResource(R.string.retry_download),
                            onClick = onRetry,
                            backgroundColor = if (item.status == DownloadStatus.STOPPED.name && item.canResume)
                                MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                            textColor = if (item.status == DownloadStatus.STOPPED.name && item.canResume)
                                MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.content_desc_delete), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * Title, description and hashtags saved with a download, each with its own copy
 * button so the text can be reused without leaving the app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryDetailsDialog(
    item: DownloadItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val hashtags = item.hashtags.filter { it.isNotBlank() }.distinct()
    val hashtagText = hashtags.joinToString(" ")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.history_details_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CopyableField(
                    heading = stringResource(R.string.content_desc_copy_title),
                    value = item.title,
                    emptyText = item.title,
                    copyLabel = stringResource(R.string.content_desc_copy_title)
                )
                CopyableField(
                    heading = stringResource(R.string.section_description),
                    value = item.description,
                    emptyText = stringResource(R.string.history_no_description),
                    copyLabel = stringResource(R.string.content_desc_copy_description)
                )
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.section_hashtags),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        if (hashtagText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    context.copyToClipboard(
                                        hashtagText,
                                        context.getString(R.string.content_desc_copy_hashtags)
                                    )
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.content_desc_copy_hashtags),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    if (hashtagText.isNotBlank()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            hashtags.forEach { hashtag ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = hashtag,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.history_no_hashtags),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val everything = listOf(item.title, item.description, hashtagText)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                    Toast.makeText(context, context.getString(R.string.history_copied_all), Toast.LENGTH_SHORT).show()
                    context.copyToClipboard(everything, context.getString(R.string.history_details_title))
                }
            ) {
                Text(stringResource(R.string.btn_copy_all))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.content_desc_close))
            }
        }
    )
}

@Composable
private fun CopyableField(
    heading: String,
    value: String,
    emptyText: String,
    copyLabel: String
) {
    val context = LocalContext.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = heading,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (value.isNotBlank()) {
                IconButton(
                    onClick = { context.copyToClipboard(value, copyLabel) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = copyLabel,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value.ifBlank { emptyText },
            style = MaterialTheme.typography.bodyMedium,
            color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun PausedDownloadCard(
    item: PausedDownload,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit
) {
    OtterCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (item.downloadedBytes > 0 && item.totalBytes > 0) {
                Text(
                    text = "${item.downloadedBytes.toReadableFileSize()} / ${item.totalBytes.toReadableFileSize()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(R.string.tap_resume),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BrutalButton(
                    text = stringResource(R.string.resume),
                    onClick = onResume,
                    backgroundColor = MaterialTheme.colorScheme.primary,
                    textColor = MaterialTheme.colorScheme.onPrimary
                )
                BrutalButton(
                    text = stringResource(R.string.stop),
                    onClick = onStop,
                    backgroundColor = MaterialTheme.colorScheme.error,
                    textColor = MaterialTheme.colorScheme.onError
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.content_desc_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun AnimatedDreamerMascot(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    val infiniteTransition = rememberInfiniteTransition(label = "history_idle")
    val idleScaleState = infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_scale"
    )

    Image(
        painter = painterResource(R.drawable.mascot_dreamer),
        contentDescription = contentDescription,
        modifier = modifier
            .height(52.dp)
            .aspectRatio(768.85f / 505.61f)
            .graphicsLayer {
                scaleX = idleScaleState.value
                scaleY = idleScaleState.value
            },
        contentScale = ContentScale.FillBounds,
        colorFilter = ColorFilter.tint(tint)
    )
}
