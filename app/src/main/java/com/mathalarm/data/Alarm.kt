package com.mathalarm.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.DayOfWeek

/** Challenge tier. Module 2 owns the generator; Module 1 only stores the choice. */
enum class Difficulty { EASY, MEDIUM, HARD }

/**
 * A single alarm row.
 *
 * Deliberately **no snooze fields** (BUILD_SPECS §0) and no absolute trigger epoch
 * (BUILD_SPECS §2 / BRAINSTORM §6.6) — only wall-clock [hour]/[minute] plus [repeatDaysMask],
 * from which the next trigger is recomputed on demand.
 *
 * [repeatDaysMask] is a 7-bit set, bit 0 = Monday … bit 6 = Sunday, matching
 * `DayOfWeek.value - 1`. Zero means a one-shot alarm. Module 3 owns the editing UI; Module 1
 * leaves it at zero.
 */
@Entity(tableName = "alarms")
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val label: String = "",
    val repeatDaysMask: Int = 0,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val questionCount: Int = 1,
    val vibrate: Boolean = true,
) {
    /** Empty when one-shot. */
    val repeatDays: Set<DayOfWeek>
        get() = maskToDays(repeatDaysMask)

    val isRepeating: Boolean get() = repeatDaysMask != 0

    companion object {
        fun maskToDays(mask: Int): Set<DayOfWeek> =
            if (mask == 0) emptySet()
            else DayOfWeek.entries.filterTo(LinkedHashSet()) { mask and (1 shl (it.value - 1)) != 0 }

        fun daysToMask(days: Set<DayOfWeek>): Int =
            days.fold(0) { acc, day -> acc or (1 shl (day.value - 1)) }
    }
}
