package com.mathalarm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mathalarm.R
import com.mathalarm.alarm.AlarmService
import com.mathalarm.domain.Diagnostics
import com.mathalarm.system.SettingsIntents
import com.mathalarm.system.SystemState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Self-diagnostic screen (BUILD_SPECS §4, BRAINSTORM §6 "Mitigations"): green/red for every
 * permission and battery setting, so a user with a broken setup can see **why**.
 *
 * **"Reflects real permission state, not cached guesses."** Every value comes from
 * [SystemState.query], which holds no cache of any kind, and the host re-runs it on every
 * `onResume` plus on [onRefresh]. Returning from a Settings page therefore re-reads the real state
 * rather than repainting what was true when the screen opened.
 *
 * This screen is where several deferred items finally become visible to a user:
 *  - **Module 1 backlog 1.11** — the exact-alarm denial path only wrote a `Log.e`, so an API 31-32
 *    device with "Alarms & reminders" off would reschedule nothing after every boot and say nothing.
 *    That is the first row, and it is a red FAIL.
 *  - **Module 1 backlog 1.11 / review M2-1.5** — notifications and full-screen intent denied.
 *    Deliberately amber, not red: Module 1 round 2's pushback #1 established that the alarm still
 *    rings audibly without either, so calling them failures would be a lie and gating scheduling on
 *    them would be worse than the problem.
 *  - **Module 1 backlog 1.12** — a Do-Not-Disturb policy rejecting the ringer's volume write. Read
 *    through `AlarmStreamStatus`, in the package that owns the audio, so `ui/` holds no
 *    `AudioManager`.
 *  - **Module 1 backlog 3.2** — "last fired", the one row that makes a silent failure legible.
 *  - **Review M2-2.3** — the second route back to a live alarm, repeated here because a user who
 *    cannot find the ringing screen is exactly the user who ends up on a diagnostics page.
 */
@Composable
fun DiagnosticsScreen(
    systemState: SystemState?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenRinging: (Long, String) -> Unit,
) {
    val context = LocalContext.current
    ScreenScaffold(
        title = stringResource(R.string.diag_title),
        onBack = onBack,
        trailing = {
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.diag_refresh)) }
        },
    ) {
        BodyText(stringResource(R.string.diag_intro))
        VSpace(12)

        val ringing = AlarmService.ringingAlarm()
        if (ringing != null) {
            SectionCard(modifier = Modifier.padding(bottom = 12.dp)) {
                BodyText(stringResource(R.string.diag_ringing_now))
                VSpace(8)
                Button(onClick = { onOpenRinging(ringing.first, ringing.second) }) {
                    Text(stringResource(R.string.diag_ringing_open))
                }
            }
        }

        if (systemState == null) {
            BodyText(stringResource(R.string.challenge_preparing))
            return@ScreenScaffold
        }

        val rows = systemState.rows()
        val summary = when (Diagnostics.worst(rows)) {
            Diagnostics.Status.OK -> R.string.diag_all_ok
            Diagnostics.Status.WARN -> R.string.diag_has_warn
            Diagnostics.Status.FAIL -> R.string.diag_has_fail
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Diagnostics.worst(rows))
                Spacer(Modifier.size(10.dp))
                Text(
                    text = stringResource(summary),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        VSpace(8)

        rows.forEach { row ->
            DiagnosticRow(
                row = row,
                systemState = systemState,
                onFix = {
                    when (row.check) {
                        Diagnostics.Check.EXACT_ALARM -> SettingsIntents.launchFirstResolvable(
                            context,
                            SettingsIntents.exactAlarmSettings(context),
                        )
                        Diagnostics.Check.BATTERY -> onOpenBattery()
                        Diagnostics.Check.NOTIFICATIONS -> SettingsIntents.launch(
                            context,
                            SettingsIntents.notificationSettings(context),
                        )
                        Diagnostics.Check.FULL_SCREEN_INTENT ->
                            SettingsIntents.launchFirstResolvable(
                                context,
                                SettingsIntents.fullScreenIntentSettings(context),
                            )
                        // No Settings page can raise the alarm stream, add an alarm or make one
                        // fire, so these three rows are informational and offer no button.
                        Diagnostics.Check.ALARM_VOLUME,
                        Diagnostics.Check.ENABLED_ALARMS,
                        Diagnostics.Check.LAST_FIRED,
                        -> Unit
                    }
                    onRefresh()
                },
            )
        }
    }
}

@Composable
private fun DiagnosticRow(
    row: Diagnostics.Row,
    systemState: SystemState,
    onFix: () -> Unit,
) {
    val fixable = row.check in FIXABLE && row.status != Diagnostics.Status.OK
    SectionCard(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(row.status)
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(titleFor(row.check)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val detail = detailFor(row, systemState)
                if (detail != null) {
                    VSpace(2)
                    BodyText(detail)
                }
            }
        }
        if (fixable) {
            VSpace(8)
            OutlinedButton(onClick = onFix, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_open_settings))
            }
        }
    }
}

private val FIXABLE = setOf(
    Diagnostics.Check.EXACT_ALARM,
    Diagnostics.Check.BATTERY,
    Diagnostics.Check.NOTIFICATIONS,
    Diagnostics.Check.FULL_SCREEN_INTENT,
)

private fun titleFor(check: Diagnostics.Check): Int = when (check) {
    Diagnostics.Check.EXACT_ALARM -> R.string.diag_exact_alarm
    Diagnostics.Check.BATTERY -> R.string.diag_battery
    Diagnostics.Check.NOTIFICATIONS -> R.string.diag_notifications
    Diagnostics.Check.FULL_SCREEN_INTENT -> R.string.diag_fsi
    Diagnostics.Check.ALARM_VOLUME -> R.string.diag_volume
    Diagnostics.Check.ENABLED_ALARMS -> R.string.diag_alarms
    Diagnostics.Check.LAST_FIRED -> R.string.diag_last_fired
}

/** The "why this matters" line, shown only when the row is not green. */
@Composable
private fun detailFor(row: Diagnostics.Row, systemState: SystemState): String? {
    if (row.check == Diagnostics.Check.LAST_FIRED) {
        val at = systemState.lastFiredAtMillis
            ?: return stringResource(R.string.diag_last_fired_never)
        return formatInstant(at)
    }
    if (row.status == Diagnostics.Status.OK) return null
    val res = when (row.check) {
        Diagnostics.Check.EXACT_ALARM -> R.string.diag_exact_alarm_bad
        Diagnostics.Check.BATTERY -> R.string.diag_battery_bad
        Diagnostics.Check.NOTIFICATIONS -> R.string.diag_notifications_bad
        Diagnostics.Check.FULL_SCREEN_INTENT -> R.string.diag_fsi_bad
        Diagnostics.Check.ALARM_VOLUME -> R.string.diag_volume_bad
        Diagnostics.Check.ENABLED_ALARMS -> R.string.diag_alarms_bad
        Diagnostics.Check.LAST_FIRED -> R.string.diag_last_fired_never
    }
    return stringResource(res)
}

private val LAST_FIRED_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun formatInstant(millis: Long): String = runCatching {
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(LAST_FIRED_FORMAT)
}.getOrDefault(millis.toString())
