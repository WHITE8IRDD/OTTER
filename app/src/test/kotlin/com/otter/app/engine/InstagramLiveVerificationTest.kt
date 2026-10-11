package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * End-to-end check against the live post from the bug report. It runs the same
 * code the app runs (InstagramPostFetcher + ImageDownloadCore) so a green result
 * really means "the carousel was read and the selected photos were written to a
 * download folder".
 *
 * Network tests are opt-in: `./gradlew :app:testDebugUnitTest -PotterLive=true`.
 */
class InstagramLiveVerificationTest {

    private val postUrl =
        "https://www.instagram.com/p/DeEo7SjjLYz/?img_index=2&mdxt=MTB3NWI3aGFiYWg3eQ=="

    private val live: Boolean = System.getProperty("otter.live") == "true"

    @Test
    fun carouselSlidesAreReadAndDownloadedToDisk() {
        assumeTrue("live Instagram verification is opt-in (-PotterLive=true)", live)

        val fetched = InstagramPostFetcher().fetch(postUrl)
        val report = StringBuilder()
        report.appendLine("LIVE-CHECK url=$postUrl")
        report.appendLine("LIVE-CHECK http=${fetched.httpStatuses.joinToString(",")}")
        report.appendLine("LIVE-CHECK sources=${fetched.sources.joinToString(",")} note=${fetched.note}")
        report.appendLine("LIVE-CHECK slides=${fetched.imageUrls.size} declared=${fetched.expectedCount}")

        assumeTrue(
            "Instagram did not return the post from this network: ${fetched.note ?: "no media"} " +
                "(http=${fetched.httpStatuses.joinToString(",")})",
            fetched.imageUrls.isNotEmpty()
        )

        // 1. Every carousel slide must be readable, not just the first one.
        assertTrue(
            "expected a multi-slide carousel, got ${fetched.imageUrls.size} slide(s)",
            fetched.imageUrls.size >= 2
        )
        assertTrue(
            "payload declared ${fetched.expectedCount} slides but only ${fetched.imageUrls.size} were read",
            fetched.imageUrls.size >= fetched.expectedCount
        )
        // 2. Signed query strings must survive parsing; without them the CDN answers 403.
        fetched.imageUrls.forEach { url ->
            assertTrue("CDN url lost its signature: $url", url.contains("?"))
        }
        // 3. No duplicate slides in the picker.
        assertEquals(fetched.imageUrls.size, fetched.imageUrls.distinctBy(InstagramMediaParser::mediaKey).size)

        // 4. Download every selected slide into a real folder, like "Download Selected".
        val downloadDir = Files.createTempDirectory("otter-instagram-live").toFile()
        val savedFiles = mutableListOf<File>()
        try {
            fetched.imageUrls.forEachIndexed { index, url ->
                val destination = File(downloadDir, "OTTER-26.1_${index + 1}.jpg")
                val contentType = ImageDownloadCore.fetchImage(
                    url = url,
                    destination = destination,
                    refererUrl = postUrl
                )
                val extension = ImageDownloadCore.extensionFor(contentType, url, destination)
                if (extension != "jpg") {
                    val renamed = File(downloadDir, "OTTER-26.1_${index + 1}.$extension")
                    destination.renameTo(renamed)
                    savedFiles.add(renamed)
                } else {
                    savedFiles.add(destination)
                }
                report.appendLine(
                    "LIVE-CHECK saved=${destination.name} bytes=${destination.length()} type=$contentType"
                )
            }

            assertEquals(fetched.imageUrls.size, savedFiles.size)
            savedFiles.forEach { file ->
                assertTrue("download folder entry missing: ${file.name}", file.exists())
                assertTrue("empty download: ${file.name}", file.length() > 5_000)
                assertTrue(
                    "not a picture: ${file.name}",
                    ImageDownloadCore.sniffImageExtension(file) != null
                )
            }
        } finally {
            downloadDir.deleteRecursively()
        }
        println(report.toString().trimEnd())
    }
}
