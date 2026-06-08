package com.otter.app.ui.screens.home

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.otter.app.data.model.DownloadState
import com.otter.app.ui.components.BrutalButton
import com.otter.app.ui.components.OtterCard
import com.otter.app.ui.components.FormatSheet
import com.otter.app.ui.components.ProgressCard
import com.otter.app.ui.components.OtterSwitch
import com.otter.app.ui.components.SpeechBubble
import com.otter.app.ui.theme.ACCENT_PRESETS
import com.otter.app.ui.theme.ThemeViewModel
import com.otter.app.ui.theme.OtterDimens
import com.otter.app.R
import com.otter.app.util.getClipboardText
import com.otter.app.util.isValidUrl
import com.otter.app.util.LocaleManager



@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    sharedUrl: String? = null,
    onSharedUrlConsumed: () -> Unit = {},
    onNavigateToCookies: () -> Unit = {},
    themeViewModel: ThemeViewModel,
    viewModel: HomeViewModel = viewModel()
) {
    val context = LocalContext.current
    val urlInput by viewModel.urlInput.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val displayState by viewModel.displayState.collectAsStateWithLifecycle()
    val activeDownloadTitle by viewModel.activeDownloadTitle.collectAsStateWithLifecycle()
    val showFormatSheet by viewModel.showFormatSheet.collectAsStateWithLifecycle()
    val currentVideoInfo by viewModel.currentVideoInfo.collectAsStateWithLifecycle()
    val alsoSaveThumbnail by viewModel.alsoSaveThumbnail.collectAsStateWithLifecycle()
    val isDarkMode by themeViewModel.isDarkMode.collectAsStateWithLifecycle()
    val accentColor by themeViewModel.accentColor.collectAsStateWithLifecycle()
    val tintLogo by themeViewModel.tintLogo.collectAsStateWithLifecycle()
    val autoDownload by viewModel.autoDownload.collectAsStateWithLifecycle()
    val fetchingMessage by viewModel.fetchingMessage.collectAsStateWithLifecycle()
    val retryStatus by viewModel.retryStatus.collectAsStateWithLifecycle()
    val selectedCarouselIndices by viewModel.selectedCarouselIndices.collectAsStateWithLifecycle()
    val selectedSubtitleLangs by viewModel.selectedSubtitleLangs.collectAsStateWithLifecycle()
    val selectedAutoCaptionLangs by viewModel.selectedAutoCaptionLangs.collectAsStateWithLifecycle()
    val embedSubtitles by viewModel.embedSubtitles.collectAsStateWithLifecycle()
    val subtitleFormat by viewModel.subtitleFormat.collectAsStateWithLifecycle()
    val selectedAudioTrackFormatId by viewModel.selectedAudioTrackFormatId.collectAsStateWithLifecycle()

    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    } else null

    var showPaletteSheet by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    var clipboardDetectedUrl by remember { mutableStateOf<String?>(null) }

    val logoTint = when {
        tintLogo -> MaterialTheme.colorScheme.primary
        isDarkMode -> Color.White
        else -> Color.Unspecified
    }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        notificationPermission?.let {
            if (!it.status.isGranted) it.launchPermissionRequest()
        }
        val clip = context.getSystemService(ClipboardManager::class.java)
        val clipText = clip?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (clipText.isValidUrl() && clipText != urlInput) {
            clipboardDetectedUrl = clipText
        }
    }

    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrEmpty()) {
            viewModel.setSharedUrl(sharedUrl, onConsumed = onSharedUrlConsumed)
            clipboardDetectedUrl = null
        }
    }

    val scrollState = rememberScrollState()

    // Haptic on error and download complete
    LaunchedEffect(downloadState) {
        when (downloadState) {
            is DownloadState.Error -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            is DownloadState.Success -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            else -> {}
        }
    }

    // Auto-scroll to top when active download appears
    LaunchedEffect(displayState) {
        if (displayState is DownloadState.Downloading || displayState is DownloadState.Paused) {
            scrollState.animateScrollTo(0)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HomeScreenHeader(
                logoColor = logoTint,
                accentColor = accentColor,
                isDarkMode = isDarkMode,
                onLogoClick = { showAboutDialog = true },
                onPaletteClick = { showPaletteSheet = true },
                onToggleDarkMode = { themeViewModel.toggleDarkMode() },
                onLanguageClick = { showLanguageSheet = true }
            )

            clipboardDetectedUrl?.let { url ->
                if (urlInput.isEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 0.dp)
                            .shadow(
                                elevation = 6.dp,
                                shape = RoundedCornerShape(16.dp),
                                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            ),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.ContentPaste,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Link detected",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                viewModel.onPasteClicked(url)
                                clipboardDetectedUrl = null
                            }) {
                                Text(
                                    "USE",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 1.sp
                                )
                            }
                            IconButton(onClick = { clipboardDetectedUrl = null }, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.content_desc_dismiss),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            OtterCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.paste_video_link),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = viewModel::onUrlChanged,
                        placeholder = {
                            Text(
                                text = stringResource(R.string.url_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        trailingIcon = {
                            if (urlInput.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearUrlInput, modifier = Modifier.size(40.dp)) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.content_desc_clear),
                                        modifier = Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        val clipText = context.getClipboardText()
                                        viewModel.onPasteClicked(clipText)
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = stringResource(R.string.content_desc_paste),
                                        modifier = Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    )

                    BrutalButton(
                        text = when (downloadState) {
                            is DownloadState.FetchingInfo -> stringResource(R.string.btn_fetching)
                            else -> stringResource(R.string.btn_fetch_download)
                        },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.fetchVideoInfo()
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        enabled = downloadState !is DownloadState.FetchingInfo &&
                                downloadState !is DownloadState.Downloading,
                        cornerRadius = 16.dp,
                        backgroundColor = if (MaterialTheme.colorScheme.background.luminance() > 0.5f)
                            Color(0xFF0A0A0A) else Color(0xFFF5F5F4),
                        textColor = if (MaterialTheme.colorScheme.background.luminance() > 0.5f)
                            Color.White else Color(0xFF0A0A0A)
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        thickness = 1.dp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.quick_download_label),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.quick_download_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OtterSwitch(
                            checked = autoDownload,
                            onCheckedChange = viewModel::setAutoDownload
                        )
                    }
                }
            }

            // ─── PROGRESS / FETCHING / DOWNLOAD / PAUSE — FIXED POSITION ───────
            // Always rendered right after URL input, regardless of trigger source

            AnimatedVisibility(
                visible = displayState is DownloadState.FetchingInfo ||
                          displayState is DownloadState.Downloading ||
                          displayState is DownloadState.Paused,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                when (val state = displayState) {
                    is DownloadState.FetchingInfo -> {
                        var showSkeleton by remember(state) { mutableStateOf(true) }
                        LaunchedEffect(state) {
                            kotlinx.coroutines.delay(2000)
                            showSkeleton = false
                        }
                        if (showSkeleton) {
                            FetchingSkeleton()
                        } else {
                            MascotFetchingState(
                                logoTint = logoTint,
                                message = state.message,
                                retryStatus = retryStatus,
                                onCancel = viewModel::cancelFetch
                            )
                        }
                    }
                    is DownloadState.Downloading, is DownloadState.Paused -> {
                        ProgressCard(
                            title = activeDownloadTitle ?: currentVideoInfo?.title ?: urlInput,
                            state = state,
                            onPause = { viewModel.onPauseDownload() },
                            onResume = { viewModel.onResumeDownload() },
                            onCancel = { viewModel.onStopDownload() }
                        )
                    }
                    else -> {}
                }
            }

            // ─── BELOW THE PROGRESS CARD ────────────────────────────────────────

            if (urlInput.isEmpty() && downloadState is DownloadState.Idle && currentVideoInfo == null
                && displayState is DownloadState.Idle) {
                MascotEmptyState(logoTint = logoTint)
            }

            if (downloadState is DownloadState.InfoReady && currentVideoInfo != null) {
                OtterCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = currentVideoInfo?.title?.take(60) ?: stringResource(R.string.info_loaded),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        IconButton(onClick = { viewModel.fetchVideoInfo() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.content_desc_refetch),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = downloadState is DownloadState.Success,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                OtterCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.mascot_archer),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(48.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.download_complete),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        BrutalButton(
                            text = stringResource(R.string.btn_download_another),
                            onClick = viewModel::resetState,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = downloadState is DownloadState.Error,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                val errorState = downloadState as? DownloadState.Error
                MascotErrorState(
                    message = errorState?.message ?: stringResource(R.string.download_error_unknown),
                    onRetry = viewModel::fetchVideoInfo,
                    isCookieError = errorState?.message?.contains("Instagram") == true ||
                            errorState?.message?.contains("cookies") == true,
                    onNavigateToCookies = onNavigateToCookies
                )
            }

            Spacer(modifier = Modifier.height(60.dp))
        }

        // PlatformsRow removed
    }

    if (showFormatSheet) {
        currentVideoInfo?.let { info ->
        FormatSheet(
            videoInfo = info,
            alsoSaveThumbnail = alsoSaveThumbnail,
            onToggleThumbnail = viewModel::toggleAlsoSaveThumbnail,
            onThumbnailOnly = viewModel::downloadThumbnailOnly,
            onFormatSelected = viewModel::onFormatSelected,
            selectedCarouselIndices = selectedCarouselIndices,
            onToggleCarouselIndex = viewModel::toggleCarouselIndex,
            onSelectAllCarousel = viewModel::selectAllCarousel,
            onDeselectAllCarousel = viewModel::deselectAllCarousel,
            onDownloadCarousel = viewModel::downloadCarouselSelected,
            onDismiss = viewModel::onDismissFormatSheet,
            subtitleTracks = info.subtitleTracks.filter { !it.isAutoGenerated },
            selectedSubtitleLangs = selectedSubtitleLangs,
            onToggleSubtitle = viewModel::toggleSubtitleLang,
            embedSubtitles = embedSubtitles,
            onToggleEmbedSubtitles = viewModel::setEmbedSubtitles,
            subtitleFormat = subtitleFormat,
            onSubtitleFormatChange = viewModel::setSubtitleFormat,
            autoCaptionTracks = info.subtitleTracks.filter { it.isAutoGenerated },
            selectedAutoCaptionLangs = selectedAutoCaptionLangs,
            onToggleAutoCaption = viewModel::toggleAutoCaptionLang,
            audioTracks = info.audioTracks,
            selectedAudioTrackFormatId = selectedAudioTrackFormatId,
            onSelectAudioTrack = viewModel::selectAudioTrack
        )
        }
    }

    if (showPaletteSheet) {
        PaletteSheet(
            accentColor = accentColor,
            isDarkMode = isDarkMode,
            onSelectColor = { color ->
                themeViewModel.setAccentColor(color)
                showPaletteSheet = false
            },
            onDismiss = { showPaletteSheet = false }
        )
    }

    if (showAboutDialog) {
        AboutDialog(
            logoTint = logoTint,
            accentColor = accentColor,
            onDismiss = { showAboutDialog = false }
        )
    }

    if (showLanguageSheet) {
        LanguagePickerSheet(
            onDismiss = { showLanguageSheet = false }
        )
    }
}

