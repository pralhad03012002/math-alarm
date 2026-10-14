package com.mathalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostic status derivation (BUILD_SPECS §4).
 *
 * The severities are not cosmetic, so they are pinned here. Two of them encode review rulings that
 * a later change could silently reverse:
 *  - exact-alarm denied is a **FAIL** — nothing can be scheduled, so no alarm rings (backlog 1.11);
 *  - notifications or full-screen intent denied is a **WARN**, never a FAIL — Module 1 round 2's
 *    pushback #1 established that `USAGE_ALARM` on `STREAM_ALARM` still rings audibly without
 *    either, so calling them failures would be wrong and gating scheduling on them would be worse.
 */
class DiagnosticsTest {

    private fun facts(
        exactAlarmAllowed: Boolean = true,
        batteryUnrestricted: Boolean = true,
        notificationsEnabled: Boolean = true,
        fullScreenIntentApplicable: Boolean = true,
        fullScreenIntentAllowed: Boolean = true,
        alarmStreamAudible: Boolean = true,
        enabledAlarmCount: Int = 1,
        lastFiredAtMillis: Long? = 1_700_000_000_000L,
    ) = Diagnostics.Facts(
        exactAlarmAllowed = exactAlarmAllowed,
        batteryUnrestricted = batteryUnrestricted,
        notificationsEnabled = notificationsEnabled,
        fullScreenIntentApplicable = fullScreenIntentApplicable,
        fullScreenIntentAllowed = fullScreenIntentAllowed,
        alarmStreamAudible = alarmStreamAudible,
        enabledAlarmCount = enabledAlarmCount,
        lastFiredAtMillis = lastFiredAtMillis,
    )

    private fun statusOf(rows: List<Diagnostics.Row>, check: Diagnostics.Check) =
        rows.firstOrNull { it.check == check }?.status

    @Test
    fun `a fully healthy device is all green`() {
        val rows = Diagnostics.rows(facts())
        assertTrue(rows.all { it.status == Diagnostics.Status.OK })
        assertEquals(Diagnostics.Status.OK, Diagnostics.worst(rows))
        assertFalse(Diagnostics.needsAttention(rows))
    }

    @Test
    fun `denied exact alarms is a failure, because nothing can be scheduled at all`() {
        val rows = Diagnostics.rows(facts(exactAlarmAllowed = false))
        assertEquals(Diagnostics.Status.FAIL, statusOf(rows, Diagnostics.Check.EXACT_ALARM))
        assertEquals(Diagnostics.Status.FAIL, Diagnostics.worst(rows))
        assertTrue(Diagnostics.needsAttention(rows))
    }

    @Test
    fun `battery optimization still on is a failure, not a warning`() {
        val rows = Diagnostics.rows(facts(batteryUnrestricted = false))
        assertEquals(Diagnostics.Status.FAIL, statusOf(rows, Diagnostics.Check.BATTERY))
        assertTrue(Diagnostics.needsAttention(rows))
    }

    @Test
    fun `denied notifications is a warning, never a failure`() {
        // Module 1 round 2 pushback 1: the alarm still rings audibly through DND with no
        // notification at all. A FAIL here would be a lie, and it is the first step towards
        // re-introducing the hard gate that ruling withdrew.
        val rows = Diagnostics.rows(facts(notificationsEnabled = false))
        assertEquals(Diagnostics.Status.WARN, statusOf(rows, Diagnostics.Check.NOTIFICATIONS))
        assertEquals(Diagnostics.Status.WARN, Diagnostics.worst(rows))
        assertFalse(Diagnostics.needsAttention(rows))
    }

    @Test
    fun `denied full-screen intent is a warning, never a failure`() {
        val rows = Diagnostics.rows(facts(fullScreenIntentAllowed = false))
        assertEquals(Diagnostics.Status.WARN, statusOf(rows, Diagnostics.Check.FULL_SCREEN_INTENT))
        assertFalse(Diagnostics.needsAttention(rows))
    }

    @Test
    fun `the full-screen row is omitted where the capability does not exist`() {
        val rows = Diagnostics.rows(
            facts(fullScreenIntentApplicable = false, fullScreenIntentAllowed = false),
        )
        // Below API 34 there is nothing to grant or deny, so showing a row at all — pass or fail —
        // would be inventing state.
        assertNull(statusOf(rows, Diagnostics.Check.FULL_SCREEN_INTENT))
        assertEquals(Diagnostics.Status.OK, Diagnostics.worst(rows))
    }

    @Test
    fun `a silent alarm stream warns`() {
        val rows = Diagnostics.rows(facts(alarmStreamAudible = false))
        assertEquals(Diagnostics.Status.WARN, statusOf(rows, Diagnostics.Check.ALARM_VOLUME))
        assertFalse(Diagnostics.needsAttention(rows))
    }

    @Test
    fun `no enabled alarms warns`() {
        assertEquals(
            Diagnostics.Status.WARN,
            statusOf(Diagnostics.rows(facts(enabledAlarmCount = 0)), Diagnostics.Check.ENABLED_ALARMS),
        )
        assertEquals(
            Diagnostics.Status.OK,
            statusOf(Diagnostics.rows(facts(enabledAlarmCount = 3)), Diagnostics.Check.ENABLED_ALARMS),
        )
    }

    @Test
    fun `never having fired warns but can never fail`() {
        // A fresh install has legitimately never fired, so this row can only ever be informational.
        val never = Diagnostics.rows(facts(lastFiredAtMillis = null))
        assertEquals(Diagnostics.Status.WARN, statusOf(never, Diagnostics.Check.LAST_FIRED))
        assertFalse(Diagnostics.needsAttention(never))

        val fired = Diagnostics.rows(facts(lastFiredAtMillis = 1L))
        assertEquals(Diagnostics.Status.OK, statusOf(fired, Diagnostics.Check.LAST_FIRED))
    }

    @Test
    fun `a failure outranks any number of warnings in the summary`() {
        val rows = Diagnostics.rows(
            facts(
                exactAlarmAllowed = false,
                notificationsEnabled = false,
                fullScreenIntentAllowed = false,
                alarmStreamAudible = false,
                enabledAlarmCount = 0,
                lastFiredAtMillis = null,
            ),
        )
        assertEquals(Diagnostics.Status.FAIL, Diagnostics.worst(rows))
        assertTrue(Diagnostics.needsAttention(rows))
    }

    @Test
    fun `the worst of nothing is OK`() {
        assertEquals(Diagnostics.Status.OK, Diagnostics.worst(emptyList()))
        assertFalse(Diagnostics.needsAttention(emptyList()))
    }

    @Test
    fun `every check that can be produced has exactly one row, and the order is stable`() {
        val rows = Diagnostics.rows(facts())
        assertEquals(rows.size, rows.map { it.check }.toSet().size)
        // Exact alarm and battery lead, because they are the two that stop an alarm ringing.
        assertEquals(Diagnostics.Check.EXACT_ALARM, rows.first().check)
        assertEquals(Diagnostics.Check.BATTERY, rows[1].check)
        assertEquals(Diagnostics.Check.LAST_FIRED, rows.last().check)
    }
}
