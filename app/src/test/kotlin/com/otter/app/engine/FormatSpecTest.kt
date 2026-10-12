package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A format spec decides how many streams yt-dlp fetches. Asking for an audio stream
 * to go with a file that already carries one downloads the soundtrack twice and forces
 * a merge, which is why this is worth pinning down.
 */
class FormatSpecTest {

    @Test
    fun muxedFormatIsFetchedOnItsOwn() {
        assertEquals(
            "22/best",
            FormatSpec.forVideo(formatId = "22", isAudioOnly = false, isMuxed = true)
        )
    }

    @Test
    fun videoOnlyFormatIsPairedWithAudio() {
        assertEquals(
            "137+bestaudio/best",
            FormatSpec.forVideo(formatId = "137", isAudioOnly = false, isMuxed = false)
        )
    }

    @Test
    fun aChosenAudioTrackReplacesTheDefault() {
        assertEquals(
            "137+251/best",
            FormatSpec.forVideo(
                formatId = "137",
                isAudioOnly = false,
                isMuxed = false,
                audioTrackId = "251"
            )
        )
    }

    @Test
    fun combinedSpecsAreNotPairedWithAnything() {
        assertEquals(
            "bestvideo+bestaudio/best",
            FormatSpec.forVideo(formatId = "bestvideo+bestaudio", isAudioOnly = false, isMuxed = false)
        )
    }

    @Test
    fun anAudioPickIsJustTheAudio() {
        assertEquals(
            "bestaudio[ext=m4a]/bestaudio",
            FormatSpec.forVideo(formatId = "140", isAudioOnly = true, isMuxed = false)
        )
        assertEquals(
            "251",
            FormatSpec.forVideo(
                formatId = "140",
                isAudioOnly = true,
                isMuxed = false,
                audioTrackId = "251"
            )
        )
    }

    @Test
    fun theFallbackSpecIsPassedThrough() {
        val fallback = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
        assertEquals(
            "$fallback/best",
            FormatSpec.forVideo(formatId = fallback, isAudioOnly = false, isMuxed = false)
        )
    }
}
