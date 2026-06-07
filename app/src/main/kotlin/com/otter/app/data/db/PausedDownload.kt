package com.otter.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paused_downloads")
data class PausedDownload(
    @PrimaryKey
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val formatId: String,
    val outputDir: String,
    val progress: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val hasSubtitles: Boolean = false,
    val subtitleLangs: String = "",
    val autoCaptionLangs: String = "",
    val embedSubtitles: Boolean = false,
    val subtitleFormat: String = "srt",
    val pausedAt: Long = System.currentTimeMillis()
)
