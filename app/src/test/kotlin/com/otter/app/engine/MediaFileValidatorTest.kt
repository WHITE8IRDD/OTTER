package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Whether a finished download is a file the user can open.
 *
 * The app used to call a download successful when the path existed and had a size — which is how a
 * CDN error page written into `video.mp4` ended up in the gallery as a 1.5 KB "video".
 * Typed downloads must fail closed when the body cannot be identified as media.
 */
class MediaFileValidatorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File =
        File(folder.root, name).apply { writeBytes(bytes) }

    /** A header plus enough padding to clear the minimum size. */
    private fun media(header: String, totalBytes: Int = 4096): ByteArray {
        val bytes = ByteArray(totalBytes)
        header.toByteArray(Charsets.ISO_8859_1).copyInto(bytes)
        return bytes
    }

    @Test
    fun aMissingFileIsNotASuccess() {
        val outcome = MediaFileValidator.validate(File(folder.root, "never-written.mp4"))

        assertTrue(outcome is MediaFileValidator.Outcome.Invalid)
        assertTrue((outcome as MediaFileValidator.Outcome.Invalid).reason.contains("not there"))
    }

    @Test
    fun anEmptyFileIsNotASuccess() {
        val outcome = MediaFileValidator.validate(file("empty.mp4", ByteArray(0)), MediaFileValidator.Kind.VIDEO)

        assertTrue((outcome as MediaFileValidator.Outcome.Invalid).reason.contains("empty"))
    }

    @Test
    fun aFewBytesOfAnythingIsNotAVideo() {
        val outcome = MediaFileValidator.validate(
            file("tiny.mp4", ByteArray(120)),
            MediaFileValidator.Kind.VIDEO
        )

        assertTrue(
            "120 bytes cannot be a video",
            outcome is MediaFileValidator.Outcome.Invalid
        )
        assertTrue(
            (outcome as MediaFileValidator.Outcome.Invalid).reason.contains("bytes")
        )
    }

    @Test
    fun anErrorPageWrittenUnderAMediaNameIsCaught() {
        val page = media("<!DOCTYPE html>\n<html><head><title>403 Forbidden</title></head></html>", 1500)
        val outcome = MediaFileValidator.validate(file("video.mp4", page), MediaFileValidator.Kind.VIDEO)

        assertTrue("an HTML body must not be reported as a video", outcome is MediaFileValidator.Outcome.Invalid)
        assertTrue(outcome.reason().contains("text"))
    }

    @Test
    fun aLeftoverPartFileMeansTheWriteStoppedEarly() {
        val video = file("clip.mp4", media("    ftypisom", 4096))
        file("clip.mp4.part", ByteArray(2048))

        val outcome = MediaFileValidator.validate(video, MediaFileValidator.Kind.VIDEO)

        assertTrue(outcome is MediaFileValidator.Outcome.Invalid)
        assertTrue((outcome as MediaFileValidator.Outcome.Invalid).reason.contains("did not finish"))
    }

    @Test
    fun recognisedContainersAreReported() {
        assertEquals(
            "mp4",
            (
                MediaFileValidator.validate(
                    file("a.mp4", media("    ftypisom", 4096)),
                    MediaFileValidator.Kind.VIDEO
                ) as MediaFileValidator.Outcome.Valid
                ).container
        )
        assertEquals(
            "jpeg",
            MediaFileValidator.sniffContainer("\u00FF\u00D8".toByteArray(Charsets.ISO_8859_1) + ByteArray(22))
        )
        assertEquals(
            "png",
            MediaFileValidator.sniffContainer("\u0089PNG".toByteArray(Charsets.ISO_8859_1) + ByteArray(20))
        )
        assertEquals("matroska", MediaFileValidator.sniffContainer("\u001AE".toByteArray(Charsets.ISO_8859_1) + ByteArray(22)))
        assertEquals("flac", MediaFileValidator.sniffContainer("fLaC".toByteArray() + ByteArray(20)))
        assertEquals("ogg", MediaFileValidator.sniffContainer("OggS".toByteArray() + ByteArray(20)))
    }

    @Test
    fun anUnknownHeaderIsNotProofOfVideo() {
        // Size alone cannot turn arbitrary bytes into proof of a valid video.
        val outcome = MediaFileValidator.validate(
            file("odd.mkv", ByteArray(4096) { 0x01 }),
            MediaFileValidator.Kind.VIDEO
        )

        assertTrue(outcome is MediaFileValidator.Outcome.Invalid)
    }

    @Test
    fun aSoundtrackIsNotAVideoAndAVideoIsNotAPicture() {
        val ogg = file("track.ogg", media("OggS", 4096))
        assertTrue(
            MediaFileValidator.validate(ogg, MediaFileValidator.Kind.AUDIO)
                is MediaFileValidator.Outcome.Valid
        )
        val videoKind = MediaFileValidator.validate(ogg, MediaFileValidator.Kind.VIDEO)
        assertTrue(videoKind is MediaFileValidator.Outcome.Invalid)
        assertTrue((videoKind as MediaFileValidator.Outcome.Invalid).reason.contains("not a video"))

        val picture = MediaFileValidator.validate(
            file("clip.mp4", media("    ftypisom", 4096)),
            MediaFileValidator.Kind.IMAGE
        )
        assertTrue((picture as MediaFileValidator.Outcome.Invalid).reason.contains("not a picture"))
    }

    @Test
    fun aPictureIsAcceptedBelowTheVideoMinimum() {
        // A small thumbnail is still a picture the user asked for; the 512-byte floor is about
        // truncated writes, and it must not fail legitimate images.
        val small = file("tiny.jpg", media("\u00FF\u00D8", 200))

        assertTrue(
            MediaFileValidator.validate(small, MediaFileValidator.Kind.IMAGE, minSizeBytes = 512L)
                is MediaFileValidator.Outcome.Valid
        )
    }

    @Test
    fun sniffingNeedsEnoughBytesToBeSure() {
        assertNull(MediaFileValidator.sniffContainer(ByteArray(4)))
        assertNull(MediaFileValidator.sniffContainer(ByteArray(0)))
    }

    private fun MediaFileValidator.Outcome.reason(): String =
        (this as MediaFileValidator.Outcome.Invalid).reason
}
