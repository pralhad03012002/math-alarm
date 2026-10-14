package com.mathalarm.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import com.mathalarm.domain.Diagnostics
import com.mathalarm.system.SettingsIntents
import com.mathalarm.system.SystemState

/**
 * Permissions onboarding (BUILD_SPECS §4, BRAINSTORM §3.1).
 *
 * **"Never a bare system dialog with no context."** Every one of the four steps is a card that says
 * what the permission is for and what breaks without it, and the system dialog or Settings page is
 * only reached by tapping that card's own button. Nothing is requested on screen entry, nothing is
 * requested as a side effect of navigation, and the order is the §4 order:
 * notifications → exact alarm → full-screen intent → battery optimization.
 *
 * **Scheduling is never gated on any of this.** Module 1 round 2's pushback #1 withdrew the original
 * "hard-gate on `canUseFullScreenIntent()`/notifications" guidance and replaced it: `USAGE_ALARM` on
 * `STREAM_ALARM` rings audibly through Do Not Disturb with no full-screen intent and no notification,
 * so refusing to schedule would turn a degraded-but-audible alarm into *no* alarm — on exactly the
 * devices whose owners are least likely to notice. So every step here is skippable, this whole screen
 * is skippable, and the honest consequence is stated on the card instead ("the alarm will still ring,
 * but…"). The replacement guidance is what is implemented: surface the state, warn, and schedule
 * regardless.
 *
 * Each step's "already set" state is read from [SystemState], which is re-queried on every
 * `onResume` — so coming back from Settings updates the step without the user doing anything.
 */
@Composable
fun OnboardingScreen(
    systemState: SystemState?,
    onFinish: () -> Unit,
    onOpenBattery: () -> Unit,
    onStateMayHaveChanged: () -> Unit,
) {
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onStateMayHaveChanged() }

    ScreenScaffold(
        title = stringResource(R.string.onboarding_title),
        onBack = null,
        trailing = {
            TextButton(onClick = onFinish) { Text(stringResource(R.string.action_skip)) }
        },
    ) {
        BodyText(stringResource(R.string.onboarding_intro))
        VSpace(8)

        // 1 — notifications.
        OnboardingStep(
            step = 1,
            title = stringResource(R.string.onboarding_notifications_title),
            why = stringResource(R.string.onboarding_notifications_why),
            satisfied = systemState?.notificationsEnabled == true &&
                systemState.notificationPermissionRequestable.not(),
            actionLabel = stringResource(R.string.onboarding_notifications_action),
            onAction = {
                if (systemState?.notificationPermissionRequestable == true) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Already asked once and denied, or below API 33: the runtime dialog will not
                    // show again, so send the user to the one place that can still turn it on.
                    SettingsIntents.launch(context, SettingsIntents.notificationSettings(context))
                }
            },
        )

        // 2 — exact alarms. The one whose absence means no alarm at all (backlog 1.11).
        OnboardingStep(
            step = 2,
            title = stringResource(R.string.onboarding_exact_title),
            why = stringResource(R.string.onboarding_exact_why),
            satisfied = systemState?.exactAlarmAllowed == true,
            actionLabel = stringResource(R.string.onboarding_exact_action),
            onAction = {
                SettingsIntents.launchFirstResolvable(
                    context,
                    SettingsIntents.exactAlarmSettings(context),
                )
            },
        )

        // 3 — full-screen intent. Omitted below API 34, where there is nothing to grant.
        if (systemState == null || systemState.fullScreenIntentApplicable) {
            OnboardingStep(
                step = 3,
                title = stringResource(R.string.onboarding_fsi_title),
                why = stringResource(R.string.onboarding_fsi_why),
                satisfied = systemState?.fullScreenIntentAllowed == true,
                actionLabel = stringResource(R.string.onboarding_fsi_action),
                onAction = {
                    SettingsIntents.launchFirstResolvable(
                        context,
                        SettingsIntents.fullScreenIntentSettings(context),
                    )
                },
            )
        }

        // 4 — battery. BRAINSTORM §6 calls this the most important screen in the app, so it gets a
        // screen of its own rather than a single button.
        OnboardingStep(
            step = 4,
            title = stringResource(R.string.onboarding_battery_title),
            why = stringResource(R.string.onboarding_battery_why),
            satisfied = systemState?.batteryUnrestricted == true,
            actionLabel = stringResource(R.string.onboarding_battery_action),
            onAction = onOpenBattery,
        )

        VSpace(24)
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_finish))
        }
    }
}

@Composable
private fun OnboardingStep(
    step: Int,
    title: String,
    why: String,
    satisfied: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    SectionCard(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (satisfied) Diagnostics.Status.OK else Diagnostics.Status.WARN)
            Spacer(Modifier.size(10.dp))
            Text(
                text = stringResource(R.string.onboarding_step, step, TOTAL_STEPS),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        VSpace(6)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        VSpace(6)
        // The explanation always precedes the request; the button below is the only route to a
        // system dialog.
        BodyText(why)
        VSpace(12)
        if (satisfied) {
            Text(
                text = stringResource(R.string.onboarding_granted),
                style = MaterialTheme.typography.labelLarge,
                color = statusColor(Diagnostics.Status.OK),
            )
        } else {
            // No step is mandatory: a step is "skipped" by not tapping this, and the header's
            // "Skip for now" leaves the whole screen. See the class KDoc on why nothing here may
            // gate scheduling.
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                Text(actionLabel)
            }
        }
    }
}

private const val TOTAL_STEPS = 4
