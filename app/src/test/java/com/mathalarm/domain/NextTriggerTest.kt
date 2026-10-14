package com.mathalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tests for the one piece of scheduling logic that can silently ruin the app.
 *
 * All of the DST cases use `America/New_York`, where in 2026 the clocks spring forward on
 * Sunday 8 March (02:00 -> 03:00) and fall back on Sunday 1 November (02:00 -> 01:00).
 */
class NextTriggerTest {

    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val london: ZoneId = ZoneId.of("Europe/London")
    private val everyDay: Set<DayOfWeek> = DayOfWeek.entries.toSet()

    private fun at(zone: ZoneId, text: String): ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.parse(text), zone)

    // ------------------------------------------------------------- one-shot

    @Test
    fun `one-shot later today fires today`() {
        val now = at(newYork, "2026-06-15T06:00")
        val next = NextTrigger.next(hour = 7, minute = 30, now = now)

        assertEquals(LocalDateTime.parse("2026-06-15T07:30"), next.toLocalDateTime())
    }

    @Test
    fun `one-shot already past today rolls to tomorrow`() {
        val now = at(newYork, "2026-06-15T09:00")
        val next = NextTrigger.next(hour = 7, minute = 30, now = now)

        assertEquals(LocalDateTime.parse("2026-06-16T07:30"), next.toLocalDateTime())
    }

    @Test
    fun `an exact match on the current minute is treated as past`() {
        val now = at(newYork, "2026-06-15T07:30")
        val next = NextTrigger.next(hour = 7, minute = 30, now = now)

        assertEquals(LocalDateTime.parse("2026-06-16T07:30"), next.toLocalDateTime())
        assertTrue(next.isAfter(now))
    }

    // ------------------------------------------------------------ repeating

    @Test
    fun `repeating picks the next matching weekday`() {
        // 2026-06-15 is a Monday.
        val now = at(newYork, "2026-06-15T09:00")
        val next = NextTrigger.next(7, 0, setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), now)

        assertEquals(DayOfWeek.WEDNESDAY, next.dayOfWeek)
        assertEquals(LocalDateTime.parse("2026-06-17T07:00"), next.toLocalDateTime())
    }

    @Test
    fun `single-day repeat whose time already passed today jumps a full week`() {
        val now = at(newYork, "2026-06-15T09:00") // Monday
        val next = NextTrigger.next(7, 0, setOf(DayOfWeek.MONDAY), now)

        assertEquals(LocalDateTime.parse("2026-06-22T07:00"), next.toLocalDateTime())
    }

    @Test
    fun `single-day repeat still ahead today fires today`() {
        val now = at(newYork, "2026-06-15T05:00") // Monday
        val next = NextTrigger.next(7, 0, setOf(DayOfWeek.MONDAY), now)

        assertEquals(LocalDateTime.parse("2026-06-15T07:00"), next.toLocalDateTime())
    }

    @Test
    fun `every-day repeat behaves like a daily alarm`() {
        val now = at(newYork, "2026-06-15T09:00")
        val next = NextTrigger.next(7, 0, everyDay, now)

        assertEquals(LocalDateTime.parse("2026-06-16T07:00"), next.toLocalDateTime())
    }

    // ------------------------------------------------- DST: spring forward

    @Test
    fun `daily alarm keeps its wall-clock time across the spring-forward transition`() {
        // The whole reason repeats store hour and minute rather than an epoch. Adding 24h of
        // milliseconds to Saturday's trigger would ring at 08:00 on Sunday; recomputing rings at
        // 07:00, and the real elapsed gap is 23 hours.
        val saturday = NextTrigger.next(7, 0, everyDay, at(newYork, "2026-03-07T06:00"))
        assertEquals(LocalDateTime.parse("2026-03-07T07:00"), saturday.toLocalDateTime())

        val sunday = NextTrigger.next(7, 0, everyDay, saturday.plusMinutes(1))
        assertEquals(LocalDateTime.parse("2026-03-08T07:00"), sunday.toLocalDateTime())
        assertEquals(LocalTime.of(7, 0), sunday.toLocalTime())

        assertEquals(Duration.ofHours(23), Duration.between(saturday, sunday))
        assertTrue(
            "offset must change across the transition",
            saturday.offset != sunday.offset,
        )
    }

    @Test
    fun `a time that does not exist on the spring-forward day is shifted forward, never skipped`() {
        // 02:30 simply does not happen on 2026-03-08 in New York. The alarm must still ring.
        val now = at(newYork, "2026-03-08T01:00")
        val next = NextTrigger.next(2, 30, everyDay, now)

        assertEquals(LocalDateTime.parse("2026-03-08T03:30"), next.toLocalDateTime())
        assertTrue(next.isAfter(now))
    }

    @Test
    fun `the day after a shifted alarm returns to its stored wall-clock time`() {
        val shifted = NextTrigger.next(2, 30, everyDay, at(newYork, "2026-03-08T01:00"))
        val afterwards = NextTrigger.next(2, 30, everyDay, shifted.plusMinutes(1))

        assertEquals(LocalDateTime.parse("2026-03-09T02:30"), afterwards.toLocalDateTime())
    }

    // ----------------------------------------------------- DST: fall back

    @Test
    fun `daily alarm keeps its wall-clock time across the fall-back transition`() {
        val saturday = NextTrigger.next(7, 0, everyDay, at(newYork, "2026-10-31T06:00"))
        assertEquals(LocalDateTime.parse("2026-10-31T07:00"), saturday.toLocalDateTime())

        val sunday = NextTrigger.next(7, 0, everyDay, saturday.plusMinutes(1))
        assertEquals(LocalDateTime.parse("2026-11-01T07:00"), sunday.toLocalDateTime())
        assertEquals(Duration.ofHours(25), Duration.between(saturday, sunday))
    }

    @Test
    fun `an ambiguous time in the fall-back overlap rings once, on the earlier offset`() {
        // 01:30 happens twice on 2026-11-01. Choosing the earlier (EDT, -04:00) offset means the
        // alarm rings on the first pass and the second pass is already in the past.
        val now = at(newYork, "2026-11-01T00:30").withEarlierOffsetAtOverlap()
        val first = NextTrigger.next(1, 30, everyDay, now)

        assertEquals(LocalDateTime.parse("2026-11-01T01:30"), first.toLocalDateTime())
        assertEquals(newYork.rules.getOffset(LocalDateTime.parse("2026-11-01T00:30")), first.offset)

        // One minute later the next occurrence is the following day, not the repeat of 01:30 EST.
        val second = NextTrigger.next(1, 30, everyDay, first.plusMinutes(1))
        assertEquals(LocalDateTime.parse("2026-11-02T01:30"), second.toLocalDateTime())
    }

    // ------------------------------------------------------ timezone change

    @Test
    fun `the same stored time resolves to a different instant in a different zone`() {
        val localTime = LocalDateTime.parse("2026-06-15T06:00")
        val inNewYork = NextTrigger.nextMillis(7, 0, everyDay, ZonedDateTime.of(localTime, newYork))
        val inLondon = NextTrigger.nextMillis(7, 0, everyDay, ZonedDateTime.of(localTime, london))

        assertTrue("a timezone change must move the trigger instant", inNewYork != inLondon)
        // New York is 5 hours behind London in June, so its 07:00 comes 5 hours later.
        assertEquals(Duration.ofHours(5).toMillis(), inNewYork - inLondon)
    }

    @Test
    fun `nextMillis agrees with next`() {
        val now = at(newYork, "2026-06-15T06:00")
        assertEquals(
            NextTrigger.next(7, 30, everyDay, now).toInstant().toEpochMilli(),
            NextTrigger.nextMillis(7, 30, everyDay, now),
        )
    }

    // -------------------------------------------------------------- guards

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an out-of-range hour`() {
        NextTrigger.next(24, 0, now = at(newYork, "2026-06-15T06:00"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an out-of-range minute`() {
        NextTrigger.next(7, 60, now = at(newYork, "2026-06-15T06:00"))
    }

    @Test
    fun `every hour and minute of the day resolves to a future instant in every zone tested`() {
        val zones = listOf(newYork, london, ZoneId.of("Australia/Lord_Howe"), ZoneId.of("UTC"))
        for (zone in zones) {
            val now = ZonedDateTime.of(LocalDateTime.parse("2026-03-08T01:15"), zone)
            for (hour in 0..23) {
                for (minute in listOf(0, 29, 30, 59)) {
                    val next = NextTrigger.next(hour, minute, everyDay, now)
                    assertTrue("$zone $hour:$minute -> $next", next.isAfter(now))
                }
            }
        }
    }
}
