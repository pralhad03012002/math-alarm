package com.mathalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Keeps alarms alive across the five system events that invalidate a registration (BRAINSTORM §6):
 *
 *  - `BOOT_COMPLETED` — reboot wipes every `AlarmManager` registration.
 *  - `LOCKED_BOOT_COMPLETED` — arrives *before* the first unlock. Without this, a phone rebooted at
 *    3am and left on the lock screen would never reschedule, because `BOOT_COMPLETED` does not
 *    arrive until someone unlocks. This is why the receiver and the database are direct-boot aware.
 *  - `MY_PACKAGE_REPLACED` — an app update stops the process and can drop registrations.
 *  - `TIME_SET` — the user (or the network) moved the clock; the stored wall-clock time now maps to
 *    a different instant.
 *  - `TIMEZONE_CHANGED` — same, after a flight.
 *
 * Both boot actions fire on a cold boot, and the work is idempotent (cancel-then-recompute), so the
 * duplicate is harmless.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in HANDLED_ACTIONS) {
            Log.w(TAG, "ignoring unexpected action $action")
            return
        }
        Log.i(TAG, "rescheduling after $action")

        val appContext = context.applicationContext
        val pending = goAsync()
        AlarmWork.scope.launch {
            try {
                AlarmRescheduler.rescheduleAll(appContext)
            } catch (t: Throwable) {
                Log.e(TAG, "reschedule after $action failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"

        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
