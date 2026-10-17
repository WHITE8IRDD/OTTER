package com.otter.app.share

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.otter.app.R
import com.otter.app.data.model.CarouselItem
import com.otter.app.ui.components.BrutalButton
import com.otter.app.ui.theme.OtterDimens

/**
 * The small Quick Download window that opens *over* the app that shared the link.
 *
 * It is a compact dialog on a dimmed backdrop, not a screen of this app: the post's
 * files are listed as tappable cards, the user picks what to save, one button starts
 * the download, and the window closes back into Instagram/TikTok/whatever was below.
 */
@Composable
fun QuickDownloadWindow(
    state: QuickDownloadUiState,
    onToggle: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onSelectKind: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val scrimInteraction = androidx.compose.runtime.remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        // Keep the backdrop separate: a clickable ancestor merged the whole window's
        // labels into its dismiss action and could dismiss taps inside the card.
        Box(
            modifier = Modifier
                .matchParentSize()
                .testTag("quick-download-backdrop")
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(indication = null, interactionSource = scrimInteraction) { onDismiss() }
        )
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(16.dp)
                // A dialog, not a page: bounded height, the file list scrolls inside it.
                .heightIn(max = 580.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                WindowHeader(
                    platformLabel = (state as? QuickDownloadUiState.Ready)?.platformLabel.orEmpty(),
                    onClose = onDismiss
                )
                when (state) {
                    is QuickDownloadUiState.Loading -> LoadingBody()
                    is QuickDownloadUiState.Ready -> ReadyBody(
                        state = state,
                        onToggle = onToggle,
                        onSelectAll = onSelectAll,
                        onClear = onClear,
                        onSelectKind = onSelectKind,
                        onDownload = onDownload,
                        onDismiss = onDismiss
                    )
                    is QuickDownloadUiState.Error -> ErrorBody(
                        message = state.message,
                        onRetry = onRetry,
                        onDismiss = onDismiss
                    )
                    is QuickDownloadUiState.Dispatching -> LoadingBody(
                        label = stringResource(R.string.quick_download_starting)
                    )
                    is QuickDownloadUiState.Done -> LoadingBody(
                        label = stringResource(R.string.quick_download_started)
                    )
                }
            }
        }
    }
}

@Composable
private fun WindowHeader(platformLabel: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.quick_download_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black
        )
        if (platformLabel.isNotBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = platformLabel,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.content_desc_close)
            )
        }
    }
}

@Composable
private fun LoadingBody(label: String = "") {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = label.ifBlank { stringResource(R.string.quick_download_reading) },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ErrorBody(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message.ifBlank { stringResource(R.string.quick_download_read_failed) },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
            TextButton(onClick = onRetry) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.btn_try_again))
            }
        }
    }
}

@Composable
private fun ReadyBody(
    state: QuickDownloadUiState.Ready,
    onToggle: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onSelectKind: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val items = state.items
    val photoCount = items.count { !it.isVideo }
    val videoCount = items.size - photoCount
    val selectedCount = state.selection.count { it in items.indices }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (state.isEngineVideo) {
                stringResource(R.string.quick_download_video_item)
            } else {
                stringResource(R.string.carousel_kinds, photoCount, videoCount)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        if (state.isPartial) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        R.string.carousel_partial,
                        items.size,
                        state.expectedCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.carousel_selected, selectedCount, items.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Black
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSelectAll) {
                    Text(
                        stringResource(R.string.btn_select_all),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                TextButton(onClick = onClear) {
                    Text(
                        stringResource(R.string.btn_clear_format),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        // A mixed post gets the one-tap "just the pictures" / "just the clips" shortcuts.
        if (photoCount > 0 && videoCount > 0 && !state.isEngineVideo) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = false,
                    onClick = { onSelectKind(false) },
                    label = { Text(stringResource(R.string.carousel_photos_only)) }
                )
                FilterChip(
                    selected = false,
                    onClick = { onSelectKind(true) },
                    label = { Text(stringResource(R.string.carousel_videos_only)) }
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 300.dp)
                .testTag("quick-media-grid")
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (isVideo in listOf(false, true)) {
                val indices = items.indices.filter { items[it].isVideo == isVideo }
                if (indices.isNotEmpty()) {
                    item(key = "section-$isVideo", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = stringResource(if (isVideo) R.string.carousel_videos else R.string.carousel_photos) + " (${indices.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black
                        )
                    }
                    items(indices.size, key = { "slide-${indices[it]}" }) { position ->
                        val index = indices[position]
                        QuickSlideCard(index, items[index], state.selection.contains(index), onToggle)
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.quick_download_background_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
            BrutalButton(
                text = stringResource(R.string.btn_download_selected, selectedCount),
                onClick = onDownload,
                enabled = selectedCount > 0,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            )
        }
    }
}

/** One listed file: poster or picture, a play badge for clips, a check when picked. */
@Composable
private fun QuickSlideCard(
    index: Int,
    item: CarouselItem,
    isSelected: Boolean,
    onToggle: (Int) -> Unit
) {
    val previewUrl = if (item.isVideo) item.posterUrl else item.url
    val cardBorder = if (isSelected) {
        BorderStroke(OtterDimens.InkyBorder, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    }

    Box(modifier = Modifier.clickable { onToggle(index) }) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = cardBorder,
            elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 0.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (previewUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(previewUrl)
                            .apply { com.otter.app.engine.MediaPreviewHeaders.forUrl(previewUrl).forEach { (name, value) -> addHeader(name, value) } }
                            .size(360)
                            .crossfade(true)
                            .build(),
                        contentDescription = stringResource(
                            if (item.isVideo) R.string.content_desc_video else R.string.content_desc_image,
                            index + 1
                        ),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (item.isVideo) Icons.Default.VideoFile else Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (item.isVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                RoundedCornerShape(50)
                            )
                            .padding(6.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.content_desc_video_slide),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.content_desc_selected_slide),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (item.detail.isBlank()) "${index + 1}" else "${index + 1} · ${item.detail}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
