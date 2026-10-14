package com.mathalarm.alarm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.app.Service
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.mathalarm.R
import com.mathalarm.data.AlarmDatabase
import com.mathalarm.domain.AlarmStartPolicy
import com.mathalarm.ui.AlarmRingActivity
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The alarm. Everything that makes the alarm an alarm lives here, not in the activity:
 * the wake lock, the audio, the vibration, and the full-screen intent that puts a face on it.
 *
 * Structural rule (BUILD_SPECS §2, BRAINSTORM §5.1): **the sound is owned by this service.**
 * [AlarmRingActivity] holds no player, no `AudioManager`, no vibrator and no reference to
 * [AlarmRinger]; it can only ask this service to stop. So killing the activity — back, home,
 * recents, swipe-away, a crash in the Compose tree — cannot silence the alarm. The only things that
 * stop the sound are [ACTION_DISMISS] and the process dying.
 *
 * Structural rule two (BUILD_SPECS §4b item 3): **one alarm at a time, and the first one wins.**
 * A start that arrives while another alarm is ringing is refused by [AlarmStartPolicy] and handled
 * by [refuseStart] — it never overwrites [ringingAlarmId], the label, the ringer or the
 * notification. There is no per-alarm state here and no queue; refusing is the whole mechanism.
 * The one exception is BUILD_SPECS §4c: a ringer that has exhausted its audio recovery is silent for
 * good, so it is taken over rather than defended — handled by the ordinary RING path below.
 */
class AlarmService : Service() {

    private var ringer: AlarmRinger? = null
    private var ringingAlarmId: Long = NO_ALARM

