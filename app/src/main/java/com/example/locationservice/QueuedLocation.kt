package com.example.locationservice

import androidx.room.Entity
import androidx.room.PrimaryKey

// QueuedLocation.kt
@Entity(tableName = "queued_locations")
data class QueuedLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val timestampUtc: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val jsonPayload: String
)