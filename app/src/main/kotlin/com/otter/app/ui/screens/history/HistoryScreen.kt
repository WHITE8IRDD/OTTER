package com.otter.app.ui.screens.history

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.otter.app.R
import com.otter.app.data.model.DownloadItem
import com.otter.app.ui.components.BrutalButton
import com.otter.app.ui.components.BrutalCard
import com.otter.app.ui.components.SpeechBubble
import com.otter.app.ui.theme.ThemeViewModel
import com.otter.app.util.toReadableDuration
import com.otter.app.util.toReadableFileSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    themeViewModel: ThemeViewModel,
    viewModel: HistoryViewModel = viewModel()
) {
    val context = LocalContext.current
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val isDarkMode by themeViewModel.isDarkMode.collectAsStateWithLifecycle()
    val tintLogo by themeViewModel.tintLogo.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

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
                }
                if (downloads.isNotEmpty()) {
                    BrutalButton(
                        text = stringResource(R.string.btn_clear_all),
                        onClick = { showClearDialog = true },
                        backgroundColor = MaterialTheme.colorScheme.error,
                        textColor = MaterialTheme.colorScheme.onError
                    )
                }
            }

            if (downloads.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    MascotHistoryEmptyState(logoTint = logoTint)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(
                        items = downloads,
                        key = { it.id }
                    ) { item ->
                        HistoryItemCard(
                            item = item,
                            fileExists = remember(item) { viewModel.fileExists(item) },
                            onOpen = { viewModel.openFile(context, item) },
                            onLocate = { viewModel.locateFile(context, item) },
                            onShare = { viewModel.shareFile(context, item) },
                            onDelete = { viewModel.deleteItemWithFile(item) }
                        )
                    }
                }
            }
        }
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
private fun MascotHistoryEmptyState(logoTint: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "history_idle")
    val idleScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            painter = painterResource(id = R.drawable.otter_logo),
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer(scaleX = idleScale, scaleY = idleScale),
            tint = logoTint
        )
        SpeechBubble(
            text = stringResource(R.string.empty_state_videos),
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .height(80.dp),
            cornerRadius = 20.dp
        )
    }
}

@Composable
fun HistoryItemCard(
    item: DownloadItem,
    fileExists: Boolean,
    onOpen: () -> Unit,
    onLocate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat.getDateInstance(SimpleDateFormat.MEDIUM, Locale.getDefault())

    BrutalCard(modifier = Modifier.fillMaxWidth()) {
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.content_desc_delete), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
