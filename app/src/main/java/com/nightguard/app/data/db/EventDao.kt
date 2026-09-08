package com.nightguard.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: TimelineEvent): Long

    @Query("SELECT * FROM timeline_events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TimelineEvent>>

    @Query("SELECT * FROM timeline_events WHERE type = :type ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastOfType(type: EventType): TimelineEvent?

    @Query("SELECT * FROM timeline_events WHERE timestamp BETWEEN :startMillis AND :endMillis ORDER BY timestamp ASC")
    suspend fun eventsBetween(startMillis: Long, endMillis: Long): List<TimelineEvent>

    @Query("SELECT * FROM timeline_events WHERE timestamp > :afterMillis ORDER BY timestamp ASC")
    suspend fun eventsAfter(afterMillis: Long): List<TimelineEvent>

    @Query("DELETE FROM timeline_events WHERE timestamp < :beforeMillis")
    suspend fun pruneOlderThan(beforeMillis: Long)
}
