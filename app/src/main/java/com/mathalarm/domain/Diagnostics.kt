package com.mathalarm.domain

/**
 * Turns the facts about this device into the green/red rows of the self-diagnostic screen
 * (BUILD_SPECS §4, BRAINSTORM §6 "Mitigations").
 *
 * **Pure Kotlin, no Android imports.** The caller queries the live state — never a cached guess —
 * and hands it over as [Facts]; the severity *policy* lives here so it is unit-testable and so the
 * reasoning behind each colour is written down in one place.
 *
 * The policy, and why each row has the severity it has:
 *
 *  - **[Check.EXACT_ALARM] — [Status.FAIL] when denied.** Without it `AlarmScheduler` registers
 *    nothing at all, so no alarm can ever ring. Module 1 backlog **1.11**: that path only wrote a
 *    `Log.e`, which no user will ever see. This row is its user-visible surface.
 *  - **[Check.BATTERY] — [Status.FAIL] when the app is still battery-optimized.** BRAINSTORM §6.2
 *    calls OEM battery managers the #1 reason alarm apps fail. It is a FAIL rather than a warning
 *    because the symptom is a *silently missed* alarm.
 *  - **[Check.NOTIFICATIONS] — [Status.WARN] when disabled.** Review M1 round 2, pushback 1
 *    established this and the decision is deliberate: `USAGE_ALARM` on `STREAM_ALARM` rings audibly
 *    through Do Not Disturb with no notification at all. What is lost is the *UI*, not the noise, so
 *    this must never be treated as "the alarm will not work" — and scheduling must never be gated on
 *    it, because refusing to schedule would turn a degraded alarm into no alarm.
 *  - **[Check.FULL_SCREEN_INTENT] — [Status.WARN] when denied** (API 34+ only; omitted below that,
 *    where the capability does not exist). Same reasoning: the alarm still rings, but it may have to
 *    be stopped from the notification shade instead of the lock screen. Module 1 backlog **1.11** and
 *    review **M2-1.5**.
 *  - **[Check.ALARM_VOLUME] — [Status.WARN] when the alarm stream is silent.** The ringer forces the
 *    stream to maximum on every ring, so this is usually self-correcting; it is surfaced because
 *    Module 1 backlog **1.12** found that a Do-Not-Disturb policy can *reject* that write without
 *    notification-policy access, and nothing told the user.
 *  - **[Check.ENABLED_ALARMS] — [Status.WARN] when there are none.** Informational: a perfectly
 *    healthy install with no alarms set will not wake anybody.
 *  - **[Check.LAST_FIRED] — never [Status.FAIL].** [Status.OK] once an alarm has fired,
 *    [Status.WARN] while none ever has. A fresh install has legitimately never fired, so this can
 *    only ever be informational — but it is the one row that makes a *silent* failure visible, which
 *    is why §4 asks for it.
 */
object Diagnostics {

    enum class Check {
        EXACT_ALARM,
        BATTERY,
        NOTIFICATIONS,
        FULL_SCREEN_INTENT,
        ALARM_VOLUME,
        ENABLED_ALARMS,
        LAST_FIRED,
    }

    enum class Status { OK, WARN, FAIL }

    data class Row(val check: Check, val status: Status)

    /**
     * Everything the policy needs, all of it queried live.
     *
     * [fullScreenIntentApplicable] is false below API 34, where there is no such capability to
     * check and the row is omitted rather than shown as a spurious pass.
     */
    data class Facts(
        val exactAlarmAllowed: Boolean,
        val batteryUnrestricted: Boolean,
        val notificationsEnabled: Boolean,
        val fullScreenIntentApplicable: Boolean,
        val fullScreenIntentAllowed: Boolean,
        val alarmStreamAudible: Boolean,
        val enabledAlarmCount: Int,
        val lastFiredAtMillis: Long?,
    )

    fun rows(facts: Facts): List<Row> = buildList {
        add(Row(Check.EXACT_ALARM, if (facts.exactAlarmAllowed) Status.OK else Status.FAIL))
        add(Row(Check.BATTERY, if (facts.batteryUnrestricted) Status.OK else Status.FAIL))
        add(Row(Check.NOTIFICATIONS, if (facts.notificationsEnabled) Status.OK else Status.WARN))
        if (facts.fullScreenIntentApplicable) {
            add(
                Row(
                    Check.FULL_SCREEN_INTENT,
                    if (facts.fullScreenIntentAllowed) Status.OK else Status.WARN,
                ),
            )
        }
        add(Row(Check.ALARM_VOLUME, if (facts.alarmStreamAudible) Status.OK else Status.WARN))
        add(Row(Check.ENABLED_ALARMS, if (facts.enabledAlarmCount > 0) Status.OK else Status.WARN))
        add(Row(Check.LAST_FIRED, if (facts.lastFiredAtMillis != null) Status.OK else Status.WARN))
    }

    /** The worst status in [rows]; what the summary line and the list-screen banner report. */
    fun worst(rows: List<Row>): Status = when {
        rows.any { it.status == Status.FAIL } -> Status.FAIL
        rows.any { it.status == Status.WARN } -> Status.WARN
        else -> Status.OK
    }

    /**
     * Whether anything is wrong badly enough to warrant nagging the user from the alarm list.
     * Only a [Status.FAIL] qualifies: a warning row is a degradation, not a broken alarm, and a
     * permanent banner for "you have no alarms yet" would train the user to ignore the banner.
     */
    fun needsAttention(rows: List<Row>): Boolean = worst(rows) == Status.FAIL
}
