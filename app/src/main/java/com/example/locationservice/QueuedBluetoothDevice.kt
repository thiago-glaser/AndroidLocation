package com.example.locationservice

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "queued_bluetooth_devices")
data class QueuedBluetoothDevice(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val address: String,
    val description: String,
    val carId: String,
    var sentAt: Long? = null
)