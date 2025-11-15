package com.example.locationservice

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LocationDao {
    @Insert
    suspend fun insert(location: QueuedLocation)

    @Query("SELECT * FROM queued_locations ORDER BY id ASC LIMIT 1")
    suspend fun getOldest(): QueuedLocation?

    @Delete
    suspend fun delete(location: QueuedLocation)

    @Query("SELECT COUNT(*) FROM queued_locations")
    suspend fun getCount(): Int
}