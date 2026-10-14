package com.mathalarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * The day-mask convention is the input to every repeating schedule (review finding 1.17).
 *
 * It is pure, zero-dependency code that Module 3's repeat-day editor will write *into* and
 * `AlarmScheduler` reads *out of*, so an off-by-one in either direction would silently move every
 * repeating alarm to the wrong day. `Alarm.kt` documents bit 0 = Monday … bit 6 = Sunday, matching
 * `DayOfWeek.value - 1`; these tests pin that in both directions.
 */
class AlarmDayMaskTest {

    private fun alarm(mask: Int) = Alarm(hour = 7, minute = 0, repeatDaysMask = mask)

    // ------------------------------------------------------- the convention

    @Test
    fun `bit 0 is Monday and bit 6 is Sunday`() {
        assertEquals(setOf(DayOfWeek.MONDAY), Alarm.maskToDays(0b000_0001))
        assertEquals(setOf(DayOfWeek.SUNDAY), Alarm.maskToDays(0b100_0000))
        assertEquals(0b000_0001, Alarm.daysToMask(setOf(DayOfWeek.MONDAY)))
        assertEquals(0b100_0000, Alarm.daysToMask(setOf(DayOfWeek.SUNDAY)))
    }

    @Test
    fun `each weekday maps to its own single bit`() {
        for (day in DayOfWeek.entries) {
            val mask = Alarm.daysToMask(setOf(day))
            assertEquals("single bit expected for $day", 1, Integer.bitCount(mask))
            assertEquals("bit index for $day", day.value - 1, Integer.numberOfTrailingZeros(mask))
        }
    }

    @Test
    fun `weekdays and weekend are the documented masks`() {
        val weekdays = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        )
        assertEquals(0b000_1111 or 0b001_0000, Alarm.daysToMask(weekdays))
        assertEquals(0b110_0000, Alarm.daysToMask(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)))
    }

    // ---------------------------------------------------------- round trips

    @Test
    fun `days to mask to days round trips for every subset of the week`() {
        for (mask in 0..0b111_1111) {
            val days = Alarm.maskToDays(mask)
            assertEquals("mask $mask round trip", mask, Alarm.daysToMask(days))
        }
    }

    @Test
    fun `mask to days to mask round trips for every single day`() {
        for (day in DayOfWeek.entries) {
            val days = setOf(day)
            assertEquals(days, Alarm.maskToDays(Alarm.daysToMask(days)))
        }
    }

    @Test
    fun `all seven days set is the full mask`() {
        val everyDay = DayOfWeek.entries.toSet()
        assertEquals(0b111_1111, Alarm.daysToMask(everyDay))
        assertEquals(everyDay, Alarm.maskToDays(0b111_1111))
    }

    @Test
    fun `days are returned in Monday-first order`() {
        val mask = Alarm.daysToMask(setOf(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY))
        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY),
            Alarm.maskToDays(mask).toList(),
        )
    }

    // --------------------------------------------------------- empty / zero

    @Test
    fun `zero mask is a one-shot alarm`() {
        assertEquals(emptySet<DayOfWeek>(), Alarm.maskToDays(0))
        assertEquals(0, Alarm.daysToMask(emptySet()))
        assertFalse(alarm(0).isRepeating)
        assertTrue(alarm(0).repeatDays.isEmpty())
    }

    @Test
    fun `any set day makes the alarm repeating`() {
        for (day in DayOfWeek.entries) {
            val a = alarm(Alarm.daysToMask(setOf(day)))
            assertTrue("$day should repeat", a.isRepeating)
            assertEquals(setOf(day), a.repeatDays)
        }
    }

    @Test
    fun `bits above the seven-day range are ignored rather than crashing`() {
        // A hand-edited or future-version row must not be able to produce a bogus DayOfWeek.
        assertEquals(setOf(DayOfWeek.MONDAY), Alarm.maskToDays(0b1000_0001))
        assertEquals(emptySet<DayOfWeek>(), Alarm.maskToDays(0b1000_0000))
    }
}
