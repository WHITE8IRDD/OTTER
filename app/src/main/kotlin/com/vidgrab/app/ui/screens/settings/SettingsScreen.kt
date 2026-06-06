package com.vidgrab.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vidgrab.app.data.model.VideoInfo
import com.vidgrab.app.ui.components.ColorDot
import com.vidgrab.app.ui.components.SettingsClickableRow
import com.vidgrab.app.ui.components.SettingsGroup
import com.vidgrab.app.ui.components.SettingsRow
import com.vidgrab.app.ui.theme.ACCENT_PRESETS
import com.vidgrab.app.ui.theme.ThemeViewModel
import com.vidgrab.app.util.NotificationHelper

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onCookieLogin: () -> Unit = {},
    onSiteCookies: () -> Unit = {},
    themeViewModel: ThemeViewModel,
    viewModel: SettingsViewModel = viewModel()
) {
    val downloadPath by viewModel.downloadPath.collectAsStateWithLifecycle()
    val autoDownload by viewModel.autoDownload.collectAsStateWithLifecycle()
    val extractAudio by viewModel.extractAudio.collectAsStateWithLifecycle()
    val audioFormat by viewModel.audioFormat.collectAsStateWithLifecycle()
    val downloadSubtitles by viewModel.downloadSubtitles.collectAsStateWithLifecycle()
    val embedThumbnail by viewModel.embedThumbnail.collectAsStateWithLifecycle()
    val embedMetadata by viewModel.embedMetadata.collectAsStateWithLifecycle()
    val rateLimit by viewModel.rateLimit.collectAsStateWithLifecycle()
    val maxRate by viewModel.maxRate.collectAsStateWithLifecycle()
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val useAria2c by viewModel.useAria2c.collectAsStateWithLifecycle()
    val concurrentFragments by viewModel.concurrentFragments.collectAsStateWithLifecycle()
    val retries by viewModel.retries.collectAsStateWithLifecycle()
    val isDarkMode by themeViewModel.isDarkMode.collectAsStateWithLifecycle()
    val accentColor by themeViewModel.accentColor.collectAsStateWithLifecycle()
    val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()
    val isUpdating by viewModel.isUpdating.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(
                modifier = Modifier.padding(top = 24.dp, bottom = 16.dp)
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "App preferences and configuration",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsGroup(title = "Appearance") {
                SettingsRow(
                    title = "Dark mode",
                    subtitle = "Use dark theme",
                    trailing = {
                        Switch(
                            checked = isDarkMode,
                            onCheckedChange = { themeViewModel.toggleDarkMode() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        )
                    }
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        text = "Accent color",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ACCENT_PRESETS.forEach { preset ->
                            val c = if (isDarkMode) preset.dark else preset.light
                            ColorDot(
                                color = c,
                                selected = c == accentColor,
                                onClick = { themeViewModel.setAccentColor(c) }
                            )
                        }
                    }
                }
            }

            SettingsGroup(title = "Downloads") {
                SettingsRow(
                    title = "Auto-download",
                    subtitle = "Skip quality picker",
                    trailing = {
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
                )
                SettingsRow(
                    title = "Download playlist",
                    subtitle = "Download entire playlist when detected",
                    trailing = {
                        Switch(
                            checked = playlist,
                            onCheckedChange = viewModel::setPlaylist,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        )
                    }
                )
                SettingsRow(
                    title = "Extract audio only",
                    subtitle = "Download audio without video",
                    trailing = {
                        Switch(
                            checked = extractAudio,
                            onCheckedChange = viewModel::setExtractAudio,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        )
                    }
                )
                SettingsRow(
                    title = "Embed thumbnail",
                    subtitle = "Embed thumbnail in file",
                    trailing = {
                        Switch(
                            checked = embedThumbnail,
                            onCheckedChange = viewModel::setEmbedThumbnail,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        )
                    }
                )
                SettingsRow(
                    title = "Embed metadata",
                    subtitle = "Embed title, artist, etc.",
                    trailing = {
                        Switch(
                            checked = embedMetadata,
                            onCheckedChange = viewModel::setEmbedMetadata,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        )
                    },
                    showDivider = true
                )
                SettingsRow(
                    title = "Download subtitles",
                    subtitle = "Include subtitles with Quick Download",
                    trailing = {
                        Switch(
                            checked = downloadSubtitles,
                            onCheckedChange = viewModel::setDownloadSubtitles,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        )
                    },
                    showDivider = false
                )
            }

            SettingsGroup(title = "Performance") {
                SettingsRow(
                    title = "Use Aria2c",
                    subtitle = "Faster downloads with Aria2",
                    trailing = {
                        Switch(
                            checked = useAria2c,
                            onCheckedChange = viewModel::setUseAria2c,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        )
                    }
                )
                SettingsRow(
                    title = "Rate limit",
                    subtitle = "Limit download speed",
                    trailing = {
                        Switch(
                            checked = rateLimit,
                            onCheckedChange = viewModel::setRateLimit,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        )
                    },
                    showDivider = false
                )
            }

            SettingsGroup(title = "Notifications") {
                val context = LocalContext.current
                SettingsRow(
                    title = "Test notification sound",
                    onClick = { NotificationHelper.playTestSound(context) },
                    trailing = {
                        Text(
                            text = "\u203A",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                SettingsRow(
                    title = "Notification settings",
                    onClick = { NotificationHelper.openNotificationSettings(context) },
                    trailing = {
                        Text(
                            text = "\u203A",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    showDivider = false
                )
            }

            SettingsGroup(title = "Site Cookies") {
                SettingsClickableRow(
                    title = "Manage Cookies",
                    subtitle = "Required for Instagram, private YouTube, and age-restricted content",
                    onClick = onSiteCookies
                )
            }

            SettingsGroup(title = "Download Engine") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "𝘈𝘝® engine version",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = viewModel.ytDlpVersion,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = viewModel::updateYtDlp,
                            enabled = !isUpdating,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                        ) {
                            Text(
                                text = if (isUpdating) "Updating\u2026" else "Update",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    if (isUpdating) {
                        Spacer(modifier = Modifier.height(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp
                        )
                    }
                    if (updateStatus != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = updateStatus!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SettingsGroup(title = "Download Location") {
                val context = LocalContext.current
                val folderLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { uri: Uri? ->
                    uri?.let {
                        context.contentResolver.takePersistableUriPermission(
                            it,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        val realPath = resolveSafPath(it)
                        if (realPath != null) {
                            viewModel.setDownloadPath(realPath)
                        }
                    }
                }
                val defaultPath = remember {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/Otter/"
                }
                val displayPath = remember(downloadPath) {
                    when {
                        downloadPath.isEmpty() || downloadPath == defaultPath -> "Default (Downloads/Otter)"
                        else -> downloadPath.substringAfterLast("/").let {
                            if (it.length > 30) it.take(30) + "…" else it
                        }
                    }
                }

                SettingsRow(
                    title = "Save to",
                    subtitle = displayPath,
                    onClick = { folderLauncher.launch(null) },
                    trailing = {
                        Text(
                            text = "\u203A",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    showDivider = false
                )
                if (downloadPath.isNotEmpty() && downloadPath != defaultPath) {
                    TextButton(
                        onClick = { viewModel.resetDownloadPath() },
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                    ) {
                        Text(
                            text = "Reset to default",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            SettingsGroup(title = "Supported Platforms") {
                SettingsRow(
                    title = "1000+ platforms supported",
                    subtitle = VideoInfo.SUPPORTED_PLATFORMS.joinToString(" \u2022 "),
                    trailing = {},
                    showDivider = false
                )
            }

            SettingsGroup(title = "Legal") {
                SettingsRow(
                    title = "Legal Notice",
                    subtitle = "Please only download content you have the right to save. Respect copyright and platform terms of service.",
                    trailing = {},
                    showDivider = false
                )
            }

            SettingsGroup(title = "About") {
                SettingsRow(
                    title = "Otter",
                    subtitle = "Version 1.1.0 \u2022 Powered by 𝘈𝘝® engine \u2022 Built with Jetpack Compose",
                    trailing = {},
                    showDivider = true
                )
                SettingsRow(
                    title = "Engine",
                    subtitle = "Powered by 𝘈𝘝® engine \u2014 ultra-fast media extraction for 1000+ platforms",
                    trailing = {},
                    showDivider = false
                )
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

private fun resolveSafPath(uri: Uri): String? {
    val docId = try { DocumentsContract.getTreeDocumentId(uri) } catch (e: Exception) { return null }
    val split = docId.split(":")
    val type = split.getOrNull(0) ?: return null
    val relativePath = split.getOrNull(1) ?: ""
    return if ("primary".equals(type, ignoreCase = true)) {
        "${Environment.getExternalStorageDirectory()}/$relativePath"
    } else {
        "/storage/$type/$relativePath"
    }
}
