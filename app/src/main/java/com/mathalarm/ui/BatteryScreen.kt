package com.mathalarm.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mathalarm.R
import com.mathalarm.domain.Diagnostics
import com.mathalarm.domain.OemBattery
import com.mathalarm.system.SettingsIntents
import com.mathalarm.system.SystemState

/**
 * **The most important screen in the app** (BRAINSTORM §6: OEM battery managers are the #1 reason
 * alarm apps fail, and they fail *silently*, overnight, with nothing to see in the morning).
 *
 * Two routes, in this order:
 *  1. the stock battery-optimization exemption, which exists on every Android build;
 *  2. this manufacturer's own autostart / background-protection screen, which usually does not.
 *
 * **Nothing is launched blind.** Every candidate goes through
 * [SettingsIntents.firstResolvable] before it is started, and the start itself is still wrapped —
 * `ActivityNotFoundException`, `SecurityException` and anything else come back as `false` rather than
 * as a crash, because an explicit OEM component can resolve and still refuse to start (not exported,
 * permission-guarded, disabled on this ROM). When nothing resolves or nothing starts, the screen
 * degrades to the written steps below, which is the only thing that works on a ROM we have never
 * seen. See §4: *"OEM deep-links wrapped so a missing intent cannot crash the app."*
 */
@Composable
fun BatteryScreen(
    systemState: SystemState?,
    onBack: () -> Unit,
    onStateMayHaveChanged: () -> Unit,
) {
    val context = LocalContext.current
    val vendor = systemState?.vendor ?: OemBattery.Vendor.STOCK
    val unrestricted = systemState?.batteryUnrestricted == true

    // Flipped the first time a deep link fails to resolve or fails to start, so the written steps
    // stop being a footnote and become the instruction.
    var oemRouteFailed by remember { mutableStateOf(false) }
    val oemAvailable = remember(vendor) {
        SettingsIntents.hasOemBatterySettings(context, vendor)
    }

    ScreenScaffold(title = stringResource(R.string.battery_title), onBack = onBack) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    if (unrestricted) Diagnostics.Status.OK else Diagnostics.Status.FAIL,
                )
                Spacer(Modifier.size(10.dp))
                BodyText(
                    text = stringResource(
                        if (unrestricted) R.string.battery_state_ok else R.string.battery_state_bad,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SectionTitle(stringResource(R.string.battery_stock_action))
        BodyText(stringResource(R.string.battery_stock_why))
        VSpace(10)
        Button(
            onClick = {
                // Explained above, user-initiated, and resolvability-checked. The request prompt is
                // only legitimate because this is an alarm clock (BRAINSTORM §5.4).
                val ok = SettingsIntents.launchFirstResolvable(
                    context,
                    SettingsIntents.stockBatterySettings(context),
                )
                if (!ok) oemRouteFailed = true
                onStateMayHaveChanged()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.battery_stock_action))
        }

        if (vendor != OemBattery.Vendor.STOCK) {
            SectionTitle(stringResource(R.string.battery_oem_action))
            BodyText(stringResource(R.string.battery_device, vendor.name))
            VSpace(10)
            if (oemAvailable && !oemRouteFailed) {
                OutlinedButton(
                    onClick = {
                        val ok = SettingsIntents.launchFirstResolvable(
                            context,
                            SettingsIntents.oemBatterySettings(context, vendor),
                        )
                        if (!ok) oemRouteFailed = true
                        onStateMayHaveChanged()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.battery_oem_action))
                }
            } else {
                BodyText(stringResource(R.string.battery_oem_unavailable))
            }
        }

        SectionTitle(stringResource(R.string.battery_steps_title))
        Text(
            text = stringResource(stepsFor(vendor)),
            style = MaterialTheme.typography.bodyMedium,
        )
        VSpace(16)
        OutlinedButton(
            onClick = {
                SettingsIntents.launch(context, SettingsIntents.appDetails(context))
                onStateMayHaveChanged()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_open_settings))
        }
        VSpace(16)
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_done))
        }
    }
}

/**
 * The written fallback, which is what actually works on a ROM version nobody has tested. Always
 * shown, never only shown on failure: a user who cannot find the deep-linked screen still needs to
 * know what they are looking for.
 */
private fun stepsFor(vendor: OemBattery.Vendor): Int = when (vendor) {
    OemBattery.Vendor.XIAOMI -> R.string.battery_steps_xiaomi
    OemBattery.Vendor.OPPO -> R.string.battery_steps_oppo
    OemBattery.Vendor.VIVO -> R.string.battery_steps_vivo
    OemBattery.Vendor.HUAWEI -> R.string.battery_steps_huawei
    OemBattery.Vendor.SAMSUNG -> R.string.battery_steps_samsung
    OemBattery.Vendor.STOCK -> R.string.battery_steps_stock
}
