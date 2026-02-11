package com.example.locationservice

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "queued_locations",
    indices = [
        Index(value = ["timestampUtc"], unique = true),
        Index(value = ["sentTimestamp"])
    ]
)
data class QueuedLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val timestampUtc: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val wasSent: Boolean = false,
    val sentTimestamp: Long? = null
)
