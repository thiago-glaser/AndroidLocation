package com.example.locationservice

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BluetoothConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingResult.finish()
            return
        }

        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val deviceName = device?.name ?: "Unknown Device"
        val deviceAddress = device?.address ?: "Unknown Address"

        val actionMessage = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> "connected"
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> "disconnected"
            else -> {
                pendingResult.finish()
                return
            }
        }

        val logMessage = "Device $actionMessage: $deviceName ($deviceAddress)"
        Log.d("BluetoothReceiver", logMessage)

        GlobalScope.launch {
            try {
                val db = LocationDatabase.getDatabase(context)
                val bluetoothDevices = db.bluetoothDeviceDao().getAll()

                Log.d("BluetoothReceiver", "Connecting device address: $deviceAddress")
                Log.d("BluetoothReceiver", "All database records: ${bluetoothDevices.joinToString { "Device(name=${it.name}, address=${it.address}, carId=${it.carId})" }}")

                val matchingDevice = bluetoothDevices.find { it.address == deviceAddress }

                // If device is in our table and has a CarId, create a session
                if (matchingDevice != null && matchingDevice.carId.isNotEmpty() && matchingDevice.carId != "0") {
                    val eventType = if (actionMessage == "connected") "start" else "end"
                    val sessionEvent = SessionEvent(
                        deviceId = LocationLoggingService.getDeviceAndroidId(context),
                        carId = matchingDevice.carId,
                        eventType = eventType,
                        timestampUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }.format(Date())
                    )
                    Log.d("BluetoothReceiver", "Queuing '${sessionEvent.eventType}' session event for device '${sessionEvent.deviceId}' (Matched car: ${matchingDevice.carId})")
                    db.sessionEventDao().insert(sessionEvent)
                    Log.d("BluetoothReceiver", "Successfully queued session event.")
                }

                // Logic for discovery of new unknown devices
                val queuedDevice = db.queuedBluetoothDeviceDao().findByAddress(deviceAddress)
                if (matchingDevice == null && queuedDevice == null && actionMessage == "connected") {
                    val newDevice = QueuedBluetoothDevice(
                        name = deviceName,
                        address = deviceAddress,
                        description = "",
                        carId = ""
                    )
                    db.queuedBluetoothDeviceDao().insert(newDevice)
                    Log.d("BluetoothReceiver", "New unknown bluetooth device queued for sending to server.")
                }
            } catch(e: Exception) {
                Log.e("BluetoothReceiver", "Failed to process bluetooth event", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}