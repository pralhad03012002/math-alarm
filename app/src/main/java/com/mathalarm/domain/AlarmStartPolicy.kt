package com.mathalarm.domain

/**
 * What `AlarmService` does with an incoming `ACTION_START` — decided here, purely, so the one
 * question that matters ("may this start touch the ringer?") is testable without an Android runtime.
 *
 * **The overlap policy is: refuse** (BUILD_SPECS §4b item 3, user decision 2026-10-11). It closes
 * backlog **1.7** / **1.8**, review **M2-1.8** and review **M3-1.2** together, and it does so without
 * making the service multi-alarm: there is still exactly one ringer, one `ringingAlarmId`, one label
 * and one notification, and nothing is queued. A second alarm arriving mid-ring is simply not allowed
 * to take any of them over.
 *
 * Why refusing is the right shape rather than taking over:
 *  - taking over overwrote `ringingAlarmId`/`ringingLabel` while `AlarmRingActivity` was still
 *    showing the first alarm, so **dismissing either one silenced the other** (1.7) and the second
 *    alarm's tier replaced the first one's — HARD x 3 downgraded to EASY x 1 (M2-1.8);
 *  - only the dismissed id ever reached `disableIfOneShot`, so the other one-shot stayed `enabled`
 *    and fired again the next day (1.8);
 *  - the test alarm reduced all of that to a single tap (M3-1.2), on the very screen the ringing
 *    banner sends people to.
 *
 * The test is deliberately **both** the id and the ringer, in the shape Module 3's ruling 1
 * established for the swipe handler: `ringingAlarmId` and `AlarmRinger.isRinging` fail in opposite
 * directions (a start can ring with no id at all, and after `PlaybackRecovery.Plan.GiveUp` the id is
 * live while `isRinging` is honestly false), so "something is already live" is **either** of them,
 * not one alone. That asymmetry is the safe one here: the cost of a wrong [Outcome.REFUSE] is one
 * alarm not ringing while the user is already standing in front of another one that is; the cost of
 * a wrong [Outcome.RING] is the live alarm being silenced out from under them.
 *
 * **The one exception, BUILD_SPECS §4c:** an *exhausted* ringer is live-but-silent for good, so
 * refusing means neither alarm rings; that one state hands the ringer over. Argument at the clause
 * in [forStart]; harm 2 is closed there by `AlarmService.settleTakenOverAlarm` (review **4c-1.1**).
 *
 * An [Outcome.REFUSE] is never the end of the refused alarm's story — `AlarmService` still books a
 * repeating alarm's next occurrence and still auto-disables a one-shot, because the registration
 * this delivery consumed is gone either way.
 */
object AlarmStartPolicy {

    /**
     * "No alarm id." The sentinel `AlarmService.ringingAlarmId` holds when nothing is ringing, and
     * also what `getLongExtra` falls back to for a start that carries no id at all — which is why it
     * cannot be the only thing the decision looks at. `AlarmService.NO_ALARM` is defined as this
     * constant, so the two can never drift apart.
     */
    const val NO_ALARM = -1L

    enum class Outcome {
        /** Start the ringer for this alarm. */
        RING,

        /** This alarm is already the one ringing: a duplicate or redelivered start. Ignore it. */
        DUPLICATE,

        /** A different alarm owns the ringer. Do not ring, and do not touch its state. */
        REFUSE,
    }

    /**
     * @param incomingAlarmId the id on the arriving `ACTION_START`, or [NO_ALARM] if it carried none.
     * @param ringingAlarmId the id the service currently believes is ringing, or [NO_ALARM].
     * @param ringerIsRinging `AlarmRinger.isRinging` — actual playback (or a start/recovery in
     *   flight), which is false after audio recovery has given up even though the alarm is live.
     * @param ringerRecoveryExhausted `AlarmRinger.recoveryExhausted` — the ring is live but its
     *   bounded audio-recovery budget is spent, so it is silent and will not try again. Defaults to
     *   false, which is the conservative value: it can only cost a takeover, never grant a wrong one.
     */
    fun forStart(
        incomingAlarmId: Long,
        ringingAlarmId: Long,
        ringerIsRinging: Boolean,
        ringerRecoveryExhausted: Boolean = false,
    ): Outcome = when {
        // Same alarm, still ringing: the duplicate-start case. Module 1 already relied on this
        // (a redelivered START_REDELIVER_INTENT intent must not restart the ringer or re-stamp
        // "last fired"), and it has to be tested before the refusal, because an alarm can never be
        // an overlap with itself.
        incomingAlarmId == ringingAlarmId && ringerIsRinging -> Outcome.DUPLICATE

        // Same alarm, not ringing: either a fresh start (both NO_ALARM, nothing has rung yet) or
        // the same alarm re-asserting itself after the audio died. Both are RING, never a refusal:
        // re-starting the alarm that is already nominally live is recovery, not overlap.
        incomingAlarmId == ringingAlarmId -> Outcome.RING

        // BUILD_SPECS §4c, the only clause that hands a live id to a different alarm, and the
        // canonical home for the argument. The ringer holding `ringingAlarmId` has given up on
        // audio: silent for good, so refusing is how §4c's two alarms *both* end up not ringing.
        // Take over — a fresh ringer with a fresh budget beats guaranteed silence. Given the cost
        // asymmetry above, all three conjuncts are load-bearing and none is redundant:
        //  - `incomingAlarmId != NO_ALARM`: an id-less start must not become the live id —
        //    `finishDismiss` would tear down on any dismiss, `fireTestAlarm` would unblock (4c-1.3);
        //  - `!ringerIsRinging`: keeps the start window (id set, `isRinging` transiently false) a
        //    refusal; the ringer's write ordering holds only *within* one generation (4c-1.2);
        //  - the refusal below stays standing: this is an exception to it, not a replacement.
        // Settling the overwritten alarm's row is the caller's job (`settleTakenOverAlarm`).
        incomingAlarmId != NO_ALARM && ringerRecoveryExhausted && !ringerIsRinging -> Outcome.RING

        // A different alarm, and something is already live by either measure. Refuse.
        ringingAlarmId != NO_ALARM || ringerIsRinging -> Outcome.REFUSE

        else -> Outcome.RING
    }
}
