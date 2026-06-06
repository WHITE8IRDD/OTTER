package com.vidgrab.app.ui.screens.home

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.vidgrab.app.data.model.DownloadState
import com.vidgrab.app.ui.components.BrutalButton
import com.vidgrab.app.ui.components.BrutalCard
import com.vidgrab.app.ui.components.FormatSheet
import com.vidgrab.app.ui.components.ProgressCard
import com.vidgrab.app.ui.components.SpeechBubble
import com.vidgrab.app.ui.theme.ACCENT_PRESETS
import com.vidgrab.app.ui.theme.ThemeViewModel
import com.vidgrab.app.ui.theme.OtterDimens
import com.vidgrab.app.R
import com.vidgrab.app.util.getClipboardText
import com.vidgrab.app.util.isValidUrl



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
    val showFormatSheet by viewModel.showFormatSheet.collectAsStateWithLifecycle()
    val currentVideoInfo by viewModel.currentVideoInfo.collectAsStateWithLifecycle()
    val alsoSaveThumbnail by viewModel.alsoSaveThumbnail.collectAsStateWithLifecycle()
    val isDarkMode by themeViewModel.isDarkMode.collectAsStateWithLifecycle()
    val accentColor by themeViewModel.accentColor.collectAsStateWithLifecycle()
    val tintLogo by themeViewModel.tintLogo.collectAsStateWithLifecycle()
    val autoDownload by viewModel.autoDownload.collectAsStateWithLifecycle()
    val fetchingMessage by viewModel.fetchingMessage.collectAsStateWithLifecycle()
    val selectedCarouselIndices by viewModel.selectedCarouselIndices.collectAsStateWithLifecycle()
    val selectedSubtitleLangs by viewModel.selectedSubtitleLangs.collectAsStateWithLifecycle()
    val embedSubtitles by viewModel.embedSubtitles.collectAsStateWithLifecycle()
    val subtitleFormat by viewModel.subtitleFormat.collectAsStateWithLifecycle()

    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    } else null

    var showPaletteSheet by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var clipboardDetectedUrl by remember { mutableStateOf<String?>(null) }

    val logoTint = when {
        tintLogo -> MaterialTheme.colorScheme.primary
        isDarkMode -> Color.White
        else -> Color.Unspecified
    }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.otter_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .clickable { showAboutDialog = true }
                    .graphicsLayer(scaleX = breatheScale, scaleY = breatheScale),
                tint = logoTint
            )
            Text(
                text = "OTTER",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 38.sp
                ),
                fontWeight = FontWeight.Black,
                color = accentColor
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { showPaletteSheet = true }) {
                        Icon(
                            Icons.Default.Palette,
                            contentDescription = "Accent color",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(onClick = { themeViewModel.toggleDarkMode() }) {
                        Icon(
                            imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = if (isDarkMode) "Light mode" else "Dark mode",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            clipboardDetectedUrl?.let { url ->
                if (urlInput.isEmpty()) {
                    BrutalCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        cornerRadius = OtterDimens.ButtonCorner
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "\uD83D\uDCCB",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Link detected",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    viewModel.onPasteClicked(url)
                                    clipboardDetectedUrl = null
                                },
                                contentPadding = androidx.compose.material3.ButtonDefaults.TextButtonContentPadding
                            ) {
                                Text(
                                    text = "USE",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = { clipboardDetectedUrl = null },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            BrutalCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Paste a video link",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = viewModel::onUrlChanged,
                        placeholder = {
                            Text(
                                text = "https://youtube.com/watch?v=\u2026",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(OtterDimens.InputCorner),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        trailingIcon = {
                            if (urlInput.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearUrlInput) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        val clipText = context.getClipboardText()
                                        viewModel.onPasteClicked(clipText)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    )

                    BrutalButton(
                        text = when (downloadState) {
                            is DownloadState.FetchingInfo -> "FETCHING\u2026"
                            else -> "FETCH & DOWNLOAD"
                        },
                        onClick = { viewModel.fetchVideoInfo() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = downloadState !is DownloadState.FetchingInfo &&
                                downloadState !is DownloadState.Downloading
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Quick download",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Skip quality picker",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoDownload,
                            onCheckedChange = viewModel::setAutoDownload,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        )
                    }
                }
            }

            if (urlInput.isEmpty() && downloadState is DownloadState.Idle && currentVideoInfo == null) {
                MascotEmptyState(logoTint = logoTint)
            }

            AnimatedVisibility(
                visible = downloadState is DownloadState.FetchingInfo,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                MascotFetchingState(
                    logoTint = logoTint,
                    message = fetchingMessage,
                    onCancel = viewModel::cancelFetch
                )
            }

            if (downloadState is DownloadState.InfoReady && currentVideoInfo != null) {
                BrutalCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
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
                            text = currentVideoInfo?.title?.take(60) ?: "Info loaded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        IconButton(onClick = { viewModel.fetchVideoInfo() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Re-fetch",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = downloadState is DownloadState.Downloading,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                val state = downloadState as? DownloadState.Downloading
                if (state != null) {
                    ProgressCard(
                        title = currentVideoInfo?.title ?: urlInput,
                        progress = state.progress,
                        speedText = state.speedText,
                        etaText = state.etaText,
                        onCancel = viewModel::onCancelDownload
                    )
                }
            }

            AnimatedVisibility(
                visible = downloadState is DownloadState.Success,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                BrutalCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Download Complete!",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        BrutalButton(
                            text = "DOWNLOAD ANOTHER",
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
                    message = errorState?.message ?: "Unknown error",
                    onRetry = viewModel::fetchVideoInfo,
                    isCookieError = errorState?.message?.contains("Instagram") == true ||
                            errorState?.message?.contains("cookies") == true,
                    onNavigateToCookies = onNavigateToCookies
                )
            }

            Spacer(modifier = Modifier.height(80.dp))

            PlatformsRow()
        }
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
            subtitleTracks = info.subtitleTracks,
            selectedSubtitleLangs = selectedSubtitleLangs,
            onToggleSubtitle = viewModel::toggleSubtitleLang,
            embedSubtitles = embedSubtitles,
            onToggleEmbedSubtitles = viewModel::setEmbedSubtitles,
            subtitleFormat = subtitleFormat,
            onSubtitleFormatChange = viewModel::setSubtitleFormat
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Icon(
            painter = painterResource(id = R.drawable.otter_logo),
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer(scaleX = idleScale, scaleY = idleScale),
            tint = logoTint
        )
        Spacer(modifier = Modifier.height(8.dp))
        SpeechBubble(
            text = "No videos here yet!\nFeed me some links!",
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .height(80.dp),
            cornerRadius = 20.dp
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun MascotFetchingState(logoTint: Color, message: String, onCancel: () -> Unit) {
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

    BrutalCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.otter_logo),
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
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) {
                Text(
                    text = "CANCEL",
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
    val infiniteTransition = rememberInfiniteTransition(label = "confused_shake")
    val shakeX by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shake_x"
    )

    BrutalCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.otter_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .graphicsLayer {
                            translationX = shakeX
                            rotationZ = shakeX * 0.5f
                        },
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Oops! Something went wrong",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            SpeechBubble(
                text = message,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                backgroundColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                textColor = MaterialTheme.colorScheme.onErrorContainer,
                borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
                cornerRadius = 20.dp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BrutalButton(
                    text = "TRY AGAIN",
                    onClick = onRetry,
                    modifier = Modifier.weight(1f)
                )
                if (isCookieError) {
                    BrutalButton(
                        text = "COOKIE SETTINGS",
                        onClick = onNavigateToCookies,
                        modifier = Modifier.weight(1f),
                        backgroundColor = MaterialTheme.colorScheme.secondary,
                        textColor = MaterialTheme.colorScheme.onSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun PlatformsRow() {
    Text(
        text = "YouTube \u2022 Instagram \u2022 Facebook \u2022 Twitter/X +1000 more",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    )
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
                text = "Accent Color",
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
                    placeholder = { Text("Hex code", style = MaterialTheme.typography.bodyMedium) },
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
                    prefix = { Text("#", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
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
                    text = "Invalid hex — use format FF5722",
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
            BrutalCard(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.85f)
                    .graphicsLayer(
                        scaleX = popScale.value,
                        scaleY = popScale.value,
                        alpha = popScale.value.coerceIn(0f, 1f)
                    ),
                backgroundColor = MaterialTheme.colorScheme.surface
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
                                text = "OTTER",
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
                                contentDescription = "Close",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = "Open-source media downloader for Android. Powered by the 𝘈𝘝® engine.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Otter lets you download video, audio, images, and subtitles from 1000+ platforms — YouTube, TikTok, Instagram, Facebook, Twitter/X, and more.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )

                    Text(
                        text = "Built with Jetpack Compose. Lightweight, fast, and fully open source.",
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
                                text = "Instagram",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = accentColor
                            )
                            Text(
                                text = "@acee.visuals",
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
                                text = "GitHub",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = accentColor
                            )
                            Text(
                                text = "/Acevisuals",
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
                            text = "2026",
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
