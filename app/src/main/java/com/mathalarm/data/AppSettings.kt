package com.mathalarm.data

import android.content.Context

/**
 * The handful of UI preferences that are not alarms.
 *
 * Ordinary credential-encrypted `SharedPreferences`, unlike the alarm database and
 * [com.mathalarm.alarm.AlarmHistory]: nothing here is read by the firing chain, so none of it has to
 * be readable before the first unlock.
 */
object AppSettings {

    private const val PREFS = "app_settings"
    private const val KEY_ONBOARDING_SEEN = "onboarding_seen"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** False on a first run, which is what opens the permissions walkthrough automatically. */
    fun onboardingSeen(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_ONBOARDING_SEEN, false) }.getOrDefault(true)

    fun markOnboardingSeen(context: Context) {
        runCatching { prefs(context).edit().putBoolean(KEY_ONBOARDING_SEEN, true).apply() }
    }
}
