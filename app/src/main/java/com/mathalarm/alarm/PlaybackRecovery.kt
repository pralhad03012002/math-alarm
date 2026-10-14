package com.mathalarm.alarm

/**
 * The decision half of [AlarmRinger.onPlaybackError], split out as a pure function so it can be
 * tested on the JVM without a device (review finding 2.1).
 *
 * Deliberately free of `android.*` imports: the whole point is that the retry/terminal policy is
 * decidable from four numbers, so a unit test can walk every branch — including the one that only
 * occurs when the mediaserver dies while the direct-boot fallback tone is playing, which is not
 * reproducible on a JVM or, realistically, by hand.
 */
internal object PlaybackRecovery {

    /**
     * Value of `android.media.MediaPlayer.MEDIA_ERROR_SERVER_DIED`, duplicated here to keep this
     * file Android-free. Frozen platform constant; `PlaybackRecoveryTest` asserts the two agree.
     */
    const val MEDIA_ERROR_SERVER_DIED = 100

    /**
     * How many times the source chain may be restarted *from the top* within one ring before the
     * ringer gives up. Three passes at [RETRY_DELAY_MS] apart covers a mediaserver restart (which
     * normally completes well inside a second) plus two spare attempts, and bounds the total
     * recovery effort of one ring at a few seconds rather than letting a permanently broken device
     * spin for the life of the alarm.
     */
    const val MAX_RESTART_PASSES = 3

    /**
     * Delay before a from-the-top restart. Long enough for a restarting mediaserver to come back —
     * an immediate re-`prepare()` would just fail again and burn a pass — and short enough that the
     * alarm's silent gap stays under the time it takes a sleeping user to notice anything.
     */
    const val RETRY_DELAY_MS = 1_500L

    sealed interface Plan {
        /** Re-enter `startPlaybackFrom(fromIndex)` after [delayMs]. */
        data class Restart(val fromIndex: Int, val delayMs: Long) : Plan

        /** Recovery budget spent. Stop retrying and say so out loud. */
        data object GiveUp : Plan
    }

    /**
     * What to do about an asynchronous `MediaPlayer` failure.
     *
     * @param failedIndex index into the candidate-source list of the player that just died.
     * @param sourceCount size of that list (always >= 1: the bundled raw tone).
     * @param what the `MediaPlayer.OnErrorListener` `what` code.
     * @param restartPassesUsed how many from-the-top restarts this ring has already consumed.
     *
     * [MEDIA_ERROR_SERVER_DIED] is **not** source-specific — the mediaserver died underneath a
     * source that was playing fine — so it never falls forward; it always restarts from index 0,
     * which is also what re-offers the user's own alarm sound once the server is back. Any other
     * error is attributed to the source that reported it, so the chain falls forward to the next
     * candidate; only when the chain is exhausted does it wrap around to a from-the-top retry.
     *
     * Termination: the index advances strictly within a pass and is bounded by [sourceCount], and
     * a from-the-top restart is the only thing that moves the index back — so the total number of
     * restarts is bounded by [MAX_RESTART_PASSES] and the total number of attempts by
     * `MAX_RESTART_PASSES * sourceCount`. At most one recovery is ever queued at a time.
     */
    fun plan(failedIndex: Int, sourceCount: Int, what: Int, restartPassesUsed: Int): Plan {
        val next = failedIndex + 1
        if (what != MEDIA_ERROR_SERVER_DIED && next < sourceCount) return Plan.Restart(next, 0L)
        if (restartPassesUsed >= MAX_RESTART_PASSES) return Plan.GiveUp
        return Plan.Restart(0, RETRY_DELAY_MS)
    }
}
