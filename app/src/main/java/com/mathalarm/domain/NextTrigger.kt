package com.mathalarm.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure next-trigger computation. **No Android imports** so it is unit-testable on the JVM.
 *
 * The whole point of this file is BRAINSTORM.md §6.6: an alarm set for 07:00 must keep ringing at
 * 07:00 *wall-clock* across daylight-saving transitions and timezone changes. That is only possible
 * if nothing ever persists an absolute epoch for a repeating alarm. We persist `hour`/`minute` plus
 * a day-of-week set and recompute the instant every single time, resolving it against the zone that
 * is in force *now*.
 *
 * DST resolution is delegated to [ZonedDateTime.of], whose documented rules are exactly what an
 * alarm clock wants:
 *  - **Gap** (spring forward, e.g. 02:30 does not exist): the local time is shifted *later* by the
 *    length of the gap, so a 02:30 alarm rings at 03:30 and is never silently skipped.
 *  - **Overlap** (fall back, e.g. 01:30 happens twice): the *earlier* offset is chosen, so the
 *    alarm rings once, on the first pass, rather than twice.
 */
object NextTrigger {

    /** Day 0 (today) through day 7 (same weekday next week) always contains a match. */
    private const val LOOKAHEAD_DAYS = 8

    /**
     * The first instant strictly after [now] at which an alarm set for [hour]:[minute] repeating on
     * [repeatDays] should ring. An empty [repeatDays] means a one-shot alarm: today if the time is
     * still ahead, otherwise tomorrow.
     *
     * The zone is taken from [now], so passing a freshly obtained `ZonedDateTime.now()` is what
     * makes `TIMEZONE_CHANGED` handling work: the same stored hour/minute resolves to a different
     * instant in a different zone.
     */
    fun next(
        hour: Int,
        minute: Int,
        repeatDays: Set<DayOfWeek> = emptySet(),
        now: ZonedDateTime = ZonedDateTime.now(),
    ): ZonedDateTime {
        require(hour in 0..23) { "hour out of range: $hour" }
        require(minute in 0..59) { "minute out of range: $minute" }

        val time = LocalTime.of(hour, minute)
        val today = now.toLocalDate()
        for (offset in 0 until LOOKAHEAD_DAYS) {
            val date: LocalDate = today.plusDays(offset.toLong())
            if (repeatDays.isNotEmpty() && date.dayOfWeek !in repeatDays) continue
            val candidate = resolve(date, time, now.zone)
            if (candidate.isAfter(now)) return candidate
        }
        // Unreachable: a non-empty day set repeats weekly and an empty one matches every day.
        throw IllegalStateException(
            "no trigger within $LOOKAHEAD_DAYS days for $hour:$minute days=$repeatDays now=$now"
        )
    }

    /** [next] as epoch milliseconds, which is what `AlarmManager` wants. */
    fun nextMillis(
        hour: Int,
        minute: Int,
        repeatDays: Set<DayOfWeek> = emptySet(),
        now: ZonedDateTime = ZonedDateTime.now(),
    ): Long = next(hour, minute, repeatDays, now).toInstant().toEpochMilli()

    private fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime =
        ZonedDateTime.of(date, time, zone)
}
