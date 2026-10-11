package com.otter.app.engine

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale

/**
 * The HTTP half of an image download, kept free of Android APIs so the exact code
 * that saves Instagram photos can also be exercised from a plain JVM test.
 */
object ImageDownloadCore {

    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.6099.144 Mobile Safari/537.36"

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "avif", "gif", "bmp", "tif", "tiff", "svg")

    class ImageHttpException(val statusCode: Int) : IOException("Image download failed with HTTP $statusCode")

    /**
     * Streams [url] into [destination]. Returns the response content type.
     * Throws when the response is not a picture, so callers never keep HTML errors.
     */
    fun fetchImage(
        url: String,
        destination: File,
        refererUrl: String? = null,
        userAgent: String = DEFAULT_UA,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 25_000
    ): String {
        val sourceUrl = URL(url)
        if (sourceUrl.protocol != "http" && sourceUrl.protocol != "https") {
            throw IOException("Unsupported image URL scheme")
        }
        val referer = refererUrl?.let { runCatching { URL(it) }.getOrNull() }
            ?.takeIf { it.protocol == "http" || it.protocol == "https" }
            ?.toExternalForm()

        var connection: HttpURLConnection? = null
        try {
            val httpConnection = sourceUrl.openConnection() as? HttpURLConnection
                ?: throw IOException("Unsupported image connection")
            connection = httpConnection
            httpConnection.connectTimeout = connectTimeoutMs
            httpConnection.readTimeout = readTimeoutMs
            httpConnection.instanceFollowRedirects = true
            httpConnection.requestMethod = "GET"
            httpConnection.setRequestProperty("User-Agent", userAgent)
            httpConnection.setRequestProperty(
                "Accept",
                "image/avif,image/webp,image/png,image/jpeg,image/gif,image/*;q=0.9,*/*;q=0.5"
            )
            httpConnection.setRequestProperty("Accept-Encoding", "identity")
            httpConnection.setRequestProperty("Connection", "keep-alive")
            referer?.let { httpConnection.setRequestProperty("Referer", it) }

            val status = httpConnection.responseCode
            if (status !in 200..299) throw ImageHttpException(status)
            val contentType = httpConnection.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)
                .orEmpty()

            destination.parentFile?.mkdirs()
            httpConnection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }

            if (destination.length() == 0L || !isLikelyImageFile(destination, contentType, url)) {
                throw IOException("The server response was not an image")
            }
            return contentType
        } finally {
            connection?.disconnect()
        }
    }

    fun isRetryable(error: Exception): Boolean {
        if (error is ImageHttpException) return error.statusCode == 429 || error.statusCode >= 500
        val message = error.message.orEmpty()
        return listOf(
            "timeout", "timed out", "connection reset", "connection refused",
            "network", "unexpected end", "eof"
        ).any { message.contains(it, ignoreCase = true) } ||
            error is SocketTimeoutException ||
            error is UnknownHostException ||
            error is java.io.EOFException
    }

    fun isLikelyImageFile(file: File, contentType: String, sourceUrl: String): Boolean {
        if (sniffImageExtension(file) != null) return true
        if (contentType.startsWith("image/")) return true
        if (contentType.contains("html") || contentType.contains("json") || contentType.startsWith("text/")) return false
        return extensionFromUrl(sourceUrl) != null &&
            (contentType.isBlank() || contentType == "application/octet-stream" || contentType == "binary/octet-stream")
    }

    fun extensionFor(contentType: String, sourceUrl: String, file: File): String {
        val mimeExtension = when (contentType.substringBefore(';').lowercase(Locale.ROOT)) {
            "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg"
            "image/png", "image/x-png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/avif" -> "avif"
            "image/bmp", "image/x-ms-bmp" -> "bmp"
            "image/tiff" -> "tif"
            "image/svg+xml" -> "svg"
            else -> null
        }
        return mimeExtension
            ?: sniffImageExtension(file)
            ?: extensionFromUrl(sourceUrl)?.let { if (it == "jpeg") "jpg" else it }
            ?: "jpg"
    }

    fun extensionFromUrl(url: String): String? {
        val extension = runCatching { URL(url).path }
            .getOrNull()
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?: return null
        return extension.takeIf { it in imageExtensions }
    }

    fun sniffImageExtension(file: File): String? = runCatching {
        val header = ByteArray(16)
        val count = file.inputStream().use { it.read(header) }
        if (count >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
            return@runCatching "jpg"
        }
        if (count >= 8 && header.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
        ) return@runCatching "png"
        if (count >= 6 && String(header, 0, 6, Charsets.US_ASCII).startsWith("GIF8")) return@runCatching "gif"
        if (count >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) return@runCatching "webp"
        if (count >= 2 && String(header, 0, 2, Charsets.US_ASCII) == "BM") return@runCatching "bmp"
        if (count >= 4 && (
                (header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() &&
                    header[2] == '*'.code.toByte() && header[3] == 0.toByte()) ||
                    (header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() &&
                        header[2] == 0.toByte() && header[3] == '*'.code.toByte())
                )
        ) return@runCatching "tif"
        if (count >= 4 && String(header, 0, 4, Charsets.US_ASCII).startsWith("RIFF")) return@runCatching null
        val prefix = String(header, 0, count.coerceAtLeast(0), Charsets.UTF_8).trimStart()
        if (prefix.startsWith("<svg", ignoreCase = true) || prefix.startsWith("<?xml", ignoreCase = true)) {
            return@runCatching "svg"
        }
        null
    }.getOrNull()
}
