package com.example.locationservice

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BluetoothDeviceDao {
    @Query("SELECT * FROM bluetooth_devices")
    suspend fun getAll(): List<BluetoothDevice>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: BluetoothDevice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(devices: List<BluetoothDevice>)

    @Query("DELETE FROM bluetooth_devices")
    suspend fun deleteAll()

    @Query("DELETE FROM bluetooth_devices WHERE id = :id")
    suspend fun delete(id: Int)
}