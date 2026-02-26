package com.example.locationservice

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "bluetooth_devices", indices = [Index(value = ["address"], unique = true)])
data class BluetoothDevice(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val description: String,
    val address: String,
    val carId: String
)