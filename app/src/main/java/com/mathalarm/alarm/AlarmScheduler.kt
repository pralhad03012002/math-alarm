package com.mathalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.mathalarm.MainActivity
import com.mathalarm.data.Alarm
import com.mathalarm.domain.NextTrigger
import java.time.ZonedDateTime

/**
 * The only place in the app that talks to `AlarmManager`.
 *
 * Always `setAlarmClock()` — never `setExact()`, never `setExactAndAllowWhileIdle()`, never
 * WorkManager (BRAINSTORM §5.2). `setAlarmClock` is the one API that is fully exempt from Doze,
 * tells the OS "this is a user-visible alarm clock", and surfaces the next-alarm indicator so a
 * silent scheduling failure is visible on the lock screen.
 *
 * Nothing here persists a trigger instant. Every call recomputes from the alarm's stored wall-clock
 * [Alarm.hour]/[Alarm.minute] via [NextTrigger], which is what keeps DST and timezone changes
 * correct (BRAINSTORM §6.5, §6.6).
 */
class AlarmScheduler(context: Context) {

    private val appContext: Context = context.applicationContext
    private val alarmManager: AlarmManager? = appContext.getSystemService(AlarmManager::class.java)

    /**
     * Schedules [alarm] at its next wall-clock occurrence after [now].
     * Returns the trigger time in epoch millis, or null if nothing was scheduled.
     */
    fun schedule(alarm: Alarm, now: ZonedDateTime = ZonedDateTime.now()): Long? {
        if (!alarm.enabled) {
            cancel(alarm.id)
            return null
        }
        val triggerAtMillis = NextTrigger.nextMillis(alarm.hour, alarm.minute, alarm.repeatDays, now)
        return scheduleAt(alarm, triggerAtMillis)
    }

    /** Schedules [alarm] at an explicit instant. Used by the Module 1 debug trigger. */
    fun scheduleAt(alarm: Alarm, triggerAtMillis: Long): Long? {
        val manager = alarmManager ?: run {
            Log.e(TAG, "no AlarmManager; cannot schedule alarm ${alarm.id}")
            return null
        }
        if (!canScheduleExactAlarms()) {
            // Module 3 owns asking the user for this. Fail loudly rather than silently half-working.
            Log.e(TAG, "exact alarms not permitted; alarm ${alarm.id} NOT scheduled")
            return null
        }
        val operation = firePendingIntent(alarm, triggerAtMillis, PendingIntent.FLAG_UPDATE_CURRENT)
            ?: return null
        return try {
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent()),
                operation,
            )
            Log.i(TAG, "alarm ${alarm.id} scheduled for $triggerAtMillis")
            triggerAtMillis
        } catch (e: SecurityException) {
            Log.e(TAG, "setAlarmClock denied for alarm ${alarm.id}", e)
            null
        }
    }

    /** Drops any pending registration for [alarmId]. Safe to call when there is none. */
    fun cancel(alarmId: Long) {
        val existing = PendingIntent.getBroadcast(
            appContext,
            requestCode(alarmId),
            fireIntent(alarmId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager?.cancel(existing)
        existing.cancel()
    }

    /** Cancel-then-schedule, so an edited alarm can never leave a stale registration behind. */
    fun reschedule(alarm: Alarm, now: ZonedDateTime = ZonedDateTime.now()): Long? {
        cancel(alarm.id)
        return schedule(alarm, now)
    }

    fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            true
        }

    private fun firePendingIntent(
        alarm: Alarm,
        triggerAtMillis: Long,
        extraFlags: Int,
    ): PendingIntent? =
        PendingIntent.getBroadcast(
            appContext,
            requestCode(alarm.id),
            fireIntent(alarm.id).apply {
                // Baked in so AlarmService can start foreground and make noise without first
                // touching the database. The DB is only needed for state changes afterwards.
                putExtra(EXTRA_ALARM_LABEL, alarm.label)
                putExtra(EXTRA_ALARM_VIBRATE, alarm.vibrate)
                // The instant this registration is booked for. Carried through to AlarmService so
                // the post-fire reschedule can baseline off the occurrence that actually fired
                // instead of a later `now()` (review finding 1.15). PendingIntent identity ignores
                // extras, so adding this cannot affect the cancel-side `filterEquals` match.
                putExtra(EXTRA_TRIGGER_AT, triggerAtMillis)
            },
            extraFlags or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * PendingIntent identity ignores extras, so the per-alarm data URI plus [requestCode] is what
     * keeps two alarms from overwriting each other's registration.
     */
    private fun fireIntent(alarmId: Long): Intent =
        Intent(appContext, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            data = Uri.parse("mathalarm://alarm/$alarmId")
            putExtra(EXTRA_ALARM_ID, alarmId)
        }

    /** What the OS opens when the user taps the next-alarm indicator. */
    private fun showIntent(): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            SHOW_INTENT_REQUEST_CODE,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun requestCode(alarmId: Long): Int = alarmId.toInt()

    companion object {
        private const val TAG = "AlarmScheduler"
        private const val SHOW_INTENT_REQUEST_CODE = -1

        const val ACTION_FIRE = "com.mathalarm.action.ALARM_FIRE"
        const val EXTRA_ALARM_ID = "com.mathalarm.extra.ALARM_ID"
        const val EXTRA_ALARM_LABEL = "com.mathalarm.extra.ALARM_LABEL"
        const val EXTRA_ALARM_VIBRATE = "com.mathalarm.extra.ALARM_VIBRATE"
        const val EXTRA_TRIGGER_AT = "com.mathalarm.extra.TRIGGER_AT"
    }
}
