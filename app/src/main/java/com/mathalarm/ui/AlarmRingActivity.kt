package com.mathalarm.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.mathalarm.R
import com.mathalarm.alarm.AlarmScheduler
import com.mathalarm.alarm.AlarmService
import com.mathalarm.data.AlarmDatabase
import com.mathalarm.data.Difficulty
import com.mathalarm.domain.ChallengeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The face of [AlarmService] over the lock screen, and the math gate that stands in front of
 * dismissing it.
 *
 * ### The rule that outranks everything else
 * **Holds no audio.** There is no `MediaPlayer`, no `AudioManager`, no `AudioAttributes`, no
 * `Vibrator`, no `Ringtone`, no `WakeLock` and no reference to `AlarmRinger` anywhere in this file
 * or in anything it composes. The only power this activity has over the alarm is sending
 * [AlarmService.dismissIntent]. That is what makes the sound survive a wrong answer, a
 * recomposition, a rotation, a Compose crash, back, home, recents and this activity being destroyed
 * outright (BUILD_SPECS §2, §3). Module 2 did not change that and nothing in Module 3 should.
 *
 * The wrong-answer haptic goes through Compose's [LocalHapticFeedback], i.e. `View`'s own
 * `performHapticFeedback`, precisely so that the UI never has to hold a `Vibrator` — the ringer's
 * vibration is the service's business and this screen cannot interfere with it.
 *
 * ### Where the challenge state lives, and why it lives there
 * [challenge] is **one activity-scoped `mutableStateOf`**, not a pile of `remember`s inside the
 * composable. Composition only reads it; every transition goes through a pure
 * [ChallengeState] method called from a click handler. So:
 *  - **a recomposition cannot regenerate the problem** — no composable calls the generator, and the
 *    only way a new problem appears is `ChallengeState.submit()` returning a new value;
 *  - **a recomposition cannot reset the failure count** — `consecutiveWrong` is a field of a value
 *    held outside composition, so there is no `remember` to miss its key;
 *  - **rotation keeps everything** — [onSaveInstanceState] writes the session's fields into the
 *    activity `Bundle` and [onCreate] reads them back before any session is started;
 *  - **process death keeps everything** — same `Bundle`, restored by the system when it recreates
 *    the activity. The alarm cannot become dismissable for free by killing the app, because the
 *    service is what is ringing and the restored session still owes the same questions.
 *
 * The one thing deliberately **not** saved is the escape countdown deadline: see [onStop].
 */
class AlarmRingActivity : ComponentActivity() {

    /** Reactive (review finding 1.16): `onNewIntent` must be able to re-label the screen. */
    private var alarmId by mutableStateOf(NO_ALARM)
    private var label by mutableStateOf("")

    /** `null` only for the few milliseconds it takes to read the alarm's tier out of the database. */
    private var challenge by mutableStateOf<ChallengeState?>(null)

    /** Which alarm the in-flight configuration read belongs to; guards a second alarm arriving. */
    private var loadingFor = NO_ALARM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockscreen()
        blockBackButton()
        readIntentExtras()

        challenge = savedInstanceState?.let(::readChallenge)
        if (challenge == null) startSession(alarmId)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AlarmChallengeScreen(
                        label = label,
                        state = challenge,
                        onDigit = { value -> challenge = challenge?.digit(value) },
                        onBackspace = { challenge = challenge?.backspace() },
                        onSubmit = ::submitAnswer,
                        onStartEscape = ::startEscape,
                        onCancelEscape = { challenge = challenge?.cancelEscape() },
                        onEscapeElapsed = ::completeEscape,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val previous = alarmId
        readIntentExtras()
        // A second alarm reusing this singleTask instance is a new session: its own tier, its own
        // question count, its own failure count. Reusing the old one would carry four free failures
        // into the new alarm.
        if (alarmId != previous) {
            challenge = null
            startSession(alarmId)
        }
    }

