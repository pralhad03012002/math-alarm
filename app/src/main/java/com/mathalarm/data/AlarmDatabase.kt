package com.mathalarm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration

/** Explicit converter rather than relying on Room's implicit enum handling. */
class DifficultyConverter {
    @TypeConverter fun toName(value: Difficulty): String = value.name

    @TypeConverter
    fun fromName(value: String): Difficulty =
        runCatching { Difficulty.valueOf(value) }.getOrDefault(Difficulty.MEDIUM)
}

/**
 * The alarm store.
 *
 * `exportSchema = true` plus `room.schemaLocation` in `app/build.gradle.kts` writes
 * `app/schemas/com.mathalarm.data.AlarmDatabase/<version>.json` at build time, and that file is
 * committed. It is the baseline every future `Migration` is written and verified against; capturing
 * it while the schema is still version 1 costs nothing and cannot be recovered later.
 *
 * Deliberately **no `fallbackToDestructiveMigration`** (review finding 1.3). With it, the first
 * schema change — Module 3 wiring a real repeat-day editor, or a `lastFiredAt` column — would make
 * Room drop `alarms` and recreate it empty on update: the user's alarm list silently empties and
 * every alarm they depend on simply stops ringing. Without it, a version bump with no matching
 * `Migration` fails fast and loudly instead:
 *  - at build time, Room's annotation processor errors out when the exported schema and the new
 *    entities disagree and no migration covers the gap;
 *  - at runtime, `openHelper` throws `IllegalStateException("A migration from X to Y was required
 *    but not found")` on first access, which the firing chain logs rather than destroying data.
 *
 * **Every version bump must add an explicit `addMigrations(...)` entry below.**
 */
@Database(entities = [Alarm::class], version = 1, exportSchema = true)
@TypeConverters(DifficultyConverter::class)
abstract class AlarmDatabase : RoomDatabase() {

    abstract fun alarmDao(): AlarmDao

    companion object {
        private const val NAME = "alarms.db"

        /** Every schema change ships its `Migration` here. Empty while the schema is at version 1. */
        private val MIGRATIONS: Array<Migration> = emptyArray()

        @Volatile private var instance: AlarmDatabase? = null

        /**
         * Singleton, backed by **device-protected storage**.
         *
         * Credential-encrypted storage (the normal `getDatabasePath`) is unreadable between
         * `LOCKED_BOOT_COMPLETED` and the first unlock after a reboot. Since [BootReceiver] must
         * reschedule alarms at `LOCKED_BOOT_COMPLETED` — a phone rebooted at 3am sits on the lock
         * screen until morning, and `BOOT_COMPLETED` never arrives until someone unlocks it — the
         * alarm table has to live where direct-boot code can read it. Every component in the firing
         * chain is declared `android:directBootAware="true"` to match.
         */
        fun get(context: Context): AlarmDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(appContext: Context): AlarmDatabase {
            val storageContext = appContext.createDeviceProtectedStorageContext() ?: appContext
            return Room.databaseBuilder(storageContext, AlarmDatabase::class.java, NAME)
                // Version 1 has no predecessor, so there is nothing to migrate from yet. Add the
                // migration here in the same commit as any @Database version bump; see the class
                // KDoc for why there is no destructive fallback.
                .addMigrations(*MIGRATIONS)
                .build()
        }
    }
}
