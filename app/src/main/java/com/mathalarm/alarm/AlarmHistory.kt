package com.mathalarm.alarm

import android.content.Context
import android.content.SharedPreferences

/**
 * "Last fired successfully" — the one record that makes a *silent* alarm failure visible
 * (BUILD_SPECS §4, BRAINSTORM §6 "Mitigations").
 *
 * **Deliberately not a database column.** Adding one would mean bumping `@Database(version = ...)`
 * and shipping a `Migration(1, 2)` plus a committed `2.json`, and Module 1 review backlog **2.6**
 * established that the migration gate is *runtime*-only — a forgotten migration compiles green and
 * then stops every alarm being rescheduled after an update. A single timestamp does not justify
 * taking on that risk, so the committed `app/schemas/.../1.json` baseline and the empty `MIGRATIONS`
 * array are left exactly as they are.
 *
 * Device-protected storage, for the same reason the alarm database is: the firing chain is
 * `directBootAware` and runs before the first unlock after a reboot, where credential-encrypted
 * storage is unreadable.
 *
 * **What "successfully" means here, precisely.** The stamp is written after `AlarmService` has gone
 * foreground, taken the wake lock and started the ringer — i.e. the alarm *fired and came up*. It is
 * not a claim that sound was produced: if every audio source fails and `PlaybackRecovery` exhausts
 * its budget, the alarm is silent and this still records a firing. Closing that gap would mean a
 * feedback loop from `AlarmRinger.isRinging`, which Module 1 backlog **3.2** notes currently has no
 * consumer at all, and inventing one inside thrice-reviewed audio code is not this record's job. The
 * value this row carries is the one §4 asks for: distinguishing "nothing has ever fired" — the OEM
 * battery manager ate it, or exact alarms were never permitted — from "it fires, something else is
 * wrong", and that distinction is intact.
 *
 * `apply()`, not `commit()`: unlike the pre-ring volume record (review finding 1.1) nothing depends
 * on this being on disk before some *other* call runs, and the write is dispatched from
 * [AlarmWork]'s process-lifetime scope rather than from the service main thread, so it cannot delay
 * the sound (review backlog 2.5 is about exactly that mistake).
 */
object AlarmHistory {

    private const val PREFS = "alarm_history"
    private const val KEY_LAST_FIRED_AT = "last_fired_at"
    private const val KEY_LAST_FIRED_ID = "last_fired_id"
    private const val NONE = -1L

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Records that [alarmId] actually started ringing. Called once per *new* ring, from
     * [AlarmService] after the ringer has started — never on a duplicate or redelivered start, which
     * is not a new firing.
     */
    fun recordFired(
        context: Context,
        alarmId: Long,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        prefs(context).edit()
            .putLong(KEY_LAST_FIRED_AT, atMillis)
            .putLong(KEY_LAST_FIRED_ID, alarmId)
            .apply()
    }

    /** Wall-clock millis of the most recent ring, or null if no alarm has ever fired. */
    fun lastFiredAtMillis(context: Context): Long? =
        prefs(context).getLong(KEY_LAST_FIRED_AT, NONE).takeIf { it != NONE }

    /** The alarm id behind [lastFiredAtMillis], or null. */
    fun lastFiredAlarmId(context: Context): Long? =
        prefs(context).getLong(KEY_LAST_FIRED_ID, NONE).takeIf { it != NONE }
}