    private fun readIntentExtras() {
        alarmId = intent?.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, NO_ALARM) ?: NO_ALARM
        label = intent?.getStringExtra(AlarmScheduler.EXTRA_ALARM_LABEL).orEmpty()
    }

    // ------------------------------------------------------------- the session

    /**
     * Read this alarm's [Difficulty] and question count out of the existing `alarms` row and open a
     * session. **No new persistence** (BUILD_SPECS §3): the columns are already there from Module 1
     * and nothing is written back.
     *
     * The database lives in device-protected storage, so this read works before the first unlock
     * after a reboot — the same reason [AlarmRingActivity] is declared `directBootAware`.
     *
     * If the row is gone, the id is absent, or the read throws, the session falls back to the
     * documented default of MEDIUM × 1 rather than leaving the user with no way to dismiss.
     *
     * **And if the read never returns at all** (review M2-1.6). `runCatching` converts a *thrown*
     * read into the fallback; it cannot convert a *hung* one. A `getById` that blocks — the
     * device-protected database opened under contention during the post-boot I/O storm, a locked
     * database file — would leave `challenge` null forever, and the null branch composes no keypad,
     * no Submit and no escape button, so the alarm would ring with no way to dismiss it at all:
     * the same stranding M2-1.1 is about, arrived at from the other side. The read is therefore
     * raced against a timeout, and it is the *await* that is timed out rather than the read itself,
     * so a merely slow read cannot be interrupted mid-query. Either way the MEDIUM × 1 fallback is
     * guaranteed rather than conditional on the read terminating.
     */
    private fun startSession(id: Long) {
        loadingFor = id
        lifecycleScope.launch {
            val read = async(Dispatchers.IO) {
                runCatching { AlarmDatabase.get(applicationContext).alarmDao().getById(id) }
                    .onFailure { Log.w(TAG, "could not read alarm $id; using the default tier", it) }
                    .getOrNull()
            }
            val alarm = withTimeoutOrNull(TIER_READ_TIMEOUT_MS) { read.await() }
            if (!read.isCompleted) {
                Log.e(TAG, "reading alarm $id timed out; falling back to the default tier")
            }
            // Completed or abandoned, this deferred has no further use; cancelling a finished one
            // is a no-op and cancelling a wedged one stops it holding the session's coroutine open.
            read.cancel()
            // A newer alarm (or a restored Bundle) has taken over since this read started.
            if (loadingFor != id || challenge != null) return@launch
            challenge = ChallengeState.start(
                difficulty = alarm?.difficulty ?: Difficulty.MEDIUM,
                questionCount = alarm?.questionCount ?: 1,
                seed = System.nanoTime(),
            )
        }
    }

    /**
     * Grade one submission. Returns `true` when the answer was right, so the caller can buzz on a
     * wrong one; the sound of the alarm itself is untouched either way.
     *
     * The dismiss happens here, in an event handler, and not in a `LaunchedEffect`: a composition
     * must never be the thing that decides the alarm is over.
     */
    private fun submitAnswer(): Boolean {
        val current = challenge ?: return true
        val next = current.submit()
        challenge = next
        // "*This* submission completed the session", not "the session is complete" (review M2-1.3).
        // `submit()` on an empty entry returns `this` unchanged, and `this` may already be solved,
        // so the old predicate fired on a submission that solved nothing — a double-tap on the last
        // question sent a second, unearned dismiss, and any state arriving already `isSolved` would
        // have been handed a free one. The module's worst defect class is an unearned dismissal, so
        // the cause has to be this correct answer rather than a property of the state.
        val progressed = next.solved > current.solved
        if (progressed && next.isSolved) dismiss()
        return progressed
    }

    /** Only ever reachable with the hatch unlocked; [ChallengeState.startEscape] re-checks anyway. */
    private fun startEscape() {
        challenge = challenge?.startEscape(SystemClock.elapsedRealtime())
    }

    /**
     * The countdown's own authority check. The composable's timer merely says "a minute of wall
     * clock has gone by"; this re-asks the state whether the hatch was earned and whether the
     * countdown is still the one that was started, and only then dismisses.
     */
    private fun completeEscape() {
        val current = challenge ?: return
        if (current.isEscapeComplete(SystemClock.elapsedRealtime())) {
            dismiss()
        } else {
            // The minute elapsed but the entitlement did not hold. Put the user back on the keypad
            // rather than leaving a dead countdown on screen; the alarm is still ringing either way.
            Log.w(TAG, "escape countdown elapsed without entitlement; returning to the problem")
            challenge = current.cancelEscape()
        }
    }

    // ------------------------------------------------------------ anti-cheat

    /**
     * Back must not dismiss the alarm.
     *
     * An always-enabled [OnBackPressedCallback] that does nothing: the modern dispatcher API, not
     * the deprecated `onBackPressed()` override, so it also swallows API 33+ predictive-back
     * gestures instead of letting them animate the activity away.
     *
     * Note what this does *not* claim. Even if back did finish the activity, the alarm would keep
     * ringing — the service owns the sound. This callback is about not making it *look* dismissed.
     */
    private fun blockBackButton() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    Log.i(TAG, "back press swallowed; alarm $alarmId still requires the math gate")
                }
            },
        )
    }

    /**
     * Volume keys must not silence the alarm.
     *
     * Consumed while this activity has window focus, which is the whole time the alarm UI is up:
     * a swallowed `ACTION_DOWN` also suppresses the repeat and long-press that would otherwise
     * ramp the stream down. `KEYCODE_VOLUME_MUTE` is included because on some OEM builds it is the
     * one key that zeroes a stream in a single press.
     *
     * Honest limit: this is window-focus scoped. It cannot cover the case where the alarm UI is not
     * in front (notifications disabled, so no full-screen intent) — there the keys reach the system
     * volume panel and only the service re-asserting the stream would help. See the report.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (isVolumeKey(keyCode)) true else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        if (isVolumeKey(keyCode)) true else super.onKeyUp(keyCode, event)

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean =
        if (isVolumeKey(keyCode)) true else super.onKeyLongPress(keyCode, event)

    private fun isVolumeKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
        keyCode == KeyEvent.KEYCODE_VOLUME_MUTE

    // -------------------------------------------------- state across lifecycle

    /**
     * Everything the session needs to come back identically.
     *
     * `seed` + `draws` is the whole random stream, so the problem on screen and the no-repeat
     * history are both exactly restored — the user does not get a fresh, possibly easier problem by
     * rotating the phone, and the failure count is not forgiven.
     *
     * **The field list itself lives in `ChallengeState.encode` / `decode`, not here.** Those are
     * pure Kotlin, so the save/restore *pair* is unit-tested off-device. It used to be eleven
     * `putX` calls against a reader that the test mirrored rather than called, which meant dropping
     * one of them would have failed no test; now it fails `a session survives the Bundle round
     * trip`. This activity keeps only the single key and the `Bundle` call, which is all that
     * genuinely needs Android.
     *
     * **The escape deadline is not written.** It is an `elapsedRealtime` instant, which keeps
     * running while the process is dead; persisting it would let a countdown complete that nobody
     * was looking at, which is exactly the thing the "stay on screen" rule exists to prevent.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val state = challenge ?: return
        outState.putString(KEY_SESSION, ChallengeState.encode(state))
    }

    /** `null` for anything that is not a complete, self-consistent session: see `decode`. */
    private fun readChallenge(saved: Bundle): ChallengeState? =
        ChallengeState.decode(saved.getString(KEY_SESSION))

    /**
     * Leaving or backgrounding resets the countdown (BUILD_SPECS §3). This is the enforcement: the
     * deadline is dropped the moment the activity stops, for *any* reason — home, recents, another
     * activity on top, the screen being turned off, a configuration change, or the process being
     * killed. Coming back restarts the full sixty seconds.
     *
     * Everything else about the session survives; only the countdown is lost, and only in the
     * direction that makes the alarm harder to escape.
     */
    override fun onStop() {
        super.onStop()
        challenge = challenge?.cancelEscape()
    }

    /**
     * Wake the screen and show through the keyguard. `setShowWhenLocked` / `setTurnScreenOn` are
     * API 27+; on API 26 the equivalent window flags are the only option, so both paths are here.
     * `FLAG_KEEP_SCREEN_ON` stops the screen timing out while the alarm is still going — which
     * matters more now than it did in Module 1, because the escape countdown requires the user to
     * sit and watch the screen for a full minute.
     *
     * **No `requestDismissKeyguard`** (BUILD_SPECS §2, review finding 1.2). On any device with a
     * PIN, pattern or password and no active trust agent, that call stacks an authentication
     * bouncer in front of this activity, so the math keypad cannot be reached until the user
     * authenticates. `setShowWhenLocked(true)` already shows the alarm over the keyguard without
     * authentication, which is what AOSP DeskClock and Google Clock do. Do not add it back.
     */
    private fun showOverLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Ask the service to stop, then get out of the way. The service — not this activity — silences
     * the audio, releases the wake lock, and auto-disables a one-shot alarm.
     */
    private fun dismiss() {
        runCatching { startService(AlarmService.dismissIntent(this, alarmId)) }
            .onFailure { Log.e(TAG, "could not deliver dismiss for alarm $alarmId", it) }
        finish()
    }

    private companion object {
        const val TAG = "AlarmRingActivity"
        const val NO_ALARM = -1L

        const val KEY_SESSION = "challenge.session"

        /** Ceiling on the tier read before the MEDIUM × 1 fallback is taken (review M2-1.6). */
        const val TIER_READ_TIMEOUT_MS = 5_000L
    }
}

