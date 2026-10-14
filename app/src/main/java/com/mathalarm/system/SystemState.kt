package com.mathalarm.system

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mathalarm.alarm.AlarmHistory
import com.mathalarm.alarm.AlarmScheduler
import com.mathalarm.alarm.AlarmStreamStatus
import com.mathalarm.domain.Diagnostics
import com.mathalarm.domain.OemBattery

/**
 * Every permission and battery fact the onboarding and diagnostic screens need, **queried fresh**.
 *
 * BUILD_SPECS §4: *"Diagnostic screen reflects real permission state, not cached guesses."* This is
 * a plain data class with no caching anywhere, produced only by [query], and the UI re-runs [query]
 * on every `onResume` — which is the moment that matters, because the only way any of these states
 * changes is the user leaving for Settings and coming back.
 *
 * It lives outside `ui/` so that the UI layer never has to hold a `PowerManager`, an `AudioManager`
 * or any other system service that BUILD_SPECS §2's structural rule keeps away from it; the alarm
 * stream in particular is read through [AlarmStreamStatus], in the package that owns the audio.
 */
data class SystemState(
    val notificationsEnabled: Boolean,
    /** API 33+ and `POST_NOTIFICATIONS` not yet granted, i.e. a runtime request would do something. */
    val notificationPermissionRequestable: Boolean,
    val exactAlarmAllowed: Boolean,
    /** API 34+ only; below that there is no such capability to grant or deny. */
    val fullScreenIntentApplicable: Boolean,
    val fullScreenIntentAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val alarmStreamAudible: Boolean,
    val vendor: OemBattery.Vendor,
    val enabledAlarmCount: Int,
    val lastFiredAtMillis: Long?,
) {
    fun diagnosticFacts(): Diagnostics.Facts = Diagnostics.Facts(
        exactAlarmAllowed = exactAlarmAllowed,
        batteryUnrestricted = batteryUnrestricted,
        notificationsEnabled = notificationsEnabled,
        fullScreenIntentApplicable = fullScreenIntentApplicable,
        fullScreenIntentAllowed = fullScreenIntentAllowed,
        alarmStreamAudible = alarmStreamAudible,
        enabledAlarmCount = enabledAlarmCount,
        lastFiredAtMillis = lastFiredAtMillis,
    )

    fun rows(): List<Diagnostics.Row> = Diagnostics.rows(diagnosticFacts())

    companion object {

        /**
         * Reads the live state. Cheap (a handful of binder calls and one `SharedPreferences` read),
         * and every individual read is guarded so that one unavailable system service cannot take the
         * whole screen down.
         *
         * [enabledAlarmCount] is passed in because counting alarms is a database read the caller has
         * already done; everything else is queried here.
         */
        fun query(context: Context, enabledAlarmCount: Int): SystemState {
            val appContext = context.applicationContext
            return SystemState(
                notificationsEnabled = runCatching {
                    NotificationManagerCompat.from(appContext).areNotificationsEnabled()
                }.getOrDefault(true),
                notificationPermissionRequestable =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            appContext,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED,
                exactAlarmAllowed = runCatching {
                    AlarmScheduler(appContext).canScheduleExactAlarms()
                }.getOrDefault(true),
                fullScreenIntentApplicable =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                fullScreenIntentAllowed = fullScreenIntentAllowed(appContext),
                batteryUnrestricted = batteryUnrestricted(appContext),
                alarmStreamAudible = AlarmStreamStatus.isAudible(appContext),
                vendor = OemBattery.vendorOf(Build.MANUFACTURER, Build.BRAND),
                enabledAlarmCount = enabledAlarmCount,
                lastFiredAtMillis = runCatching { AlarmHistory.lastFiredAtMillis(appContext) }
                    .getOrNull(),
            )
        }

        private fun fullScreenIntentAllowed(appContext: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
            return runCatching {
                appContext.getSystemService(NotificationManager::class.java)
                    ?.canUseFullScreenIntent() == true
            }.getOrDefault(true)
        }

        /**
         * Whether the app is exempt from battery optimization. BRAINSTORM §6.2 makes this the single
         * most important state in the app; a false here is a [Diagnostics.Status.FAIL].
         */
        private fun batteryUnrestricted(appContext: Context): Boolean = runCatching {
            appContext.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(appContext.packageName) == true
        }.getOrDefault(false)
    }
}
