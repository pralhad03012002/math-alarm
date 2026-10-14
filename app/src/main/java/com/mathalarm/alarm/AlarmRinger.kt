package com.mathalarm.alarm

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import com.mathalarm.R

/**
 * Owns the noise: alarm-stream volume, the looping player, and the vibration.
 *
 * **This class is only ever instantiated by [AlarmService].** Nothing in the UI layer holds or can
 * reach it — that is the structural rule that makes killing [com.mathalarm.ui.AlarmRingActivity]
 * harmless (BUILD_SPECS §2, BRAINSTORM §5.1).
 *
 * Threading: [start], [stop] and [shutdown] are called from the service's main thread. Everything
 * that touches [MediaPlayer] runs on a private [HandlerThread] instead, because `setDataSource` on a
 * `content://` ringtone and `prepare()` both do blocking IO through `MediaProvider`, and the one
 * thread that must stay responsive is the one that processes `ACTION_DISMISS` (review finding 1.13).
 * `MediaPlayer` callbacks are delivered on the looper that created the instance, so the error
 * listener also lands on that thread.
 */
internal class AlarmRinger(context: Context) {

    private val appContext: Context = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)

    /**
     * Device-protected so it is readable and writable before the first unlock, exactly like the
     * alarm database. See [rememberVolumeBeforeRinging].
     */
    private val volumeStore: SharedPreferences =
        appContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(VOLUME_PREFS, Context.MODE_PRIVATE)

    private val audioThread = HandlerThread(AUDIO_THREAD_NAME).also { it.start() }
    private val audioHandler = Handler(audioThread.looper)

    /** Audio thread only. */
    private var player: MediaPlayer? = null

    /** The caller's intent to be ringing. Checked on the audio thread so a [stop] that races a
     * queued start or error-recovery can never bring the sound back up. */
    @Volatile private var ringRequested = false

    /** True between a requested start and the moment the source chain resolves (or is exhausted). */
    @Volatile private var startPending = false

    /** True only while a [MediaPlayer] is actually alive and playing. */
    @Volatile private var audioAlive = false

    /**
     * Recovery is spent: this ring is silent for good, while `AlarmService.ringingAlarmId` is still
     * set. The third signal [com.mathalarm.domain.AlarmStartPolicy.forStart] needs, so a *different*
     * incoming alarm takes this dead ringer over instead of being refused (BUILD_SPECS §4c).
     *
     * Written on the audio thread, always *after* [startPending] goes false, so **within one start
     * generation** a main-thread reader that sees this true sees [isRinging] false. It does *not*
     * order against a new [start] (review **4c-1.2**), which is why `forStart`'s `&& !ringerIsRinging`
     * conjunct is the real guard and must not be deleted as redundant. [start] therefore clears this
     * twice: on the main thread, for a delivery arriving before its posted lambda runs, and again
     * inside that lambda, on the audio thread, after any in-flight exhaustion write.
     */
    @Volatile var recoveryExhausted = false
        private set

    /**
     * How many times [onPlaybackError] has restarted the source chain from the top during this
     * ring. Bounds recovery so a permanently broken device cannot retry forever (review finding
     * 2.1).
     *
     * Audio thread only — hence no `@Volatile`. [start] resets it *inside* the posted lambda rather
     * than on the main thread precisely so that this field never crosses threads.
     */
    private var restartPasses = 0

    private var focusRequest: AudioFocusRequest? = null
    private var vibrator: Vibrator? = null

    /**
     * The alarm-stream index **this ringer itself** last wrote, and the whole self-write filter for
     * [reassertAlarmVolume]. [NO_RECORDED_VOLUME] until the first write of a ring.
     *
     * Main thread only, like [focusRequest] and [vibrator] — see [startWatchingAlarmVolume] for why
     * the observer is deliberately dispatched onto the main looper rather than the audio thread, and
     * hence why this needs no `@Volatile`.
     */
    private var lastWrittenVolume: Int = NO_RECORDED_VOLUME

    /** Registered for the duration of a ring; null otherwise. Main thread only. */
    private var volumeObserver: ContentObserver? = null

    /**
     * `USAGE_ALARM` + `CONTENT_TYPE_SONIFICATION` is what routes this to `STREAM_ALARM` and gets it
     * through Do Not Disturb (BRAINSTORM §6.7).
     */
    private val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * Whether this ringer is making, or is about to make, sound.
     *
     * Deliberately **not** `player != null`: a player that hit an asynchronous error is dead but
     * still a non-null object, and reporting it as ringing is what made a mid-ring failure permanent
     * (review finding 1.4). A redelivered `ACTION_START` must be able to see that the alarm has gone
     * quiet and restart it.
     */
    val isRinging: Boolean get() = audioAlive || startPending

    /**
     * Start ringing. Every step is individually guarded: audio focus and vibration are niceties, and
     * a failure in a nicety must never propagate to [AlarmService.handleStart], whose catch cancels
     * the alarm outright (review finding 1.14).
     */
    fun start(vibrate: Boolean) {
        ringRequested = true
        startPending = true
        // Main thread, before anything is posted: a reused ringer (a §4c takeover, or the same alarm
        // re-asserting itself) must not be read as exhausted for even one delivery.
        recoveryExhausted = false
        runCatching { forceMaxAlarmVolume() }.onFailure { Log.w(TAG, "volume setup failed", it) }
        runCatching { startWatchingAlarmVolume() }
            .onFailure { Log.w(TAG, "volume watch setup failed", it) }
        runCatching { requestAudioFocus() }.onFailure { Log.w(TAG, "focus setup failed", it) }

        // Posted before vibration so nothing on the main thread can delay the sound.
        audioHandler.post {
            restartPasses = 0
            // Again, on the audio thread: strictly after any exhaustion write still in flight from
            // the previous ring, which the main-thread clear above cannot order against (4c-1.2).
            recoveryExhausted = false
            startPlaybackFrom(0)
        }

        stopVibration()
        if (vibrate) {
            runCatching { startVibration() }.onFailure { Log.w(TAG, "vibration setup failed", it) }
        }
    }

    /** Idempotent. Safe to call whether or not [start] ever succeeded. */
    private fun stop() {
        ringRequested = false
        startPending = false
        audioAlive = false
        // Before restoreAlarmVolume, and after ringRequested went false. Both halves matter: see
        // [reassertAlarmVolume] for why this ordering is what keeps the watcher from racing the
        // restore.
        stopWatchingAlarmVolume()
        audioHandler.removeCallbacksAndMessages(null)
        audioHandler.post { releasePlayer() }

        stopVibration()
        abandonAudioFocus()
        restoreAlarmVolume()
    }

    /** [stop], plus retire the playback thread. The ringer cannot be reused afterwards. */
    fun shutdown() {
        stop()
        audioHandler.post { audioThread.quitSafely() }
    }

    // ---------------------------------------------------------------- volume

    /** Instant maximum, no ramp (BUILD_SPECS §0). */
    private fun forceMaxAlarmVolume() {
        val manager = audioManager ?: return
        runCatching {
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            rememberVolumeBeforeRinging(manager.getStreamVolume(AudioManager.STREAM_ALARM))
            // Recorded *before* the write, never after: the self-write filter must already know the
            // index we are about to set by the time the setting change can be observed.
            lastWrittenVolume = max
            manager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        }.onFailure {
            // Some DND policies reject volume changes without notification-policy access.
            Log.w(TAG, "could not raise alarm volume", it)
        }
    }

    /**
     * Records the user's own alarm volume **durably, before the stream is touched**, so that we can
     * always put it back (review finding 1.1).
     *
     * An in-memory field is not enough. The service returns `START_REDELIVER_INTENT`, so after an
     * OOM kill the redelivered start runs in a fresh process and reads a stream the dead instance
     * already pinned to maximum — recording *max* as "the user's choice" and permanently rewriting a
     * system setting the user owns. So:
     *  - the write is `commit()`, not `apply()`: it must be on disk before `setStreamVolume` runs;
     *  - an existing record is never overwritten. A record that is still present means the previous
     *    ring never restored, so *that* value — not the current maxed one — is the user's volume;
     *  - the record is cleared only by a successful restore ([restoreAlarmVolume]).
     */
    private fun rememberVolumeBeforeRinging(current: Int) {
        if (volumeStore.contains(KEY_PRIOR_VOLUME)) {
            Log.i(TAG, "keeping stale pre-ring volume record; a previous ring never restored")
            return
        }
        val committed = volumeStore.edit().putInt(KEY_PRIOR_VOLUME, current).commit()
        if (!committed) Log.e(TAG, "could not persist pre-ring alarm volume $current")
    }

    private fun restoreAlarmVolume() {
        val manager = audioManager ?: return
        val previous = volumeStore.getInt(KEY_PRIOR_VOLUME, NO_RECORDED_VOLUME)
        if (previous == NO_RECORDED_VOLUME) return
        val max = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_ALARM) }
            .getOrDefault(previous)
        val target = previous.coerceIn(0, max)
        // Same ordering rule as forceMaxAlarmVolume: the filter learns the index before the write.
        lastWrittenVolume = target
        runCatching { manager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0) }
            .onSuccess { volumeStore.edit().remove(KEY_PRIOR_VOLUME).commit() }
            .onFailure {
                // Keep the record: the next dismiss gets another chance to put the volume back.
                Log.w(TAG, "could not restore alarm volume to $previous", it)
            }
    }

    // ------------------------------------------------------- volume watchdog

    /**
     * BUILD_SPECS §4, from review **M2-1.7**: the notification shade opens over any activity and
     * takes window focus, and its volume row targets `STREAM_ALARM` while the ringer plays — so the
     * activity's volume-key interception, which is all Module 2 was asked for, does not cover a user
     * who drags the alarm slider to zero on a fully permissioned device. AVRCP and OEM mute keys get
     * around the window too. So the stream is watched here, in the layer that owns it, and re-asserted
     * to maximum when something *else* lowers it.
     *
     * **Why this is not the naive observer that review ruling 1 rejected.** Two independent guards,
     * and both are required:
     *
     *  1. **The self-write filter.** [lastWrittenVolume] records the index this ringer is about to
     *     write, *before* it writes it (both in [forceMaxAlarmVolume] and in [restoreAlarmVolume]).
     *     An observation equal to that index is our own write echoing back and is ignored. Without
     *     this the ringer's own `setStreamVolume(max)` would re-trigger the observer, and the
     *     *restore* on dismiss would be instantly undone by a re-assert — which is the finding-1.1
     *     bug class the deferral was about.
     *  2. **Main-looper dispatch plus the `ringRequested` gate.** The observer is constructed with a
     *     main-looper `Handler`, so its callbacks are serialized against [start] and [stop], which
     *     also run on the main thread. [stop] sets `ringRequested = false` **first**, then
     *     unregisters, and only then calls [restoreAlarmVolume]. So a callback that was already
     *     queued when [stop] ran cannot interleave with the restore, and when it does run it sees
     *     `ringRequested == false` and returns without touching the stream. Unregistering alone would
     *     not be enough — an already-dispatched message survives `unregisterContentObserver`.
     *
     * **The durable pre-ring record is never touched on this path.** [rememberVolumeBeforeRinging] is
     * reachable only from [forceMaxAlarmVolume], i.e. only from [start]; this watcher reads and writes
     * nothing in [volumeStore], so the one thing finding 1.1 created cannot be corrupted by it.
     *
     * Honest limit: `AudioService` persists a stream's volume to `Settings.System` on a short delay,
     * so the re-assert lands a fraction of a second after the user's drag rather than instantly, and
     * a ROM that does not persist the alarm stream at all will not notify us. This is hardening —
     * silencing is not dismissing, the gate still stands and the alarm row is not auto-disabled — so
     * best-effort is the right contract here.
     */
    private fun startWatchingAlarmVolume() {
        if (volumeObserver != null) return
        val manager = audioManager ?: return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = reassertAlarmVolume(manager)
        }
        appContext.contentResolver
            .registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        volumeObserver = observer
    }

    /** Main thread. Idempotent. */
    private fun stopWatchingAlarmVolume() {
        val observer = volumeObserver ?: return
        volumeObserver = null
        runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
            .onFailure { Log.w(TAG, "could not unregister the volume observer", it) }
    }

    /** Main thread, via the observer's handler. See [startWatchingAlarmVolume]. */
    private fun reassertAlarmVolume(manager: AudioManager) {
        if (!ringRequested) return
        runCatching {
            val current = manager.getStreamVolume(AudioManager.STREAM_ALARM)
            // Our own write echoing back, or a setting that is not the alarm stream at all.
            if (current == lastWrittenVolume) return@runCatching
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (current >= max) {
                // Somebody else set it to maximum. Adopt it as ours and write nothing.
                lastWrittenVolume = current
                return@runCatching
            }
            Log.w(TAG, "alarm stream lowered to $current while ringing; re-asserting $max")
            lastWrittenVolume = max
            manager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        }.onFailure {
            // A DND policy can reject the write exactly as it can reject the initial one; surfaced
            // to the user by the diagnostics screen's alarm-volume row, not by fighting it here.
            Log.w(TAG, "could not re-assert the alarm volume", it)
        }
    }

    // ----------------------------------------------------------------- focus

    /** Transient-exclusive focus so music or a podcast playing in the room gets out of the way. */
    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        // A reused ringer (alarm B starting while A rings) must not leak A's request.
        abandonAudioFocus()
        val request = AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(alarmAttributes)
            .setWillPauseWhenDucked(false)
            // Deliberately no listener: losing focus must never silence an alarm.
            .build()
        focusRequest = request
        runCatching { manager.requestAudioFocus(request) }
            .onFailure { Log.w(TAG, "audio focus request failed", it) }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager
        val request = focusRequest
        focusRequest = null
        if (manager == null || request == null) return
        runCatching { manager.abandonAudioFocusRequest(request) }
    }

    // ---------------------------------------------------------------- player

    /**
     * Tries the user's default alarm sound, then the default ringtone, then a bundled raw tone,
     * starting at [fromIndex]. The last one matters more than it looks: it is the only source
     * guaranteed to be readable before the first unlock after a reboot, when `MediaStore` and
     * `Settings` are still locked behind credential-encrypted storage.
     *
     * Audio thread only.
     */
    private fun startPlaybackFrom(fromIndex: Int) {
        releasePlayer()
        if (!ringRequested) {
            startPending = false
            return
        }
        val sources = candidateSources()
        for (index in fromIndex until sources.size) {
            val source = sources[index]
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(alarmAttributes)
                mp.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
                mp.setOnErrorListener { failed, what, extra ->
                    onPlaybackError(failed, index, what, extra)
                    true
                }
                when (source) {
                    is Source.FromUri -> mp.setDataSource(appContext, source.uri)
                    is Source.FromRaw ->
                        appContext.resources.openRawResourceFd(source.resId).use { fd ->
                            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                        }
                }
                mp.isLooping = true
                mp.setVolume(1f, 1f)
                mp.prepare()
                routeToSpeaker(mp)
                mp.start()
                player = mp
                audioAlive = true
                startPending = false
                return
            } catch (t: Throwable) {
                Log.w(TAG, "audio source $source unusable", t)
                runCatching { mp.release() }
            }
        }
        audioAlive = false
        // Every source failed *synchronously* — the usual cause is the same one [onPlaybackError]
        // recovers from: a mediaserver that is still restarting, so `prepare()` throws for all three
        // sources. Giving up here would hand back exactly the permanent silence finding 2.1 is
        // about, one step further along, so this path spends the same bounded budget on the same
        // delayed retry. Shares [PlaybackRecovery]'s constants; the policy is the sibling of
        // [PlaybackRecovery.plan]'s wrap-around branch.
        if (restartPasses < PlaybackRecovery.MAX_RESTART_PASSES) {
            restartPasses++
            startPending = true // keep isRinging true across the delay, as on the async path
            Log.e(TAG, "every audio source from index $fromIndex failed; retrying the whole chain")
            audioHandler.postDelayed({ startPlaybackFrom(0) }, PlaybackRecovery.RETRY_DELAY_MS)
            return
        }
        startPending = false
        recoveryExhausted = true // after startPending, never before: see [recoveryExhausted].
        Log.e(TAG, "every audio source from index $fromIndex failed and recovery is exhausted; alarm is silent")
    }

    /**
     * An asynchronous `MediaPlayer` failure — `MEDIA_ERROR_SERVER_DIED` on a mediaserver restart, or
     * a `content://` ringtone whose provider dies mid-ring — used to stop the alarm permanently with
     * nothing observing it (review finding 1.4). [PlaybackRecovery.plan] decides what to do: a
     * source-specific error falls forward to the next candidate, an exhausted chain (or a
     * `MEDIA_ERROR_SERVER_DIED`, which is not source-specific) wraps around to a delayed restart
     * from index 0, and the restart passes are bounded (review finding 2.1). Without that wrap-around
     * an error on the *last* source — the bundled tone that the direct-boot path depends on — left
     * `startPlaybackFrom` with an empty range and the alarm silent for the rest of the ring.
     *
     * [startPending] stays true across the retry delay so [isRinging] keeps reporting the alarm as
     * live while recovery is in progress; otherwise a redelivered `ACTION_START` landing in the
     * delay window would be misread as a duplicate. It is cleared only when the budget is spent, so
     * a redelivered start can still retry from scratch.
     *
     * Audio thread (MediaPlayer callbacks are delivered on the creating looper).
     */
    private fun onPlaybackError(failed: MediaPlayer, failedIndex: Int, what: Int, extra: Int) {
        audioAlive = false
        if (!ringRequested) {
            runCatching { failed.release() }
            return
        }
        if (player !== failed) {
            // A stale player we have already moved past; nothing to recover.
            runCatching { failed.release() }
            return
        }
        // Drop the dead player now rather than keeping it (and its listener) alive across the delay.
        releasePlayer()
        val plan = PlaybackRecovery.plan(failedIndex, candidateSources().size, what, restartPasses)
        Log.e(TAG, "media error what=$what extra=$extra on source $failedIndex; plan=$plan")
        when (plan) {
            is PlaybackRecovery.Plan.Restart -> {
                // A fall-forward always targets failedIndex + 1, i.e. >= 1, so index 0 identifies a
                // wrap-around and nothing else. Counting every wrap-around is what makes the chain
                // finite even when source 0 itself keeps dying.
                if (plan.fromIndex == 0) restartPasses++
                startPending = true
                // postDelayed, not post: the MediaPlayer work stays on the audio HandlerThread, and
                // startPlaybackFrom re-reads ringRequested there, after the delay.
                audioHandler.postDelayed({ startPlaybackFrom(plan.fromIndex) }, plan.delayMs)
            }
            PlaybackRecovery.Plan.GiveUp -> {
                // Honest state: isRinging goes false, so a redelivered ACTION_START is not
                // mistaken for a duplicate and can start a fresh ring with a fresh budget.
                startPending = false
                recoveryExhausted = true // after startPending, never before: see [recoveryExhausted].
                Log.e(
                    TAG,
                    "audio recovery exhausted after ${PlaybackRecovery.MAX_RESTART_PASSES} " +
                        "restarts; alarm is silent while the notification still claims it rings",
                )
            }
        }
    }

    /** Audio thread only. */
    private fun releasePlayer() {
        val mp = player
        player = null
        audioAlive = false
        if (mp == null) return
        runCatching { mp.setOnErrorListener(null) }
        runCatching { if (mp.isPlaying) mp.stop() }.onFailure { Log.w(TAG, "stop failed", it) }
        runCatching { mp.release() }.onFailure { Log.w(TAG, "release failed", it) }
    }

    private fun candidateSources(): List<Source> = buildList {
        runCatching { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) }
            .getOrNull()?.let { add(Source.FromUri(it)) }
        runCatching { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) }
            .getOrNull()?.let { add(Source.FromUri(it)) }
        add(Source.FromRaw(R.raw.fallback_alarm))
    }

    /**
     * BRAINSTORM §6.8: never let the alarm play only into Bluetooth headphones nobody is wearing.
     * `setPreferredDevice` is API 28+; below that we accept default routing.
     */
    private fun routeToSpeaker(mp: MediaPlayer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val manager = audioManager ?: return
        val speaker = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        }.getOrNull() ?: return
        runCatching { mp.setPreferredDevice(speaker) }
            .onFailure { Log.w(TAG, "could not force speaker output", it) }
    }

    // ------------------------------------------------------------- vibration

    private fun startVibration() {
        val v = resolveVibrator() ?: return
        if (!v.hasVibrator()) return
        vibrator = v
        val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, VIBRATION_REPEAT_INDEX)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, alarmAttributes)
        }
    }

    private fun stopVibration() {
        vibrator?.let { v -> runCatching { v.cancel() } }
        vibrator = null
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Vibrator::class.java)
        }

    private sealed interface Source {
        data class FromUri(val uri: Uri) : Source
        data class FromRaw(val resId: Int) : Source
    }

    private companion object {
        const val TAG = "AlarmRinger"
        const val AUDIO_THREAD_NAME = "AlarmRingerAudio"

        const val VOLUME_PREFS = "alarm_volume"
        const val KEY_PRIOR_VOLUME = "volume_before_ringing"
        const val NO_RECORDED_VOLUME = -1

        /** wait, buzz, pause — repeating from index 0. */
        val VIBRATION_PATTERN = longArrayOf(0L, 800L, 600L)
        const val VIBRATION_REPEAT_INDEX = 0
    }
}
