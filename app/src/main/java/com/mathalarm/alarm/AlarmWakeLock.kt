package com.mathalarm.alarm

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * The one wake lock in the app, held from the instant [AlarmReceiver] fires until [AlarmService]
 * is torn down.
 *
 * It is process-global on purpose. The gap between `BroadcastReceiver.onReceive()` returning and
 * `Service.onCreate()` running is the window where the device can fall back asleep and the alarm
 * silently dies; the system's own wake lock only covers `onReceive`. So the receiver acquires and
 * the service releases.
 *
 * Release paths, all of which must work:
 *  1. Normal dismiss -> `AlarmService.stopAlarm()` -> [release].
 *  2. Service destroyed for any reason -> `AlarmService.onDestroy()` -> [release].
 *  3. Receiver fails to start the service -> receiver's own catch -> [release].
 *  4. **Crash / kill**: the lock is acquired with [TIMEOUT_MS], so the OS drops it even if no code
 *     of ours ever runs again. A wake lock also dies with its process, so a hard kill is safe too.
 */
object AlarmWakeLock {

    private const val TAG = "AlarmWakeLock"
    private const val LOCK_TAG = "MathAlarm:ringing"

    /** Hard ceiling. Far longer than any sane ring, short enough that a leak cannot flatten a battery. */
    private const val TIMEOUT_MS = 10L * 60L * 1000L

    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        if (wakeLock?.isHeld == true) return
        val power = context.getSystemService(PowerManager::class.java)
        if (power == null) {
            Log.e(TAG, "no PowerManager; continuing without a wake lock")
            return
        }
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(TIMEOUT_MS)
        }
    }

    @Synchronized
    fun release() {
        val lock = wakeLock
        wakeLock = null
        if (lock == null) return
        runCatching { if (lock.isHeld) lock.release() }
            .onFailure { Log.w(TAG, "wake lock release failed", it) }
    }
}
