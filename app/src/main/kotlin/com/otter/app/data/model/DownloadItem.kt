package com.otter.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloadStatus {
    COMPLETED,
    STOPPED,
    FAILED
}

@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val filePath: String,
    val fileSize: Long,
    val duration: Long,
    val formatId: String,
    val ext: String,
    val isAudioOnly: Boolean,
    val hasSubtitles: Boolean = false,
    val downloadedAt: Long = System.currentTimeMillis(),
    val status: String = DownloadStatus.COMPLETED.name,
    val progressAtStop: Float = 0f,
    val downloadedBytesAtStop: Long = 0L,
    val totalBytesAtStop: Long = 0L,
    val outputDir: String = "",
    val resolvedFilename: String = "",
    val canResume: Boolean = false
)
