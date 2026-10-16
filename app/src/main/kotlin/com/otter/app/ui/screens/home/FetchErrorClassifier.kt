package com.otter.app.ui.screens.home

/**
 * Classifies the raw failure text an extractor produced into the reason the user
 * actually sees.
 *
 * This is deliberately free of Android types so the mapping can be unit tested
 * against the exact strings real sites return; [HomeViewModel] only turns the
 * resulting [Kind] into a localised message.
 *
 * The mapping matters because the advice attached to a failure has to match its
 * cause. A bot challenge ("Sign in to confirm you're not a bot") was previously
 * reported as an age restriction, which sent users to add cookies for a video that
 * was never age restricted and whose real problem is the network the device is on.
 */
object FetchErrorClassifier {

    const val PLATFORM_YOUTUBE = 0
    const val PLATFORM_FACEBOOK = 1
    const val PLATFORM_INSTAGRAM = 2
    const val PLATFORM_TWITTER = 3
    const val PLATFORM_TIKTOK = 4
    const val PLATFORM_OTHER = 5

    enum class Kind {
        NETWORK_TIMEOUT,
        NO_INTERNET,
        VIDEO_UNAVAILABLE,
        PRIVATE_VIDEO,
        VIDEO_REMOVED,
        COPYRIGHT,
        AUTH_REQUIRED,
        REGION_BLOCKED,
        LIVE_NOT_STARTED,
        LIVE_ENDED,
        CODEC_ISSUE,
        FORMAT_UNAVAILABLE,
        INSTAGRAM_LOGIN,
        TWITTER_RATE_LIMIT,
        TIKTOK_EXTRACTOR,
        FACEBOOK_LOGIN,
        /** The site asked this device to prove it is not an automated client. */
        BOT_CHALLENGE,
        /** The site's own anti-automation refused to answer at all. */
        SOURCE_RESTRICTED,
        /** The link works, the page loaded, and there is nothing in it to download. */
        NO_DOWNLOADABLE_MEDIA,
        /** The engine could not read the page at all (a broken or outdated extractor). */
        EXTRACTION_FAILED,
        /** The device ran out of room while writing. */
        INSUFFICIENT_STORAGE,
        GENERIC
    }

    fun detectPlatform(url: String): Int = when {
        url.contains("youtube.com") || url.contains("youtu.be") -> PLATFORM_YOUTUBE
        url.contains("facebook.com") || url.contains("fb.watch") || url.contains("fb.com") -> PLATFORM_FACEBOOK
        url.contains("instagram.com") -> PLATFORM_INSTAGRAM
        url.contains("twitter.com") || url.contains("x.com") -> PLATFORM_TWITTER
        url.contains("tiktok.com") -> PLATFORM_TIKTOK
        else -> PLATFORM_OTHER
    }

    fun classify(rawError: String, url: String): Kind = classify(rawError, detectPlatform(url))

    fun classify(rawError: String, platform: Int): Kind {
        val lower = rawError.lowercase()

        // Checked before the generic "sign in" branch below: several sites word a
        // bot challenge as a sign-in request, and reporting that as an age
        // restriction or a login wall sends the user down the wrong path.
        if (lower.contains("not a bot") || lower.contains("confirm you're not a bot") ||
            lower.contains("confirm you’re not a bot") || lower.contains("unusual traffic")
        ) {
            return Kind.BOT_CHALLENGE
        }

        return when {
            // Checked first: "no space left on device" reads like a download failure, and advice
            // about cookies or a retry is useless while the storage is full.
            lower.contains("no space left") || lower.contains("not enough free space") ||
                lower.contains("enospc") || lower.contains("storage space") ->
                Kind.INSUFFICIENT_STORAGE

            lower.contains("timed out") || lower.contains("connection refused") ->
                Kind.NETWORK_TIMEOUT

            lower.contains("unable to resolve host") || lower.contains("no internet") ->
                Kind.NO_INTERNET

            // TikTok answers a blocked automated request with "status code 0"; the
            // video is usually public, so this is a source restriction and not a
            // missing or region blocked video.
            lower.contains("status code 0") ->
                Kind.SOURCE_RESTRICTED

            lower.contains("video unavailable") || lower.contains("this video isn't available") ->
                Kind.VIDEO_UNAVAILABLE

            lower.contains("private video") ->
                Kind.PRIVATE_VIDEO

            lower.contains("removed") || lower.contains("404") || lower.contains("not found") ->
                Kind.VIDEO_REMOVED

            lower.contains("copyright") ->
                Kind.COPYRIGHT

            lower.contains("sign in") || lower.contains("login required") ||
                (lower.contains("age") && lower.contains("restrict")) ->
                Kind.AUTH_REQUIRED

            lower.contains("not available in your country") ||
                (lower.contains("geo") && lower.contains("restrict")) ->
                Kind.REGION_BLOCKED

            lower.contains("live event will begin") ->
                Kind.LIVE_NOT_STARTED

            lower.contains("dvr") || lower.contains("live stream has ended") ->
                Kind.LIVE_ENDED

            lower.contains("opus") && lower.contains("error") ->
                Kind.CODEC_ISSUE

            lower.contains("requested format not available") ->
                Kind.FORMAT_UNAVAILABLE

            // Nothing to take, as opposed to a page that refused to be read: the link is fine, the
            // media is not there, is not public, or is not a file the app can save.
            lower.contains("no video formats found") ||
                lower.contains("no readable slides") ||
                lower.contains("no downloadable media") ||
                (lower.contains("page") && lower.contains("video") && lower.contains("could not")) ->
                Kind.NO_DOWNLOADABLE_MEDIA

            // The extractor itself failed, which is a stale-engine problem rather than the user's.
            lower.contains("unable to extract") || lower.contains("extractor failed") ||
                lower.contains("not a valid json") || lower.contains("regex json") ->
                Kind.EXTRACTION_FAILED

            platform == PLATFORM_INSTAGRAM && lower.contains("login") ->
                Kind.INSTAGRAM_LOGIN

            platform == PLATFORM_TWITTER && lower.contains("rate") ->
                Kind.TWITTER_RATE_LIMIT

            platform == PLATFORM_TIKTOK && lower.contains("extract") ->
                Kind.TIKTOK_EXTRACTOR

            platform == PLATFORM_FACEBOOK && lower.contains("login") ->
                Kind.FACEBOOK_LOGIN

            else -> Kind.GENERIC
        }
    }
}