// ---------------------------------------------------------------------- the UI

private val KEY_HEIGHT = 64.dp
private val KEY_GAP = 8.dp

/**
 * The whole alarm screen. Stateless: it renders [state] and reports taps. It never generates a
 * problem, never grades an answer and never decides the alarm is over.
 *
 * [onSubmit] returns whether the answer was right, which is the only reason this layer needs a
 * return value at all — a wrong answer has to buzz, and the buzz belongs to the view.
 */
@Composable
private fun AlarmChallengeScreen(
    label: String,
    state: ChallengeState?,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Boolean,
    onStartEscape: () -> Unit,
    onCancelEscape: () -> Unit,
    onEscapeElapsed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Seeing the actual time is itself motivating (BRAINSTORM §4.3), and it stays on screen
        // through every wrong answer and through the escape countdown.
        Clock()
        Spacer(Modifier.height(4.dp))
        Text(
            text = label.ifBlank { stringResource(R.string.alarm_default_label) },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))

        when {
            state == null -> Text(
                text = stringResource(R.string.challenge_preparing),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )

            state.escapeRunning -> EscapeCountdown(
                state = state,
                onElapsed = onEscapeElapsed,
                onCancel = onCancelEscape,
            )

            else -> Challenge(
                state = state,
                onDigit = onDigit,
                onBackspace = onBackspace,
                onSubmit = onSubmit,
                onStartEscape = onStartEscape,
            )
        }
    }
}

