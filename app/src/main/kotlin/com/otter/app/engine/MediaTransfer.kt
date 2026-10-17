package com.otter.app.engine

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection

/** Bounded-memory transfer. Neither a MIME label nor a filename is proof of media contents. */
object MediaTransfer {
    fun copyResponse(
        connection: HttpURLConnection,
        destination: File,
        kind: MediaFileValidator.Kind,
        checkCancelled: () -> Unit = {},
        onBytes: (Long, Long) -> Unit = { _, _ -> }
    ): MediaFileValidator.Outcome.Valid {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("Expected complete media response, got HTTP ${connection.responseCode}")
        }
        val expected = connection.contentLengthLong
        var received = 0L
        connection.inputStream.use { input ->
            destination.outputStream().buffered(65_536).use { output ->
                val buffer = ByteArray(65_536)
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    output.write(buffer, 0, n)
                    received += n
                    onBytes(received, expected)
                }
            }
        }
        checkCancelled()
        if (expected >= 0 && received != expected) throw IOException("Unexpected end of media: $received of $expected bytes")
        return when (val result = MediaFileValidator.validate(destination, kind)) {
            is MediaFileValidator.Outcome.Invalid -> throw IOException(result.reason)
            is MediaFileValidator.Outcome.Valid -> result
        }
    }
}
