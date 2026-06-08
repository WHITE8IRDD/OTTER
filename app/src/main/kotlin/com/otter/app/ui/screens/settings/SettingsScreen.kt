package com.otter.app.ui.screens.settings

import android.app.Activity
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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.otter.app.R
import com.otter.app.ui.components.ColorDot
import com.otter.app.ui.components.SettingsClickableRow
import com.otter.app.ui.components.SettingsGroup
import com.otter.app.ui.components.SettingsRow
import com.otter.app.ui.theme.ACCENT_PRESETS
import com.otter.app.ui.theme.ThemeViewModel
import com.otter.app.util.LocaleManager
import com.otter.app.util.NotificationHelper

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
    val tintLogo by themeViewModel.tintLogo.collectAsStateWithLifecycle()
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
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.settings_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsGroup(title = stringResource(R.string.settings_appearance)) {
                SettingsRow(
                    title = stringResource(R.string.setting_dark_mode),
                    subtitle = stringResource(R.string.setting_dark_mode_sub),
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
                        text = stringResource(R.string.setting_accent_color),
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
                SettingsRow(
                    title = stringResource(R.string.setting_tint_logo),
                    subtitle = stringResource(R.string.setting_tint_logo_sub),
                    trailing = {
                        Switch(
                            checked = tintLogo,
                            onCheckedChange = { themeViewModel.toggleTintLogo() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        )
                    }
                )
            }

            SettingsGroup(title = stringResource(R.string.settings_downloads)) {
                SettingsRow(
                    title = stringResource(R.string.setting_auto_download),
                    subtitle = stringResource(R.string.setting_auto_download_sub),
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
                    title = stringResource(R.string.setting_download_playlist),
                    subtitle = stringResource(R.string.setting_download_playlist_sub),
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
                    title = stringResource(R.string.setting_extract_audio),
                    subtitle = stringResource(R.string.setting_extract_audio_sub),
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
                    title = stringResource(R.string.setting_embed_thumbnail),
                    subtitle = stringResource(R.string.setting_embed_thumbnail_sub),
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
                    title = stringResource(R.string.setting_embed_metadata),
                    subtitle = stringResource(R.string.setting_embed_metadata_sub),
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
                    title = stringResource(R.string.setting_download_subtitles),
                    subtitle = stringResource(R.string.setting_download_subtitles_sub),
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

            SettingsGroup(title = stringResource(R.string.settings_performance)) {
                SettingsRow(
                    title = stringResource(R.string.setting_use_aria2c),
                    subtitle = stringResource(R.string.setting_use_aria2c_sub),
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
                    title = stringResource(R.string.setting_rate_limit),
                    subtitle = stringResource(R.string.setting_rate_limit_sub),
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

            SettingsGroup(title = stringResource(R.string.settings_notifications)) {
                val context = LocalContext.current
                SettingsRow(
                    title = stringResource(R.string.setting_test_notification),
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
                    title = stringResource(R.string.setting_notification_settings),
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

            SettingsGroup(title = stringResource(R.string.settings_app_language)) {
                val context = LocalContext.current
                var showLangPicker by remember { mutableStateOf(false) }
                SettingsClickableRow(
                    title = stringResource(R.string.settings_app_language),
                    subtitle = when (LocaleManager.getPersistedLanguage(context)) {
                        "ar" -> stringResource(R.string.language_ar)
                        "fr" -> stringResource(R.string.language_fr)
                        else -> stringResource(R.string.language_en)
                    },
                    onClick = { showLangPicker = true }
                )
                if (showLangPicker) {
                    LanguageSettingsPickerSheet(
                        onDismiss = { showLangPicker = false }
                    )
                }
            }

            SettingsGroup(title = stringResource(R.string.settings_site_cookies)) {
                SettingsClickableRow(
                    title = stringResource(R.string.setting_manage_cookies),
                    subtitle = stringResource(R.string.setting_manage_cookies_sub),
                    onClick = onSiteCookies
                )
            }

            SettingsGroup(title = stringResource(R.string.settings_download_engine)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.setting_engine_version),
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
                                text = if (isUpdating) stringResource(R.string.btn_checking_updates) else stringResource(R.string.btn_update),
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

            SettingsGroup(title = stringResource(R.string.settings_download_location)) {
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
                val displayPath = remember(downloadPath, context) {
                    when {
                        downloadPath.isEmpty() || downloadPath == defaultPath -> context.getString(R.string.setting_default_path)
                        else -> downloadPath.substringAfterLast("/").let {
                            if (it.length > 30) it.take(30) + "…" else it
                        }
                    }
                }

                SettingsRow(
                    title = stringResource(R.string.setting_save_to),
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
                            text = stringResource(R.string.btn_reset_default),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            SettingsGroup(title = stringResource(R.string.settings_supported_platforms)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.supported_platforms_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    PlatformRow(
                        icon = Icons.Default.PlayArrow,
                        name = "YouTube",
                        description = stringResource(R.string.supported_platforms_youtube_desc)
                    )
                    PlatformRow(
                        icon = Icons.Default.PlayArrow,
                        name = "Facebook",
                        description = stringResource(R.string.supported_platforms_facebook_desc)
                    )
                    PlatformRow(
                        icon = Icons.Default.PhotoCamera,
                        name = "Instagram",
                        description = stringResource(R.string.supported_platforms_instagram_desc)
                    )
                    PlatformRow(
                        icon = Icons.Default.PlayArrow,
                        name = "Twitter / X",
                        description = stringResource(R.string.supported_platforms_twitter_desc)
                    )
                    PlatformRow(
                        icon = Icons.Default.MusicNote,
                        name = "TikTok",
                        description = stringResource(R.string.supported_platforms_tiktok_desc)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = buildAnnotatedString {
                            val full = stringResource(R.string.supported_platforms_footer)
                            val avIndex = full.indexOf("AV")
                            if (avIndex >= 0) {
                                append(full.substring(0, avIndex))
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                                    append("AV")
                                }
                                append(full.substring(avIndex + 2))
                            } else {
                                append(full)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            SettingsGroup(title = stringResource(R.string.settings_legal)) {
                SettingsRow(
                    title = stringResource(R.string.setting_legal_notice),
                    subtitle = stringResource(R.string.setting_legal_notice_sub),
                    trailing = {},
                    showDivider = false
                )
            }

            SettingsGroup(title = stringResource(R.string.settings_about)) {
                SettingsRow(
                    title = stringResource(R.string.setting_app_name),
                    subtitle = stringResource(R.string.setting_version_info, viewModel.appVersion),
                    trailing = {},
                    showDivider = true
                )
                SettingsRow(
                    title = stringResource(R.string.setting_engine_info),
                    subtitle = stringResource(R.string.setting_engine_info_sub),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSettingsPickerSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val current = LocaleManager.getPersistedLanguage(context)
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
private fun PlatformRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = name,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