@Composable
private fun FetchingSkeleton() {
    val shimmer = rememberInfiniteTransition(label = "shimmer")
    val alpha by shimmer.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    OtterCard {
        Column(modifier = Modifier.padding(20.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha), RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.7f)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha), RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.4f)
                    .height(16.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha), RoundedCornerShape(6.dp))
            )
        }
    }
}

@Composable
private fun MascotEmptyState(logoTint: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "idle_bounce")
    val idleScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_scale"
    )

    val parts = stringResource(R.string.empty_state_videos).split("\n")

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(id = R.drawable.otter_logo),
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .graphicsLayer(scaleX = idleScale, scaleY = idleScale),
            tint = Color.Unspecified
        )
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Color.Black.copy(alpha = 0.04f),
                spotColor = Color.Black.copy(alpha = 0.06f)
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(
                    text = parts.getOrElse(0) { "" },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = parts.getOrElse(1) { "" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MascotFetchingState(logoTint: Color, message: String, retryStatus: RetryStatus, onCancel: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "fetch_bounce")
    val bounceY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -8f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounce_y"
    )
    val searchRotate by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "search_rotate"
    )

    // Stage progression for perceived speed (disabled during retry)
    var stage by remember { mutableStateOf<Int>(0) }
    val isRetrying = retryStatus.isRetrying
    val stageMessages = listOf(
        message,
        stringResource(R.string.fetch_stage_connecting),
        stringResource(R.string.fetch_stage_reading),
        stringResource(R.string.fetch_stage_parsing),
        stringResource(R.string.fetch_stage_finishing)
    )
    LaunchedEffect(Unit) {
        if (isRetrying) return@LaunchedEffect
        stage = 0
        kotlinx.coroutines.delay(800)
        if (stage == 0) stage = 1
        kotlinx.coroutines.delay(1200)
        if (stage == 1) stage = 2
        kotlinx.coroutines.delay(1500)
        if (stage == 2) stage = 3
    }
    LaunchedEffect(message) { if (!isRetrying) stage = 0 }

    OtterCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.mascot_pounce),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        translationY = bounceY
                        rotationZ = searchRotate
                    },
                tint = logoTint
            )
            Text(
                text = stageMessages.getOrElse(stage) { message },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) {
                Text(
                    text = stringResource(R.string.btn_cancel),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}



@Composable
private fun MascotErrorState(
    message: String,
    onRetry: () -> Unit,
    isCookieError: Boolean,
    onNavigateToCookies: () -> Unit
) {
    OtterCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.mascot_surfer),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.error_title_friendly),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onErrorContainer),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text(stringResource(R.string.btn_try_again), style = MaterialTheme.typography.labelLarge)
            }
            if (isCookieError) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onNavigateToCookies,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onErrorContainer),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(stringResource(R.string.btn_cookie_settings), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaletteSheet(
    accentColor: Color,
    isDarkMode: Boolean,
    onSelectColor: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    var hexInput by remember { mutableStateOf("") }
    var hexError by remember { mutableStateOf(false) }
    val columns = 4

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.palette_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ACCENT_PRESETS.chunked(columns).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        row.forEach { preset ->
                            val c = if (isDarkMode) preset.dark else preset.light
                            val isSelected = c == accentColor
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(72.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Surface(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clickable { onSelectColor(c) },
                                        shape = MaterialTheme.shapes.small,
                                        color = c,
                                        tonalElevation = if (isSelected) 4.dp else 0.dp,
                                        border = if (isSelected) BorderStroke(3.dp, accentColor)
                                        else BorderStroke(0.dp, Color.Transparent)
                                    ) {}
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = if (c.luminance() > 0.5f) Color.Black else Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) accentColor
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1
                                )
                            }
                        }
                        if (row.size < columns) {
                            repeat(columns - row.size) {
                                Spacer(modifier = Modifier.width(72.dp))
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                thickness = OtterDimens.InkyBorder * 0.5f
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        val cleaned = input.replace("#", "")
                        if (cleaned.all { it.isLetterOrDigit() } && cleaned.length <= 6) {
                            hexInput = cleaned
                            hexError = false
                            if (cleaned.length == 6) {
                                try {
                                    val colorLong = (0xFF000000L or cleaned.toLong(16))
                                    onSelectColor(Color(colorLong))
                                } catch (_: Exception) {
                                    hexError = true
                                }
                            }
                        } else if (cleaned.isEmpty()) {
                            hexInput = ""
                            hexError = false
                        }
                    },
                    placeholder = { Text(stringResource(R.string.hex_input_placeholder), style = MaterialTheme.typography.bodyMedium) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(OtterDimens.InputCorner),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    prefix = { Text(stringResource(R.string.hex_prefix), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    isError = hexError
                )
                if (hexInput.length == 6 && !hexError) {
                    val colorLong = 0xFF000000L or hexInput.toLong(16)
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = MaterialTheme.shapes.small,
                        color = Color(colorLong),
                        tonalElevation = 2.dp,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    ) {}
                } else if (hexInput.isEmpty()) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = MaterialTheme.shapes.small,
                        color = accentColor,
                        tonalElevation = 2.dp
                    ) {}
                }
            }
            if (hexError) {
                Text(
                    text = stringResource(R.string.hex_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AboutDialog(
    logoTint: Color,
    accentColor: Color,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val popScale = remember { Animatable(0.85f) }

    LaunchedEffect(Unit) {
        popScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            OtterCard(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.85f)
                    .graphicsLayer(
                        scaleX = popScale.value,
                        scaleY = popScale.value,
                        alpha = popScale.value.coerceIn(0f, 1f)
                    ),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.otter_logo),
                                contentDescription = null,
                                modifier = Modifier.size(96.dp),
                                tint = logoTint
                            )
                            Text(
                                text = stringResource(R.string.about_app_name),
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontSize = 48.sp
                                ),
                                fontWeight = FontWeight.Black,
                                color = accentColor
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(32.dp)
                                .align(Alignment.TopEnd)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.content_desc_close),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.about_description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(R.string.about_description2),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )

                    Text(
                        text = stringResource(R.string.about_description3),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/acee.visuals/"))
                                context.startActivity(intent)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InstagramIcon()
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.social_instagram),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = accentColor
                            )
                            Text(
                                text = stringResource(R.string.social_instagram_handle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Acevisuals"))
                                context.startActivity(intent)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GithubIcon()
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.social_github),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = accentColor
                            )
                            Text(
                                text = stringResource(R.string.social_github_handle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.about_year),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                                    append("AV")
                                }
                                append("\u00AE")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstagramIcon(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0xFFE4405F)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val strokeW = 2.dp.toPx()
            drawRoundRect(
                color = Color.White,
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(width = strokeW)
            )
            drawCircle(
                color = Color.White,
                radius = size.minDimension / 4.5f,
                style = Stroke(width = strokeW)
            )
            drawCircle(
                color = Color.White,
                radius = 1.5.dp.toPx()
            )
        }
    }
}

