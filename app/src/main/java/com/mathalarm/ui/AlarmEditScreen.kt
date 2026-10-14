package com.mathalarm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mathalarm.R
import com.mathalarm.data.Alarm
import com.mathalarm.data.Difficulty
import java.time.DayOfWeek

/**
 * Create or edit one alarm (BUILD_SPECS §4): time picker, label, **repeat-day selector wired for
 * real**, difficulty tier, question count, vibrate toggle, delete. Saving reschedules.
 *
 * The repeat-day selector goes through [Alarm.daysToMask] / [Alarm.maskToDays] and nothing else.
 * Module 1 left `repeatDaysMask` at zero and Module 1 round 2 added `AlarmDayMaskTest`, which pins
 * the bit-0-is-Monday convention in both directions over all 128 subsets — so this screen uses those
 * helpers rather than shifting bits itself. That is the whole reason those tests exist.
 *
 * Saving and deleting both go through `AlarmRepository`, which is where §4's "cancel the old
 * registration before creating the new one" and "deleting cancels the pending registration" actually
 * live. This screen never touches `AlarmScheduler`.
 *
 * `questionCount` is clamped to [MIN_QUESTIONS]..[MAX_QUESTIONS] here as well as being coerced
 * upward by `ChallengeState` — a zero or negative count would be a session that is already solved,
 * i.e. a free dismissal, which is Module 2's worst defect class.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    existing: Alarm?,
    exactAlarmAllowed: Boolean,
    onBack: () -> Unit,
    onSave: (Alarm) -> Unit,
    onDelete: (Alarm) -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val seed = existing ?: DEFAULT_NEW_ALARM
    val timeState = rememberTimePickerState(
        initialHour = seed.hour,
        initialMinute = seed.minute,
        is24Hour = true,
    )
    var label by remember { mutableStateOf(seed.label) }
    var days by remember { mutableStateOf(Alarm.maskToDays(seed.repeatDaysMask)) }
    var difficulty by remember { mutableStateOf(seed.difficulty) }
    var questionCount by remember {
        mutableStateOf(seed.questionCount.coerceIn(MIN_QUESTIONS, MAX_QUESTIONS))
    }
    var vibrate by remember { mutableStateOf(seed.vibrate) }
    var confirmDelete by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = stringResource(
            if (existing == null) R.string.edit_title_new else R.string.edit_title_existing,
        ),
        onBack = onBack,
    ) {
        if (!exactAlarmAllowed) {
            SectionCard(modifier = Modifier.padding(bottom = 12.dp)) {
                BodyText(stringResource(R.string.edit_no_exact_alarm))
                VSpace(8)
                Button(onClick = onOpenDiagnostics) {
                    Text(stringResource(R.string.list_setup_check))
                }
            }
        }

        SectionTitle(stringResource(R.string.edit_time))
        SectionCard {
            TimePicker(state = timeState)
        }

        SectionTitle(stringResource(R.string.edit_label))
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(MAX_LABEL_LENGTH) },
            placeholder = { Text(stringResource(R.string.edit_label_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle(stringResource(R.string.edit_repeat))
        DaySelector(selected = days, onToggle = { day ->
            days = if (day in days) days - day else days + day
        })
        if (days.isEmpty()) {
            VSpace(6)
            BodyText(stringResource(R.string.edit_repeat_none))
        }

        SectionTitle(stringResource(R.string.edit_difficulty))
        DifficultyPicker(selected = difficulty, onSelect = { difficulty = it })

        SectionTitle(stringResource(R.string.edit_question_count))
        Stepper(
            value = questionCount,
            min = MIN_QUESTIONS,
            max = MAX_QUESTIONS,
            onChange = { questionCount = it },
        )

        SectionTitle(stringResource(R.string.edit_vibrate))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BodyText(
                text = stringResource(R.string.edit_vibrate_hint),
                modifier = Modifier.weight(1f),
            )
            Switch(checked = vibrate, onCheckedChange = { vibrate = it })
        }

        VSpace(24)
        Button(
            onClick = {
                onSave(
                    seed.copy(
                        id = existing?.id ?: 0L,
                        hour = timeState.hour,
                        minute = timeState.minute,
                        // Saving arms the alarm, including one that had auto-disabled itself after
                        // a one-shot ring. Editing an alarm is an unambiguous statement of intent to
                        // use it, and the alternative fails in the silent direction: the user sets a
                        // time, taps Save, and nothing rings.
                        enabled = true,
                        label = label.trim(),
                        repeatDaysMask = Alarm.daysToMask(days),
                        difficulty = difficulty,
                        questionCount = questionCount.coerceIn(MIN_QUESTIONS, MAX_QUESTIONS),
                        vibrate = vibrate,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_save))
        }

        if (existing != null) {
            VSpace(8)
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_delete))
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.edit_delete_confirm)) },
            text = { Text(stringResource(R.string.edit_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(existing)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Monday-first, matching the mask's bit order so the UI and the scheduler cannot disagree. */
@Composable
private fun DaySelector(selected: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DayOfWeek.entries.forEach { day ->
            val on = day in selected
            if (on) {
                Button(
                    onClick = { onToggle(day) },
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                ) { DayText(day) }
            } else {
                OutlinedButton(
                    onClick = { onToggle(day) },
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                ) { DayText(day) }
            }
        }
    }
}

@Composable
private fun DayText(day: DayOfWeek) = Text(
    text = shortDayName(day).take(2),
    style = MaterialTheme.typography.labelMedium,
)

@Composable
private fun DifficultyPicker(selected: Difficulty, onSelect: (Difficulty) -> Unit) {
    Column {
        Difficulty.entries.forEach { tier ->
            val on = tier == selected
            val label = stringResource(difficultyLabel(tier))
            val hint = stringResource(
                when (tier) {
                    Difficulty.EASY -> R.string.edit_difficulty_easy_hint
                    Difficulty.MEDIUM -> R.string.edit_difficulty_medium_hint
                    Difficulty.HARD -> R.string.edit_difficulty_hard_hint
                },
            )
            val content: @Composable () -> Unit = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = label, fontWeight = FontWeight.SemiBold)
                    Text(text = hint, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (on) {
                Button(
                    onClick = { onSelect(tier) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) { content() }
            } else {
                OutlinedButton(
                    onClick = { onSelect(tier) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) { content() }
            }
        }
    }
}

@Composable
private fun Stepper(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onChange((value - 1).coerceAtLeast(min)) },
            enabled = value > min,
        ) { Text(stringResource(R.string.edit_decrease)) }
        Spacer(Modifier.size(16.dp))
        Text(text = value.toString(), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(16.dp))
        OutlinedButton(
            onClick = { onChange((value + 1).coerceAtMost(max)) },
            enabled = value < max,
        ) { Text(stringResource(R.string.edit_increase)) }
    }
}

/** BUILD_SPECS §0: the default is MEDIUM × 1. */
private val DEFAULT_NEW_ALARM = Alarm(
    id = 0L,
    hour = 7,
    minute = 0,
    enabled = true,
    label = "",
    repeatDaysMask = 0,
    difficulty = Difficulty.MEDIUM,
    questionCount = 1,
    vibrate = true,
)

private const val MIN_QUESTIONS = 1
private const val MAX_QUESTIONS = 10
private const val MAX_LABEL_LENGTH = 40
