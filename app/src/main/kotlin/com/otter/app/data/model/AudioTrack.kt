package com.otter.app.data.model

data class AudioTrack(
    val formatId: String,
    val languageCode: String,
    val languageName: String,
    val isOriginal: Boolean,
    val isDubbed: Boolean,
    val codec: String,
    val bitrate: Int?,
    val filesize: Long?
) {
    val displayName: String
        get() = buildString {
            append(languageName)
            if (isOriginal) append(" (Original)")
            if (isDubbed) append(" (Dubbed)")
        }
}
