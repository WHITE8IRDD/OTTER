package com.otter.app.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * Engine command-line decisions that two classes used to make twice, in different ways.
 *
 * `applySettings` and the platform argument pass both added `--concurrent-fragments`, and yt-dlp
 * keeps the last occurrence, so the value in Settings did nothing for YouTube, Instagram, TikTok,
 * Facebook and Vimeo. Emitting one value, resolved in one place, makes the setting mean what it says.
 *
 * Pure so the resolution rules are testable without Android.
 */
object EngineArgs {

    /** The value `UserPreferences` falls back to when the user never opened the setting. */
    const val PREFERENCE_DEFAULT_CONCURRENT_FRAGMENTS = 8

    /** yt-dlp accepts 1..N; above 16 makes small files slower and gets the source throttled. */
    const val MIN_FRAGMENTS = 1
    const val MAX_FRAGMENTS = 16

    /**
     * The user's value wins whenever it is not the untouched default; the platform default wins
     * otherwise. A user who lowered the number did so for a reason (a network or a source that
     * answers parallel segment requests with 403s), and that intent used to be discarded.
     */
    fun concurrentFragments(userValue: Int, platformValue: Int): Int {
        val user = userValue.takeIf { it != PREFERENCE_DEFAULT_CONCURRENT_FRAGMENTS }
            ?.coerceIn(MIN_FRAGMENTS, MAX_FRAGMENTS)
        return (user ?: platformValue).coerceIn(MIN_FRAGMENTS, MAX_FRAGMENTS)
    }

    /** Per-attempt socket timeout: patient enough for a cold extractor, short enough to retry. */
    fun socketTimeoutSeconds(baseSeconds: Int, attempt: Int, isLive: Boolean, capSeconds: Int = 30): Int {
        if (isLive) return capSeconds.coerceAtMost(30)
        return (baseSeconds + (attempt - 1) * 10).coerceAtMost(capSeconds).coerceAtLeast(5)
    }

    /**
     * Id of a running engine process, unique per launch. The id is what cancellation addresses, so
     * two attempts of the same URL must not share one: a download that retried after a failure used
     * to reuse the id, and the wrapper refuses a duplicate ("Process ID already exists") or kills the
     * wrong process.
     */
    private val sequence = AtomicInteger()

    fun processId(url: String): String =
        "otter-${url.hashCode().toUInt().toString(16)}-${sequence.incrementAndGet()}"

    /**
     * The engine's own network retries for a metadata request. One: the app owns the retry ladder,
     * and a nested ladder is how a two-second failure turns into a two-minute wait.
     */
    const val FETCH_NETWORK_RETRIES = 1

    /** yt-dlp flag set that keeps a format request cheap and quiet. */
    val FETCH_QUIET_ARGS = listOf(
        "--skip-download",
        "--no-warnings",
        "--no-write-info-json",
        "--no-write-description",
        "--no-write-thumbnail"
    )
}
