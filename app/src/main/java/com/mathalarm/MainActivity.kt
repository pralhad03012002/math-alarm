package com.mathalarm

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.mathalarm.alarm.AlarmService
import com.mathalarm.alarm.AlarmWork
import com.mathalarm.data.Alarm
import com.mathalarm.data.AlarmRepository
import com.mathalarm.data.AppSettings
import com.mathalarm.system.SystemState
import com.mathalarm.ui.AlarmEditScreen
import com.mathalarm.ui.AlarmListScreen
import com.mathalarm.ui.BatteryScreen
import com.mathalarm.ui.DiagnosticsScreen
import com.mathalarm.ui.OnboardingScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's one normal-mode activity: alarm list, alarm editor, permissions onboarding, the battery
 * screen and the self-diagnostic screen (BUILD_SPECS §4).
 *
 * **The Module 1 debug trigger is gone.** It is replaced by the real list plus the test-alarm button
 * on it, which is §4's requirement.
 *
 * Navigation is a `sealed interface` in one `mutableStateOf` rather than a navigation library:
 * five screens with no deep links and no nested back stacks do not justify a dependency, and
 * BUILD_SPECS' build config asks for any new dependency to be justified.
 *
 * Two lifecycle behaviours are load-bearing:
 *  - **[onResume] re-queries [SystemState] every time.** §4 requires the diagnostic screen to reflect
 *    real state and not a cached guess, and the only way these states change is the user going to
 *    Settings and coming back — i.e. exactly at `onResume`. Nothing anywhere caches a permission.
 *  - **[onCreate] reconciles every alarm, but only on a genuine first create.** That is §4's
 *    defensive rescheduling (BRAINSTORM §6), on top of Module 1's system-event receivers. It is
 *    skipped when `savedInstanceState != null` because a reconcile *cancels and re-books* every
 *    registration, and a rotation must not be able to cancel the five-second test alarm the user just
 *    armed.
 */
class MainActivity : ComponentActivity() {

    private sealed interface Screen {
        data object List : Screen

        /** Carries the loaded row, so the editor never has to render against a null while loading. */
        data class Edit(val alarm: Alarm?) : Screen
        data object Onboarding : Screen
        data object Battery : Screen
        data object Diagnostics : Screen
    }

    private lateinit var repository: AlarmRepository

    private var screen by mutableStateOf<Screen>(Screen.List)
    private var alarms by mutableStateOf<List<Alarm>>(emptyList())
    private var systemState by mutableStateOf<SystemState?>(null)
    private var testAlarmStatus by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AlarmRepository(this)

        if (savedInstanceState == null) {
            // Defensive rescheduling on app open (§4). Process-lifetime scope, not lifecycleScope:
            // this must finish even if the user leaves immediately.
            AlarmWork.scope.launch { repository.reconcileAll() }
            if (!AppSettings.onboardingSeen(this)) screen = Screen.Onboarding
        }

        refreshAlarms()

