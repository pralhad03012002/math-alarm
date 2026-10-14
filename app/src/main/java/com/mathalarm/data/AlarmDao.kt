package com.mathalarm.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface AlarmDao {

    @Query("SELECT * FROM alarms ORDER BY hour, minute, id")
    suspend fun getAll(): List<Alarm>

    @Query("SELECT * FROM alarms WHERE enabled = 1 ORDER BY hour, minute, id")
    suspend fun getEnabled(): List<Alarm>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getById(id: Long): Alarm?

    /** REPLACE so the debug trigger can keep reusing one fixed row instead of piling up rows. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alarm: Alarm): Long

    @Update
    suspend fun update(alarm: Alarm)

    @Query("UPDATE alarms SET enabled = 0 WHERE id = :id")
    suspend fun disable(id: Long)

    @Delete
    suspend fun delete(alarm: Alarm)
}
