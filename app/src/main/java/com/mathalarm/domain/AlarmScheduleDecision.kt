package com.mathalarm.domain

/**
 * What a mutation of an alarm must do to its `AlarmManager` registration.
 *
 * **Pure Kotlin, no Android imports**, so BUILD_SPECS §4's two scheduling acceptance criteria are
 * pinned by unit tests rather than by a code read:
 *  - *deleting an alarm cancels its pending registration*;
 *  - *editing an alarm cancels the old registration before creating the new one*.
 *
 * The invariant is deliberately blunt: **every** mutation cancels first, unconditionally, and only
 * then decides whether to schedule. There is no "has the time changed?" fast path, because the
 * question is not whether the trigger instant moved — a disabled-then-re-enabled alarm, a changed
 * repeat-day mask, or a label edit all leave a registration that was computed from the *old* row,
 * and `AlarmManager` keeps exactly one registration per `PendingIntent` identity. Cancelling
 * unconditionally costs one binder call and removes the whole class of stale-registration bugs.
 *
 * `AlarmScheduler.cancel` is the only cancel path and its `PendingIntent` identity is thrice
 * review-verified (Module 1 "Verified correct" #2/#3, re-verified in round 2). The caller must use it
 * as-is rather than reconstructing intents.
 */
object AlarmScheduleDecision {

    /**
     * [cancelFirst] is always true — it is modelled as a field rather than hard-coded at the call
     * sites so that a test can assert it for every entry point, including any added later.
     */
    data class Decision(val cancelFirst: Boolean, val scheduleAfter: Boolean)

    /** Creating or editing an alarm. Re-schedules only if the saved row is enabled. */
    fun forSave(enabled: Boolean): Decision = Decision(cancelFirst = true, scheduleAfter = enabled)

    /**
     * Flipping the enabled toggle in the list. Same shape as [forSave] — the toggle is just an edit
     * of one field, and it must take effect immediately in both directions.
     */
    fun forToggle(enabled: Boolean): Decision = forSave(enabled)

    /**
     * Deleting an alarm. Cancel and never re-schedule — and the cancel has to happen while the row
     * still exists conceptually, because the registration is keyed on the alarm id.
     */
    fun forDelete(): Decision = Decision(cancelFirst = true, scheduleAfter = false)
}
