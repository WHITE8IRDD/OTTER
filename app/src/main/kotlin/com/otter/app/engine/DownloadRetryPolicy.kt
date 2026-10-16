package com.otter.app.engine

/**
 * What to do after a download attempt failed.
 *
 * This used to be five `if`s inline in the download loop, and the shape of them was a bug: the
 * loop was bounded by an `attempt` counter that the expired-format recovery pushed *backwards*, so
 * a video whose format URLs kept answering 403 never stopped retrying — a full engine run plus a
 * full re-extraction each lap, with nothing reported to the UI. The counter now only moves forward,
 * and the recovery is bounded by its own budget, so the same case ends after a fixed number of
 * attempts with an error the user can act on.
 *
 * Kept free of Android and of the engine so every rule below is unit tested.
 */
object DownloadRetryPolicy {

    /** Attempts per download, including the first one. */
    const val MAX_ATTEMPTS = 3

    /** A re-extraction is expensive; exactly one is allowed per download. */
    const val MAX_FORMAT_REFRESHES = 1

    /** Delay before attempt 1, 2, 3 … (index = attempt - 1). */
    val waitBeforeAttemptMs = listOf(0L, 2_000L, 5_000L)

    enum class Action {
        /** Stop: report the file as downloaded. */
        SUCCEED,

        /** Stop: report [reason] to the user. */
        FAIL,

        /** Run the same format again after a delay. */
        RETRY,

        /** Re-read the format list, then run the new format id. */
        REFRESH_FORMAT
    }

    data class Decision(val action: Action, val waitMs: Long = 0L, val reason: String = "")

    fun waitBefore(attempt: Int): Long =
        waitBeforeAttemptMs.getOrElse(attempt - 1) { waitBeforeAttemptMs.last() }

    /** The format URLs of an earlier extraction went stale, so a fresh extraction can fix it. */
    private val formatExpiredPatterns = listOf(
        Regex("HTTP Error 40[134]"),
        Regex("HTTP Error 410"),
        Regex("Forbidden", RegexOption.IGNORE_CASE),
        Regex("has expired", RegexOption.IGNORE_CASE),
        Regex("Signature has expired", RegexOption.IGNORE_CASE),
        Regex("token.*invalid", RegexOption.IGNORE_CASE)
    )

    /**
     * Reasons that will not change by trying again. The list is deliberately specific: a bare
     * `private` matcher used to sit here, and it stopped any error whose text merely contained the
     * word — including TLS and CDN messages that have nothing to do with a private video.
     */
    private val permanentPatterns = listOf(
        Regex("video unavailable", RegexOption.IGNORE_CASE),
        Regex("this video is not available", RegexOption.IGNORE_CASE),
        Regex("private video", RegexOption.IGNORE_CASE),
        Regex("this video is private", RegexOption.IGNORE_CASE),
        Regex("requires login", RegexOption.IGNORE_CASE),
        Regex("sign in to confirm", RegexOption.IGNORE_CASE),
        Regex("copyright", RegexOption.IGNORE_CASE),
        Regex("takedown", RegexOption.IGNORE_CASE),
        Regex("removed by the uploader", RegexOption.IGNORE_CASE),
        Regex("account terminated", RegexOption.IGNORE_CASE),
        Regex("no video formats found", RegexOption.IGNORE_CASE),
        Regex("requested format is not available", RegexOption.IGNORE_CASE),
        Regex("usage:", RegexOption.IGNORE_CASE),
        Regex("no space left on device", RegexOption.IGNORE_CASE),
        Regex("read-only file system", RegexOption.IGNORE_CASE)
    )

    /** Reasons worth another attempt. Unknown errors are retried too — see [isUnknown]. */
    private val transientPatterns = listOf(
        Regex("timeout", RegexOption.IGNORE_CASE),
        Regex("timed out", RegexOption.IGNORE_CASE),
        Regex("connection reset", RegexOption.IGNORE_CASE),
        Regex("connection refused", RegexOption.IGNORE_CASE),
        Regex("HTTPSConnectionPool", RegexOption.IGNORE_CASE),
        Regex("fragment not found", RegexOption.IGNORE_CASE),
        Regex("unable to download", RegexOption.IGNORE_CASE),
        Regex("HTTP Error 5\\d{2}", RegexOption.IGNORE_CASE),
        Regex("throttl", RegexOption.IGNORE_CASE),
        Regex("network error", RegexOption.IGNORE_CASE),
        Regex("socket", RegexOption.IGNORE_CASE),
        Regex("reset by peer", RegexOption.IGNORE_CASE),
        Regex("TLS", RegexOption.IGNORE_CASE),
        Regex("EOF", RegexOption.IGNORE_CASE),
        Regex("unexpected error", RegexOption.IGNORE_CASE)
    )

    fun isFormatExpired(message: String?): Boolean =
        !message.isNullOrBlank() && formatExpiredPatterns.any { it.containsMatchIn(message) }

    fun isPermanent(message: String?): Boolean =
        !message.isNullOrBlank() && permanentPatterns.any { it.containsMatchIn(message) }

    fun isTransient(message: String?): Boolean =
        !message.isNullOrBlank() && transientPatterns.any { it.containsMatchIn(message) }

    /** Neither permanent nor recognisably transient: retried, but only until the attempt budget. */
    fun isUnknown(message: String?): Boolean = !isPermanent(message) && !isTransient(message)

    /**
     * @param attempt the attempt that just finished, 1-based; never decreases across a download.
     * @param formatRefreshes refreshes already performed for this download.
     * @param cancelledByUser the user paused or stopped this download.
     * @param canReExtract whether a re-extraction is available to refresh the format from.
     */
    fun decide(
        attempt: Int,
        formatRefreshes: Int,
        message: String?,
        cancelledByUser: Boolean = false,
        canReExtract: Boolean = true
    ): Decision {
        if (cancelledByUser) return Decision(Action.FAIL, reason = "cancelled")
        if (isFormatExpired(message) && canReExtract && formatRefreshes < MAX_FORMAT_REFRESHES) {
            return Decision(Action.REFRESH_FORMAT, waitMs = 0L)
        }
        if (isPermanent(message)) return Decision(Action.FAIL, reason = message.orEmpty())
        if (attempt >= MAX_ATTEMPTS) {
            return Decision(Action.FAIL, reason = message.orEmpty().ifBlank { "download failed" })
        }
        return Decision(Action.RETRY, waitMs = waitBefore(attempt + 1), reason = message.orEmpty())
    }

    /** The fetch ladder's permanent-error check, shared with the retry policy so the two agree. */
    fun shouldStopReFetching(message: String?): Boolean = isPermanent(message)
}
