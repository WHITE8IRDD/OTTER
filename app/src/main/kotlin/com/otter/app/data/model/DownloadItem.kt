package com.otter.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

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
    val status: String = "completed"
)
