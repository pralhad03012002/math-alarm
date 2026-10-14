package com.mathalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Step one of the firing chain. `AlarmManager` -> here -> [AlarmService].
 *
 * Does as little as possible: grab the wake lock while the system's own one still covers us, hand
 * off to the foreground service, return. No database access, no audio — all of that happens in the
 * service, where it can outlive this 10-second receiver budget.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) {
            Log.w(TAG, "ignoring unexpected action ${intent.action}")
            return
        }

        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_LABEL).orEmpty()
        val vibrate = intent.getBooleanExtra(AlarmScheduler.EXTRA_ALARM_VIBRATE, true)
        // The instant this registration was booked for; the service uses it to baseline the
        // post-fire reschedule rather than re-reading the clock (review finding 1.15).
        val triggerAt = intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, 0L)
        Log.i(TAG, "alarm $alarmId fired")

        // Acquired here, released by AlarmService. Covers the gap between onReceive returning and
        // the service actually starting, which is where a device can drop back to sleep.
        AlarmWakeLock.acquire(context)
        try {
            ContextCompat.startForegroundService(
                context,
                AlarmService.startIntent(context, alarmId, label, vibrate, triggerAt),
            )
        } catch (t: Throwable) {
            // Nothing else will ever release the lock if the service never starts.
            Log.e(TAG, "could not start AlarmService for alarm $alarmId", t)
            AlarmWakeLock.release()
        }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
