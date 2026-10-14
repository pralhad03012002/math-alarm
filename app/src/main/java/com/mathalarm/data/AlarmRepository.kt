package com.mathalarm.data

import android.content.Context
import android.util.Log
import com.mathalarm.alarm.AlarmRescheduler
import com.mathalarm.alarm.AlarmScheduler
import com.mathalarm.domain.AlarmScheduleDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/**
 * The single place where an alarm row and its `AlarmManager` registration are changed together.
 *
 * This is where BUILD_SPECS §4's two scheduling acceptance criteria are actually implemented:
 *
 *  - **"Deleting an alarm cancels its pending registration."** [delete] cancels *before* the row is
 *    removed. Order matters: the registration is identified by the alarm id, so cancelling after the
 *    delete would still work today but would leave the code one refactor away from leaking a
 *    registration for a row that no longer exists — and a leaked registration fires an alarm the user
 *    has deleted, with `AlarmService` then finding no row at all.
 *  - **"Editing an alarm cancels the old registration before creating the new one."** [save] runs
 *    [AlarmScheduleDecision.forSave], whose `cancelFirst` is unconditionally true, and the cancel
 *    completes synchronously before `schedule` is called — they are ordinary blocking calls on the
 *    same IO thread, not concurrent work.
 *
 * Cancellation goes through [AlarmScheduler.cancel] and nothing else. Its `PendingIntent` identity is
 * review-verified three times over (Module 1 "Verified correct" #2/#3, re-verified in round 2 after
 * `EXTRA_TRIGGER_AT` was added), so this class never builds an intent of its own — reconstructing one
 * is precisely how the classic silent `AlarmManager` cancel bug happens.
 *
 * Every method hops to [Dispatchers.IO]: the DAO is `suspend` and Room forbids main-thread access.
 */
class AlarmRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    // Lazy, and every use is already inside withContext(Dispatchers.IO): constructing the
    // repository on the main thread must not be the thing that opens the database.
    private val dao: AlarmDao by lazy { AlarmDatabase.get(appContext).alarmDao() }
    private val scheduler = AlarmScheduler(appContext)

    /** Every alarm the user owns, in list order. Excludes the hidden test-alarm row. */
    suspend fun userAlarms(): List<Alarm> = withContext(Dispatchers.IO) {
        dao.getAll().filter { it.id != TEST_ALARM_ID }
    }

    suspend fun byId(id: Long): Alarm? = withContext(Dispatchers.IO) { dao.getById(id) }

    /**
     * Inserts or updates [alarm], then re-registers it. Returns the stored row (with its real id for
     * a freshly created alarm) paired with the trigger instant that was booked, or null if nothing
     * was scheduled — a disabled alarm, or exact alarms not permitted.
     */
    suspend fun save(alarm: Alarm): Saved = withContext(Dispatchers.IO) {
        val id = if (alarm.id == 0L) dao.upsert(alarm) else {
            dao.update(alarm)
            alarm.id
        }
        val stored = alarm.copy(id = id)
        val triggerAt = applyDecision(AlarmScheduleDecision.forSave(stored.enabled), stored)
        Saved(stored, triggerAt)
    }

    /** The list's enabled toggle. Schedules or cancels immediately, in the same call. */
    suspend fun setEnabled(alarm: Alarm, enabled: Boolean): Saved = withContext(Dispatchers.IO) {
        val stored = alarm.copy(enabled = enabled)
        dao.update(stored)
        val triggerAt = applyDecision(AlarmScheduleDecision.forToggle(enabled), stored)
        Saved(stored, triggerAt)
    }

    suspend fun delete(alarm: Alarm) = withContext(Dispatchers.IO) {
        val decision = AlarmScheduleDecision.forDelete()
        // Cancel first, while the id is still meaningful. forDelete().scheduleAfter is false, so
        // nothing is ever re-registered here.
        if (decision.cancelFirst) scheduler.cancel(alarm.id)
        dao.delete(alarm)
    }

    /**
     * **Defensive rescheduling on app open** (BUILD_SPECS §4, BRAINSTORM §6 "Mitigations"), in
     * addition to Module 1's `BootReceiver`. Delegates to [AlarmRescheduler], which cancels and
     * recomputes every row — the only safe operation, since the correct instant for a stored
     * wall-clock time changes whenever the clock or the zone moves.
     *
     * The hidden test-alarm row is **skipped entirely** by [AlarmRescheduler.rescheduleAll] (review
     * **M3-1.1**). It used to be merely `enabled = false`, which meant a reconcile cancelled its
     * registration and — being disabled — never re-booked one, so opening the app inside the test
     * alarm's five-second lead silently cancelled the alarm under test. The row stays `enabled =
     * false` for the original reason (nothing may resurrect it tomorrow morning); the skip is what
     * keeps the reconcile from cancelling it.
     */
    suspend fun reconcileAll() {
        runCatching { AlarmRescheduler.rescheduleAll(appContext) }
            .onFailure { Log.e(TAG, "reconcile on app open failed", it) }
    }

    /**
     * **Test alarm** (BUILD_SPECS §4, BRAINSTORM §3.1 step 5): fires the *real* alarm
     * [TEST_LEAD_SECONDS] from now, so the user experiences the whole chain once while awake — and so
     * an OEM battery manager that would have eaten the 6am alarm is discovered now rather than then.
     *
     * The row is persisted because the firing chain reads it (for the tier) and writes it (the
     * one-shot auto-disable), but it is stored **disabled** and under a reserved negative id:
     *  - disabled, so neither [reconcileAll] nor `BootReceiver` can ever resurrect it for tomorrow at
     *    the same wall-clock minute. `AlarmScheduler.scheduleAt` does not consult `enabled`, so the
     *    one registration we want is still booked — and [AlarmRescheduler.rescheduleAll] skips this
     *    id outright, so a reconcile can no longer cancel the booked instant either (**M3-1.1**);
     *  - reserved id, so it never appears in [userAlarms] and cannot collide with an autoincrement id.
     *
     * Returns the trigger instant in epoch millis, or null if exact alarms are not permitted — which
     * the caller reports, because that is the single most useful thing a failed test can tell a user.
     */
    suspend fun scheduleTestAlarm(label: String): Long? = withContext(Dispatchers.IO) {
        val target = ZonedDateTime.now().plusSeconds(TEST_LEAD_SECONDS)
        val row = Alarm(
            id = TEST_ALARM_ID,
            hour = target.hour,
            minute = target.minute,
            enabled = false,
            label = label,
            repeatDaysMask = 0,
            difficulty = Difficulty.MEDIUM,
            questionCount = 1,
            vibrate = true,
        )
        dao.upsert(row)
        scheduler.cancel(TEST_ALARM_ID)
        scheduler.scheduleAt(row, target.toInstant().toEpochMilli())
    }

    fun canScheduleExactAlarms(): Boolean = scheduler.canScheduleExactAlarms()

    private fun applyDecision(
        decision: AlarmScheduleDecision.Decision,
        alarm: Alarm,
    ): Long? {
        if (decision.cancelFirst) scheduler.cancel(alarm.id)
        return if (decision.scheduleAfter) scheduler.schedule(alarm) else null
    }

    /** A stored row plus the instant its registration was booked for, if any. */
    data class Saved(val alarm: Alarm, val triggerAtMillis: Long?)

    companion object {
        private const val TAG = "AlarmRepository"

        /**
         * Reserved row for the test alarm. Negative, so it can never collide with an
         * `AUTOINCREMENT` id, and filtered out of [userAlarms].
         */
        const val TEST_ALARM_ID = -2L

        /** BRAINSTORM §3.1: "fires the alarm in 5 seconds so the user sees the real thing". */
        const val TEST_LEAD_SECONDS = 5L
    }
}
