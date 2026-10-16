package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one place engine command-line values are decided.
 *
 * Two of these rules exist because of a bug each: `--concurrent-fragments` was added twice (once by
 * the platform pass, once by the user settings) and yt-dlp keeps the *last* occurrence, so the
 * setting in Settings did nothing for the five platforms that had a platform default; and a process
 * id reused across attempts either got refused by the wrapper ("Process ID already exists") or made
 * Cancel kill the wrong process.
 */
class EngineArgsTest {

    @Test
    fun anUntouchedDefaultLetsThePlatformDecide() {
        assertEquals(
            4,
            EngineArgs.concurrentFragments(
                userValue = EngineArgs.PREFERENCE_DEFAULT_CONCURRENT_FRAGMENTS,
                platformValue = 4
            )
        )
    }

    @Test
    fun aLoweredValueIsRespectedBecauseItWasLoweredOnPurpose() {
        // Some CDNs answer a burst of parallel segment requests with 403s; that is exactly when a
        // user goes looking for this setting.
        assertEquals(1, EngineArgs.concurrentFragments(userValue = 1, platformValue = 8))
        assertEquals(2, EngineArgs.concurrentFragments(userValue = 2, platformValue = 8))
    }

    @Test
    fun valuesAreClampedIntoTheRangeTheEngineAccepts() {
        assertEquals(EngineArgs.MAX_FRAGMENTS, EngineArgs.concurrentFragments(100, 8))
        assertEquals(EngineArgs.MIN_FRAGMENTS, EngineArgs.concurrentFragments(0, 8))
        assertEquals(EngineArgs.MAX_FRAGMENTS, EngineArgs.concurrentFragments(8, 400))
        assertEquals(EngineArgs.MIN_FRAGMENTS, EngineArgs.concurrentFragments(8, 0))
    }

    @Test
    fun thePreferenceDefaultIsTheValueTheAppShipsSoTheComparisonIsHonest() {
        assertEquals(
            "UserPreferences falls back to this number; if they drift apart the rule above silently " +
                "starts treating an untouched setting as a user choice",
            EngineArgs.PREFERENCE_DEFAULT_CONCURRENT_FRAGMENTS,
            8
        )
    }

    @Test
    fun socketTimeoutGrowsPerAttemptAndStopsAtTheCap() {
        assertEquals(10, EngineArgs.socketTimeoutSeconds(baseSeconds = 10, attempt = 1, isLive = false))
        assertEquals(20, EngineArgs.socketTimeoutSeconds(baseSeconds = 10, attempt = 2, isLive = false))
        assertEquals(30, EngineArgs.socketTimeoutSeconds(baseSeconds = 10, attempt = 3, isLive = false))
        assertEquals(30, EngineArgs.socketTimeoutSeconds(baseSeconds = 10, attempt = 9, isLive = false))
        assertEquals(5, EngineArgs.socketTimeoutSeconds(baseSeconds = 1, attempt = 1, isLive = false))
    }

    @Test
    fun aLiveStreamNeverWaitsLongerThanTheCap() {
        assertEquals(30, EngineArgs.socketTimeoutSeconds(baseSeconds = 10, attempt = 1, isLive = true))
        assertEquals(
            20,
            EngineArgs.socketTimeoutSeconds(
                baseSeconds = 10,
                attempt = 2,
                isLive = false,
                capSeconds = 20
            )
        )
    }

    @Test
    fun everyEngineRunGetsItsOwnProcessId() {
        val url = "https://www.youtube.com/watch?v=abcdefghijk"
        val ids = (1..64).map { EngineArgs.processId(url) }.toSet()

        assertEquals("a retry must not address the id a previous attempt used", 64, ids.size)
        assertTrue(ids.all { it.startsWith("otter-") })
        assertNotEquals(EngineArgs.processId(url), EngineArgs.processId("https://example.org/other"))
    }

    @Test
    fun aMetadataRequestAsksTheEngineNotToRunItsOwnRetryLadder() {
        assertEquals(
            "the app owns the ladder; a nested one turns a two-second failure into a minutes-long wait",
            1,
            EngineArgs.FETCH_NETWORK_RETRIES
        )
        assertTrue(EngineArgs.FETCH_QUIET_ARGS.contains("--skip-download"))
        assertTrue(EngineArgs.FETCH_QUIET_ARGS.contains("--no-warnings"))
        assertFalse(
            "--dump-json already implies no download, and repeating flags invites drift",
            EngineArgs.FETCH_QUIET_ARGS.contains("--format")
        )
    }
}
