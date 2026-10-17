package com.otter.app.engine

import java.io.File

/**
 * Proof that a finished download is actually a file the user can open.
 *
 * The engine exiting 0 and a path existing is not the same thing as a playable video: a CDN that
 * answers an expired signed URL with an error page, an interrupted merge, or a poster saved under
 * a `.mp4` name all leave a file on disk. This is the last check before a download is reported as a
 * success, so a green "Download complete" means the bytes are there, the container is the kind that
 * was asked for, and no `.part` sibling says the write stopped early.
 *
 * Typed downloads fail closed on unknown contents. This checks container identity, not
 * decodability; Android track inspection and device playback tests provide stronger checks.
 */
object MediaFileValidator {

    enum class Kind { VIDEO, AUDIO, IMAGE, ANY }

    sealed interface Outcome {
        data class Valid(val sizeBytes: Long, val container: String) : Outcome
        data class Invalid(val reason: String) : Outcome
    }

    private val imageContainers = setOf("jpeg", "png", "gif", "webp", "bmp", "avif", "heif", "tiff", "svg")
    private val videoContainers = setOf("mp4", "matroska", "webm", "mpegts", "avi", "flv", "mov")
    /** A video container also holds an extracted soundtrack, so it is accepted for audio. */
    private val audioContainers = setOf("mp3", "id3", "adts", "ogg", "flac", "wav", "m4a", "aiff") +
        videoContainers

    /** Container identity for validation and extension selection; never derived from HTTP labels. */
    fun containerOf(file: File): String? = sniffContainer(firstBytes(file, 512))

    fun extensionFor(container: String): String? = when (container) {
        "jpeg" -> "jpg"
        "heif" -> "heic"
        "tiff" -> "tif"
        "matroska" -> "mkv"
        "mpegts" -> "ts"
        "id3" -> "mp3"
        in imageContainers, in videoContainers, in audioContainers -> container
        else -> null
    }

    /** Unknown headers return null and fail typed validation. */
    fun sniffContainer(header: ByteArray): String? {
        if (header.size < 12) return null
        fun ascii(offset: Int, length: Int): String =
            String(header, offset, length, Charsets.ISO_8859_1)

        return when {
            String(header, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t').let {
                it.startsWith("<svg", true) || (it.startsWith("<?xml", true) && it.contains("<svg", true))
            } -> "svg"
            String(header, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t').let {
                it.startsWith("<") || it.startsWith("{") || it.startsWith("[")
            } -> "text"
            ascii(4, 4) == "ftyp" -> when (ascii(8, 4)) {
                "avif", "avis" -> "avif"
                "heic", "heix", "hevc", "hevx", "mif1", "msf1" -> "heif"
                "M4A ", "M4B " -> "m4a"
                "qt  " -> "mov"
                else -> "mp4"
            }
            ascii(0, 3) == "GIF" -> "gif"
            ascii(0, 5) == "%FLAC" || ascii(0, 4) == "fLaC" -> "flac"
            ascii(0, 4) == "OggS" -> "ogg"
            ascii(0, 3) == "ID3" -> "id3"
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> "webp"
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WAVE" -> "wav"
            ascii(0, 4) == "FORM" -> "aiff"
            header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() -> "jpeg"
            header[0] == 0x89.toByte() && ascii(1, 3) == "PNG" -> "png"
            header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() -> if (String(header, Charsets.ISO_8859_1).contains("webm")) "webm" else "matroska"
            header.size > 188 && header[0] == 0x47.toByte() && header[188] == 0x47.toByte() -> "mpegts"
            header[0] == 0xFF.toByte() && (header[1].toInt() and 0xF6) == 0xF2 -> "mp3"
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "AVI " -> "avi"
            ascii(0, 2) == "BM" -> "bmp"
            ascii(0, 4) == "II*\u0000" || ascii(0, 4) == "MM\u0000*" -> "tiff"
            ascii(0, 3) == "FLV" -> "flv"
            else -> null
        }
    }

    private fun firstBytes(file: File, count: Int): ByteArray {
        return runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(count)
                var read = 0
                while (read < count) {
                    val n = input.read(buffer, read, count - read)
                    if (n < 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
        }.getOrDefault(ByteArray(0))
    }

    /**
     * @param file the file the download produced.
     * @param kind what the app asked the source for.
     * @param minSizeBytes a real media file is never a few bytes; 512 is well under any playable
     *                     frame and well over an error page written to the wrong name.
     */
    fun validate(file: File, kind: Kind = Kind.ANY, minSizeBytes: Long = 512L): Outcome {
        if (!file.isFile) return Outcome.Invalid("the file is not there")
        val size = file.length()
        if (size <= 0L) return Outcome.Invalid("the file is empty")
        if (size < minSizeBytes && kind != Kind.IMAGE) return Outcome.Invalid("the file is only $size bytes")

        val unfinished = listOf(".part", ".ytdl", ".temp").firstNotNullOfOrNull { suffix ->
            File(file.parentFile, file.name + suffix).takeIf { it.isFile && it.length() > 0 }
        }
        if (unfinished != null) return Outcome.Invalid("the download did not finish (${unfinished.name})")

        val container = containerOf(file) ?: return if (kind == Kind.ANY) Outcome.Valid(size, "unknown")
            else Outcome.Invalid("unrecognised media contents")
        if (container == "text") return Outcome.Invalid("the file is a text response, not media")

        val accepted = when (kind) {
            Kind.VIDEO -> container in videoContainers
            Kind.AUDIO -> container in audioContainers
            Kind.IMAGE -> container in imageContainers
            Kind.ANY -> true
        }
        if (!accepted) {
            val word = when (kind) {
                Kind.VIDEO -> "video"
                Kind.AUDIO -> "audio"
                Kind.IMAGE -> "picture"
                Kind.ANY -> "media"
            }
            return Outcome.Invalid("the file is a $container, not a $word")
        }
        return Outcome.Valid(size, container)
    }
}
