package com.vidgrab.app.data.db

import androidx.room.TypeConverter
import com.vidgrab.app.data.model.VideoFormat

class Converters {
    @TypeConverter
    fun fromFormatList(formats: List<VideoFormat>?): String {
        if (formats == null) return ""
        return formats.joinToString("|") { f ->
            "${f.formatId},${f.displayName},${f.ext},${f.width ?: ""},${f.height ?: ""},${f.fps ?: ""},${f.tbr ?: ""},${f.isAudioOnly},${f.filesize ?: ""},${f.formatNote},${f.vcodec},${f.acodec}"
        }
    }

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
