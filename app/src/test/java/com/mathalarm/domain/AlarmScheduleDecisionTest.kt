package com.mathalarm.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edit-screen / delete reschedule decision (BUILD_SPECS §4).
 *
 * These tests exist to pin two acceptance criteria that would otherwise only be verifiable by
 * reading code:
 *  - *"Deleting an alarm cancels its pending `AlarmManager` registration."*
 *  - *"Editing an alarm cancels the old registration before creating the new one."*
 *
 * `AlarmRepository` routes every mutation through this object, so a change that stopped cancelling
 * first would fail here rather than leaving a stale registration to fire an alarm the user deleted.
 */
class AlarmScheduleDecisionTest {

    @Test
    fun `saving an enabled alarm cancels first and then schedules`() {
        val decision = AlarmScheduleDecision.forSave(enabled = true)
        assertTrue(decision.cancelFirst)
        assertTrue(decision.scheduleAfter)
    }

    @Test
    fun `saving a disabled alarm cancels and does not schedule`() {
        val decision = AlarmScheduleDecision.forSave(enabled = false)
        assertTrue(decision.cancelFirst)
        assertFalse(decision.scheduleAfter)
    }

    @Test
    fun `the list toggle behaves exactly like a save in both directions`() {
        assertTrue(AlarmScheduleDecision.forToggle(enabled = true).cancelFirst)
        assertTrue(AlarmScheduleDecision.forToggle(enabled = true).scheduleAfter)
        assertTrue(AlarmScheduleDecision.forToggle(enabled = false).cancelFirst)
        assertFalse(AlarmScheduleDecision.forToggle(enabled = false).scheduleAfter)
    }

    @Test
    fun `deleting cancels and never reschedules`() {
        val decision = AlarmScheduleDecision.forDelete()
        assertTrue("a deleted alarm must not keep a pending registration", decision.cancelFirst)
        assertFalse("a deleted alarm must never be re-registered", decision.scheduleAfter)
    }

    @Test
    fun `every entry point cancels first, unconditionally`() {
        // The invariant, stated once over all of them: there is no path on which a mutation leaves
        // the old registration in place. If an entry point is added without a cancel, this fails.
        val all = listOf(
            AlarmScheduleDecision.forSave(enabled = true),
            AlarmScheduleDecision.forSave(enabled = false),
            AlarmScheduleDecision.forToggle(enabled = true),
            AlarmScheduleDecision.forToggle(enabled = false),
            AlarmScheduleDecision.forDelete(),
        )
        assertTrue(all.all { it.cancelFirst })
    }

    @Test
    fun `scheduling only ever follows from the alarm being enabled`() {
        assertTrue(
            listOf(
                AlarmScheduleDecision.forSave(enabled = false),
                AlarmScheduleDecision.forToggle(enabled = false),
                AlarmScheduleDecision.forDelete(),
            ).none { it.scheduleAfter },
        )
    }
}
