package com.vidgrab.app.data.model

data class VideoFormat(
    val formatId: String,
    val displayName: String,
    val ext: String,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    val tbr: Double?,
    val isAudioOnly: Boolean,
    val filesize: Long?,
    val formatNote: String = "",
    val vcodec: String = "",
    val acodec: String = ""
) {
    val qualityLabel: String
        get() = when {
            isAudioOnly -> "Audio • ${ext.uppercase()}"
            height != null && height >= 2160 -> "4K • ${ext.uppercase()}"
            height != null && height >= 1440 -> "2K • ${ext.uppercase()}"
            height != null && height >= 1080 -> "1080p • ${ext.uppercase()}"
            height != null && height >= 720 -> "720p • ${ext.uppercase()}"
            height != null -> "${height}p • ${ext.uppercase()}"
            else -> displayName
        }

    val filesizeText: String
        get() = filesize?.let { bytes ->
            when {
                bytes > 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
                bytes > 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
                bytes > 1_024L -> "%.1f KB".format(bytes / 1_024.0)
                else -> "$bytes B"
            }
        } ?: ""

    val isVideoOnly: Boolean
        get() = (acodec == "none" || acodec.isEmpty()) && vcodec.isNotEmpty() && vcodec != "none"

    val isMuxed: Boolean
        get() = !isAudioOnly && !isVideoOnly
}
