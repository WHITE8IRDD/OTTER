package com.vidgrab.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vidgrab.app.R
import com.vidgrab.app.data.model.SubtitleTrack
import com.vidgrab.app.data.model.VideoFormat
import com.vidgrab.app.data.model.VideoInfo
import com.vidgrab.app.ui.theme.OtterDimens
import com.vidgrab.app.util.toReadableDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatSheet(
    videoInfo: VideoInfo?,
    alsoSaveThumbnail: Boolean,
    onToggleThumbnail: () -> Unit,
    onThumbnailOnly: () -> Unit,
    onFormatSelected: (VideoFormat) -> Unit,
    selectedCarouselIndices: Set<Int> = emptySet(),
    onToggleCarouselIndex: (Int) -> Unit = {},
    onSelectAllCarousel: () -> Unit = {},
    onDeselectAllCarousel: () -> Unit = {},
    onDownloadCarousel: () -> Unit = {},
    onDismiss: () -> Unit,
    subtitleTracks: List<SubtitleTrack> = emptyList(),
    selectedSubtitleLangs: Set<String> = emptySet(),
    onToggleSubtitle: (String) -> Unit = {},
    embedSubtitles: Boolean = false,
    onToggleEmbedSubtitles: (Boolean) -> Unit = {},
    subtitleFormat: String = "srt",
    onSubtitleFormatChange: (String) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    var visible by remember { mutableStateOf(false) }
    val popScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.92f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pop_scale"
    )

    LaunchedEffect(Unit) { visible = true }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        if (videoInfo == null) {
            SkeletonLoader()
        } else if (videoInfo.isCarousel) {
            CarouselGalleryContent(
                videoInfo = videoInfo,
                selectedIndices = selectedCarouselIndices,
                onToggleIndex = onToggleCarouselIndex,
                onSelectAll = onSelectAllCarousel,
                onDeselectAll = onDeselectAllCarousel,
                onDownload = onDownloadCarousel,
                popScale = popScale
            )
        } else {
            FormatSheetContent(
                videoInfo = videoInfo,
                alsoSaveThumbnail = alsoSaveThumbnail,
                onToggleThumbnail = onToggleThumbnail,
                onThumbnailOnly = onThumbnailOnly,
                onFormatSelected = onFormatSelected,
                popScale = popScale,
                subtitleTracks = subtitleTracks,
                selectedSubtitleLangs = selectedSubtitleLangs,
                onToggleSubtitle = onToggleSubtitle,
                embedSubtitles = embedSubtitles,
                onToggleEmbedSubtitles = onToggleEmbedSubtitles,
                subtitleFormat = subtitleFormat,
                onSubtitleFormatChange = onSubtitleFormatChange
            )
        }
    }
}

@Composable
private fun FormatSheetContent(
    videoInfo: VideoInfo,
    alsoSaveThumbnail: Boolean,
    onToggleThumbnail: () -> Unit,
    onThumbnailOnly: () -> Unit,
    onFormatSelected: (VideoFormat) -> Unit,
    popScale: Float = 1f,
    subtitleTracks: List<SubtitleTrack> = emptyList(),
    selectedSubtitleLangs: Set<String> = emptySet(),
    onToggleSubtitle: (String) -> Unit = {},
    embedSubtitles: Boolean = false,
    onToggleEmbedSubtitles: (Boolean) -> Unit = {},
    subtitleFormat: String = "srt",
    onSubtitleFormatChange: (String) -> Unit = {}
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.graphicsLayer(scaleX = popScale, scaleY = popScale)
    ) {
        item(key = "header") {
            VideoInfoHeader(videoInfo = videoInfo)
        }

        if (videoInfo.tags.isNotEmpty() || videoInfo.hashtags.isNotEmpty()) {
            item(key = "tags") {
                TagsSection(videoInfo = videoInfo)
            }
        }

        val descriptionText = videoInfo.description
        if (!descriptionText.isNullOrEmpty()) {
            item(key = "description") {
                DescriptionSection(description = descriptionText)
            }
        }

        item(key = "divider1") {
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                thickness = OtterDimens.InkyBorder * 0.5f,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )
        }

        item(key = "thumbnail_options") {
            ThumbnailOptionsSection(
                alsoSaveThumbnail = alsoSaveThumbnail,
                onToggleThumbnail = onToggleThumbnail,
                onThumbnailOnly = onThumbnailOnly
            )
        }

        item(key = "divider2") {
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                thickness = OtterDimens.InkyBorder * 0.5f,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )
        }

        if (subtitleTracks.isNotEmpty()) {
            item(key = "subtitle_header") {
                SubtitleSection(
                    tracks = subtitleTracks,
                    selectedLangs = selectedSubtitleLangs,
                    onToggle = onToggleSubtitle,
                    embedSubtitles = embedSubtitles,
                    onToggleEmbed = onToggleEmbedSubtitles,
                    subtitleFormat = subtitleFormat,
                    onFormatChange = onSubtitleFormatChange
                )
            }
        }

        item(key = "divider3") {
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                thickness = OtterDimens.InkyBorder * 0.5f,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )
        }

        item(key = "format_header") {
            Text(
                text = "CHOOSE QUALITY",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        items(videoInfo.formats, key = { it.formatId }) { format ->
            FormatItem(
                format = format,
                onClick = { onFormatSelected(format) }
            )
        }
    }
}

