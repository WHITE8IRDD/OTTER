package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The download half of "Download Selected": extension handling and the guard that
 * stops an HTML error page from being saved as a photo.
 */
class ImageDownloadCoreTest {

    private fun tempFile(bytes: ByteArray, suffix: String = ".part"): File {
        val file = File.createTempFile("otter-test", suffix)
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }

    @Test
    fun detectsRealPictureSignatures() {
        val jpeg = tempFile(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10))
        assertEquals("jpg", ImageDownloadCore.sniffImageExtension(jpeg))

        val png = tempFile(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00))
        assertEquals("png", ImageDownloadCore.sniffImageExtension(png))

        val webp = tempFile("RIFF____WEBPVP8 ".toByteArray())
        assertEquals("webp", ImageDownloadCore.sniffImageExtension(webp))
    }

    @Test
    fun rejectsHtmlErrorPagesEvenWhenTheUrlLooksLikeAnImage() {
        val html = tempFile("<html><body>Forbidden</body></html>".toByteArray(), ".jpg")
        assertNull(ImageDownloadCore.sniffImageExtension(html))
        assertFalse(ImageDownloadCore.isLikelyImageFile(html, "text/html", "https://x.cdninstagram.com/v/a.jpg?oe=1"))
    }

    @Test
    fun keepsSignedQueryStringsOutOfTheExtensionLookup() {
        assertEquals("jpg", ImageDownloadCore.extensionFromUrl("https://x.cdninstagram.com/v/t51.2885-15/a.jpg?stp=dst-jpg&oe=1"))
        assertNull(ImageDownloadCore.extensionFromUrl("https://www.instagram.com/p/DeEo7SjjLYz/"))
        assertEquals("webp", ImageDownloadCore.extensionFor("image/webp", "https://x/a", tempFile(byteArrayOf(0x00))))
    }

    @Test
    fun retriesOnlyTransientFailures() {
        assertTrue(ImageDownloadCore.isRetryable(java.net.SocketTimeoutException("timeout")))
        assertTrue(ImageDownloadCore.isRetryable(ImageDownloadCore.ImageHttpException(503)))
        assertTrue(ImageDownloadCore.isRetryable(ImageDownloadCore.ImageHttpException(429)))
        assertFalse(ImageDownloadCore.isRetryable(ImageDownloadCore.ImageHttpException(403)))
        assertFalse(ImageDownloadCore.isRetryable(java.io.IOException("The server response was not an image")))
    }
}
