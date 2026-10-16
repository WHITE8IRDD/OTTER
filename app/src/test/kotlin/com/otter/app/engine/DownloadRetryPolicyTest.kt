package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder between "this attempt failed" and what the user sees.
 *
 * These cases exist because of one real failure: a long video whose CDN URLs kept answering 403. The
 * recovery for a stale format list reset the attempt counter, so the loop never ended — no error in
 * the UI, a notification that never cleared, and an engine run plus a full re-extraction every lap.
 * The tests below assert that the ladder *terminates*, and that the errors that are not the app's
 * fault end it early instead of late.
 */
class DownloadRetryPolicyTest {

    @Test
    fun waitsGrowWithTheAttemptAndStopGrowingAfterwards() {
        assertEquals(0L, DownloadRetryPolicy.waitBefore(1))
        assertEquals(2_000L, DownloadRetryPolicy.waitBefore(2))
        assertEquals(5_000L, DownloadRetryPolicy.waitBefore(3))
        assertEquals(5_000L, DownloadRetryPolicy.waitBefore(40))
    }

    @Test
    fun expiredFormatGetsOneRefreshAndNothingMore() {
        val stale = "Unable to download replica video fragment: HTTP Error 403: Forbidden"

        assertEquals(
            DownloadRetryPolicy.Action.REFRESH_FORMAT,
            DownloadRetryPolicy.decide(attempt = 1, formatRefreshes = 0, message = stale).action
        )
        assertEquals(
            DownloadRetryPolicy.Action.RETRY,
            DownloadRetryPolicy.decide(attempt = 1, formatRefreshes = 1, message = stale).action
        )
    }

    @Test
    fun aRefreshThatAlsoFailsStillEndsInsideTheBudget() {
        val stale = "HTTP Error 403: Forbidden"
        var attempt = 0
        var refreshes = 0
        var guard = 0
        var last = DownloadRetryPolicy.decide(attempt, refreshes, stale)

        // Exactly the shape of the download loop: refresh once, keep trying, and stop.
        while (last.action != DownloadRetryPolicy.Action.FAIL && guard++ < 50) {
            when (last.action) {
                DownloadRetryPolicy.Action.REFRESH_FORMAT -> refreshes++
                else -> Unit
            }
            attempt++
            last = DownloadRetryPolicy.decide(attempt, refreshes, stale)
        }

        assertEquals(DownloadRetryPolicy.Action.FAIL, last.action)
        assertTrue(
            "the ladder must end within its attempt budget, took $guard laps",
            attempt <= DownloadRetryPolicy.MAX_ATTEMPTS
        )
        assertEquals(1, refreshes)
    }

    @Test
    fun permanentReasonsEndTheLadderAtOnce() {
        listOf(
            "ERROR: This video is private",
            "ERROR: [youtube] Video unavailable",
            "ERROR: Sign in to confirm you're not a bot",
            "ERROR: Requested format is not available",
            "ERROR: no space left on device"
        ).forEach { message ->
            val decision = DownloadRetryPolicy.decide(attempt = 1, formatRefreshes = 0, message = message)
            assertEquals("for: $message", DownloadRetryPolicy.Action.FAIL, decision.action)
            assertEquals("for: $message", message, decision.reason)
        }
    }

    @Test
    fun theWordPrivateAloneIsNotAPermanentReason() {
        // The old matcher was a bare `Regex("private")`, so a TLS failure that happened to mention a
        // private certificate authority ended a download that would have worked on the next try.
        val tls = "javax.net.ssl.SSLException: handshake_failure while verifying a private CA (TLS)"

        assertFalse(DownloadRetryPolicy.isPermanent(tls))
        assertTrue(DownloadRetryPolicy.isTransient(tls))
        assertEquals(
            DownloadRetryPolicy.Action.RETRY,
            DownloadRetryPolicy.decide(attempt = 1, formatRefreshes = 0, message = tls).action
        )
    }

    @Test
    fun transientNetworkFailuresAreRetriedWithAWait() {
        val decision = DownloadRetryPolicy.decide(
            attempt = 1,
            formatRefreshes = 0,
            message = "ERROR: unable to download video data: HTTP Error 503: Service Unavailable"
        )

        assertEquals(DownloadRetryPolicy.Action.RETRY, decision.action)
        assertEquals(2_000L, decision.waitMs)
    }

    @Test
    fun unknownFailuresAreRetriedUntilTheBudgetRunsOut() {
        val weird = "something the app has never seen"

        assertTrue(DownloadRetryPolicy.isUnknown(weird))
        assertEquals(
            DownloadRetryPolicy.Action.RETRY,
            DownloadRetryPolicy.decide(attempt = 2, formatRefreshes = 0, message = weird).action
        )
        val last = DownloadRetryPolicy.decide(
            attempt = DownloadRetryPolicy.MAX_ATTEMPTS,
            formatRefreshes = 0,
            message = weird
        )
        assertEquals(DownloadRetryPolicy.Action.FAIL, last.action)
    }

    @Test
    fun aBlankFailureStillEndsInTheBudget() {
        val decision = DownloadRetryPolicy.decide(
            attempt = DownloadRetryPolicy.MAX_ATTEMPTS,
            formatRefreshes = 0,
            message = ""
        )

        assertEquals(DownloadRetryPolicy.Action.FAIL, decision.action)
        assertEquals("download failed", decision.reason)
    }

    @Test
    fun aStopsBeforeAnythingElseIsConsidered() {
        val decision = DownloadRetryPolicy.decide(
            attempt = 1,
            formatRefreshes = 0,
            message = "HTTP Error 403: Forbidden",
            cancelledByUser = true
        )

        assertEquals(DownloadRetryPolicy.Action.FAIL, decision.action)
        assertEquals("cancelled", decision.reason)
    }

    @Test
    fun withoutAnExtractorToAskThereIsNothingToRefresh() {
        assertEquals(
            DownloadRetryPolicy.Action.RETRY,
            DownloadRetryPolicy.decide(
                attempt = 1,
                formatRefreshes = 0,
                message = "HTTP Error 403: Forbidden",
                canReExtract = false
            ).action
        )
    }

    @Test
    fun theFetchLadderAndTheDownloadLadderAgreeOnPermanent() {
        assertTrue(DownloadRetryPolicy.shouldStopReFetching("ERROR: this video is not available"))
        assertFalse(DownloadRetryPolicy.shouldStopReFetching("HTTPSConnectionPool: Read timed out"))
        assertFalse(DownloadRetryPolicy.shouldStopReFetching(null))
        assertFalse(DownloadRetryPolicy.isFormatExpired(null))
        assertFalse(DownloadRetryPolicy.isPermanent("   "))
    }
}