@Composable
private fun VideoInfoHeader(videoInfo: VideoInfo) {
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (videoInfo.thumbnailUrl != null) {
            AsyncImage(
                model = videoInfo.thumbnailUrl,
                contentDescription = "Thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = videoInfo.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (videoInfo.uploader.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = videoInfo.uploader,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    IconButton(
                        onClick = {
                            copyToClipboard(context, videoInfo.uploader, "Uploader")
                        },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy uploader",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }
            if (videoInfo.duration > 0) {
                Text(
                    text = videoInfo.duration.toReadableDuration(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        IconButton(
            onClick = {
                copyToClipboard(context, videoInfo.title, "Title")
            }
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "Copy title",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(videoInfo: VideoInfo) {
    val context = LocalContext.current

    if (videoInfo.hashtags.isNotEmpty()) {
        Text(
            text = "Hashtags",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            videoInfo.hashtags.forEach { hashtag ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    modifier = Modifier.clickable {
                        copyToClipboard(context, hashtag, "Hashtag")
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = hashtag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }

    if (videoInfo.tags.isNotEmpty()) {
        Text(
            text = "Tags",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            videoInfo.tags.forEach { tag ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                    modifier = Modifier.clickable {
                        copyToClipboard(context, tag, "Tag")
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DescriptionSection(description: String) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val isLong = description.length > 150

    Text(
        text = "Description",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )

    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = if (expanded || !isLong) description else description.take(150) + "...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = { copyToClipboard(context, description, "Description") },
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "Copy description",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }

    if (isLong) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.padding(top = 0.dp)
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Show less" else "Show more",
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (expanded) "Show less" else "Show more",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ThumbnailOptionsSection(
    alsoSaveThumbnail: Boolean,
    onToggleThumbnail: () -> Unit,
    onThumbnailOnly: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onThumbnailOnly,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(OtterDimens.ButtonCorner),
            border = BorderStroke(OtterDimens.InkyBorder, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        ) {
            Icon(
                Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "DOWNLOAD THUMBNAIL ONLY",
                style = MaterialTheme.typography.labelLarge
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleThumbnail() }
                .padding(vertical = 4.dp)
        ) {
            ToggleSwitch(
                checked = alsoSaveThumbnail,
                onCheckedChange = { onToggleThumbnail() },
                modifier = Modifier.padding(end = 12.dp)
            )
            Text(
                text = "Also save thumbnail with video",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun CarouselGalleryContent(
    videoInfo: VideoInfo,
    selectedIndices: Set<Int>,
    onToggleIndex: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onDownload: () -> Unit,
    popScale: Float = 1f
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(scaleX = popScale, scaleY = popScale)
    ) {
        VideoInfoHeader(videoInfo = videoInfo)

        if (videoInfo.carouselImages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No carousel images available",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Add Instagram cookies in Settings to view and download carousel images",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.height(32.dp))
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${selectedIndices.size}/${videoInfo.carouselImages.size} selected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSelectAll) {
                        Text("SELECT ALL", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = onDeselectAll) {
                        Text("CLEAR", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 1000.dp)
            ) {
                items(videoInfo.carouselImages, key = { it }) { imageUrl ->
                    val index = videoInfo.carouselImages.indexOf(imageUrl)
                    val isSelected = selectedIndices.contains(index)

                    val cardBorder = if (isSelected) {
                        BorderStroke(OtterDimens.InkyBorder, MaterialTheme.colorScheme.primary)
                    } else {
                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleIndex(index) }
                    ) {
                        androidx.compose.material3.Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = cardBorder,
                            elevation = androidx.compose.material3.CardDefaults.cardElevation(
                                defaultElevation = if (isSelected) 4.dp else 0.dp
                            )
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                AsyncImage(
                                    model = imageUrl,
                                    contentDescription = "Image ${index + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
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
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            BrutalButton(
                text = "Download Selected (${selectedIndices.size})",
                onClick = onDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                enabled = selectedIndices.isNotEmpty()
            )
        }
    }
}

@Composable
private fun ToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val animDuration = 200
    val infiniteTransition = rememberInfiniteTransition(label = "toggle")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(animDuration, delayMillis = animDuration),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val containerColor = if (checked) {
        MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(OtterDimens.InkyBorder, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = modifier.size(44.dp, 24.dp)
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.padding(horizontal = 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer {
                        translationX = if (checked) 20.dp.toPx() else 0f
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline)
            )
        }
    }
}

@Composable
private fun SkeletonLoader() {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer"
    )

    val shimmerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(shape)
                    .background(shimmerColor)
            )
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(16.dp)
                        .clip(shape)
                        .background(shimmerColor)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(12.dp)
                        .clip(shape)
                        .background(shimmerColor)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.3f)
                        .height(12.dp)
                        .clip(shape)
                        .background(shimmerColor)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(shimmerColor)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(shape)
                .background(shimmerColor)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(12.dp)
                .clip(shape)
                .background(shimmerColor)
        )

        Spacer(modifier = Modifier.height(8.dp))

        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(OtterDimens.ButtonCorner))
                    .background(shimmerColor)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SubtitleSection(
    tracks: List<SubtitleTrack>,
    selectedLangs: Set<String>,
    onToggle: (String) -> Unit,
    embedSubtitles: Boolean,
    onToggleEmbed: (Boolean) -> Unit,
    subtitleFormat: String,
    onFormatChange: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "SUBTITLES & CAPTIONS",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        if (tracks.size > 10) {
            BrutalTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Search languages\u2026",
                modifier = Modifier.fillMaxWidth()
            )
        }

        val filtered = if (searchQuery.isBlank()) tracks
            else tracks.filter {
                it.languageName.contains(searchQuery, ignoreCase = true) ||
                it.languageCode.contains(searchQuery, ignoreCase = true)
            }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            filtered.forEach { track ->
                val isSelected = selectedLangs.contains(track.languageCode)
                Surface(
                    onClick = { onToggle(track.languageCode) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        if (isSelected) OtterDimens.InkyBorder else 1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = track.languageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        if (track.isAutoGenerated) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "AUTO",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                       else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        if (selectedLangs.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleEmbed(!embedSubtitles) }
                    .padding(vertical = 4.dp)
            ) {
                ToggleSwitch(
                    checked = embedSubtitles,
                    onCheckedChange = onToggleEmbed,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    text = "Embed subtitles in video",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Format:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(end = 8.dp)
                )
                listOf("srt", "vtt", "ass").forEach { fmt ->
                    val isFmtSelected = subtitleFormat == fmt
                    Surface(
                        onClick = { onFormatChange(fmt) },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isFmtSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            if (isFmtSelected) 1.5.dp else 1.dp,
                            if (isFmtSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text(
                            text = fmt.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isFmtSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatItem(
    format: VideoFormat,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        format.isAudioOnly -> MaterialTheme.colorScheme.tertiaryContainer
        (format.height ?: 0) >= 1080 -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.secondary
    }
    val onBackgroundColor = when {
        format.isAudioOnly -> MaterialTheme.colorScheme.onTertiaryContainer
        (format.height ?: 0) >= 1080 -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSecondary
    }

    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(OtterDimens.ButtonCorner),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = backgroundColor,
            contentColor = onBackgroundColor
        ),
        border = BorderStroke(OtterDimens.InkyBorder, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = if (format.isAudioOnly) Icons.Default.AudioFile else Icons.Default.VideoFile,
            contentDescription = null,
            tint = onBackgroundColor
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = format.qualityLabel,
                style = MaterialTheme.typography.labelLarge,
                color = onBackgroundColor
            )
            if (!format.isAudioOnly && format.fps != null) {
                Text(
                    text = "${format.fps.toInt()} fps",
                    style = MaterialTheme.typography.bodySmall,
                    color = onBackgroundColor.copy(alpha = 0.7f)
                )
            }
        }
        Text(
            text = format.filesizeText,
            style = MaterialTheme.typography.bodySmall,
            color = onBackgroundColor.copy(alpha = 0.7f)
        )
    }
}

private fun copyToClipboard(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "${label} copied", Toast.LENGTH_SHORT).show()
}
