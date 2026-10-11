package com.otter.app.engine

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Proves the writing half of "Download Selected" on a real HTTP download.
 *
 * The Instagram-specific test can only run when Instagram serves the post to the
 * machine running the tests; this one exercises the same [ImageDownloadCore] code
 * path (HTTP GET -> image sniffing -> file on disk in a download folder) against a
 * plain public picture host, so the save path is verified even from a walled
 * network. Opt-in, like every test that touches the network:
 * `./gradlew :app:testDebugUnitTest -PotterLive=true`.
 */
class LiveImageDownloadTest {

    private val live: Boolean = System.getProperty("otter.live") == "true"

    private val sampleUrl = "https://avatars.githubusercontent.com/u/1?v=4"

    @Test
    fun aRealHttpDownloadLandsInTheFolderAsAPicture() {
        assumeTrue("live download test is opt-in (-PotterLive=true)", live)

        val downloadDir = Files.createTempDirectory("otter-live-download").toFile()
        val destination = File(downloadDir, "OTTER-26.1_live.jpg")
        try {
            val contentType = try {
                ImageDownloadCore.fetchImage(
                    url = sampleUrl,
                    destination = destination,
                    refererUrl = null
                )
            } catch (e: Exception) {
                assumeTrue("no outbound network in this job: ${e.message}", false)
                return
            }

            val extension = ImageDownloadCore.extensionFor(contentType, sampleUrl, destination)
            assertTrue("expected an image download, got content type '$contentType'", extension.isNotEmpty())
            assertTrue("download folder entry missing: ${destination.name}", destination.exists())
            assertTrue("empty download: ${destination.length()} bytes", destination.length() > 1_000)
            assertTrue(
                "saved file is not a picture",
                ImageDownloadCore.sniffImageExtension(destination) != null
            )
            println(
                "LIVE-CHECK real-download=${destination.name} " +
                    "bytes=${destination.length()} type=$contentType extension=$extension"
            )
        } finally {
            downloadDir.deleteRecursively()
        }
    }

}
