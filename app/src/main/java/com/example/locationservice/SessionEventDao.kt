package com.example.locationservice

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SessionEventDao {
    @Insert
    suspend fun insert(sessionEvent: SessionEvent)

    @Query("SELECT * FROM session_events WHERE wasSent = 0 ORDER BY id ASC LIMIT :limit")
    suspend fun getUnsentBatch(limit: Int): List<SessionEvent>

    @Query("UPDATE session_events SET wasSent = 1, sentTimestamp = :sentTimestamp WHERE id IN (:ids)")
    suspend fun markAsSent(ids: List<Long>, sentTimestamp: Long)

    @Query("DELETE FROM session_events WHERE wasSent = 1 AND sentTimestamp < :timestamp")
    suspend fun deleteSentBefore(timestamp: Long)
}