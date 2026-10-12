package com.otter.app.engine

/**
 * Builds the yt-dlp format spec for a format the user picked.
 *
 * Kept apart from the view model so the rules can be tested without Android: this is
 * the difference between one stream and two, and getting it wrong downloads the
 * soundtrack twice and forces a merge the file never needed.
 */
object FormatSpec {

    /**
     * @param formatId the yt-dlp id of the picked format, or an already combined spec
     *                 such as `bestvideo+bestaudio`.
     * @param isAudioOnly the pick is an audio track.
     * @param isMuxed the pick carries video *and* audio in one file.
     * @param audioTrackId a specific audio track to pair with, when one was chosen.
     */
    fun forVideo(
        formatId: String,
        isAudioOnly: Boolean,
        isMuxed: Boolean,
        audioTrackId: String? = null
    ): String {
        if (isAudioOnly) return audioTrackId ?: "bestaudio[ext=m4a]/bestaudio"

        // Already one file, or already a combined spec: pair it with nothing.
        if (isMuxed || formatId.contains("+")) return "$formatId/best"

        // Video only, so it needs an audio stream and a merge to go with it.
        return "$formatId+${audioTrackId ?: "bestaudio"}/best"
    }
}
