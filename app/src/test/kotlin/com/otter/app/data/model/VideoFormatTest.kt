package com.otter.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The labels a format row is made of.
 *
 * A sheet full of "1080p • MP4" tells a user nothing: the engine reports frame size, codec, frame
 * rate and bitrate per format, and the only reason to look at them is to pick between two rows that
 * otherwise read the same.
 */
class VideoFormatTest {

    private fun format(
        height: Int?,
        width: Int? = null,
        ext: String = "mp4",
        fps: Double? = null,
        tbr: Double? = null,
        vcodec: String = "",
        acodec: String = "none",
        filesize: Long? = null,
        formatNote: String = "",
        isAudioOnly: Boolean = false
    ) = VideoFormat(
        formatId = "137",
        displayName = "1080p",
        ext = ext,
        width = width,
        height = height,
        fps = fps,
        tbr = tbr,
        isAudioOnly = isAudioOnly,
        filesize = filesize,
        formatNote = formatNote,
        vcodec = vcodec,
        acodec = acodec
    )

    @Test
    fun qualityLabelBucketsTheHeightAndNamesTheContainer() {
        assertEquals("4K • MP4", format(height = 2160).qualityLabel)
        assertEquals("1080p • MP4", format(height = 1080, ext = "mp4").qualityLabel)
        assertEquals("720p • WEBM", format(height = 720, ext = "webm").qualityLabel)
        assertEquals("360p • MP4", format(height = 360).qualityLabel)
        assertEquals("Audio • M4A", format(height = null, ext = "m4a", isAudioOnly = true).qualityLabel)
    }

    @Test
    fun detailTextCarriesSizeCodecAndBitrate() {
        val row = format(
            height = 1080,
            width = 1920,
            fps = 60.0,
            tbr = 4200.0,
            vcodec = "vp09.00.10.08",
            acodec = "opus"
        )

        assertEquals("1920×1080 • 60 fps • VP9 • Opus • 4200 kbps", row.detailText)
    }

    @Test
    fun codecNamesAreHumanWhereTheEngineIdsAreNot() {
        assertEquals("AV1", format(height = 720, vcodec = "av01.0.05M.08").detailText)
        assertTrue(format(height = 720, width = 1280, vcodec = "avc1.64001f").detailText.contains("H.264"))
        assertTrue(format(height = 720, width = 1280, vcodec = "hev1.1.6.L93.B0").detailText.contains("H.265"))
    }

    @Test
    fun anAudioRowShowsWhatItIsEncodedIn() {
        val audio = format(height = null, ext = "m4a", isAudioOnly = true, acodec = "mp4a.40.2", tbr = 129.0)

        assertEquals("AAC • 129 kbps", audio.detailText)
        // Without a codec from the source, the container is still better than an empty line.
        assertEquals("MP3", format(height = null, ext = "mp3", isAudioOnly = true, acodec = "").detailText)
    }

    @Test
    fun aMissingValueIsLeftOutInsteadOfShownAsZero() {
        assertEquals("1280×720", format(height = 720, width = 1280).detailText)
        assertEquals("", format(height = null).detailText)
    }

    @Test
    fun formatsAreGroupedIntoVideoOnlyAudioAndMuxed() {
        assertTrue(format(height = 1080, vcodec = "vp09", acodec = "none").isVideoOnly)
        assertTrue(format(height = null, ext = "m4a", isAudioOnly = true, acodec = "mp4a").isAudioOnly)
        assertTrue(format(height = 360, vcodec = "avc1", acodec = "mp4a").isMuxed)
    }

    @Test
    fun filesizeIsReadableAtBothEndsOfTheRange() {
        assertEquals("900 B", format(height = 10, filesize = 900L).filesizeText)
        assertEquals("1024 B", format(height = 10, filesize = 1024L).filesizeText)
        assertEquals("1.0 KB", format(height = 10, filesize = 1025L).filesizeText)
        assertEquals("12.5 MB", format(height = 10, filesize = 13_107_200L).filesizeText)
        assertEquals("2.0 GB", format(height = 10, filesize = 2_147_483_648L).filesizeText)
        assertEquals("", format(height = 10).filesizeText)
    }
}
