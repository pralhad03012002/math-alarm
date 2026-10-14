package com.mathalarm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mathalarm.R
import com.mathalarm.alarm.AlarmService
import com.mathalarm.data.Alarm
import com.mathalarm.domain.Diagnostics
import com.mathalarm.system.SystemState
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * The app's home screen: every alarm with its time, label, enabled toggle and difficulty badge,
 * create / edit / delete, and a real empty state (BUILD_SPECS §4).
 *
 * The enabled toggle schedules or cancels **immediately** — [onToggle] goes straight to
 * `AlarmRepository.setEnabled`, which cancels the old registration and then schedules or not, in one
 * suspend call. There is no "apply" step and no deferred write.
 *
 * Two things on this screen exist because of the review ledger rather than because of §4's bullet
 * list:
 *  - **"An alarm is ringing — open it"** (review **M2-2.3**). The notification re-post is still the
 *    only route back to the keypad and it is best-effort by construction; this is the second route,
 *    and it sends the byte-identical intent that the notification's own full-screen `PendingIntent`
 *    carries, so it lands in the same `singleTask` activity with the same alarm id and cannot wipe a
 *    session that already has questions solved.
 *  - **The setup-check banner**, shown only on a [Diagnostics.Status.FAIL]. Module 1 backlog **1.11**
 *    and review **M2-1.5** both end at "a `Log.e` is invisible to the user"; this and the diagnostics
 *    screen are where that becomes visible.
 */
@Composable
fun AlarmListScreen(
    alarms: List<Alarm>,
    systemState: SystemState?,
    testAlarmStatus: String?,
    onAdd: () -> Unit,
    onEdit: (Alarm) -> Unit,
    onToggle: (Alarm, Boolean) -> Unit,
    onTestAlarm: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenRinging: (Long, String) -> Unit,
) {
    ScreenScaffold(
        title = stringResource(R.string.list_title),
        onBack = null,
        trailing = {
            TextButton(onClick = onOpenDiagnostics) {
                Text(stringResource(R.string.list_setup_check))
            }
        },
    ) {
        // Polled once for the whole screen. Two things need it: the "an alarm is ringing — open it"
        // banner (review M2-2.3) and the test-alarm button, which must be **blocked** while an alarm
        // is ringing (review M3-1.2, BUILD_SPECS §4b item 3) — it was one tap from here to a second
        // alarm reusing the live ringer.
        var ringing by remember { mutableStateOf(AlarmService.ringingAlarm()) }
        LaunchedEffect(Unit) {
            while (true) {
                ringing = AlarmService.ringingAlarm()
                delay(1_000)
            }
        }

        RingingBanner(ringing, onOpenRinging)

        val rows = systemState?.rows()
        if (rows != null && Diagnostics.needsAttention(rows)) {
            SectionCard(modifier = Modifier.padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(Diagnostics.Status.FAIL)
                    Spacer(Modifier.size(10.dp))
                    BodyText(
                        text = stringResource(R.string.list_setup_warning),
                        modifier = Modifier.weight(1f),
                    )
                }
                VSpace(8)
                Button(onClick = onOpenDiagnostics) {
                    Text(stringResource(R.string.list_setup_fix))
                }
            }
        }

        VSpace(12)

        if (alarms.isEmpty()) {
            EmptyState(onAdd)
        } else {
            alarms.forEachIndexed { index, alarm ->
                if (index > 0) HorizontalDivider()
                AlarmRow(
                    alarm = alarm,
                    onEdit = { onEdit(alarm) },
                    onToggle = { enabled -> onToggle(alarm, enabled) },
                )
            }
            VSpace(16)
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.list_add))
            }
        }

        VSpace(24)
        TestAlarmCard(
            status = testAlarmStatus,
            alarmRinging = ringing != null,
            onTestAlarm = onTestAlarm,
        )
    }
}

/**
 * Review **M2-2.3**. Polled rather than pushed: `AlarmService.ringingAlarm()` is a volatile read in
 * the same process, and a one-second poll that only runs while this screen is composed is cheaper and
 * far less failure-prone than a binder, a receiver or a bound service — none of which may touch the
 * alarm's lifecycle.
 */
