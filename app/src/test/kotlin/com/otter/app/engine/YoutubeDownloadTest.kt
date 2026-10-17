package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Reading a YouTube video id out of a link, and picking the file a download produced
 * out of a folder that already holds other videos. Both are pure, so both run here.
 */
class YoutubeDownloadTest {

    @get:Rule
    val folder = TemporaryFolder()

    // ---------------------------------------------------------------- video id

    @Test
    fun videoIdIsReadFromAWatchUrl() {
        assertEquals(
            "dQw4w9WgXcQ",
            MediaUrlNormalizer.youtubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun videoIdIsReadFromAShortsUrl() {
        assertEquals(
            "dQw4w9WgXcQ",
            MediaUrlNormalizer.youtubeVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ")
        )
    }

    @Test
    fun videoIdIsReadFromASharedShortsUrlWithTracking() {
        assertEquals(
            "dQw4w9WgXcQ",
            MediaUrlNormalizer.youtubeVideoId(
                "https://www.youtube.com/shorts/dQw4w9WgXcQ?feature=share&si=abc123"
            )
        )
    }

    @Test
    fun videoIdIsReadFromAShortLink() {
        assertEquals(
            "dQw4w9WgXcQ",
            MediaUrlNormalizer.youtubeVideoId("https://youtu.be/dQw4w9WgXcQ")
        )
    }

    @Test
    fun otherPlatformsHaveNoVideoId() {
        for (url in listOf(
            "https://www.instagram.com/p/DeFLpLOAQd1/",
            "https://www.facebook.com/watch/?v=123456",
            "https://www.tiktok.com/@user/video/123456",
            "https://example.com/watch?v=dQw4w9WgXcQ"
        )) {
            assertEquals(url, "", MediaUrlNormalizer.youtubeVideoId(url))
        }
    }

    // --------------------------------------------------- finding the file written

    private fun media(name: String, modifiedAt: Long): File =
        folder.newFile(name).also { it.setLastModified(modifiedAt) }

    @Test
    fun theFileCarryingTheVideoIdIsPicked() {
        val wanted = media("Some Title [dQw4w9WgXcQ].mp4", 1_000L)
        media("Unrelated video [aaaaaaaaaaa].mp4", 9_000L)

        assertEquals(wanted, MediaFileLocator.findDownloaded(folder.root, "dQw4w9WgXcQ"))
    }

    @Test
    fun aNewerFileFromAnotherVideoIsNotPicked() {
        media("Another download [zzzzzzzzzzz].mkv", 9_000L)
        val wanted = media("Ours [dQw4w9WgXcQ].mp4", 1_000L)

        assertEquals(wanted, MediaFileLocator.findDownloaded(folder.root, "dQw4w9WgXcQ"))
    }

    @Test
    fun aMissingRequestedIdDoesNotReturnSomebodyElsesDownload() {
        media("Other [aaaaaaaaaaa].mp4", 9000L)
        assertNull(MediaFileLocator.findDownloaded(folder.root, "BaW_jenozKc"))
    }

    @Test
    fun withoutAnIdTheNewestMediaFileIsPicked() {
        media("older.mp4", 1_000L)
        val newest = media("newest.m4a", 9_000L)

        assertEquals(newest, MediaFileLocator.findDownloaded(folder.root, ""))
    }

    @Test
    fun nonMediaFilesAreIgnored() {
        folder.newFile("poster.jpg").setLastModified(9_000L)
        folder.newFile("video.f137.mp4.part").setLastModified(9_000L)
        val wanted = media("Ours [dQw4w9WgXcQ].mp4", 1_000L)

        assertEquals(wanted, MediaFileLocator.findDownloaded(folder.root, "dQw4w9WgXcQ"))
    }

    @Test
    fun nothingFoundInAnEmptyFolder() {
        assertNull(MediaFileLocator.findDownloaded(folder.root, "dQw4w9WgXcQ"))
    }

    // ------------------------------------------------------ reusing an extraction

    @Test
    fun aFreshExtractionMayBeReused() {
        val file = folder.newFile("dQw4w9WgXcQ.info.json")

        assertTrue(YoutubeInfoCache.isFresh(file, file.lastModified()))
        assertTrue(YoutubeInfoCache.isFresh(file, file.lastModified() + 60_000L))
    }

    @Test
    fun aStaleExtractionIsNotReused() {
        val file = folder.newFile("dQw4w9WgXcQ.info.json")

        // Older than the window: the format URLs inside will have expired.
        org.junit.Assert.assertFalse(
            YoutubeInfoCache.isFresh(file, file.lastModified() + YoutubeInfoCache.FRESHNESS_MS + 1L)
        )
    }

    @Test
    fun aMissingExtractionIsNotReused() {
        org.junit.Assert.assertFalse(YoutubeInfoCache.isFresh(File(folder.root, "nope.info.json")))
    }
}
