package com.otter.app.ui.components

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.otter.app.R
import com.otter.app.data.model.AudioTrack
import com.otter.app.data.model.SubtitleTrack
import com.otter.app.data.model.VideoFormat
import com.otter.app.data.model.VideoInfo
import com.otter.app.ui.theme.OtterDimens
import com.otter.app.util.toReadableDuration

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
    onSubtitleFormatChange: (String) -> Unit = {},
    autoCaptionTracks: List<SubtitleTrack> = emptyList(),
    selectedAutoCaptionLangs: Set<String> = emptySet(),
    onToggleAutoCaption: (String) -> Unit = {},
    audioTracks: List<AudioTrack> = emptyList(),
    selectedAudioTrackFormatId: String? = null,
    onSelectAudioTrack: (AudioTrack?) -> Unit = {}
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
                onSubtitleFormatChange = onSubtitleFormatChange,
                autoCaptionTracks = autoCaptionTracks,
                selectedAutoCaptionLangs = selectedAutoCaptionLangs,
                onToggleAutoCaption = onToggleAutoCaption,
                audioTracks = audioTracks,
                selectedAudioTrackFormatId = selectedAudioTrackFormatId,
                onSelectAudioTrack = onSelectAudioTrack
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
    onSubtitleFormatChange: (String) -> Unit = {},
    autoCaptionTracks: List<SubtitleTrack> = emptyList(),
    selectedAutoCaptionLangs: Set<String> = emptySet(),
    onToggleAutoCaption: (String) -> Unit = {},
    audioTracks: List<AudioTrack> = emptyList(),
    selectedAudioTrackFormatId: String? = null,
    onSelectAudioTrack: (AudioTrack?) -> Unit = {}
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

        val hasMultipleAudio = audioTracks.size > 1
        if (hasMultipleAudio) {
            item(key = "audio_language") {
                AudioLanguageSection(
                    audioTracks = audioTracks,
                    selectedFormatId = selectedAudioTrackFormatId,
                    onSelect = onSelectAudioTrack
                )
            }
        }

        val hasSubtitles = subtitleTracks.isNotEmpty() || autoCaptionTracks.isNotEmpty()
        if (hasSubtitles) {
            item(key = "subtitle_header") {
                EnhancedSubtitleSection(
                    manualTracks = subtitleTracks,
                    autoTracks = autoCaptionTracks,
                    selectedManualLangs = selectedSubtitleLangs,
                    selectedAutoLangs = selectedAutoCaptionLangs,
                    onToggleManual = onToggleSubtitle,
                    onToggleAuto = onToggleAutoCaption,
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
                text = stringResource(R.string.choose_quality),
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
                contentDescription = stringResource(R.string.content_desc_thumbnail),
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
                            contentDescription = stringResource(R.string.content_desc_copy_uploader),
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
                contentDescription = stringResource(R.string.content_desc_copy_title),
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
            text = stringResource(R.string.section_hashtags),
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
                            contentDescription = stringResource(R.string.content_desc_copy),
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
            text = stringResource(R.string.section_tags),
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
                            contentDescription = stringResource(R.string.content_desc_copy),
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
            text = stringResource(R.string.section_description),
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
                contentDescription = stringResource(R.string.content_desc_copy_description),
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
                contentDescription = if (expanded) stringResource(R.string.show_less) else stringResource(R.string.show_more),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (expanded) stringResource(R.string.show_less) else stringResource(R.string.show_more),
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
                stringResource(R.string.btn_download_thumbnail),
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
                text = stringResource(R.string.toggle_save_thumbnail),
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
                        text = stringResource(R.string.carousel_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.carousel_empty_sub),
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
                    text = stringResource(R.string.carousel_selected, selectedIndices.size, videoInfo.carouselImages.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSelectAll) {
                        Text(stringResource(R.string.btn_select_all), style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = onDeselectAll) {
                        Text(stringResource(R.string.btn_clear_format), style = MaterialTheme.typography.labelLarge)
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
                                    contentDescription = stringResource(R.string.content_desc_image, index + 1),
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
                text = stringResource(R.string.btn_download_selected, selectedIndices.size),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioLanguageSection(
    audioTracks: List<AudioTrack>,
    selectedFormatId: String?,
    onSelect: (AudioTrack?) -> Unit
) {
    val deviceLang = java.util.Locale.getDefault().language
    val sorted = audioTracks.sortedBy {
        if (it.languageCode == deviceLang) 0 else if (it.isOriginal) 1 else 2
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.audio_language),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.audio_available, audioTracks.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            sorted.forEach { track ->
                val isSelected = track.formatId == selectedFormatId
                Surface(
                    onClick = {
                        onSelect(if (isSelected) null else track)
                    },
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
                            text = track.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnhancedSubtitleSection(
    manualTracks: List<SubtitleTrack>,
    autoTracks: List<SubtitleTrack>,
    selectedManualLangs: Set<String>,
    selectedAutoLangs: Set<String>,
    onToggleManual: (String) -> Unit,
    onToggleAuto: (String) -> Unit,
    embedSubtitles: Boolean,
    onToggleEmbed: (Boolean) -> Unit,
    subtitleFormat: String,
    onFormatChange: (String) -> Unit
) {
    var enabled by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(0) } // 0 = Manual, 1 = Auto
    var searchQuery by remember { mutableStateOf("") }

    val currentTracks = if (activeTab == 0) manualTracks else autoTracks
    val selectedLangs = if (activeTab == 0) selectedManualLangs else selectedAutoLangs
    val onToggle: (String) -> Unit = if (activeTab == 0) onToggleManual else onToggleAuto

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Master toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { enabled = !enabled }
                .padding(vertical = 4.dp)
        ) {
            ToggleSwitch(
                checked = enabled,
                onCheckedChange = { enabled = it },
                modifier = Modifier.padding(end = 12.dp)
            )
            Column {
                Text(
                    text = stringResource(R.string.toggle_download_subtitles),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                val manualCount = manualTracks.size
                val autoCount = autoTracks.size
                val parts = mutableListOf<String>()
                if (manualCount > 0) parts.add("$manualCount manual")
                if (autoCount > 0) parts.add("$autoCount auto-generated")
                val label = if (parts.isNotEmpty()) stringResource(R.string.subtitles_available, parts.joinToString(", ")) else ""
                if (label.isNotEmpty()) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        AnimatedVisibility(visible = enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Tab row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tabs = listOf(
                        "Manual (${manualTracks.size})" to 0,
                        "Auto (${autoTracks.size})" to 1
                    ).filter { it.first.contains(Regex("\\([1-9]\\d*\\)")) || (activeTab == it.second) }

                    tabs.forEach { (label, idx) ->
                        val isSelected = activeTab == idx
                        Surface(
                            onClick = { activeTab = idx; searchQuery = "" },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(
                                if (isSelected) OtterDimens.InkyBorder else 1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // Search bar
                if (currentTracks.size > 5) {
                    BrutalTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = stringResource(R.string.search_languages),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Select All / Deselect All
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.subtitle_selected, selectedLangs.size, currentTracks.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            currentTracks.forEach { t ->
                                if (!selectedLangs.contains(t.languageCode)) onToggle(t.languageCode)
                            }
                        }) {
                            Text(stringResource(R.string.btn_select_all), style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = {
                            selectedLangs.toList().forEach { onToggle(it) }
                        }) {
                            Text(stringResource(R.string.btn_clear_format), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // Language chips
                val filtered = if (searchQuery.isBlank()) currentTracks
                    else currentTracks.filter {
                        it.languageName.contains(searchQuery, ignoreCase = true) ||
                        it.languageCode.contains(searchQuery, ignoreCase = true)
                    }

                // Sort: device language first, then alphabetically
                val deviceLang = java.util.Locale.getDefault().language
                val sortedFiltered = filtered.sortedBy {
                    if (it.languageCode == deviceLang) 0 else 1
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    sortedFiltered.forEach { track ->
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
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // Embed toggle + format selector (shown when anything selected)
                val totalSelected = selectedManualLangs.size + selectedAutoLangs.size
                if (totalSelected > 0) {
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
                            text = stringResource(R.string.toggle_embed_subtitles),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.format_label),
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

                    // Summary
                    Text(
                        text = if (totalSelected == 1) stringResource(R.string.subtitle_summary_single) else stringResource(R.string.subtitle_summary, totalSelected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
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
    Toast.makeText(context, context.getString(R.string.copied_toast, label), Toast.LENGTH_SHORT).show()
}
