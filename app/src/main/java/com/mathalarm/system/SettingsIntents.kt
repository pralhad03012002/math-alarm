package com.mathalarm.system

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.mathalarm.domain.OemBattery

/**
 * Every "take me to the right Settings page" intent in the app, built here and **never launched
 * blind**.
 *
 * BUILD_SPECS §4's acceptance criterion is *"OEM deep-links wrapped so a missing intent cannot
 * crash the app"*, and that is enforced twice over, because one layer is not enough:
 *
 *  1. **Resolvability is checked before launching.** [firstResolvable] runs every candidate through
 *     `PackageManager.resolveActivity` and returns only one the system says it can start, or null.
 *     For the OEM deep links this needs `<queries>` in the manifest (API 30+ hides other packages
 *     from resolution entirely), which is why [OemBattery.allPackages] exists.
 *  2. **The launch itself is still wrapped.** [launch] catches `ActivityNotFoundException`,
 *     `SecurityException` and anything else, because an activity can resolve and still refuse to
 *     start — it may be `exported="false"`, permission-guarded, or disabled on this ROM. It returns
 *     false instead of throwing, and the caller degrades to written instructions.
 *
 * These components vary by OEM *and* by ROM version, and most devices have none of them. "Absent" is
 * the normal case, not the error case.
 */
object SettingsIntents {

    private const val TAG = "SettingsIntents"

    /**
     * The first candidate the system reports it can start, or null if none resolve.
     *
     * `resolveActivity` with `MATCH_DEFAULT_ONLY` is deliberately *not* used: these OEM activities
     * are not default-category handlers, and asking for defaults only would reject every one of them.
     */
    fun firstResolvable(context: Context, candidates: List<Intent>): Intent? {
        val packageManager = context.packageManager
        return candidates.firstOrNull { intent ->
            runCatching { packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)
        }
    }

    /**
     * Starts [intent], returning whether it actually went anywhere. Never throws.
     *
     * `FLAG_ACTIVITY_NEW_TASK` is set because several of these screens live in other apps' tasks and
     * some OEM activities refuse to start into ours.
     */
    fun launch(context: Context, intent: Intent): Boolean =
        runCatching {
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrElse { t ->
            when (t) {
                is ActivityNotFoundException, is SecurityException ->
                    Log.w(TAG, "settings screen refused to start: ${intent.component ?: intent.action}", t)
                else ->
                    Log.e(TAG, "unexpected failure starting ${intent.component ?: intent.action}", t)
            }
            false
        }

    /** Resolve-then-launch in one call. False means "nothing here worked; show instructions". */
    fun launchFirstResolvable(context: Context, candidates: List<Intent>): Boolean {
        val intent = firstResolvable(context, candidates) ?: return false
        return launch(context, intent)
    }

    // ------------------------------------------------------------- permissions

    /** App notification settings. Always present on API 26+. */
    fun notificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /**
     * "Alarms & reminders" for this app (API 31+). On API 33+ `USE_EXACT_ALARM` is granted at install
     * to genuine alarm clocks and cannot be revoked, so this is normally only needed on API 31-32 —
     * but it is offered whenever `canScheduleExactAlarms()` is false, whatever the API level, because
     * that is the state that actually breaks scheduling (Module 1 backlog **1.11**).
     */
    fun exactAlarmSettings(context: Context): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(appUri(context)),
            )
        }
        add(appDetails(context))
    }

    /** The API 34+ full-screen-intent toggle for this app, with the app page as a fallback. */
    fun fullScreenIntentSettings(context: Context): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(appUri(context)),
            )
        }
        add(notificationSettings(context))
        add(appDetails(context))
    }

    // --------------------------------------------------------------- battery

    /**
     * The stock battery-optimization exemption prompt, then the whole-system list, then the app page.
     *
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is the one that shows a single yes/no dialog, and
     * it is only legitimate because this app is an alarm clock whose entire function depends on it
     * (BRAINSTORM §5.4, §6.2). It must be user-initiated and explained first, which is exactly how
     * the battery screen uses it.
     */
    fun stockBatterySettings(context: Context): List<Intent> = listOf(
        @Suppress("BatteryLife")
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(appUri(context)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetails(context),
    )

    /**
     * The vendor-specific autostart / background-protection screens for [vendor], most useful first,
     * followed by the stock routes so that there is always *something* to offer. Every one of these
     * explicit components is likely to be absent; see the class KDoc.
     */
    fun oemBatterySettings(context: Context, vendor: OemBattery.Vendor): List<Intent> =
        OemBattery.targetsFor(vendor).map { target ->
            Intent().setComponent(ComponentName(target.packageName, target.className))
        } + stockBatterySettings(context)

    /** True when at least one of [vendor]'s own screens is present on this ROM. */
    fun hasOemBatterySettings(context: Context, vendor: OemBattery.Vendor): Boolean {
        val oemOnly = OemBattery.targetsFor(vendor).map { target ->
            Intent().setComponent(ComponentName(target.packageName, target.className))
        }
        return firstResolvable(context, oemOnly) != null
    }

    // ----------------------------------------------------------------- common

    /** This app's own settings page. The universal last resort; present on every Android build. */
    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(appUri(context))

    private fun appUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)
}
