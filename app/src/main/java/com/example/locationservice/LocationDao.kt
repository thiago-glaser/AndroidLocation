package com.example.locationservice

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LocationDao {
    @Insert
    suspend fun insert(location: QueuedLocation)

    @Query("SELECT * FROM queued_locations WHERE wasSent = 0 ORDER BY id ASC LIMIT :limit")
    suspend fun getUnsentBatch(limit: Int): List<QueuedLocation>

    @Query("UPDATE queued_locations SET wasSent = 1, sentTimestamp = :sentTimestamp WHERE id IN (:ids)")
    suspend fun markAsSent(ids: List<Long>, sentTimestamp: Long)

    @Query("DELETE FROM queued_locations WHERE wasSent = 1 AND sentTimestamp < :timestamp")
    suspend fun deleteSentBefore(timestamp: Long)

    @Query("SELECT COUNT(*) FROM queued_locations")
    suspend fun getCount(): Int
}