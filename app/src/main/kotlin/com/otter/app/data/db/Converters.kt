package com.otter.app.data.db

import androidx.room.TypeConverter
import com.otter.app.data.model.VideoFormat

class Converters {

    private companion object {
        const val LIST_SEPARATOR = "\u001f"
    }
    @TypeConverter
    fun fromFormatList(formats: List<VideoFormat>?): String {
        if (formats == null) return ""
        return formats.joinToString("|") { f ->
            "${f.formatId},${f.displayName},${f.ext},${f.width ?: ""},${f.height ?: ""},${f.fps ?: ""},${f.tbr ?: ""},${f.isAudioOnly},${f.filesize ?: ""},${f.formatNote},${f.vcodec},${f.acodec}"
        }
    }

    /** Plain string lists (hashtags …) are stored separated by a unit separator. */
    @TypeConverter
    fun fromStringList(values: List<String>?): String =
        values?.joinToString(LIST_SEPARATOR).orEmpty()

    @TypeConverter
    fun toStringList(value: String?): List<String> =
        if (value.isNullOrEmpty()) emptyList()
        else value.split(LIST_SEPARATOR).filter { it.isNotEmpty() }

    @TypeConverter
    fun toFormatList(value: String?): List<VideoFormat> {
        if (value.isNullOrEmpty()) return emptyList()
        return value.split("|").mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size < 9) return@mapNotNull null
            try {
                VideoFormat(
                    formatId = parts[0],
                    displayName = parts[1],
                    ext = parts[2],
                    width = parts[3].toIntOrNull(),
                    height = parts[4].toIntOrNull(),
                    fps = parts[5].toDoubleOrNull(),
                    tbr = parts[6].toDoubleOrNull(),
                    isAudioOnly = parts[7].toBooleanStrictOrNull() ?: false,
                    filesize = parts[8].toLongOrNull(),
                    formatNote = parts.getOrElse(9) { "" },
                    vcodec = parts.getOrElse(10) { "" },
                    acodec = parts.getOrElse(11) { "" }
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