        setContent {
            MaterialTheme {
                BackHandler(enabled = screen != Screen.List) { screen = Screen.List }
                Host()
            }
        }
    }

    /**
     * Re-read on every resume. This is the "not cached guesses" guarantee: returning from a Settings
     * page, from the OEM battery screen or from a permission dialog lands here.
     */
    override fun onResume() {
        super.onResume()
        refreshAlarms()
    }

    @Composable
    private fun Host() {
        when (val current = screen) {
            Screen.List -> AlarmListScreen(
                alarms = alarms,
                systemState = systemState,
                testAlarmStatus = testAlarmStatus,
                onAdd = { screen = Screen.Edit(null) },
                onEdit = { alarm -> screen = Screen.Edit(alarm) },
                onToggle = ::toggleAlarm,
                onTestAlarm = ::fireTestAlarm,
                onOpenDiagnostics = { screen = Screen.Diagnostics },
                onOpenRinging = ::openRingingAlarm,
            )

            is Screen.Edit -> key(current.alarm?.id) {
                AlarmEditScreen(
                    existing = current.alarm,
                    exactAlarmAllowed = systemState?.exactAlarmAllowed != false,
                    onBack = { screen = Screen.List },
                    onSave = ::saveAlarm,
                    onDelete = ::deleteAlarm,
                    onOpenDiagnostics = { screen = Screen.Diagnostics },
                )
            }

            Screen.Onboarding -> OnboardingScreen(
                systemState = systemState,
                onFinish = {
                    AppSettings.markOnboardingSeen(this)
                    screen = Screen.List
                },
                onOpenBattery = { screen = Screen.Battery },
                onStateMayHaveChanged = ::refreshAlarms,
            )

            Screen.Battery -> BatteryScreen(
                systemState = systemState,
                onBack = { screen = Screen.List },
                onStateMayHaveChanged = ::refreshAlarms,
            )

            Screen.Diagnostics -> DiagnosticsScreen(
                systemState = systemState,
                onBack = { screen = Screen.List },
                onRefresh = ::refreshAlarms,
                onOpenBattery = { screen = Screen.Battery },
                onOpenRinging = ::openRingingAlarm,
            )
        }
    }

    // --------------------------------------------------------------- actions

    /** Reads the alarm rows, then re-queries the live system state off the fresh count. */
    private fun refreshAlarms() {
        lifecycleScope.launch {
            val rows = runCatching { repository.userAlarms() }
                .onFailure { Log.e(TAG, "could not read alarms", it) }
                .getOrDefault(alarms)
            alarms = rows
            // Off the main thread: the last-fired record is a SharedPreferences read, which is
            // disk IO on first access. Queried fresh every time — nothing is cached (§4).
            systemState = runCatching {
                withContext(Dispatchers.IO) {
                    SystemState.query(this@MainActivity, rows.count { it.enabled })
                }
            }.onFailure { Log.e(TAG, "could not read the system state", it) }.getOrNull()
        }
    }

    /**
     * Saving reschedules (§4). `AlarmRepository.save` cancels the old registration and only then
     * creates the new one; nothing here touches `AlarmScheduler`.
     */
    private fun saveAlarm(alarm: Alarm) {
        lifecycleScope.launch {
            runCatching { repository.save(alarm) }
                .onFailure { Log.e(TAG, "could not save alarm ${alarm.id}", it) }
            screen = Screen.List
            refreshAlarms()
        }
    }

    /** Deleting cancels the pending registration before the row is removed (§4). */
    private fun deleteAlarm(alarm: Alarm) {
        lifecycleScope.launch {
            runCatching { repository.delete(alarm) }
                .onFailure { Log.e(TAG, "could not delete alarm ${alarm.id}", it) }
            screen = Screen.List
            refreshAlarms()
        }
    }

    /** The list toggle. Schedules or cancels immediately, in the same call (§4). */
    private fun toggleAlarm(alarm: Alarm, enabled: Boolean) {
        lifecycleScope.launch {
            runCatching { repository.setEnabled(alarm, enabled) }
                .onFailure { Log.e(TAG, "could not toggle alarm ${alarm.id}", it) }
            refreshAlarms()
        }
    }

    /**
     * §4's test alarm: the real chain, five seconds out, while the user is awake.
     *
     * **Refused while a real alarm is ringing** (review **M3-1.2**, BUILD_SPECS §4b item 3). The
     * list already disables the button, but the button is driven by a one-second poll, so this is
     * the check that closes the gap between two ticks — and the one that answers a tap landing at
     * the same moment an alarm fires. `AlarmService` refuses the start as well; all three layers say
     * the same thing, and this is the only one that can tell the user why.
     */
    private fun fireTestAlarm() {
        if (AlarmService.ringingAlarm() != null) {
            testAlarmStatus = getString(R.string.list_test_alarm_blocked)
            return
        }
        val label = getString(R.string.list_test_alarm_label)
        lifecycleScope.launch {
            val trigger = runCatching { repository.scheduleTestAlarm(label) }
                .onFailure { Log.e(TAG, "could not schedule the test alarm", it) }
                .getOrNull()
            testAlarmStatus = getString(
                if (trigger != null) {
                    R.string.list_test_alarm_armed
                } else {
                    R.string.list_test_alarm_failed
                },
            )
            refreshAlarms()
        }
    }

    /**
     * Review **M2-2.3**: the second route back to a ringing alarm's keypad. Sends
     * [AlarmService.openRingingIntent], which is the identical intent the notification's own
     * full-screen `PendingIntent` carries, so the `singleTask` ring activity is re-entered with the
     * same alarm id and the running session — solved questions, failure count, escape countdown —
     * is left alone.
     */
    private fun openRingingAlarm(alarmId: Long, label: String) {
        runCatching { startActivity(AlarmService.openRingingIntent(this, alarmId, label)) }
            .onFailure { Log.e(TAG, "could not open the ringing alarm $alarmId", it) }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