    /**
     * The label the currently-posted notification was built with. Written only where
     * [ringingAlarmId] is written, and read only while [ringingAlarmId] is set, so it can never be
     * read stale. Needed because [ACTION_NOTIFICATION_DELETED] has to rebuild the notification
     * without an intent to read it from (review M2-1.1).
     */
    private var ringingLabel: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The alarm-clock broadcast gives us a short window to reach the foreground. Post the
        // notification before anything that can block or throw.
        return when (intent?.action) {
            ACTION_DISMISS -> {
                handleDismiss(intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, NO_ALARM), startId)
                START_NOT_STICKY
            }
            ACTION_NOTIFICATION_DELETED -> {
                handleNotificationDeleted(startId)
                START_NOT_STICKY
            }
            ACTION_START -> {
                handleStart(intent, startId)
                // If the OS kills us under memory pressure mid-ring, come back with the same intent
                // and keep ringing rather than failing silently.
                START_REDELIVER_INTENT
            }
            else -> {
                Log.w(TAG, "unexpected action ${intent?.action}; stopping")
                stopAlarm()
                stopSelf(startId)
                START_NOT_STICKY
            }
        }
    }

    private fun handleStart(intent: Intent, startId: Int) {
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, NO_ALARM)
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_LABEL).orEmpty()
        val vibrate = intent.getBooleanExtra(AlarmScheduler.EXTRA_ALARM_VIBRATE, true)
        val triggerAtMillis = intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, NO_TRIGGER)

        // One decision, taken once, from pure code (AlarmStartPolicy). Evaluating it here rather
        // than further down is safe and deliberate: `ringingAlarmId` is a main-thread-only field of
        // this service, the two ringer signals are `@Volatile` and read once here, and nothing
        // between this line and the duplicate check below can write any of them — startForeground
        // and AlarmWakeLock.acquire do not re-enter the service.
        val outcome = AlarmStartPolicy.forStart(
            alarmId,
            ringingAlarmId,
            ringer?.isRinging == true,
            ringer?.recoveryExhausted == true,
        )
        if (outcome == AlarmStartPolicy.Outcome.REFUSE) {
            refuseStart(alarmId, triggerAtMillis)
            return
        }
        // A §4c takeover overwrites `ringingAlarmId` below: settle that row first, before the try,
        // so the cancelling catch cannot skip it. A non-NO_ALARM argument here means a takeover and
        // nothing else — the policy's other RING branches all have no overwrite to make.
        settleTakenOverAlarm(if (alarmId != ringingAlarmId) ringingAlarmId else NO_ALARM)

        // Everything inside this try is load-bearing: without the foreground notification, the wake
        // lock and the ringer there is no alarm at all, so a failure here really does mean "give up".
        // Anything that is merely *nice* is started afterwards, outside the catch, because a catch
        // that cancels the alarm must never be reachable from a non-essential step (finding 1.14).
        try {
            ensureChannel()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(alarmId, label),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            // Handed over from AlarmReceiver; acquiring again is a no-op if it is already held.
            AlarmWakeLock.acquire(this)

            if (outcome == AlarmStartPolicy.Outcome.DUPLICATE) {
                Log.i(TAG, "alarm $alarmId already ringing; ignoring duplicate start")
                return
            }
            // §4c takeover: the exhausted AlarmRinger is reused, not replaced — `start` releases any
            // player, re-requests focus and resets the budget, and the wake lock is untouched.
            // Rationale for the decision: [AlarmStartPolicy.forStart]'s exhausted clause.
            ringingAlarmId = alarmId
            ringingLabel = label
            liveAlarmId = alarmId
            liveAlarmLabel = label
            (ringer ?: AlarmRinger(this).also { ringer = it }).start(vibrate)
        } catch (t: Throwable) {
            // Never leave a held wake lock or a half-started foreground service behind.
            Log.e(TAG, "failed to start alarm $alarmId", t)
            stopAlarm()
            stopSelf(startId)
            return
        }

        warnIfNoUiPath(alarmId)
        launchRingActivity(alarmId, label)
        rescheduleIfRepeating(alarmId, triggerAtMillis)
        // Everything from here is outside the catch that cancels the alarm, so it is individually
        // guarded (finding 1.14, and backlog 2.7 on unguarded synchronous prologues).
        runCatching { recordFired(alarmId) }
            .onFailure { Log.w(TAG, "could not dispatch the last-fired record", it) }
    }

    /**
     * A second alarm arrived while one was already ringing. **It is refused** (BUILD_SPECS §4b item
     * 3; closes backlog **1.7** / **1.8**, review **M2-1.8** and review **M3-1.2**). The decision
     * itself is [AlarmStartPolicy.forStart]; this is only what refusing *does*.
     *
     * What it deliberately does **not** touch — this is the whole point of the policy:
     *  - [ringingAlarmId], [ringingLabel], [liveAlarmId], [liveAlarmLabel] and [ringer] are left
     *    exactly as the live alarm set them, so `AlarmRingActivity` keeps the right alarm, the right
     *    tier and the right question count, and a dismiss keeps targeting the alarm the user is
     *    actually looking at;
     *  - the wake lock. It is already held — nothing can be live by either of the policy's measures
     *    without it, `AlarmWakeLock.acquire` is a no-op while held, and the live alarm's own dismiss
     *    releases it exactly once. So this path touches neither side of Module 1's ten-path
     *    accounting;
     *  - `stopSelf`. The live alarm needs this service. Leaving this start id outstanding costs
     *    nothing, for the same reason the swipe handler's does: the dismiss that eventually arrives
     *    is a newer delivery and its `stopSelf(startId)` is the one that stops the service. It also
     *    means a process kill redelivers this intent, after the live alarm's own — which replays
     *    this same decision rather than losing it.
     *
     * The one thing it does do to the notification is re-post the **live** alarm's, unchanged: this
     * delivery may carry a `startForegroundService` obligation of its own, and the honest way to
     * satisfy it is the notification that is already showing. Built from [ringingAlarmId] /
     * [ringingLabel], never from the refused alarm, so the full-screen intent cannot be re-pointed
     * at an alarm that is not ringing. Same notification id and same extras as the posted one, so
     * `FLAG_UPDATE_CURRENT` mints an identical `PendingIntent` and the shade just updates in place —
     * the same operation [handleNotificationDeleted] has been doing since M2-1.1.
     */
    private fun refuseStart(alarmId: Long, triggerAtMillis: Long) {
        Log.e(
            TAG,
            "alarm $alarmId refused: alarm $ringingAlarmId is already ringing. One alarm at a " +
                "time; the refused alarm is still rescheduled or auto-disabled below",
        )
        runCatching {
            ensureChannel()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(ringingAlarmId, ringingLabel),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        }.onFailure { Log.e(TAG, "could not re-post the ringing alarm's notification", it) }

        settleRefusedAlarm(alarmId, triggerAtMillis)
    }

    /**
     * A refused alarm must not be *lost*, only unrung. Its `AlarmManager` registration has already
     * been consumed by this delivery, so without this the refusal would silently cost the user their
     * next occurrence (a repeating alarm never rings again) or hand them a phantom one (a one-shot
     * stays `enabled`, and `AlarmRescheduler` re-books it for tomorrow at the same wall-clock
     * minute — backlog **1.8**'s harm, arriving on a morning the user never set an alarm for).
     *
     * Both calls are made and exactly one of them acts: [rescheduleIfRepeating] returns immediately
     * for a one-shot and [disableIfOneShot] returns immediately for a repeating alarm, so they are
     * disjoint on the same row and their order does not matter. Auto-disabling is also precisely
     * what a dismiss would have done to this row, which is what makes the list afterwards honest:
     * a one-shot that could not ring reads as off, not as armed.
     *
     * No "last fired" stamp: nothing rang. `AlarmHistory` is the record the diagnostics screen uses
     * to distinguish "it fires" from "nothing has ever fired", and a refused alarm firing a second
     * ringer is the one thing that did not happen here.
     */
    private fun settleRefusedAlarm(alarmId: Long, triggerAtMillis: Long) {
        if (alarmId == NO_ALARM) return
        rescheduleIfRepeating(alarmId, triggerAtMillis)
        val appContext = applicationContext
        AlarmWork.scope.launch {
            runCatching { disableIfOneShot(appContext, alarmId) }
                .onFailure { Log.e(TAG, "could not auto-disable refused alarm $alarmId", it) }
        }
    }

    /**
     * A §4c takeover handed this alarm's ringer away, so it can never be dismissed ([handleDismiss]
     * marks its dismiss stale, and the keypad has switched) and nothing else would ever disable it.
     * A one-shot left `enabled` with its registration consumed is backlog **1.8**'s phantom:
     * `AlarmRescheduler` re-books it for a morning the user never set (review **4c-1.1**).
     *
     * Only the [disableIfOneShot] half of [settleRefusedAlarm]: [rescheduleIfRepeating] genuinely
     * *did* run at this alarm's own start, so re-running it would book a second occurrence.
     */
    private fun settleTakenOverAlarm(alarmId: Long) {
        if (alarmId == NO_ALARM) return
        val appContext = applicationContext
        AlarmWork.scope.launch {
            runCatching { disableIfOneShot(appContext, alarmId) }
                .onFailure { Log.e(TAG, "could not auto-disable taken-over alarm $alarmId", it) }
        }
    }

    /**
     * "Last fired successfully" (BUILD_SPECS §4). Recorded here, after the ringer has started and
     * only on a *new* ring — the duplicate-start early return above never reaches this line, so a
     * redelivered `ACTION_START` does not re-stamp an existing ring.
     *
     * Dispatched onto [AlarmWork]'s process-lifetime scope rather than written here: backlog **2.5**
     * is about blocking `SharedPreferences` IO landing on the one thread that must stay free to
     * process `ACTION_DISMISS`, and this must not repeat that mistake.
     */
    private fun recordFired(alarmId: Long) {
        if (alarmId == NO_ALARM) return
        val appContext = applicationContext
        AlarmWork.scope.launch {
            runCatching { AlarmHistory.recordFired(appContext, alarmId) }
                .onFailure { Log.w(TAG, "could not record last-fired for alarm $alarmId", it) }
        }
    }

    /**
     * Dismiss: silence, release, and for a one-shot alarm auto-disable the row (keep it, so the user
     * can re-enable it later — BUILD_SPECS §2).
     *
     * Two things matter about the ordering here.
     *
     * **Target specificity (finding 1.6).** If alarm B started after this dismiss was queued, the
     * dismiss belongs to an alarm that is no longer ringing and must not silence B. `stopSelf` is
     * also always `stopSelf(startId)`, so a newer start keeps the service alive.
     *
     * **Durability (finding 1.9).** The auto-disable DB write used to be dispatched at the exact
     * moment the wake lock was released and `stopSelf()` made the process cached — with the screen
     * off, autosuspend can freeze the write mid-flight and the one-shot alarm then rings again
     * tomorrow. So teardown is deferred: the sound stops immediately, but the foreground
     * notification and the wake lock are held until the write has committed.
     */
    private fun handleDismiss(alarmId: Long, startId: Int) {
        val stale = alarmId != NO_ALARM && ringingAlarmId != NO_ALARM && alarmId != ringingAlarmId
        if (stale) {
            Log.w(TAG, "dismiss for alarm $alarmId ignored: alarm $ringingAlarmId is now ringing")
        } else {
            silenceRinger()
        }

        if (alarmId == NO_ALARM) {
            finishDismiss(startId)
            return
        }

        val appContext = applicationContext
        AlarmWork.scope.launch {
            try {
                val write = launch {
                    runCatching { disableIfOneShot(appContext, alarmId) }
                        .onFailure { Log.e(TAG, "could not auto-disable alarm $alarmId", it) }
                }
                // The wake lock cannot be held forever if the DB wedges, so there is a ceiling. The
                // join is what is timed out, not the write itself, so a slow write still completes.
                if (withTimeoutOrNull(DISMISS_WRITE_TIMEOUT_MS) { write.join() } == null) {
                    Log.e(TAG, "auto-disable for alarm $alarmId still pending; tearing down anyway")
                }
            } finally {
                // Unconditional: nothing may leave the wake lock held or the service foreground.
                mainHandler.post { finishDismiss(startId) }
            }
        }
    }

    /**
     * The user swiped the foreground-service notification out of the shade.
     *
     * `setOngoing(true)` only makes a notification unswipeable up to API 33. On API 34-36 — the
     * whole target range — a foreground-service notification is user-dismissible on an unlocked
     * device, and that notification is the **only** route back to [AlarmRingActivity]: its task is
     * `excludeFromRecents` with no `taskAffinity`, so it is invisible in recents, and `MainActivity`
     * cannot reach it. Module 1 could live with that (dismiss was one tap, so nobody left the
     * screen); Module 2 is the module that makes the user *stay*, so losing the route back means a
     * willing user ringing at full volume with no reachable keypad, no escape hatch and no way out
     * short of Force stop. That is review **M2-1.1**, and this is the fix: re-post the notification
     * the moment the system tells us it was removed.
     *
     * What this deliberately does **not** do:
     *  - it never touches the wake lock. [AlarmWakeLock] accounting is Module 1's, verified across
     *    ten paths, and a notification swipe is not an alarm lifecycle event;
     *  - it never writes [ringingAlarmId]. It *reads* it, and that read is the resurrection guard:
     *    once an alarm has stopped ringing the field is [NO_ALARM] and nothing is re-posted, so a
     *    swipe landing inside a dismiss teardown cannot put a dead alarm's notification back;
     *  - it never calls `stopSelf` while an alarm is live. This delivery is the newest start id, so
     *    a `stopSelf(startId)` here would stop the service outright — exactly the shape that makes
     *    backlog 2.2 able to silence the wrong alarm. The outstanding start id costs nothing while
     *    ringing, because the dismiss that follows is newer still and its `stopSelf(startId)` does
     *    stop the service; in the not-ringing branch it is tidied on a delay, after Module 1's own
     *    teardown has had its full 10 s write ceiling.
     *
     * The re-posted notification is built by [buildNotification], so it carries this same delete
     * intent: a second swipe is answered the same way.
     *
     * **Review M2-2.1.** The "nothing is ringing" test used to be `ringingAlarmId == NO_ALARM`
     * alone, which overloaded "this alarm has no id" onto "no alarm is ringing". An `ACTION_START`
     * without `EXTRA_ALARM_ID` would then ring normally while this handler took the dead-alarm
     * branch: no re-post (M2-1.1 re-opened for that ring) *and* a delayed `stopSelf` armed, which
     * would have silenced a live alarm 12 s later. The predicate is now the ringer itself, as the
     * review prescribed — with the id sentinel kept as a second clause rather than dropped, because
     * `isRinging` alone would regress the opposite case: after audio recovery gives up
     * (`PlaybackRecovery.Plan.GiveUp`, backlog **3.2**) `isRinging` is honestly false while an alarm
     * is still nominally live, and that ring must keep its route back to the keypad. So the branch is
     * taken only when *neither* says anything is live, which is exactly and only the dismiss-teardown
     * window the branch was written for.
     */
    private fun handleNotificationDeleted(startId: Int) {
        val alarmId = ringingAlarmId
        if (ringer?.isRinging != true && alarmId == NO_ALARM) {
            // Nothing is ringing. The notification is only ever deleted by a user *swipe*, and only
            // while it is posted, so this is the dismiss teardown window: the alarm is already
            // silent and the pending finishDismiss owns what is left. Re-posting here would
            // resurrect a notification for an alarm that has stopped.
            //
            // Nor may this stop the service: finding 1.9 holds the foreground notification and the
            // wake lock until the one-shot auto-disable write commits, and stopping here would end
            // both early, on the newest start id, with the write unprotected. So the outstanding
            // start id is cleared well after that ceiling instead, and only if nothing has started
            // ringing in the meantime. onDestroy purges this message along with every other.
            Log.i(TAG, "notification deleted after the alarm stopped; nothing to re-post")
            mainHandler.postDelayed(
                { if (ringer?.isRinging != true && ringingAlarmId == NO_ALARM) stopSelf(startId) },
                IDLE_STOP_DELAY_MS,
            )
            return
        }
        Log.w(TAG, "notification for alarm $alarmId was swiped away while ringing; re-posting it")
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(alarmId, ringingLabel),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        }.onFailure {
            // Nothing else to try: the sound continues either way, and tearing the alarm down
            // because its notification could not be re-posted would be strictly worse.
            Log.e(TAG, "could not re-post the alarm notification for $alarmId", it)
        }
    }

    /** Stops the noise but leaves the service foreground and the wake lock held. */
    private fun silenceRinger() {
        ringer?.shutdown()
        ringer = null
        ringingAlarmId = NO_ALARM
        liveAlarmId = NO_ALARM
    }

    /**
     * Last step of a dismiss. Re-checks [ringingAlarmId] because a new alarm can have started while
     * the auto-disable write was in flight; in that case this dismiss must leave the foreground
     * notification and the wake lock exactly where they are.
     *
     * **Backlog 2.2** — `stopSelf(startId)` is inside the guard, not after it. It used to run
     * unconditionally, which left the last way a stale dismiss could silence a live alarm: on the
     * stale path the dismiss delivery is the *newest* start id, so `stopSelf` is not declined, the
     * service is destroyed, and `onDestroy` -> [stopAlarm] shuts down the ringer of the alarm that
     * really is ringing. Exactly the harm finding 1.6 was filed for, re-entered through teardown
     * instead of through [silenceRinger]. A stale dismiss must be a **total** no-op, teardown
     * included, which is what this is.
     *
     * Leaving the start id outstanding costs nothing (the same reasoning as the swipe handler): the
     * live alarm's own dismiss arrives later with a newer start id, and *its* `stopSelf(startId)` is
     * the most recent delivery, so it does stop the service. [onDestroy] purges the rest.
     */
    private fun finishDismiss(startId: Int) {
        if (ringingAlarmId != NO_ALARM) {
            Log.w(
                TAG,
                "dismiss teardown skipped: alarm $ringingAlarmId is ringing, so this dismiss " +
                    "neither stops the service nor releases the wake lock",
            )
            return
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        AlarmWakeLock.release()
        stopSelf(startId)
    }

    /** Idempotent. Called from the failure path and from [onDestroy]. */
    private fun stopAlarm() {
        ringer?.shutdown()
        ringer = null
        ringingAlarmId = NO_ALARM
        liveAlarmId = NO_ALARM
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        AlarmWakeLock.release()
    }

    override fun onDestroy() {
        // The backstop for every exit path we did not anticipate, including the Compose/activity
        // side crashing or the system destroying the service.
        mainHandler.removeCallbacksAndMessages(null)
        stopAlarm()
        super.onDestroy()
    }

    // ------------------------------------------------------------ scheduling

    /**
     * A repeating alarm has just consumed its registration, so immediately book the next one.
     * Recomputed from wall-clock, never from a persisted epoch.
     *
     * Baselined off the instant that just fired rather than `now()` (finding 1.15). `NextTrigger`
     * returns the first candidate strictly after its baseline, so if this coroutine's `now` landed
     * at or a hair before the nominal trigger time — a clock correction, an NTP/`TIME_SET` nudge
     * arriving during boot, or a trigger delivered marginally early — today's occurrence would be
     * re-booked and fire again immediately. One minute past the known trigger instant is after the
     * occurrence we just consumed and before any other (alarms are minute-granular and the next
     * occurrence is at least a day away), so it cannot skip one either.
     */
    private fun rescheduleIfRepeating(alarmId: Long, triggerAtMillis: Long) {
        if (alarmId == NO_ALARM) return
        val appContext = applicationContext
        val baseline = rescheduleBaseline(triggerAtMillis)
        AlarmWork.scope.launch {
            runCatching {
                val alarm = AlarmDatabase.get(appContext).alarmDao().getById(alarmId) ?: return@runCatching
                if (alarm.isRepeating && alarm.enabled) {
                    AlarmScheduler(appContext).schedule(alarm, baseline)
                }
            }.onFailure { Log.e(TAG, "could not reschedule alarm $alarmId", it) }
        }
    }

    private fun rescheduleBaseline(triggerAtMillis: Long): ZonedDateTime {
        val now = ZonedDateTime.now()
        if (triggerAtMillis <= NO_TRIGGER) return now
        val afterTrigger = Instant.ofEpochMilli(triggerAtMillis)
            .atZone(ZoneId.systemDefault())
            .plusMinutes(1)
        return if (afterTrigger.isAfter(now)) afterTrigger else now
    }

    // ---------------------------------------------------------- presentation

    /**
     * The full-screen intent on the foreground-service notification is the **only** path to the
     * alarm UI; this direct `startActivity` is not a second one.
     *
     * A foreground service is not on the background-activity-launch exemption list, and
     * `AlarmManagerService` calls `setPendingIntentBackgroundActivityLaunchAllowed(false)` for
     * broadcast alarm operations, so the alarm broadcast grants no BAL privilege either. With no
     * visible window the system *silently drops* this launch — it does not throw, so the
     * `runCatching` below is a formality that will almost never see a failure. It is kept only for
     * the case where the app does have a visible window (the user is in the app when the alarm
     * fires), where it does take effect immediately rather than waiting on the notification.
     * See review finding 1.5.
     */
    private fun launchRingActivity(alarmId: Long, label: String) {
        runCatching { startActivity(ringIntent(alarmId, label)) }
            .onFailure { Log.i(TAG, "direct activity start refused; relying on full-screen intent", it) }
    }

    /**
     * Detection and logging only — Module 3 owns the remediation UX (finding 1.5).
     *
     * With notifications disabled, or `canUseFullScreenIntent()` denied on API 34+, the alarm rings
     * with no UI and therefore no way to dismiss it short of Settings. That is a total failure of the
     * module's promise, so it is logged at error level with the alarm id.
     */
    private fun warnIfNoUiPath(alarmId: Long) {
        runCatching {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                Log.e(
                    TAG,
                    "alarm $alarmId: notifications are DISABLED — the full-screen intent cannot be " +
                        "posted, so the alarm will ring with no dismissable UI",
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val manager = getSystemService(NotificationManager::class.java)
                if (manager != null && !manager.canUseFullScreenIntent()) {
                    Log.e(
                        TAG,
                        "alarm $alarmId: USE_FULL_SCREEN_INTENT is DENIED — the alarm UI will be " +
                            "downgraded to a heads-up notification and may not be reachable",
                    )
                }
            }
        }.onFailure { Log.w(TAG, "could not check notification capability", it) }
    }

    /**
     * Delegates to [openRingingIntent] so that the alarm list's "an alarm is ringing — open it"
     * route (review **M2-2.3**) sends a *byte-identical* intent to the one the notification's
     * full-screen and content `PendingIntent`s carry: same component, same action, same flags, same
     * extras. Anything else would reach `onNewIntent` looking like a different alarm.
     */
    private fun ringIntent(alarmId: Long, label: String): Intent =
        openRingingIntent(this, alarmId, label)

    private fun ensureChannel() {
        val channel = NotificationChannelCompat
            .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(getString(R.string.alarm_channel_name))
            .setDescription(getString(R.string.alarm_channel_description))
            // No channel sound or vibration: AlarmRinger owns both, on STREAM_ALARM.
            .setSound(null, null)
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .setLightsEnabled(true)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun buildNotification(alarmId: Long, label: String): Notification {
        val fullScreen = PendingIntent.getActivity(
            this,
            alarmId.toInt(),
            ringIntent(alarmId, label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Answers an API 34+ swipe by re-posting this notification (review M2-1.1). No extras and a
        // fixed request code: there is only ever one alarm notification, and the handler reads the
        // alarm that is ringing *now* rather than whichever one this PendingIntent was minted for.
        val deleted = PendingIntent.getService(
            this,
            REQUEST_NOTIFICATION_DELETED,
            Intent(this, AlarmService::class.java).setAction(ACTION_NOTIFICATION_DELETED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = label.ifBlank { getString(R.string.alarm_default_label) }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(getString(R.string.alarm_ringing))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setShowWhen(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setDeleteIntent(deleted)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "AlarmService"

        /**
         * Defined as [AlarmStartPolicy.NO_ALARM] rather than re-declared, so the service and the
         * pure start policy can never disagree about what "no alarm" is.
         */
        private const val NO_ALARM = AlarmStartPolicy.NO_ALARM

        /**
         * Process-global mirror of the instance fields [ringingAlarmId] / [ringingLabel], so the UI
         * can offer a **second route back to the keypad** while an alarm is live (review
         * **M2-2.3**: the notification re-post is self-healing but is still the only route, and it is
         * best-effort by construction).
         *
         * Written in exactly three places, all on the main thread, all alongside the instance fields they
         * mirror: `handleStart` sets both, `silenceRinger` and `stopAlarm` clear the id. The label is
         * deliberately *not* cleared, for the same reason [ringingLabel] is not — it is only ever
         * read while the id is set, so it cannot be read stale. A process death clears both by
         * clearing the process.
         *
         * This is a *status* mirror only. It is never an input to any alarm-lifecycle decision: the
         * service keeps deciding on its own instance fields and on [AlarmRinger.isRinging], so no
         * Module 1 or Module 2 invariant depends on it being fresh.
         */
        @Volatile private var liveAlarmId: Long = NO_ALARM

        @Volatile private var liveAlarmLabel: String = ""
        private const val NO_TRIGGER = 0L
        private const val NOTIFICATION_ID = 0x4A1A
        private const val CHANNEL_ID = "alarm_ringing"

        /** Fixed: one notification, so one delete `PendingIntent`, carrying no alarm id. */
        private const val REQUEST_NOTIFICATION_DELETED = 0x4A1B

        /** Ceiling on how long a dismiss will hold the wake lock waiting for the auto-disable write. */
        private const val DISMISS_WRITE_TIMEOUT_MS = 10_000L

        /**
         * How long a notification-delete delivery that found nothing ringing waits before clearing
         * its own start id. Comfortably past [DISMISS_WRITE_TIMEOUT_MS], so it can never shorten the
         * window finding 1.9 exists to protect.
         */
        private const val IDLE_STOP_DELAY_MS = DISMISS_WRITE_TIMEOUT_MS + 2_000L

        const val ACTION_START = "com.mathalarm.action.START_ALARM"
        const val ACTION_DISMISS = "com.mathalarm.action.DISMISS_ALARM"
        const val ACTION_RING = "com.mathalarm.action.RING"

        /**
         * Sent by the notification's own delete intent when the user swipes it away. Internal: no
         * other component constructs it, which is why there is no public factory for it.
         */
        private const val ACTION_NOTIFICATION_DELETED = "com.mathalarm.action.NOTIFICATION_DELETED"

        fun startIntent(
            context: Context,
            alarmId: Long,
            label: String,
            vibrate: Boolean,
            triggerAtMillis: Long = NO_TRIGGER,
        ): Intent =
            Intent(context, AlarmService::class.java).apply {
                action = ACTION_START
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmScheduler.EXTRA_ALARM_LABEL, label)
                putExtra(AlarmScheduler.EXTRA_ALARM_VIBRATE, vibrate)
                putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, triggerAtMillis)
            }

        /**
         * The alarm ringing in this process right now as `(id, label)`, or null.
         *
         * Read by the alarm list so it can show "an alarm is ringing — open it" (review
         * **M2-2.3**). Safe to call from anywhere; it is a plain volatile read and touches neither
         * the ringer nor the wake lock.
         */
        fun ringingAlarm(): Pair<Long, String>? =
            liveAlarmId.takeIf { it != NO_ALARM }?.let { it to liveAlarmLabel }

        /**
         * The intent that opens the ringing alarm's keypad. Used by the notification's full-screen
         * and content `PendingIntent`s *and* by the alarm list's second route, so both land in the
         * same `singleTask` instance with the same alarm id — which is what keeps the
         * `alarmId != previous` guard in `AlarmRingActivity.onNewIntent` from wiping a session that
         * already has questions solved and failures counted.
         */
        fun openRingingIntent(context: Context, alarmId: Long, label: String): Intent =
            Intent(context, AlarmRingActivity::class.java).apply {
                action = ACTION_RING
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmScheduler.EXTRA_ALARM_LABEL, label)
            }

        fun dismissIntent(context: Context, alarmId: Long): Intent =
            Intent(context, AlarmService::class.java).apply {
                action = ACTION_DISMISS
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            }

        private suspend fun disableIfOneShot(context: Context, alarmId: Long) {
            val dao = AlarmDatabase.get(context).alarmDao()
            val alarm = dao.getById(alarmId) ?: return
            if (alarm.isRepeating) return
            dao.disable(alarmId)
            AlarmScheduler(context).cancel(alarmId)
        }
    }
}