@Composable
private fun RingingBanner(ringing: Pair<Long, String>?, onOpenRinging: (Long, String) -> Unit) {
    val live = ringing ?: return
    SectionCard(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = stringResource(R.string.list_ringing_now),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        VSpace(4)
        BodyText(live.second.ifBlank { stringResource(R.string.alarm_default_label) })
        VSpace(8)
        Button(onClick = { onOpenRinging(live.first, live.second) }) {
            Text(stringResource(R.string.list_ringing_open))
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    SectionCard {
        Text(
            text = stringResource(R.string.list_empty_title),
            style = MaterialTheme.typography.titleLarge,
        )
        VSpace(8)
        BodyText(stringResource(R.string.list_empty_body))
        VSpace(16)
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.list_empty_action))
        }
    }
}

@Composable
private fun AlarmRow(
    alarm: Alarm,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val toggleDescription = stringResource(R.string.list_enabled_toggle)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatTime(alarm.hour, alarm.minute),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = alarm.label.ifBlank { stringResource(R.string.alarm_default_label) },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = repeatSummary(alarm.repeatDaysMask),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.size(8.dp))
                DifficultyBadge(alarm)
            }
        }
        Switch(
            checked = alarm.enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics {
                contentDescription = toggleDescription
            },
        )
    }
}

@Composable
private fun DifficultyBadge(alarm: Alarm) {
    val tier = stringResource(difficultyLabel(alarm.difficulty))
    val count = alarm.questionCount.coerceAtLeast(1)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = if (count > 1) "$tier × $count" else tier,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * The test alarm, and the one thing that may not be done with it: **armed while another alarm is
 * ringing** (review **M3-1.2**, BUILD_SPECS §4b item 3). Five seconds after that tap the service
 * would have been handed a second alarm, and before the overlap policy existed it reused the live
 * ringer and overwrote the ringing id. `AlarmService` now refuses such a start outright, so this
 * disabled button is not the guard — it is the *explanation*, shown where the tap would have been,
 * because a button that silently does nothing is worse than one that says why.
 */
@Composable
private fun TestAlarmCard(status: String?, alarmRinging: Boolean, onTestAlarm: () -> Unit) {
    SectionCard {
        Text(
            text = stringResource(R.string.list_test_alarm),
            style = MaterialTheme.typography.titleMedium,
        )
        VSpace(4)
        BodyText(stringResource(R.string.list_test_alarm_hint))
        VSpace(12)
        OutlinedButton(
            onClick = onTestAlarm,
            enabled = !alarmRinging,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.list_test_alarm))
        }
        if (alarmRinging) {
            VSpace(8)
            BodyText(stringResource(R.string.list_test_alarm_blocked))
        } else if (status != null) {
            VSpace(8)
            BodyText(status)
        }
    }
}

// ------------------------------------------------------------------ formatting

/** 24-hour, zero-padded. Unambiguous at 6am, which is the only time it matters. */
fun formatTime(hour: Int, minute: Int): String =
    "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59))

/**
 * Human summary of a repeat mask, read through [Alarm.maskToDays] so it uses the same
 * bit-0-is-Monday convention as the scheduler. That convention is pinned in both directions by
 * `AlarmDayMaskTest`; nothing here re-derives it.
 */
@Composable
fun repeatSummary(mask: Int): String {
    val days = Alarm.maskToDays(mask)
    return when {
        days.isEmpty() -> stringResource(R.string.list_one_shot)
        days.size == 7 -> stringResource(R.string.list_everyday)
        else -> days.joinToString(" ") { shortDayName(it) }
    }
}

fun shortDayName(day: DayOfWeek): String =
    day.getDisplayName(TextStyle.SHORT, Locale.getDefault())

fun difficultyLabel(difficulty: com.mathalarm.data.Difficulty): Int = when (difficulty) {
    com.mathalarm.data.Difficulty.EASY -> R.string.edit_difficulty_easy
    com.mathalarm.data.Difficulty.MEDIUM -> R.string.edit_difficulty_medium
    com.mathalarm.data.Difficulty.HARD -> R.string.edit_difficulty_hard
}
