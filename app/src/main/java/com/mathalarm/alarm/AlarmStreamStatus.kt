package com.mathalarm.alarm

import android.content.Context
import android.media.AudioManager

/**
 * A **read-only** look at the alarm stream, for the self-diagnostic screen.
 *
 * This lives in `alarm/` and not in `ui/` on purpose. BUILD_SPECS §2's structural rule is that the
 * UI layer holds no audio object at all — no `AudioManager`, no `MediaPlayer`, no `Vibrator` — so the
 * diagnostics screen asks *this* object a question instead of reading the stream itself. Nothing here
 * writes: the alarm stream is [AlarmRinger]'s to set and restore (review finding 1.1's durable
 * pre-ring record depends on exactly one writer), and this object must never acquire a second one.
 *
 * Surfaces Module 1 backlog **1.12**: a Do-Not-Disturb policy can reject `setStreamVolume` without
 * notification-policy access, and until now nothing told the user that the alarm stream was muted.
 */
object AlarmStreamStatus {

    /**
     * Whether an alarm would currently be audible at all, i.e. the alarm stream is not at zero.
     *
     * A false here is a warning rather than a failure: [AlarmRinger] forces the stream to maximum on
     * every ring, so the usual cause of a lasting zero is a policy that rejects that write.
     * Returns true when the state cannot be read, so a missing `AudioManager` shows as a pass rather
     * than as a scary red row the user cannot act on.
     */
    fun isAudible(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(AudioManager::class.java)
            ?: return true
        return runCatching { manager.getStreamVolume(AudioManager.STREAM_ALARM) > 0 }
            .getOrDefault(true)
    }
}
