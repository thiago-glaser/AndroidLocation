package com.example.locationservice

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface QueuedBluetoothDeviceDao {
    @Query("SELECT * FROM queued_bluetooth_devices WHERE sentAt IS NULL")
    suspend fun getUnsent(): List<QueuedBluetoothDevice>

    @Query("SELECT * FROM queued_bluetooth_devices WHERE address = :address")
    suspend fun findByAddress(address: String): QueuedBluetoothDevice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: QueuedBluetoothDevice)

    @Query("UPDATE queued_bluetooth_devices SET sentAt = :sentAt WHERE id IN (:ids)")
    suspend fun markAsSent(ids: List<Int>, sentAt: Long)
}