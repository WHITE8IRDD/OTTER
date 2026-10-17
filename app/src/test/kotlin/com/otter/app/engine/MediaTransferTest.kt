package com.otter.app.engine

import java.net.ServerSocket
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException

/** Actual HTTP bytes through the same transfer code used by Downloader; no source-site claims. */
class MediaTransferTest {
    @get:Rule val folder = TemporaryFolder()
    private fun media(name: String) = javaClass.getResourceAsStream("/media/$name")!!.use { it.readBytes() }

    private fun response(bytes: ByteArray, mime: String, status: Int = 200, declared: Long = bytes.size.toLong(), block: (HttpURLConnection) -> Unit) {
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit {
            server.accept().use { client ->
                val input = client.getInputStream().bufferedReader()
                input.readLine()
                var line = input.readLine()
                while (!line.isNullOrEmpty()) line = input.readLine()
                client.getOutputStream().use { output ->
                    output.write("HTTP/1.1 $status Test\r\nContent-Type: $mime\r\nContent-Length: $declared\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(bytes)
                    output.flush()
                }
            }
        }
        val c = URL("http://127.0.0.1:${server.localPort}/media").openConnection() as HttpURLConnection
        c.readTimeout = 1500
        try { block(c) } finally {
            c.disconnect()
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun downloadsRealVideoWithExactBytesAndProgress() {
        val bytes = media("clip.mp4")
        response(bytes, "application/octet-stream") { c ->
            val file = folder.newFile("output.mp4")
            var progress = 0L
            val result = MediaTransfer.copyResponse(c, file, MediaFileValidator.Kind.VIDEO, onBytes = { n, _ -> progress = n })
            assertEquals("mp4", result.container)
            assertEquals(bytes.size.toLong(), progress)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun downloadsRealPictureEvenWithWrongMimeLabel() {
        val bytes = media("picture.png")
        response(bytes, "video/mp4") { c ->
            val file = folder.newFile("output.png")
            assertEquals("png", MediaTransfer.copyResponse(c, file, MediaFileValidator.Kind.IMAGE).container)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun imageMasqueradingAsVideoFails() {
        response(media("picture.png"), "video/mp4") { c ->
            assertThrows(IOException::class.java) { MediaTransfer.copyResponse(c, folder.newFile(), MediaFileValidator.Kind.VIDEO) }
        }
    }

    @Test fun htmlAndJsonErrorBodiesFailEvenWithMediaHeaders() {
        for (body in listOf("   <!DOCTYPE html><h1>denied</h1>", "  {\"error\":\"expired\"}")) {
            response(body.toByteArray() + ByteArray(1024), "video/mp4") { c ->
                assertThrows(IOException::class.java) { MediaTransfer.copyResponse(c, folder.newFile(), MediaFileValidator.Kind.VIDEO) }
            }
        }
    }

    @Test fun truncatedResponseFails() {
        response(media("clip.mp4"), "video/mp4", declared = 20_000) { c ->
            assertThrows(IOException::class.java) { MediaTransfer.copyResponse(c, folder.newFile(), MediaFileValidator.Kind.VIDEO) }
        }
    }

    @Test fun partialAndUnavailableResponsesFail() {
        for (status in listOf(206, 403, 404, 429, 503)) response(media("clip.mp4"), "video/mp4", status) { c ->
            assertThrows(IOException::class.java) { MediaTransfer.copyResponse(c, folder.newFile(), MediaFileValidator.Kind.VIDEO) }
        }
    }

    @Test fun cancellationStopsCopyBeforeSuccess() {
        response(media("clip.mp4"), "video/mp4") { c ->
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                MediaTransfer.copyResponse(c, folder.newFile(), MediaFileValidator.Kind.VIDEO,
                    checkCancelled = { throw java.util.concurrent.CancellationException() })
            }
        }
    }

    @Test fun avifAndHeifAreNotMp4Videos() {
        for (brand in listOf("avif", "avis", "heic", "mif1")) {
            val file = folder.newFile().apply { writeBytes("    ftyp$brand".toByteArray() + ByteArray(1024)) }
            assertTrue(MediaFileValidator.validate(file, MediaFileValidator.Kind.VIDEO) is MediaFileValidator.Outcome.Invalid)
            assertTrue(MediaFileValidator.validate(file, MediaFileValidator.Kind.IMAGE) is MediaFileValidator.Outcome.Valid)
        }
    }
}
