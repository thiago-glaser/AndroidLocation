package com.example.locationservice

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_events")
data class SessionEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val bluetoothAddress: String = "",
    val carId: String? = null,
    val sessionId: String? = null,
    val eventType: String, // "start" or "end"
    val timestampUtc: String,
    var wasSent: Boolean = false,
    var sentTimestamp: Long? = null
)
