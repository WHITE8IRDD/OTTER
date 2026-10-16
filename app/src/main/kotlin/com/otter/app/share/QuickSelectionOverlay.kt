package com.otter.app.share

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.otter.app.R
import com.otter.app.data.model.DownloadState
import com.otter.app.data.repository.DownloadRepository
import com.otter.app.data.model.VideoInfo
import com.otter.app.engine.MediaSlide
import com.otter.app.ui.components.BrutalButton
import com.otter.app.ui.components.CarouselGalleryContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The selection window the share sheet opens over the app the link came from.
 *
 * It exists because "Quick Download" on a gallery post used to launch the main window: the share
 * action navigated the user out of Instagram, into a different app's screen, to choose slides for a
 * post they were already looking at. Everything the main flow does is kept — same fetch, same
 * picker, same slide-saving code — and only the *host* is different: a translucent activity of this
 * app over the previous one, which closes back into it. Android gives an app no way to inject a view
 * inside another app (that is a deliberate restriction, not a missing API), so a floating window of
 * our own that never leaves the previous app's task in front of it is the closest thing to the
 * requested behaviour that the platform allows.
 */
@Composable
fun QuickSelectionOverlay(url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(url) { DownloadRepository(context) }
    val scope = rememberCoroutineScope()

    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }

    LaunchedEffect(url) {
        loading = true
        val result = runCatching { repository.fetchVideoInfo(url) }
        val fetched = result.getOrNull()?.getOrNull()
        val failure = result.getOrNull()?.exceptionOrNull() ?: result.exceptionOrNull()
        info = fetched
        if (fetched != null && fetched.isCarousel) {
            // Everything the post offered, chosen by default: one tap to take the whole gallery,
            // and any number of taps to leave items out.
            selected = fetched.carouselMedia.indices.toSet()
        }
        when {
            fetched == null -> error = failure?.message
                ?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.failed_fetch_video)
            else -> error = null
        }
        loading = false
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClose)
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                // Swallows taps that miss a control: without this the scrim behind would close the
                // window on a scroll that started in a gap.
                .pointerInput(Unit) { detectTapGestures { } },
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.quick_download_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.quick_download_close))
                    }
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.quick_download_close),
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(onClick = onClose),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))

                when {
                    loading -> CenteredStatus(
                        text = stringResource(R.string.quick_download_reading_post),
                        indeterminate = true
                    )

                    saving -> CenteredStatus(
                        text = stringResource(
                            R.string.quick_download_saving,
                            (progress * 100).toInt().coerceIn(0, 100)
                        ),
                        indeterminate = progress <= 0f
                    )

                    error != null -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        BrutalButton(
                            text = stringResource(R.string.retry_download),
                            onClick = {
                                error = null
                                loading = true
                                scope.launch {
                                    val result = runCatching { repository.fetchVideoInfo(url) }
                                    val fetched = result.getOrNull()?.getOrNull()
                                    info = fetched
                                    if (fetched != null) {
                                        selected = fetched.carouselMedia.indices.toSet()
                                        error = null
                                    } else {
                                        error = result.exceptionOrNull()?.message
                                            ?: context.getString(R.string.failed_fetch_video)
                                    }
                                    loading = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onClose) {
                            Text(stringResource(R.string.quick_download_back))
                        }
                    }

                    else -> {
                        val videoInfo = info
                        if (videoInfo == null || videoInfo.carouselMedia.isEmpty()) {
                            CenteredStatus(
                                text = stringResource(R.string.quick_download_nothing_to_pick),
                                indeterminate = false
                            )
                        } else {
                            CarouselGalleryContent(
                                videoInfo = videoInfo,
                                selectedIndices = selected,
                                onToggleIndex = { index ->
                                    val next = selected.toMutableSet()
                                    if (!next.add(index)) next.remove(index)
                                    selected = next
                                },
                                onSelectKind = { isVideo ->
                                    selected = videoInfo.carouselMedia.indices
                                        .filter { videoInfo.carouselMedia[it].isVideo == isVideo }
                                        .toSet()
                                },
                                onSelectAll = { selected = videoInfo.carouselMedia.indices.toSet() },
                                onDeselectAll = { selected = emptySet() },
                                onDownload = {
                                    val slides = selected.sorted()
                                        .mapNotNull { videoInfo.carouselMedia.getOrNull(it) }
                                        .map { MediaSlide(it.url, isVideo = it.isVideo) }
                                    if (slides.isEmpty()) return@CarouselGalleryContent
                                    saving = true
                                    scope.launch {
                                        val outputDir = runCatching {
                                            repository.downloadPath.first()
                                        }.getOrDefault("")
                                        val summary = runCatching {
                                            repository.downloadMedia(
                                                slides = slides,
                                                baseName = videoInfo.title,
                                                outputDir = outputDir,
                                                refererUrl = videoInfo.originalUrl,
                                                onProgress = { state ->
                                                    progress = when (state) {
                                                        is DownloadState.Downloading -> state.progress
                                                        else -> progress
                                                    }
                                                }
                                            )
                                        }.getOrNull()
                                        saving = false
                                        val saved = summary?.savedCount ?: 0
                                        val failed = (summary?.failedCount ?: slides.size)
                                        Toast.makeText(
                                            context,
                                            when {
                                                summary == null ->
                                                    context.getString(R.string.quick_download_failed_save)
                                                saved == 0 -> context.getString(
                                                    R.string.media_saved_none_reason,
                                                    summary.firstFailure.ifBlank {
                                                        context.getString(R.string.error_generic_with_hint)
                                                    }
                                                )
                                                failed > 0 -> context.getString(
                                                    R.string.media_saved_partial, saved, failed
                                                )
                                                else -> context.getString(R.string.media_saved, saved)
                                            },
                                            Toast.LENGTH_LONG
                                        ).show()
                                        if (saved > 0) onClose()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredStatus(text: String, indeterminate: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (indeterminate) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(modifier = Modifier.height(14.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