@Composable
private fun Clock() {
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1_000L)
        }
    }
    Text(
        text = now.format(formatter),
        style = MaterialTheme.typography.displaySmall,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Challenge(
    state: ChallengeState,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Boolean,
    onStartEscape: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    if (state.questionCount > 1) {
        Text(
            text = stringResource(
                R.string.challenge_progress,
                state.questionNumber,
                state.questionCount,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }

    Text(
        text = state.questionText,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))

    // The entry field. Not a TextField: there is no soft keyboard to fight with, no IME to
    // mis-handle, and nothing here is multiple choice — the answer is typed digit by digit.
    val entryColor =
        if (state.showError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val hint = stringResource(R.string.challenge_answer_hint)
    Text(
        text = state.entry.ifEmpty { "–" },
        style = MaterialTheme.typography.displayMedium,
        color = entryColor,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .widthIn(min = 120.dp)
            .semantics { contentDescription = state.entry.ifEmpty { hint } },
    )
    Text(
        text = if (state.showError) stringResource(R.string.challenge_wrong) else hint,
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.showError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))

    Keypad(
        onDigit = onDigit,
        onBackspace = onBackspace,
    )
    Spacer(Modifier.height(KEY_GAP))

    Button(
        onClick = {
            // Graded here and nowhere else. A wrong answer buzzes and the state hands back a brand
            // new problem; the alarm keeps ringing throughout, because this screen cannot stop it.
            //
            // Honest limit (review M2-1.9), left as it is on purpose. `LocalHapticFeedback`
            // resolves to `View.performHapticFeedback`, which the platform requests at
            // `VibrationAttributes.USAGE_TOUCH`, and the vibrator service ignores a new vibration
            // whose usage importance is below that of one already playing — so on an alarm with
            // `vibrate = true` this buzz is dropped while the ringer's `USAGE_ALARM` waveform runs.
            // There is no way to raise the usage from here: neither Compose's haptics nor
            // `View.performHapticFeedback` exposes `VibrationAttributes`, and asking for a higher
            // usage means the UI holding a `Vibrator` — which is the one rule that outranks every
            // review finding. On `vibrate = false` alarms, which have no ongoing vibration, it is
            // delivered. The red error state below carries the same information either way.
            if (!onSubmit()) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        },
        enabled = state.canSubmit,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Text(
            text = stringResource(R.string.challenge_submit),
            style = MaterialTheme.typography.titleLarge,
        )
    }

    // The escape hatch. Composed *only* once five answers in a row have been wrong, so before then
    // there is nothing on screen to tap — and `ChallengeState.startEscape` would refuse anyway.
    // There is no skip, no "easier question", no difficulty reduction and no other way out.
    if (state.escapeUnlocked) {
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = onStartEscape,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(stringResource(R.string.escape_reveal))
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun Keypad(onDigit: (Int) -> Unit, onBackspace: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KEY_GAP),
    ) {
        // Big targets, phone-dialer order: fingers are clumsy when asleep (BRAINSTORM §4.3).
        listOf(1..3, 4..6, 7..9).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
            ) {
                row.forEach { value ->
                    Key(
                        text = value.toString(),
                        modifier = Modifier.weight(1f),
                        onClick = { onDigit(value) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
        ) {
            val backspaceLabel = stringResource(R.string.challenge_backspace)
            Key(
                text = "⌫",
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = backspaceLabel },
                onClick = onBackspace,
            )
            Key(text = "0", modifier = Modifier.weight(2f), onClick = { onDigit(0) })
        }
    }
}

@Composable
private fun Key(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(KEY_HEIGHT),
        contentPadding = ButtonDefaults.TextButtonContentPadding,
    ) {
        Text(text = text, style = MaterialTheme.typography.headlineSmall)
    }
}

/**
 * The 60-second countdown.
 *
 * It only *displays* and only *reports*: the authority on whether the minute entitles anyone to a
 * dismiss is `AlarmRingActivity.completeEscape`, which re-checks the five-failure gate and the
 * deadline against the clock. Keyed on the deadline, so a cancel-and-restart gets a new timer
 * rather than inheriting the old one.
 *
 * The seconds come from [ChallengeState.escapeSecondsLeft] — the copy the tests drive. This
 * composable used to carry its own `secondsUntil`, so the number the user watched and the number
 * four tests pinned were two different implementations of the same ceiling (review M2-1.5).
 *
 * The alarm is at full volume for the whole minute. Nothing in this composable can change that.
 */
@Composable
private fun EscapeCountdown(state: ChallengeState, onElapsed: () -> Unit, onCancel: () -> Unit) {
    val deadlineElapsedMs = state.escapeDeadlineElapsedMs
    var secondsLeft by remember(deadlineElapsedMs) {
        mutableStateOf(state.escapeSecondsLeft(SystemClock.elapsedRealtime()))
    }
    LaunchedEffect(deadlineElapsedMs) {
        while (true) {
            secondsLeft = state.escapeSecondsLeft(SystemClock.elapsedRealtime())
            if (secondsLeft <= 0) {
                onElapsed()
                return@LaunchedEffect
            }
            delay(200L)
        }
    }

    Text(
        text = stringResource(R.string.escape_countdown, secondsLeft),
        style = MaterialTheme.typography.displayLarge,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.escape_explain),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.escape_stay),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    TextButton(onClick = onCancel) {
        Text(stringResource(R.string.escape_cancel))
    }
}
