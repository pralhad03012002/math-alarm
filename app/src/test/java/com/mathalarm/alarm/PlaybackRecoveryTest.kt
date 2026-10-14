package com.mathalarm.alarm

import android.media.MediaPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal/retry policy for an asynchronous `MediaPlayer` failure (review finding 2.1).
 *
 * Before the fix, an error on the *last* candidate source re-entered `startPlaybackFrom` with an
 * empty range and the alarm stayed silent for the rest of the ring — the direct-boot case, where
 * the last source (the bundled tone) is the only one that works, was exactly the worst case. These
 * tests pin the three properties that make that impossible: the chain always has somewhere to go,
 * `MEDIA_ERROR_SERVER_DIED` never falls forward, and the whole thing terminates.
 */
class PlaybackRecoveryTest {

    private val lastIndex = 2
    private val sourceCount = 3

    /** The constant is duplicated into Android-free code; this is the drift guard. */
    @Test
    fun `server-died constant matches the platform`() {
        assertEquals(MediaPlayer.MEDIA_ERROR_SERVER_DIED, PlaybackRecovery.MEDIA_ERROR_SERVER_DIED)
    }

    // ------------------------------------------------- source-specific errors

    @Test
    fun `a source-specific error falls forward immediately`() {
        assertEquals(
            PlaybackRecovery.Plan.Restart(1, 0L),
            PlaybackRecovery.plan(0, sourceCount, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0),
        )
        assertEquals(
            PlaybackRecovery.Plan.Restart(2, 0L),
            PlaybackRecovery.plan(1, sourceCount, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0),
        )
    }

    /** The regression: index 2 of 3 used to produce `startPlaybackFrom(3)` and permanent silence. */
    @Test
    fun `an error on the last source wraps around instead of falling off the end`() {
        val plan = PlaybackRecovery.plan(lastIndex, sourceCount, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        assertEquals(PlaybackRecovery.Plan.Restart(0, PlaybackRecovery.RETRY_DELAY_MS), plan)
    }

    @Test
    fun `a single-source chain still wraps around`() {
        assertEquals(
            PlaybackRecovery.Plan.Restart(0, PlaybackRecovery.RETRY_DELAY_MS),
            PlaybackRecovery.plan(0, 1, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0),
        )
    }

    @Test
    fun `the wrap-around is delayed and the fall-forward is not`() {
        val wrap = PlaybackRecovery.plan(lastIndex, sourceCount, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
                as PlaybackRecovery.Plan.Restart
        assertTrue(
            "a mediaserver needs time to come back: ${wrap.delayMs}ms",
            wrap.delayMs in 1_000L..2_000L,
        )
        val forward = PlaybackRecovery.plan(0, sourceCount, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
                as PlaybackRecovery.Plan.Restart
        assertEquals(0L, forward.delayMs)
    }

    // ------------------------------------------------------ MEDIA_ERROR_SERVER_DIED

    @Test
    fun `server-died retries from zero rather than falling forward`() {
        // The error is not source-specific: the source was fine, the mediaserver died under it.
        for (failedIndex in 0..lastIndex) {
            assertEquals(
                "failedIndex=$failedIndex",
                PlaybackRecovery.Plan.Restart(0, PlaybackRecovery.RETRY_DELAY_MS),
                PlaybackRecovery.plan(
                    failedIndex,
                    sourceCount,
                    MediaPlayer.MEDIA_ERROR_SERVER_DIED,
                    0,
                ),
            )
        }
    }

    /** The reviewer's scenario: direct boot lands on the raw tone, then the mediaserver restarts. */
    @Test
    fun `the direct-boot scenario recovers instead of going silent`() {
        val plan = PlaybackRecovery.plan(
            failedIndex = lastIndex,
            sourceCount = sourceCount,
            what = MediaPlayer.MEDIA_ERROR_SERVER_DIED,
            restartPassesUsed = 0,
        )
        assertEquals(PlaybackRecovery.Plan.Restart(0, PlaybackRecovery.RETRY_DELAY_MS), plan)
    }

    // ------------------------------------------------------------ the bound

    @Test
    fun `restarts are bounded and then give up`() {
        for (used in 0 until PlaybackRecovery.MAX_RESTART_PASSES) {
            assertTrue(
                "pass $used should still retry",
                PlaybackRecovery.plan(lastIndex, sourceCount, MediaPlayer.MEDIA_ERROR_SERVER_DIED, used)
                        is PlaybackRecovery.Plan.Restart,
            )
        }
        assertEquals(
            PlaybackRecovery.Plan.GiveUp,
            PlaybackRecovery.plan(
                lastIndex,
                sourceCount,
                MediaPlayer.MEDIA_ERROR_SERVER_DIED,
                PlaybackRecovery.MAX_RESTART_PASSES,
            ),
        )
        assertEquals(
            PlaybackRecovery.Plan.GiveUp,
            PlaybackRecovery.plan(
                lastIndex,
                sourceCount,
                MediaPlayer.MEDIA_ERROR_UNKNOWN,
                PlaybackRecovery.MAX_RESTART_PASSES + 7,
            ),
        )
    }

    @Test
    fun `the bound is a handful of passes, not unbounded and not zero`() {
        assertTrue(PlaybackRecovery.MAX_RESTART_PASSES in 2..5)
    }

    /**
     * Drives the real loop the way `AlarmRinger` does — every error recovered, wrap-arounds
     * counted, nothing else — and asserts it stops. This is the "no infinite recursion, no
     * unbounded handler queue growth" property, and the queue never holds more than the one
     * recovery this returns.
     */
    @Test
    fun `the recovery chain always terminates`() {
        for (what in listOf(MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_SERVER_DIED)) {
            for (count in 1..5) {
                var index = 0
                var passes = 0
                var steps = 0
                while (true) {
                    val plan = PlaybackRecovery.plan(index, count, what, passes)
                    if (plan is PlaybackRecovery.Plan.GiveUp) break
                    val restart = plan as PlaybackRecovery.Plan.Restart
                    if (restart.fromIndex == 0) passes++
                    index = restart.fromIndex
                    assertTrue(
                        "chain did not terminate for what=$what count=$count",
                        ++steps <= PlaybackRecovery.MAX_RESTART_PASSES * (count + 1) + 1,
                    )
                }
                assertEquals(
                    "gave up without spending the whole budget (what=$what count=$count)",
                    PlaybackRecovery.MAX_RESTART_PASSES,
                    passes,
                )
            }
        }
    }
}
