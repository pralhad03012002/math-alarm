package com.mathalarm.alarm

import android.content.Context
import android.util.Log
import com.mathalarm.data.AlarmDatabase
import com.mathalarm.data.AlarmRepository
import java.time.ZonedDateTime

/**
 * Recomputes and re-registers every alarm from scratch.
 *
 * `AlarmManager` registrations are process- and boot-scoped, and the correct trigger instant for a
 * stored wall-clock time changes whenever the clock or the zone moves. So the one safe operation is
 * "throw away every registration and recompute all of them", which is exactly this.
 *
 * **One row is exempt: the test alarm** (review **M3-1.1**, BUILD_SPECS §4b item 1). Its row is
 * deliberately `enabled = false` so that nothing can resurrect it for tomorrow morning, which means
 * this loop would cancel its registration and never re-book it. That turns the one feature whose
 * whole purpose is to tell the user whether their device eats alarms into a **false negative in the
 * discouraging direction**: tap "Test the alarm now", leave the app and come back inside those five
 * seconds — or let an aggressive ROM recreate `MainActivity` — and the reconcile silently cancels
 * the very alarm being tested. So the reserved row is skipped entirely here.
 *
 * Skipping the *cancel* as well as the schedule costs nothing. A registration booked by
 * [AlarmRepository.scheduleTestAlarm] is five seconds out and either fires or is cancelled by its
 * own dismiss (`disableIfOneShot`); re-arming cancels first; and `AlarmManager` registrations do not
 * survive a reboot at all, so the boot path has nothing stale to clear either.
 */
object AlarmRescheduler {

    private const val TAG = "AlarmRescheduler"

    suspend fun rescheduleAll(context: Context) {
        val appContext = context.applicationContext
        val alarms = AlarmDatabase.get(appContext).alarmDao().getAll()
        val scheduler = AlarmScheduler(appContext)
        val now = ZonedDateTime.now()
        var scheduled = 0
        var skipped = 0
        for (alarm in alarms) {
            // The test alarm is exempt in both directions — see the class KDoc (M3-1.1).
            if (alarm.id == AlarmRepository.TEST_ALARM_ID) {
                skipped++
                continue
            }
            // Cancel even disabled alarms: clears registrations left over from a previous state.
            scheduler.cancel(alarm.id)
            if (alarm.enabled && scheduler.schedule(alarm, now) != null) scheduled++
        }
        Log.i(TAG, "rescheduled $scheduled of ${alarms.size - skipped} alarms ($skipped left alone)")
    }
}
