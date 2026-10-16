package com.otter.app.data.model

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

    /**
     * The numbers that tell two rows of the same quality apart: frame size, codec, bitrate.
     *
     * The picker used to show "1080p • MP4" for four different formats, which is no help when the
     * choice is between 60 fps and 30, or between AV1 (small, slower to decode) and H.264 (big, plays
     * everywhere). The engine reported all of it; it was simply thrown away.
     */
    val detailText: String
        get() = listOfNotNull(
            if (width != null && height != null) "${width}×${height}" else null,
            fps?.takeIf { it >= 1.0 }?.let { "${it.toInt()} fps" },
            codecLabel(vcodec).takeIf { it.isNotEmpty() && !isAudioOnly },
            if (isAudioOnly) codecLabel(acodec).ifBlank { ext.uppercase() } else codecLabel(acodec)
                .takeIf { it.isNotEmpty() },
            tbr?.takeIf { it >= 1.0 }?.let { "${it.toInt()} kbps" },
            formatNote.takeIf { it.isNotBlank() }
        ).filter { it.isNotBlank() }.distinct().joinToString(" • ")

    /** The two-line version of a format id: "1080p • MP4" already says the container. */
    private fun codecLabel(codec: String): String {
        val lower = codec.lowercase()
        return when {
            lower.isEmpty() || lower == "none" -> ""
            lower.startsWith("vp9") || lower.startsWith("vp09") -> "VP9"
            lower.startsWith("vp8") || lower.startsWith("vp08") -> "VP8"
            lower.startsWith("av01") || lower.startsWith("av1") -> "AV1"
            lower.startsWith("avc") || lower.startsWith("h264") -> "H.264"
            lower.startsWith("hev") || lower.startsWith("h265") -> "H.265"
            lower.startsWith("opus") -> "Opus"
            lower.startsWith("mp4a") -> "AAC"
            lower.startsWith("vorbis") -> "Vorbis"
            lower.startsWith("aac") -> "AAC"
            lower.startsWith("mp3") -> "MP3"
            else -> codec.uppercase()
        }
    }

    val isVideoOnly: Boolean
        get() = (acodec == "none" || acodec.isEmpty()) && vcodec.isNotEmpty() && vcodec != "none"

    val isMuxed: Boolean
        get() = !isAudioOnly && !isVideoOnly
}