@Composable
private fun GithubIcon(modifier: Modifier = Modifier) {
    val bgColor = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFF24292F) else Color(0xFFF0F0F0)
    val fgColor = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color.White else Color(0xFF24292F)

    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val strokeW = 1.8.dp.toPx()
            val cx = size.width / 2
            val cy = size.height / 2
            val r = size.minDimension / 3.2f

            drawCircle(fgColor, radius = r, center = Offset(cx, cy), style = Stroke(width = strokeW))
            drawCircle(fgColor, radius = r * 0.32f, center = Offset(cx - r * 0.65f, cy - r * 0.65f), style = Stroke(width = strokeW))
            drawCircle(fgColor, radius = r * 0.32f, center = Offset(cx + r * 0.65f, cy - r * 0.65f), style = Stroke(width = strokeW))
            drawCircle(fgColor, radius = 1.2.dp.toPx(), center = Offset(cx - r * 0.28f, cy + r * 0.25f))
            drawCircle(fgColor, radius = 1.2.dp.toPx(), center = Offset(cx + r * 0.28f, cy + r * 0.25f))
        }
    }
}

@Composable
private fun HomeScreenHeader(
    logoColor: Color,
    accentColor: Color,
    isDarkMode: Boolean,
    onLogoClick: () -> Unit,
    onPaletteClick: () -> Unit,
    onToggleDarkMode: () -> Unit,
    onLanguageClick: () -> Unit = {}
) {
    val headerBreathe = rememberInfiniteTransition(label = "header_breathe")
    val breatheScale by headerBreathe.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe_scale"
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.otter_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onLogoClick)
                    .graphicsLayer(scaleX = breatheScale, scaleY = breatheScale),
                tint = logoColor
            )
            Text(
                text = stringResource(R.string.header_app_name),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 38.sp,
                    letterSpacing = (-1.5).sp
                ),
                fontWeight = FontWeight.Black,
                color = accentColor
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(22.dp)
                )
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            IconButton(onClick = onLanguageClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = stringResource(R.string.content_desc_language),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
            IconButton(onClick = onPaletteClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Palette,
                    contentDescription = stringResource(R.string.content_desc_palette),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
            IconButton(onClick = onToggleDarkMode, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = if (isDarkMode) stringResource(R.string.content_desc_light_mode) else stringResource(R.string.content_desc_dark_mode),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePickerSheet(currentLanguage: String = "", onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val current = if (currentLanguage.isNotEmpty()) currentLanguage else LocaleManager.getPersistedLanguage(context)
    val languages = listOf(
        "en" to stringResource(R.string.language_en),
        "ar" to stringResource(R.string.language_ar),
        "fr" to stringResource(R.string.language_fr)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.language_picker_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            languages.forEach { (code, displayName) ->
                val isSelected = code == current
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            if (activity != null) {
                                LocaleManager.persistAndRestart(activity, code)
                            }
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = code.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Hairline,
    color: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
) {
    androidx.compose.material3.HorizontalDivider(
        modifier = modifier,
        thickness = thickness,
        color = color
    )
}
