package com.otter.app.util

import android.content.Context
import com.otter.app.R
import com.otter.app.ui.screens.home.FetchErrorClassifier

/**
 * Turns a raw extraction/download failure into the sentence the user reads.
 *
 * One place for the wording, so the Quick Download window and the Home screen say the
 * same thing about the same failure. When a caller has already composed an explanation
 * after diagnosing the failure ([composed]), that text is used as-is: classifying it
 * would replace a specific reason with a generic hint.
 */
object FriendlyErrors {

    fun message(context: Context, rawError: String, url: String, composed: String? = null): String {
        if (!composed.isNullOrBlank()) return composed
        val platform = FetchErrorClassifier.detectPlatform(url)
        return when (FetchErrorClassifier.classify(rawError, platform)) {
            FetchErrorClassifier.Kind.NO_DOWNLOADABLE_MEDIA ->
                context.getString(R.string.error_no_downloadable_media)

            FetchErrorClassifier.Kind.EXTRACTION_FAILED ->
                context.getString(R.string.error_extraction_failed)

            FetchErrorClassifier.Kind.INSUFFICIENT_STORAGE ->
                context.getString(R.string.error_storage_full)

            FetchErrorClassifier.Kind.NETWORK_TIMEOUT ->
                context.getString(R.string.error_network_timeout)

            FetchErrorClassifier.Kind.NO_INTERNET ->
                context.getString(R.string.error_no_internet)

            FetchErrorClassifier.Kind.VIDEO_UNAVAILABLE ->
                context.getString(R.string.error_video_unavailable)

            FetchErrorClassifier.Kind.PRIVATE_VIDEO ->
                context.getString(R.string.error_private_video)

            FetchErrorClassifier.Kind.VIDEO_REMOVED ->
                context.getString(R.string.error_video_removed)

            FetchErrorClassifier.Kind.COPYRIGHT ->
                context.getString(R.string.error_copyright)

            FetchErrorClassifier.Kind.AUTH_REQUIRED ->
                context.getString(R.string.error_auth_required, cookieHint(context, platform))

            FetchErrorClassifier.Kind.REGION_BLOCKED ->
                context.getString(R.string.error_region_blocked)

            FetchErrorClassifier.Kind.LIVE_NOT_STARTED ->
                context.getString(R.string.error_live_not_started)

            FetchErrorClassifier.Kind.LIVE_ENDED ->
                context.getString(R.string.error_live_ended)

            FetchErrorClassifier.Kind.CODEC_ISSUE ->
                context.getString(R.string.error_codec_issue)

            FetchErrorClassifier.Kind.FORMAT_UNAVAILABLE ->
                context.getString(R.string.error_format_unavailable)

            FetchErrorClassifier.Kind.INSTAGRAM_LOGIN ->
                context.getString(R.string.error_instagram_login)

            FetchErrorClassifier.Kind.TWITTER_RATE_LIMIT ->
                context.getString(R.string.error_twitter_rate_limit)

            FetchErrorClassifier.Kind.TIKTOK_EXTRACTOR ->
                context.getString(R.string.error_tiktok_extractor)

            FetchErrorClassifier.Kind.FACEBOOK_LOGIN ->
                context.getString(R.string.error_facebook_login)

            FetchErrorClassifier.Kind.BOT_CHALLENGE ->
                context.getString(R.string.error_bot_challenge)

            FetchErrorClassifier.Kind.SOURCE_RESTRICTED ->
                context.getString(R.string.error_source_restricted)

            FetchErrorClassifier.Kind.GENERIC ->
                context.getString(R.string.error_generic_with_hint)
        }
    }

    private fun cookieHint(context: Context, platform: Int): String = when (platform) {
        FetchErrorClassifier.PLATFORM_INSTAGRAM -> context.getString(R.string.cookie_hint_instagram)
        FetchErrorClassifier.PLATFORM_FACEBOOK -> context.getString(R.string.cookie_hint_facebook)
        FetchErrorClassifier.PLATFORM_TWITTER -> context.getString(R.string.cookie_hint_twitter)
        FetchErrorClassifier.PLATFORM_YOUTUBE -> context.getString(R.string.cookie_hint_youtube_age)
        else -> context.getString(R.string.cookie_hint_generic)
    }
}
